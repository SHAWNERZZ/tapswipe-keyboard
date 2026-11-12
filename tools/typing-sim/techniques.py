"""The user's two-thumb techniques for "form" and "from" (described 2026-11-12), typed on the emulator
with the finger model's natural scatter, several times each.

form: one thumb touches F, the other touches O; the F thumb slides up to R and lifts while the O thumb
      slides down to M.
from: one thumb touches F and drags to O and then down to M; on the way to O the other thumb taps R.

Usage: python3 techniques.py [times]
"""
import datetime
import random
import sys
import time

sys.path.insert(0, ".")
from simlib import finger  # noqa: E402
from simlib.device import Device  # noqa: E402
from simlib.runner import Session, landed_words  # noqa: E402

N = int(sys.argv[1]) if len(sys.argv) > 1 else 5


def timed_swipe(rng, layout, letters, p, t0, t1, pointer):
    s = finger.swipe(rng, layout, letters, p, 0.0)
    a, b = s.points[0][0], s.points[-1][0]
    s.points = [(t0 + (t - a) / (b - a) * (t1 - t0), x, y) for t, x, y in s.points]
    s.pointer = pointer
    return s


def form(rng, layout, p):
    j = lambda: rng.uniform(-30, 30)
    return [timed_swipe(rng, layout, "fr", p, 0, 300 + j(), 0),
            timed_swipe(rng, layout, "om", p, 120 + j(), 450 + j(), 1)]


def from_(rng, layout, p):
    j = lambda: rng.uniform(-30, 30)
    drag = timed_swipe(rng, layout, "fom", p, 0, 600 + j(), 0)
    tap = finger.tap(rng, layout, "r", p, 150 + j())
    tap.pointer = 1
    return [drag, tap]


dev = Device()
sess = Session(dev, "techniques-" + datetime.datetime.now().strftime("%H%M%S"), finger.FingerParams())
p = sess.params
tally = {}
for name, make in (("form", form), ("from", from_)):
    for k in range(N):
        rng = random.Random(1000 + k)
        strokes = make(rng, sess.layout, p)
        end = max(s.points[-1][0] for s in strokes)
        strokes.append(finger.space_tap(rng, sess.layout, p, end + 250))
        sess.new_events()
        sess.player.play(finger.to_script(strokes))
        time.sleep(0.8)
        out = sess.new_events()
        after = sess.last_field_text(out) or sess.field_text
        got = " ".join(landed_words(sess.field_text, after))
        sess.field_text = after
        det = [e for e in out if e.get("type") == "decode_detail"]
        d = det[-1] if det else None
        cands = ""
        if d:
            pen = d.get("orderPenalty") or [0] * len(d["words"])
            cands = ", ".join(f"{w} {s:.1f}" + (f"-{q:.1f}" if q else "") for w, s, q in zip(d["words"], d["score"], pen))
        print(f"{name} #{k + 1}: {got!r:10} [{cands}]")
        tally.setdefault(name, {}).setdefault(got.lower(), 0)
        tally[name][got.lower()] += 1
sess.close()
print(tally)
