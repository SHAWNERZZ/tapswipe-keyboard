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
args = ap.parse_args()

cases, group = [], ""
for line in open(args.list):
    line = line.strip()
    if line.startswith("# ---"):
        group = line[5:].strip()
    if not line or line.startswith("#"):
        continue
    left, want = [x.strip() for x in line.split("=>")]
    if args.only and args.only not in want:
        continue
    parts = [(p[0], p[2:]) for p in left.split()]
    cases.append((group, parts, want))

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
since_open = 0
results = []
for tempo in tempos:
    for n, (group, parts, want) in enumerate(cases):
        if since_open >= REOPEN_EVERY:
            sess.close()
            restarted = emulator.ensure()
            sess = open_session(fresh=restarted)
            since_open = 0
        since_open += 1
        rng = random.Random(args.seed * 1000 + n)
        p = sess.params
        # Parts that start a new expected word get the long gap in "pause" tempo.
        n_words = len(want.split())
        strokes, t = [], 0.0
        for i, (kind, letters) in enumerate(parts):
            for j, ch in enumerate(letters if kind == "t" else [letters]):
                if strokes:
                    new_word = (i > 0 and j == 0 and n_words > 1)
                    t = strokes[-1].points[-1][0] + (450 if tempo == "pause" and new_word else 50)
                s = (finger.tap(rng, sess.layout, ch, p, t) if kind == "t"
                     else finger.swipe(rng, sess.layout, ch, p, t))
                strokes.append(s)
        strokes.append(finger.space_tap(rng, sess.layout, p, strokes[-1].points[-1][0] + 300))
        before = sess.field_text
        sess.new_events()
        sess.player.play(finger.to_script(strokes))
        events = sess.settle()
        after = sess.last_field_text(events)
        if after is None:
            after = before
        sess.field_text = after
        got = (after[len(before):] if after.startswith(before) else after).strip()
        ok = got.lower() == want.lower()
        results.append({"tempo": tempo, "group": group, "want": want, "got": got, "ok": ok})
        print(f"{tempo:5} {'ok ' if ok else 'BAD'} {want:18} -> {got!r}", flush=True)
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
