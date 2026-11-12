"""Replays the user's "then" (tap T, swipe H to N, other thumb taps E) several times and prints the
decode requests and applied results, to trace which result overwrote which. Diagnostic script for
the second typing test of 2026-11-11."""
import datetime
import json
import random
import sys
import time

sys.path.insert(0, ".")
from simlib import finger, replay  # noqa: E402
from simlib.device import Device  # noqa: E402
from simlib.runner import Session  # noqa: E402

F = sys.argv[1]
N = int(sys.argv[2]) if len(sys.argv) > 2 else 8
STY = {1: "typing", 2: "update", 3: "tail"}
ev = [json.loads(l) for l in open(F) if l.strip()]
dev = Device()
sess = Session(dev, "then-probe-" + datetime.datetime.now().strftime("%H%M%S"), finger.FingerParams())
lines = replay.script(ev, replay.Frame.of(sess.layout_event), 480786690, 480787080)
for k in range(N):
    sess.new_events()
    sess.player.play(lines)
    time.sleep(0.7)
    out = sess.new_events()
    t0 = next((e["t"] for e in out if e.get("type") == "stroke"), 0)
    steps = []
    for e in out:
        t = e.get("type")
        if t == "decode_request":
            steps.append(f"+{e['t'] - t0}ms req {STY.get(e['style'], e['style'])}#{e['seq']} segs={e['segments']}")
        elif t == "decode":
            steps.append(f"+{e['t'] - t0}ms APPLY {e['text']!r} from {STY.get(e.get('resultStyle'), e.get('resultStyle'))}"
                         f"#{e.get('resultSeq')}")
    final = sess.last_field_text(out)
    print(f"try {k + 1}: field={final!r}")
    for s in steps:
        print("    " + s)
    sess.player.play(finger.to_script([finger.space_tap(random.Random(k), sess.layout, sess.params, 0)]))
    time.sleep(0.5)
    sess.new_events()
sess.close()
