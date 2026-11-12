"""Apostrophe check: does a swiped contraction come out right, and do its look-alikes stay intact?

Part 1 swipes "i'm" (through the apostrophe key) after several lead-ins, 3 tries each, and prints the
decoder's top candidates. Part 2 runs the contraction list (lists/contractions.txt) with three single
swipes per word, through `sim.py run`. Compare two builds by running this on each.

Usage: python3 apostrophe_check.py [label]
"""
import datetime
import random
import subprocess
import sys
import time

sys.path.insert(0, ".")
from simlib import finger  # noqa: E402
from simlib.device import Device  # noqa: E402
from simlib.runner import Session  # noqa: E402

LABEL = sys.argv[1] if len(sys.argv) > 1 else "check"
LEAD_INS = ["", "and", "so", "yes today", "i think"]
TARGETS = ["i'm", "don't", "we'll"]

dev = Device()
tally = {}
for target in TARGETS:
    for lead in LEAD_INS:
        for k in range(3):
            sess = Session(dev, f"apos-{datetime.datetime.now():%H%M%S%f}"[:20], finger.FingerParams())
            rng = random.Random(100 * k + len(lead))
            strokes, t = [], 0.0
            for w in lead.split():
                s = finger.swipe(rng, sess.layout, w, sess.params, t)
                sp = finger.space_tap(rng, sess.layout, sess.params, s.points[-1][0] + 250)
                strokes += [s, sp]
                t = sp.points[-1][0] + 400
            s = finger.swipe(rng, sess.layout, target, sess.params, t)
            strokes += [s, finger.space_tap(rng, sess.layout, sess.params, s.points[-1][0] + 250)]
            sess.new_events()
            sess.player.play(finger.to_script(strokes))
            time.sleep(1.0)
            out = sess.new_events()
            after = (sess.last_field_text(out) or "").strip()
            got = after.split()[-1] if after.split() else ""
            ok = got.lower() == target
            tally.setdefault(target, [0, 0])
            tally[target][0] += ok
            tally[target][1] += 1
            d = [e for e in out if e.get("type") == "decode_detail"]
            cands = ""
            if d:
                cands = ", ".join(f"{w} {sc:.1f}" for w, sc in zip(d[-1]["words"], d[-1]["score"]))
            print(f"{target:6} after {lead or '(start)':10} #{k + 1}: {after!r:22} "
                  f"{'ok ' if ok else 'BAD'} [{cands}]", flush=True)
            sess.close()
print("part 1:", {k: f"{a}/{b}" for k, (a, b) in tally.items()}, flush=True)

r = subprocess.run([sys.executable, "sim.py", "run", "--list", "lists/contractions.txt",
                    "--styles", "S,S,S", "--seed", "7", "--no-probe"],
                   capture_output=True, text=True)
print("part 2:", "\n".join(r.stdout.strip().splitlines()[-3:]))
