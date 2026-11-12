"""Replays only the first attempt of chosen prompt words from a typing test, several times, and prints
what landed plus the decoder's top candidates with any timing-order penalty.

Usage: python3 probe_first_attempts.py <recording> <word> [times]
"""
import datetime
import json
import sys
import time

sys.path.insert(0, ".")
from simlib import finger, replay  # noqa: E402
from simlib.analyze import _assign_words  # noqa: E402
from simlib.device import Device  # noqa: E402
from simlib.runner import Session, landed_words  # noqa: E402

F, WORD = sys.argv[1], sys.argv[2]
N = int(sys.argv[3]) if len(sys.argv) > 3 else 3
ev = [json.loads(l) for l in open(F) if l.strip()]
prompt = replay.prompt_words(ev)
owner = _assign_words(ev)
attempts = []
for i, w in enumerate(prompt):
    if w != WORD:
        continue
    seg = [e for e, o in zip(ev, owner) if o == i]
    touches = [e for e in seg if e.get("type") == "touch"]
    fin = next((e for e in seg if e.get("type") == "finalize"), None)
    if not touches or fin is None:
        continue
    attempts.append((i, touches[0]["et"] - 1, fin["t"] + 5))

dev = Device()
sess = Session(dev, "first-" + WORD + "-" + datetime.datetime.now().strftime("%H%M%S"), finger.FingerParams())
target = replay.Frame.of(sess.layout_event)
tally = {}
for i, t0, t1 in attempts:
    lines = replay.script(ev, target, t0, t1)
    for k in range(N):
        sess.new_events()
        sess.player.play(lines)
        time.sleep(0.8)
        out = sess.new_events()
        after = sess.last_field_text(out) or sess.field_text
        got = " ".join(landed_words(sess.field_text, after))
        sess.field_text = after
        tally[got] = tally.get(got, 0) + 1
        det = [e for e in out if e.get("type") == "decode_detail"]
        d = det[-1] if det else None
        cands = ""
        if d:
            pen = d.get("orderPenalty") or [0] * len(d["words"])
            cands = ", ".join(f"{w} {s:.1f}" + (f"-{p:.1f}" if p else "") for w, s, p in zip(d["words"], d["score"], pen))
        print(f"#{i} try {k + 1}: {got!r:10}  [{cands}]")
sess.close()
print("landed:", tally)
