"""Replays a phone recording's touches on the emulator.

A recording's touch positions are view-local on the phone's keyboard. Each point is moved into
keyboard coordinates, divided by the phone keyboard's size, then placed on the emulator keyboard. The
phone and the emulator must use the same layout and settings, or the keys will not line up.
"""
from __future__ import annotations

from dataclasses import dataclass

ACTIONS = {"ACTION_DOWN": 0, "ACTION_UP": 1, "ACTION_MOVE": 2, "ACTION_CANCEL": 3,
           "ACTION_POINTER_DOWN": 5, "ACTION_POINTER_UP": 6}
MAX_GAP_MS = 1500


def action_name(raw: str) -> str:
    """Newer Android versions add the pointer index: `ACTION_POINTER_DOWN(0)`. Drop it; the
    recorder stores the index separately."""
    return raw.split("(", 1)[0]  # long pauses are shortened; nothing in the keyboard waits that long


@dataclass
class Frame:
    """Maps view-local points of one keyboard to screen points of another."""
    pad_left: float
    pad_top: float
    vc: float
    kb_w: float
    kb_h: float
    origin_x: float = 0.0  # screen position of keyboard coordinate (0, 0)
    origin_y: float = 0.0

    @staticmethod
    def of(layout: dict) -> "Frame":
        return Frame(layout["padLeft"], layout["padTop"], layout["verticalCorrection"],
                     layout["kbW"], layout["kbH"],
                     layout["screenX"] + layout["padLeft"],
                     layout["screenY"] + layout["padTop"] - layout["verticalCorrection"])

    def normalize(self, x: float, y: float) -> tuple[float, float]:
        kx = x - self.pad_left
        ky = y - self.pad_top + self.vc
        return kx / self.kb_w, ky / self.kb_h

    def place(self, nx: float, ny: float) -> tuple[float, float]:
        return self.origin_x + nx * self.kb_w, self.origin_y + ny * self.kb_h


def touches(events: list[dict]) -> list[dict]:
    return [e for e in events if e.get("type") == "touch"]


def script(events: list[dict], target: Frame, t_from: int | None = None, t_to: int | None = None) -> list[str]:
    """Builds player lines from the recording's touches between two times (uptime ms).

    Uses the newest phone layout in force at each touch. Batched move history becomes separate move
    events, so the replay has the phone's full sample rate.
    """
    src: Frame | None = None
    lines: list[str] = []
    clock = 0          # replay time, ms
    prev_abs = None    # phone time of the previous emitted event
    down = False
    for e in events:
        if e.get("type") == "layout" and e.get("viewW", 0) > 0:
            src = Frame.of(e)
            continue
        if e.get("type") != "touch" or src is None:
            continue
        et = e["et"]
        if t_from is not None and et < t_from and not down:
            continue
        if t_to is not None and et > t_to and not down:
            break
        action = ACTIONS.get(action_name(e["action"]))
        if action is None or action == 3:
            continue
        ids, xs, ys = e["ids"], e["x"], e["y"]
        n = len(ids)

        def emit(t: int, act: int, idx: int, px: list[float], py: list[float]) -> None:
            nonlocal clock, prev_abs
            if prev_abs is not None:
                delta = max(0, t - prev_abs)
                # Pauses between touches are shortened. Inside a touch, timing is kept exactly.
                if act == 0 and delta > MAX_GAP_MS:
                    delta = MAX_GAP_MS
                clock += delta
            prev_abs = t
            pts = []
            for i in range(n):
                sx, sy = target.place(*src.normalize(px[i], py[i]))
                pts.append(f"{ids[i]} {sx:.1f} {sy:.1f}")
            lines.append(f"{clock} {act} {idx} {n} {' '.join(pts)}")

        if action == 2 and "ht" in e:
            for k, ht in enumerate(e["ht"]):
                emit(ht, 2, 0, e["hx"][k * n:(k + 1) * n], e["hy"][k * n:(k + 1) * n])
        emit(et, action, e.get("index", 0), xs, ys)
        if action == 0:
            down = True
        elif action == 1:
            down = False
    return lines


def prompt_words(events: list[dict]) -> list[str]:
    for e in events:
        if e.get("type") == "test_start":
            return e.get("words", [])
    return []


def final_text(events: list[dict]) -> str:
    texts = [e["text"] for e in events if e.get("type") == "field"]
    return texts[-1] if texts else ""


def first_try(events: list[dict], n_words: int) -> list[str | None]:
    """For each word position, the word as it stood the first time it was finished (followed by
    whitespace), before any correction. None if never reached."""
    first: list[str | None] = [None] * n_words
    for e in events:
        if e.get("type") != "field":
            continue
        t = e["text"]
        toks = t.split()
        done = len(toks) if t and t[-1].isspace() else len(toks) - 1
        for i in range(min(done, n_words)):
            if first[i] is None:
                first[i] = toks[i]
    return first


def word_windows(events: list[dict], n_words: int) -> list[dict]:
    """Splits a typing test into one window per prompt word: from the moment the previous word was
    first finished to the moment this one was. A window holding a backspace is marked, because its
    touches may fix an earlier word and cannot be replayed on their own."""
    starts = [e["t"] for e in events if e.get("type") == "test_start"]
    t_prev = starts[0] if starts else 0
    finished_at: list[int | None] = [None] * n_words
    for e in events:
        if e.get("type") != "field":
            continue
        t = e["text"]
        toks = t.split()
        done = len(toks) if t and t[-1].isspace() else len(toks) - 1
        for i in range(min(done, n_words)):
            if finished_at[i] is None:
                finished_at[i] = e["t"]
    backspaces = [e["t"] for e in events if e.get("type") == "backspace"]
    out = []
    for i in range(n_words):
        t_end = finished_at[i]
        if t_end is None:
            break
        out.append({"index": i, "t_from": t_prev, "t_to": t_end,
                    "backspace": any(t_prev < b <= t_end for b in backspaces)})
        t_prev = t_end
    return out
