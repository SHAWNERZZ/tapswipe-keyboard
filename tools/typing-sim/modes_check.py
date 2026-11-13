"""Mode check: auto space after tapped short words, peck mode by pause, a swipe that ends peck mode,
and tap-only mode turning auto space off. Needs tapswipe_auto_space on.

Each scenario is a list of steps: ("t", letters, gap_ms) taps each letter with gap_ms before it,
("s", letters, gap_ms) swipes, ("space", gap_ms) taps space, ("wait", ms) waits. The text the
scenario adds is compared with the expected text, spaces included.

Usage: python3 modes_check.py
"""
import datetime
import random
import sys
import time

sys.path.insert(0, ".")
from simlib import emulator, finger  # noqa: E402
from simlib.device import Device  # noqa: E402
from simlib.runner import Session  # noqa: E402

SCENARIOS = [
    # Tapped short words get the auto space (250 ms), like swiped words.
    ("short tapped words", [("t", "is", 0), ("wait", 450), ("t", "a", 0), ("wait", 600)], "is a "),
    ("three short tapped words", [("t", "it", 0), ("wait", 450), ("t", "is", 0), ("wait", 450),
                                  ("t", "a", 0), ("wait", 600)], "it is a "),
    ("one letter", [("t", "i", 0), ("wait", 450), ("s", "think", 0), ("wait", 600)], "I think "),
    # Letters that are not a word wait, then become a peck word after 350 ms: no auto space.
    ("peck by pause", [("t", "qz", 0), ("wait", 700), ("t", "x", 0), ("wait", 900)], "qzx"),
    ("peck word ends with space", [("t", "qz", 0), ("wait", 700), ("t", "x", 0), ("space", 300),
                                   ("wait", 500)], "qzx "),
    # A swipe ends peck mode: the word is decoded from all its strokes, and gets the auto space.
    ("swipe ends peck", [("t", "sh", 0), ("wait", 700), ("s", "ow", 60), ("wait", 700)], "show "),
    # A longer word typed letter by letter gets no auto space, even when it is a word.
    ("tapped long word", [("t", "hello", 0), ("wait", 250), ("space", 0), ("wait", 400)], "hello "),
]

# Tap-only mode: several long words typed letter by letter, quickly, with manual spaces. After that
# a tapped short word must not get an auto space.
TAP_ONLY = ("tap-only mode", [("t", "hello", 0), ("space", 80), ("t", "there", 120), ("space", 80),
                              ("t", "world", 120), ("space", 80), ("t", "is", 120), ("wait", 700)],
            "hello there world is")

TAP_GAP = 80  # ms between taps inside a word: quick, well under the 250 ms pause


def play(sess, steps, rng):
    strokes, t = [], 0.0
    p = sess.params

    def last_end():
        return strokes[-1].points[-1][0] if strokes else 0.0

    for step in steps:
        kind = step[0]
        if kind == "wait":
            if strokes:
                sess.player.play(finger.to_script(strokes))
                strokes.clear()
            time.sleep(step[1] / 1000)
            continue
        if kind == "space":
            strokes.append(finger.space_tap(rng, sess.layout, p, last_end() + step[1] + (1 if strokes else 0)))
            continue
        letters, gap = step[1], step[2]
        if kind == "s":
            strokes.append(finger.swipe(rng, sess.layout, letters, p, last_end() + gap))
        else:
            for j, ch in enumerate(letters):
                g = gap if j == 0 else TAP_GAP
                strokes.append(finger.tap(rng, sess.layout, ch, p, last_end() + (g if strokes else 0)))
    if strokes:
        sess.player.play(finger.to_script(strokes))
    time.sleep(0.4)


emulator.ensure()
dev = Device()
sess = Session(dev, f"modes-{datetime.datetime.now():%H%M%S%f}"[:20], finger.FingerParams(), fresh=False)
ok_count = 0
for n, (name, steps, want) in enumerate(SCENARIOS + [TAP_ONLY]):
    rng = random.Random(10 + n)
    # Context "So " gives each scenario a fresh word and a sentence the model can read.
    sess.dev.set_field_text(sess.run_id, "So ")
    sess.settle(commits=0, quiet_s=0.3, timeout=1.5)
    sess.new_events()
    play(sess, steps, rng)
    events = sess.new_events()
    after = sess.last_field_text(events) or ""
    got = after[3:] if after.startswith("So ") else after
    ok = got.lower() == want.lower()
    ok_count += ok
    modes = sorted({e.get("mode") for e in events if e.get("type") == "finalize"} - {None})
    spaces = [e["what"] for e in events if e.get("type") == "auto_space" and e["what"] != "armed"]
    print(f"{'ok ' if ok else 'BAD'} {name:26} want={want!r:22} got={got!r:22} modes={modes} timer={spaces}",
          flush=True)
sess.close()
print(f"\n{ok_count}/{len(SCENARIOS) + 1}")
