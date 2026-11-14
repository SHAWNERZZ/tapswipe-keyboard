"""Types a real-text passage the way a TapSwipe user would with auto space on, and compares the
result with the passage.

Words of one or two letters are tapped; longer words, and words with an apostrophe, are swiped
(through the apostrophe key). No space key between words: each word waits for the auto space.
Punctuation comes after the auto space, as on the phone: "." is one space press, "," is the swipe
from V down onto the space bar, "?" and "!" are swipes up and down off Enter. Needs
tapswipe_auto_space on and the user's settings.

Reports word errors (case and punctuation ignored) and punctuation errors separately.

Usage: python3 text_check.py [list names...]   (default: text-chat text-email text-story)
"""
import datetime
import difflib
import os
import random
import re
import sys
import time

sys.path.insert(0, ".")
from simlib import emulator, finger  # noqa: E402
from simlib.device import Device  # noqa: E402
from simlib.runner import Session  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
LISTS = os.path.join(HERE, "..", "..", "java", "assets-tapswipe", "typing-tests")
ENTER = 10
PAUSE_MS = 420   # after a word: longer than the 250 ms auto space pause, as a calm typist
SETTLE_MS = 450  # after a punctuation gesture


def passage(name: str) -> str:
    lines = [l.strip() for l in open(os.path.join(LISTS, name + ".txt")) if l.strip() and not l.startswith("#")]
    return " ".join(lines)


def tokens(text: str) -> list[tuple[str, str]]:
    """(word, trailing punctuation) pairs."""
    out = []
    for t in text.split():
        m = re.match(r"^(.*?)([.,?!]*)$", t)
        out.append((m.group(1), m.group(2)))
    return out


def flick(layout, code, dy, t0):
    k = layout.key_by_code(code)
    return finger.Stroke("swipe", "flick", [(t0 + i / 9 * 140, k.cx, k.cy + dy * k.h * i / 9) for i in range(10)])


def comma_pull(layout, t0):
    v, sp = layout.key_for("v"), layout.key_by_code(32)
    return finger.Stroke("swipe", "comma", [(t0 + i / 9 * 160, v.cx, v.cy + (sp.cy - v.cy) * i / 9) for i in range(10)])


def type_passage(sess, text, rng):
    p = sess.params
    for word, punct in tokens(text):
        letters = word.lower()
        if len(letters) <= 2 and "'" not in letters:
            strokes, t = [], 0.0
            for ch in letters:
                strokes.append(finger.tap(rng, sess.layout, ch, p, t))
                t = strokes[-1].points[-1][0] + 80
        else:
            strokes = [finger.swipe(rng, sess.layout, letters, p, 0.0)]
        sess.player.play(finger.to_script(strokes))
        time.sleep(PAUSE_MS / 1000)
        for mark in punct:
            if mark == ".":
                st = finger.space_tap(rng, sess.layout, p, 0.0)
            elif mark == ",":
                st = comma_pull(sess.layout, 0.0)
            elif mark == "?":
                st = flick(sess.layout, ENTER, -1.3, 0.0)
            else:
                st = flick(sess.layout, ENTER, 1.3, 0.0)
            sess.player.play(finger.to_script([st]))
            time.sleep(SETTLE_MS / 1000)


def compare(want: str, got: str):
    w = [t for t in tokens(want)]
    g = [t for t in tokens(got)]
    wa = [x[0].lower() for x in w]
    ga = [x[0].lower() for x in g]
    word_err, punct_err, notes = 0, 0, []
    sm = difflib.SequenceMatcher(a=wa, b=ga, autojunk=False)
    for op, i1, i2, j1, j2 in sm.get_opcodes():
        if op == "equal":
            for k in range(i2 - i1):
                if w[i1 + k][1] != g[j1 + k][1]:
                    punct_err += 1
                    notes.append(f"punct: {w[i1+k][0]}{w[i1+k][1]!s} -> {g[j1+k][0]}{g[j1+k][1]!s}")
        else:
            word_err += max(i2 - i1, j2 - j1)
            notes.append(f"{op}: {' '.join(wa[i1:i2])!r} -> {' '.join(ga[j1:j2])!r}")
    return len(w), word_err, punct_err, notes


names = sys.argv[1:] or ["text-chat", "text-email", "text-story"]
emulator.ensure()
dev = Device()
sess = Session(dev, f"text-{datetime.datetime.now():%H%M%S%f}"[:20], finger.FingerParams(), fresh=False)
totals = [0, 0, 0]
for n, name in enumerate(names):
    want = passage(name)
    sess.dev.set_field_text(sess.run_id, "")
    sess.settle(commits=0, quiet_s=0.3, timeout=1.5)
    sess.new_events()
    started = time.time()
    type_passage(sess, want, random.Random(100 + n))
    time.sleep(0.6)
    events = sess.new_events()
    got = (sess.last_field_text(events) or "").strip()
    count, we, pe, notes = compare(want, got)
    totals[0] += count; totals[1] += we; totals[2] += pe
    print(f"== {name}: {count} words, {count - we} right ({100 * (count - we) / count:.0f}%), "
          f"punctuation errors {pe}, {time.time() - started:.0f} s")
    print(f"   got: {got}")
    for line in notes:
        print(f"   {line}")
sess.close()
print(f"\nall: {totals[0]} words, {totals[0] - totals[1]} right, punctuation errors {totals[2]}")
