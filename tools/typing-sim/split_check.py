"""Word split check: type each case in lists/split-phrases.txt as one stroke list, no space key.

Each case runs in two tempos: "burst" (50 ms between strokes, the user's median inside a word) and
"pause" (450 ms between the parts that are separate words in the expected text). After the strokes,
the script taps space so the word finalizes, then compares the new text with the expected text.

Speed: one keyboard process for the whole run, and one field for several cases. Each case is judged
by the text it added. The field reopens every REOPEN_EVERY cases, and the emulator memory guard runs
then (see simlib/emulator.py). Waits end on the keyboard's own commit events, not fixed sleeps.

Usage: python3 split_check.py [label] [--tempo burst|pause|both] [--list path] [--only text]
"""
import argparse
import datetime
import json
import random
import sys
import time

sys.path.insert(0, ".")
from simlib import emulator, finger  # noqa: E402
from simlib.device import Device  # noqa: E402
from simlib.runner import Session  # noqa: E402

REOPEN_EVERY = 12

ap = argparse.ArgumentParser()
ap.add_argument("label", nargs="?", default="split")
ap.add_argument("--tempo", default="both")
ap.add_argument("--list", default="lists/split-phrases.txt")
ap.add_argument("--seed", type=int, default=3)
ap.add_argument("--only", default="", help="run only cases whose expected text contains this")
ap.add_argument("--auto", action="store_true",
                help="no space tap at the end: wait for the auto space timer (setting tapswipe_auto_space)")
args = ap.parse_args()

cases, group = [], ""
for line in open(args.list):
    line = line.strip()
    if line.startswith("# ---"):
        group = line[5:].strip()
    if not line or line.startswith("#"):
        continue
    left, want = [x.strip() for x in line.split("=>")]
    ctx, _, strokes_text = left.rpartition("|")
    if args.only and args.only not in want:
        continue
    # "~" before a part: a 450 ms pause before it, in both tempos (a hesitation inside a word).
    parts, pause_next = [], False
    for tok in strokes_text.split():
        if tok == "~":
            pause_next = True
            continue
        parts.append((tok[0], tok[2:], pause_next))
        pause_next = False
    cases.append((group, ctx.strip(), parts, want))

tempos = ["burst", "pause"] if args.tempo == "both" else [args.tempo]
started = time.time()
emulator.ensure()
dev = Device()


def open_session(fresh: bool) -> Session:
    for attempt in range(3):
        try:
            s = Session(dev, f"split-{datetime.datetime.now():%H%M%S%f}"[:20], finger.FingerParams(),
                        fresh=fresh)
            # Warm-up: the first stroke after a field opens sometimes lands nothing (a known
            # simulator quirk). Type "ok" and a space first.
            rng = random.Random(0)
            warm = [finger.swipe(rng, s.layout, "ok", s.params, 0.0)]
            warm.append(finger.space_tap(rng, s.layout, s.params, warm[-1].points[-1][0] + 300))
            s.new_events()
            s.player.play(finger.to_script(warm))
            t = s.last_field_text(s.settle())
            s.field_text = t or ""
            return s
        except RuntimeError as e:
            print(f"   retry: {e}", flush=True)
            fresh = True
            dev.set_ime()
    raise RuntimeError("could not open a field")


sess = open_session(fresh=True)
run_ids = [sess.run_id]
since_open = 0
results = []
for tempo in tempos:
    for n, (group, ctx, parts, want) in enumerate(cases):
        if since_open >= REOPEN_EVERY:
            sess.close()
            restarted = emulator.ensure()
            sess = open_session(fresh=restarted)
            run_ids.append(sess.run_id)
            since_open = 0
        since_open += 1
        rng = random.Random(args.seed * 1000 + n)
        p = sess.params
        # In "pause" tempo, the long gap goes only where a new expected word starts, counted in
        # letters. A part that finishes the word before it (thi + s) gets the short gap.
        boundaries, acc = set(), 0
        for w in want.split()[:-1]:
            acc += len(w.replace("'", ""))
            boundaries.add(acc)
        strokes, t, letters_before = [], 0.0, 0
        for i, (kind, letters, hesitate) in enumerate(parts):
            for j, ch in enumerate(letters if kind == "t" else [letters]):
                if strokes:
                    new_word = letters_before in boundaries
                    long_gap = (tempo == "pause" and new_word) or (hesitate and j == 0)
                    t = strokes[-1].points[-1][0] + (450 if long_gap else 50)
                s = (finger.tap(rng, sess.layout, ch, p, t) if kind == "t"
                     else finger.swipe(rng, sess.layout, ch, p, t))
                strokes.append(s)
                letters_before += len(ch)
        if not args.auto:
            strokes.append(finger.space_tap(rng, sess.layout, p, strokes[-1].points[-1][0] + 300))
        # The case's own context, so earlier cases do not leak into the language model. A trailing
        # space, so the first stroke starts a new word.
        before = (ctx + " ") if ctx else ""
        sess.dev.set_field_text(sess.run_id, before)
        sess.settle(commits=0, quiet_s=0.3, timeout=1.5)
        sess.new_events()
        sess.player.play(finger.to_script(strokes))
        # With --auto, the timer presses space 300 ms after the last lift. A word of taps alone gets
        # no space and stays composing, so wait for quiet rather than for a commit.
        events = sess.settle(commits=0 if args.auto else 1, quiet_s=0.6 if args.auto else 0.3,
                             timeout=4.0)
        after = sess.last_field_text(events)
        if after is None:
            after = before
        sess.field_text = after
        got = (after[len(before):] if after.lower().startswith(before.lower()) else after).strip()
        ok = got.lower() == want.lower()
        seg = [e for e in events if e.get("type") in ("segment", "segment_commit", "auto_space",
                                                       "rejoin_check", "rejoin")]
        results.append({"tempo": tempo, "group": group, "want": want, "got": got, "ok": ok,
                        "segment": seg})
        last_seg = next((e for e in reversed(seg) if e.get("type") == "segment"), None)
        why = f"   [{last_seg['readings']}] {last_seg['ms']:.0f} ms" if last_seg and not ok else ""
        print(f"{tempo:5} {'ok ' if ok else 'BAD'} {want:18} -> {got!r}{why}", flush=True)
sess.close()

print(f"\n== {args.label}  ({time.time() - started:.0f} s, emulator {emulator.rss_mb()} MB)")
for tempo in tempos:
    for g in dict.fromkeys(r["group"] for r in results):
        rs = [r for r in results if r["tempo"] == tempo and r["group"] == g]
        if rs:
            print(f"{tempo:5} {sum(r['ok'] for r in rs)}/{len(rs)}  {g}")
    rs = [r for r in results if r["tempo"] == tempo]
    print(f"{tempo:5} {sum(r['ok'] for r in rs)}/{len(rs)}  total")
json.dump(results, open(f"runs/split-{args.label}-{datetime.datetime.now():%Y%m%d-%H%M%S}.json", "w"),
          indent=1)
