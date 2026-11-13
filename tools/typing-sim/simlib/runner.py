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

    def __init__(self, dev: Device, run_id: str, params: finger.FingerParams, fresh: bool = True):
        """fresh=True restarts the keyboard process first: the clean state a long run wants.

        A run that ended mid-gesture (for example when a tap opened the emoji panel) left the keyboard
        believing a finger was still down, and every later touch became a second finger that typed
        nothing. But each app start leaks 100 to 150 MB in the emulator (see simlib/emulator.py) and
        costs a cold load of the dictionaries, so scripts that open many fields pass fresh=False and
        restart only when something went wrong.
        """
        self.dev = dev
        self.run_id = run_id
        self.params = params
        if fresh:
            dev.stop_app()
            dev.set_ime()  # force-stop, or Android itself, may have switched keyboards
        dev.start_target(run_id)
        self.layout_event = dev.wait_for_layout(run_id)
        self.layout = finger.Layout.from_layout_event(self.layout_event)
        if params.swipe_speed_keyw:
            params.swipe_speed = params.swipe_speed_keyw * self.layout.key_w
        # The first touches after the keyboard appears were sometimes lost or misread. Let the
        # keyboard, the decoder, and the language model finish loading first.
        time.sleep(4.0 if fresh else 1.0)
        _, self.offset = dev.recording_since(run_id, 0)
        self.field_text = ""
        self.player = dev.player()

    def recover_if_off_letters(self, events: list[dict]) -> None:
        """If the keyboard left the letter layout (emoji, symbols), reopen the field. The recording
        continues in the same file."""
        layouts = [e for e in events if e.get("type") == "layout" and e.get("viewW", 0) > 0]
        if not layouts:
            return
        if any(97 <= k["code"] <= 122 or 65 <= k["code"] <= 90 for k in layouts[-1]["keys"]):
            return
        print("  keyboard left the letters; reopening the field", flush=True)
        self.dev.start_target(self.run_id)
        self.layout_event = self.dev.wait_for_layout(self.run_id)
        self.layout = finger.Layout.from_layout_event(self.layout_event)
        time.sleep(1.5)
        self.new_events()
        self.field_text = ""

    def close(self) -> None:
        self.player.close()

    def reopen(self) -> None:
        """A new, empty field in the same keyboard process and the same recording file."""
        self.dev.start_target(self.run_id)
        self.layout_event = self.dev.wait_for_layout(self.run_id)
        self.layout = finger.Layout.from_layout_event(self.layout_event)
        time.sleep(1.0)
        self.new_events()
        self.field_text = ""

    def settle(self, commits: int = 1, quiet_s: float = 0.3, timeout: float = 3.0) -> list[dict]:
        """Events until the keyboard has committed at least `commits` times and then gone quiet.

        Replaces a fixed sleep. Most words settle in a fraction of the old wait, and a slow decode
        gets the time it needs.
        """
        events: list[dict] = []
        end = time.time() + timeout
        last_new = time.time()
        while time.time() < end:
            new = self.new_events()
            if new:
                events += new
                last_new = time.time()
            done = sum(1 for e in events if e.get("type") == "commit") >= commits
            if done and time.time() - last_new >= quiet_s:
                break
            time.sleep(0.05)
        return events

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
        pending = self.new_events()
        self.recover_if_off_letters(pending)
        before = self.last_field_text(pending)
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
