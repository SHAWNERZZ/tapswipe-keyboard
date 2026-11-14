"""Edge cases around auto space: punctuation, double space, Enter swipes, the comma gesture,
apostrophes, backspace, shift, and strokes during the countdown. Needs tapswipe_auto_space on and
the user's settings (Nintype gestures, Enter swipes: up = ?, down = !).

Each scenario starts from the field text "So " and lists steps:
  ("s", letters, gap)    swipe the letters, gap ms after the previous stroke ends
  ("t", letters, gap)    tap each letter (80 ms apart inside the run)
  ("space", gap)         tap space
  ("key", code, gap)     tap the key with that code (39 = ', 10 = Enter, -1 = shift, -5 = backspace)
  ("flick", code, dx, dy, gap)  swipe off a key by dx, dy key sizes (Enter up: 0, -1.2)
  ("comma", gap)         swipe from V straight down onto the space bar
  ("wait", ms)           play what is queued, then wait
The expected text is what an attentive user would want. A mismatch is a finding, not always a bug.

Usage: python3 edge_check.py [--only name]
"""
import argparse
import datetime
import random
import sys
import time

sys.path.insert(0, ".")
from simlib import emulator, finger  # noqa: E402
from simlib.device import Device  # noqa: E402
from simlib.runner import Session  # noqa: E402

ENTER, SHIFT, DELETE, APOS = 10, -1, -5, 39
UP = ("flick", ENTER, 0.0, -1.3)
DOWN = ("flick", ENTER, 0.0, 1.3)
W = 650  # long enough for the 250 ms auto space and the decode

SCENARIOS = [
    # Period by double space.
    ("double space after auto space", [("s", "think", 0), ("wait", W), ("space", 0), ("space", 90), ("wait", 500)], "think. "),
    ("one space after auto space", [("s", "think", 0), ("wait", W), ("space", 0), ("wait", 500)], "think. "),
    ("double space before the timer", [("s", "think", 0), ("space", 100), ("space", 90), ("wait", 600)], "think. "),
    ("period, then next word", [("s", "think", 0), ("wait", W), ("space", 0), ("space", 90), ("wait", 400),
                                ("s", "that", 0), ("wait", W)], "think. That "),
    ("period after tapped short word", [("t", "is", 0), ("wait", W), ("space", 0), ("space", 90), ("wait", 500)], "is. "),
    ("period after peck word", [("t", "qz", 0), ("wait", 700), ("t", "x", 0), ("space", 300), ("space", 90),
                                ("wait", 500)], "qzx. "),
    ("slow double space after auto space", [("s", "think", 0), ("wait", W), ("space", 0), ("space", 350), ("wait", 500)], "think. "),
    ("one late space after auto space", [("s", "think", 0), ("wait", 2000), ("space", 0), ("wait", 500)], "think. "),
    ("triple space after auto space", [("s", "think", 0), ("wait", W), ("space", 0), ("space", 90), ("space", 90), ("wait", 500)], "think.  "),
    ("period then tapped I", [("s", "think", 0), ("wait", W), ("space", 0), ("space", 90), ("wait", 400),
                              ("t", "i", 0), ("wait", W)], "think. I "),
    ("period right after a split", [("t", "i", 0), ("s", "want", 50), ("t", "a", 50), ("wait", W), ("space", 0),
                                    ("space", 90), ("wait", 500)], "I want a. "),
    ("double space right after the swipe", [("s", "think", 0), ("space", 40), ("space", 90), ("wait", 700)], "think. "),
    # Enter swipes: question and exclamation marks.
    ("question after auto space", [("s", "think", 0), ("wait", W), ("flick", ENTER, 0.0, -1.3, 0), ("wait", 600)], "think? "),
    ("question before the timer", [("s", "think", 0), ("flick", ENTER, 0.0, -1.3, 100), ("wait", 600)], "think? "),
    ("exclamation after auto space", [("s", "great", 0), ("wait", W), ("flick", ENTER, 0.0, 1.3, 0), ("wait", 600)], "great! "),
    ("question then next word", [("s", "think", 0), ("wait", W), ("flick", ENTER, 0.0, -1.3, 0), ("wait", 400),
                                 ("s", "that", 0), ("wait", W)], "think? That "),
    # The comma gesture.
    ("comma after auto space", [("s", "yes", 0), ("wait", W), ("comma", 0), ("wait", 600)], "yes, "),
    ("comma before the timer", [("s", "yes", 0), ("comma", 100), ("wait", 600)], "yes, "),
    ("comma then next word", [("s", "yes", 0), ("wait", W), ("comma", 0), ("wait", 400), ("s", "that", 0),
                              ("wait", W)], "yes, that "),
    # Apostrophe after the auto space: the user meant to continue the word.
    ("apostrophe s after auto space", [("s", "dog", 0), ("wait", W), ("key", APOS, 0), ("t", "s", 80), ("wait", W)], "dog's"),
    # Backspace.
    # A tap after a space removes the space first, then the word (with the user's "last gesture"
    # setting: the word's last stroke).
    ("backspace removes auto space", [("s", "cat", 0), ("wait", W), ("key", DELETE, 0), ("wait", 600)], "cat"),
    ("two backspaces after auto space", [("s", "cat", 0), ("wait", W), ("key", DELETE, 0), ("wait", 300),
                                         ("key", DELETE, 0), ("wait", 600)], ""),
    ("backspace after typed space", [("s", "think", 0), ("space", 100), ("wait", 500), ("key", DELETE, 0),
                                     ("wait", 600)], "think"),
    ("second backspace pops last stroke", [("s", "cat", 0), ("t", "s", 60), ("wait", W), ("key", DELETE, 0),
                                           ("wait", 300), ("key", DELETE, 0), ("wait", 600)], "cat"),
    ("backspace after period", [("s", "think", 0), ("wait", W), ("space", 0), ("wait", 400), ("key", DELETE, 0),
                                ("wait", 600)], "think."),
    ("backspace then s", [("s", "cat", 0), ("wait", W), ("key", DELETE, 0), ("wait", 300), ("t", "s", 0),
                          ("wait", W)], "cats"),
    ("backspace in the countdown", [("s", "think", 0), ("key", DELETE, 120), ("wait", 700)], ""),
    # Strokes during the countdown continue the word.
    ("tap during the countdown", [("s", "think", 0), ("t", "s", 150), ("wait", W)], "thinks "),
    ("swipe right after a space", [("s", "think", 0), ("space", 100), ("s", "that", 120), ("wait", W)], "think that "),
    # Shift, Enter.
    ("shift then word", [("s", "think", 0), ("wait", W), ("key", SHIFT, 0), ("s", "that", 120), ("wait", W)], "think That "),
    ("enter after auto space", [("s", "think", 0), ("wait", W), ("key", ENTER, 0), ("wait", 500)], "think\n"),
]

ap = argparse.ArgumentParser()
ap.add_argument("--only", default="")
args = ap.parse_args()


def flick(layout, code, dx, dy, t0):
    k = layout.key_by_code(code)
    pts = []
    for i in range(10):
        u = i / 9
        pts.append((t0 + u * 140, k.cx + dx * k.w * u, k.cy + dy * k.h * u))
    return finger.Stroke("swipe", f"flick{code}", pts)


def comma_pull(layout, t0):
    v = layout.key_for("v")
    sp = layout.key_by_code(32)
    pts = []
    for i in range(10):
        u = i / 9
        pts.append((t0 + u * 160, v.cx, v.cy + (sp.cy - v.cy) * u))
    return finger.Stroke("swipe", "comma", pts)


def play(sess, steps, rng):
    strokes = []
    p = sess.params

    def start(gap):
        return (strokes[-1].points[-1][0] + gap + 1) if strokes else 0.0

    for step in steps:
        kind = step[0]
        if kind == "wait":
            if strokes:
                sess.player.play(finger.to_script(strokes))
                strokes.clear()
            time.sleep(step[1] / 1000)
        elif kind == "space":
            strokes.append(finger.space_tap(rng, sess.layout, p, start(step[1])))
        elif kind == "key":
            k = sess.layout.key_by_code(step[1])
            t0 = start(step[2])
            strokes.append(finger.Stroke("tap", f"key{step[1]}", [(t0, k.cx, k.cy), (t0 + 70, k.cx, k.cy)]))
        elif kind == "flick":
            strokes.append(flick(sess.layout, step[1], step[2], step[3], start(step[4])))
        elif kind == "comma":
            strokes.append(comma_pull(sess.layout, start(step[1])))
        elif kind == "s":
            strokes.append(finger.swipe(rng, sess.layout, step[1], p, start(step[2])))
        elif kind == "t":
            for j, ch in enumerate(step[1]):
                strokes.append(finger.tap(rng, sess.layout, ch, p, start(step[2] if j == 0 else 80)))
    if strokes:
        sess.player.play(finger.to_script(strokes))
    time.sleep(0.3)


emulator.ensure()
dev = Device()
sess = Session(dev, f"edge-{datetime.datetime.now():%H%M%S%f}"[:20], finger.FingerParams(), fresh=False)
results = []
for n, (name, steps, want) in enumerate(SCENARIOS):
    if args.only and args.only not in name:
        continue
    rng = random.Random(20 + n)
    sess.dev.set_field_text(sess.run_id, "So ")
    sess.settle(commits=0, quiet_s=0.3, timeout=1.5)
    sess.new_events()
    play(sess, steps, rng)
    events = sess.new_events()
    after = sess.last_field_text(events)
    after = after if after is not None else "So "
    got = after[3:] if after.startswith("So ") else after
    ok = got == want
    results.append(ok)
    timer = [e["what"] for e in events if e.get("type") == "auto_space" and e["what"] not in ("armed", "cancelled")]
    print(f"{'ok ' if ok else 'BAD'} {name:32} want={want!r:16} got={got!r:18} timer={timer}", flush=True)
    if after and "So" not in after[:3]:
        print(f"     (field: {after!r})")
sess.close()
print(f"\n{sum(results)}/{len(results)}")
