"""Word split check: type each case in lists/split-phrases.txt as one stroke list, no space key.

Each case runs in two tempos: "burst" (50 ms between strokes, the user's median inside a word) and
"pause" (450 ms between the parts that are separate words in the expected text). After the strokes,
the script taps space so the word finalizes, then compares the field with the expected text.

Usage: python3 split_check.py [label] [--tempo burst|pause|both] [--list path]
"""
import argparse
import datetime
import json
import random
import sys
import time

sys.path.insert(0, ".")
from simlib import finger  # noqa: E402
from simlib.device import Device  # noqa: E402
from simlib.runner import Session  # noqa: E402

ap = argparse.ArgumentParser()
ap.add_argument("label", nargs="?", default="split")
ap.add_argument("--tempo", default="both")
ap.add_argument("--list", default="lists/split-phrases.txt")
ap.add_argument("--seed", type=int, default=3)
args = ap.parse_args()

cases, group = [], ""
for line in open(args.list):
    line = line.strip()
    if line.startswith("# ---"):
        group = line[5:].strip()
    if not line or line.startswith("#"):
        continue
    left, want = [x.strip() for x in line.split("=>")]
    parts = [(p[0], p[2:]) for p in left.split()]
    cases.append((group, parts, want))

tempos = ["burst", "pause"] if args.tempo == "both" else [args.tempo]
dev = Device()
results = []
for tempo in tempos:
    for n, (group, parts, want) in enumerate(cases):
        rng = random.Random(args.seed * 1000 + n)
        sess = None
        for attempt in range(3):
            try:
                sess = Session(dev, f"split-{datetime.datetime.now():%H%M%S%f}"[:20], finger.FingerParams())
                break
            except RuntimeError as e:
                # The keyboard sometimes fails to show on a fresh field. Select it again and retry.
                print(f"   retry: {e}", flush=True)
                dev.set_ime()
                time.sleep(2)
        if sess is None:
            results.append({"tempo": tempo, "group": group, "want": want, "got": None, "ok": False})
            print(f"{tempo:5} SKIP {want}", flush=True)
            continue
        p = sess.params
        # Parts that start a new expected word get the long gap in "pause" tempo.
        n_words = len(want.split())
        # Warm-up: the first stroke after a field opens sometimes lands nothing (a known simulator
        # quirk). Type "ok" and a space first, and judge only the text after it.
        warm = [finger.swipe(rng, sess.layout, "ok", p, 0.0)]
        warm.append(finger.space_tap(rng, sess.layout, p, warm[-1].points[-1][0] + 300))
        sess.new_events()
        sess.player.play(finger.to_script(warm))
        time.sleep(1.0)
        before = (sess.last_field_text(sess.new_events()) or "")
        strokes, t = [], 0.0
        for i, (kind, letters) in enumerate(parts):
            for j, ch in enumerate(letters if kind == "t" else [letters]):
                if strokes:
                    new_word = (i > 0 and j == 0 and n_words > 1)
                    t = strokes[-1].points[-1][0] + (450 if tempo == "pause" and new_word else 50)
                s = finger.tap(rng, sess.layout, ch, p, t) if kind == "t" else finger.swipe(rng, sess.layout, ch, p, t)
                strokes.append(s)
        strokes.append(finger.space_tap(rng, sess.layout, p, strokes[-1].points[-1][0] + 300))
        sess.new_events()
        sess.player.play(finger.to_script(strokes))
        time.sleep(1.2)
        out = sess.new_events()
        after = sess.last_field_text(out) or ""
        got = (after[len(before):] if after.startswith(before) else after).strip()
        ok = got.lower() == want.lower()
        results.append({"tempo": tempo, "group": group, "want": want, "got": got, "ok": ok})
        print(f"{tempo:5} {'ok ' if ok else 'BAD'} {want:18} -> {got!r}", flush=True)
        sess.close()

print(f"\n== {args.label}")
for tempo in tempos:
    for g in dict.fromkeys(r["group"] for r in results):
        rs = [r for r in results if r["tempo"] == tempo and r["group"] == g]
        if rs:
            print(f"{tempo:5} {sum(r['ok'] for r in rs)}/{len(rs)}  {g}")
    rs = [r for r in results if r["tempo"] == tempo]
    print(f"{tempo:5} {sum(r['ok'] for r in rs)}/{len(rs)}  total")
json.dump(results, open(f"runs/split-{args.label}-{datetime.datetime.now():%Y%m%d-%H%M%S}.json", "w"), indent=1)
