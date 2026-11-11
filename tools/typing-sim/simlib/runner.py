"""Runs typing attempts on the device and scores them."""
from __future__ import annotations

import random
import time

from . import finger
from .device import Device
from .score import Attempt, classify, probe_result

SETTLE_S = 0.45  # after the space, for the decode and commit to land


class Session:
    """One target field and its recording."""

    def __init__(self, dev: Device, run_id: str, params: finger.FingerParams):
        self.dev = dev
        self.run_id = run_id
        self.params = params
        dev.start_target(run_id)
        self.layout_event = dev.wait_for_layout(run_id)
        self.layout = finger.Layout.from_layout_event(self.layout_event)
        self.seen = len(dev.recording(run_id))

    def new_events(self) -> list[dict]:
        ev = self.dev.recording(self.run_id)
        out = ev[self.seen:]
        self.seen = len(ev)
        return out

    def last_field_text(self, events: list[dict]) -> str | None:
        texts = [e["text"] for e in events if e.get("type") == "field"]
        return texts[-1] if texts else None

    def type_word(self, word: str, style: str, seed: int, probe: bool = True) -> Attempt | None:
        rng = random.Random(seed)
        p = self.params
        strokes = finger.strokes_for(rng, self.layout, word, style, p, t0=0.0)
        if strokes is None:
            return None
        end = max(s.points[-1][0] for s in strokes)
        strokes.append(finger.space_tap(rng, self.layout, p, end + rng.uniform(*p.space_gap_ms)))
        lines = finger.to_script(strokes)
        a = Attempt(word=word, style=style, plan=[(s.kind, s.text) for s in strokes[:-1]], seed=seed,
                    script_lines=len(lines))
        self.new_events()  # drop anything left from before this attempt
        self.dev.play(lines)
        time.sleep(SETTLE_S)
        events = self.new_events()
        classify(a, events)

        if probe and a.verdict not in ("OK", "LOST"):
            bs_key = self.layout.key_by_code(finger.CODE_DELETE)
            bs = None
            if bs_key is not None:
                bs = finger.Stroke("tap", "<bs>", [(0.0, bs_key.cx, bs_key.cy), (70.0, bs_key.cx, bs_key.cy)])
            if bs is not None:
                self.dev.play(finger.to_script([bs]))
                time.sleep(SETTLE_S)
                ev2 = self.new_events()
                text = self.last_field_text(ev2)
                if text is not None:
                    probe_result(a, text)
                # Finish the reopened word so the next attempt starts clean.
                sp = finger.space_tap(rng, self.layout, p, 0.0)
                self.dev.play(finger.to_script([sp]))
                time.sleep(SETTLE_S)
                self.new_events()
        return a
