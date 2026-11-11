"""Runs typing attempts on the device and scores them."""
from __future__ import annotations

import random
import time

from . import finger
from .device import Device
from .score import Attempt, classify, probe_result

SETTLE_S = 0.45  # after the space, for the decode and commit to land


def landed_words(before: str, after: str) -> list[str]:
    """Words the field gained. The keyboard only edits the end of the text, so the common prefix
    is untouched, and the gain is whatever follows it, from the start of that word."""
    i = 0
    while i < min(len(before), len(after)) and before[i] == after[i]:
        i += 1
    # Back up to the start of the word the change began in.
    while i > 0 and not after[i - 1].isspace():
        i -= 1
    gained = after[i:].split()
    lost = before[i:].split()
    # Words that were already there before the attempt are not part of it.
    while gained and lost and gained[0] == lost[0]:
        gained.pop(0)
        lost.pop(0)
    return gained


class Session:
    """One target field and its recording."""

    def __init__(self, dev: Device, run_id: str, params: finger.FingerParams):
        self.dev = dev
        self.run_id = run_id
        self.params = params
        dev.set_ime()  # Android may have switched keyboards since setup
        dev.start_target(run_id)
        self.layout_event = dev.wait_for_layout(run_id)
        self.layout = finger.Layout.from_layout_event(self.layout_event)
        _, self.offset = dev.recording_since(run_id, 0)
        self.field_text = ""
        self.player = dev.player()

    def close(self) -> None:
        self.player.close()

    def new_events(self) -> list[dict]:
        out, self.offset = self.dev.recording_since(self.run_id, self.offset)
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
        before = self.last_field_text(self.new_events())
        if before is not None:
            self.field_text = before
        self.player.play(lines)
        time.sleep(SETTLE_S)
        events = self.new_events()
        after = self.last_field_text(events)
        landed = None
        if after is not None:
            landed = landed_words(self.field_text, after)
            self.field_text = after
        classify(a, events, landed)

        if probe and a.verdict not in ("OK", "LOST"):
            bs_key = self.layout.key_by_code(finger.CODE_DELETE)
            bs = None
            if bs_key is not None:
                bs = finger.Stroke("tap", "<bs>", [(0.0, bs_key.cx, bs_key.cy), (70.0, bs_key.cx, bs_key.cy)])
            if bs is not None:
                self.player.play(finger.to_script([bs]))
                time.sleep(SETTLE_S)
                ev2 = self.new_events()
                text = self.last_field_text(ev2)
                if text is not None:
                    probe_result(a, text)
                    self.field_text = text
                # Finish the reopened word so the next attempt starts clean.
                sp = finger.space_tap(rng, self.layout, p, 0.0)
                self.player.play(finger.to_script([sp]))
                time.sleep(SETTLE_S)
                t = self.last_field_text(self.new_events())
                if t is not None:
                    self.field_text = t
        return a
