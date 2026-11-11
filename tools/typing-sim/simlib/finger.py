"""Finger model: turns a word and a typing style into timed touch events.

Pure functions, no device access. Every random choice comes from the `random.Random` passed in, so
one seed always gives the same events.

Coordinates are screen pixels. A `Layout` maps each character to a key rectangle on screen.
"""
from __future__ import annotations

import math
import random
from dataclasses import dataclass, field

DOWN, UP, MOVE, POINTER_DOWN, POINTER_UP = 0, 1, 2, 5, 6
SAMPLE_MS = 8  # about 120 Hz, a common touch report rate
CODE_DELETE = -5  # Constants.CODE_DELETE


@dataclass
class Key:
    code: int
    label: str
    x: float  # screen left
    y: float  # screen top
    w: float
    h: float

    @property
    def cx(self) -> float:
        return self.x + self.w / 2

    @property
    def cy(self) -> float:
        return self.y + self.h / 2


@dataclass
class Layout:
    keys: list[Key]
    by_char: dict[str, Key] = field(default_factory=dict)

    def __post_init__(self):
        for k in self.keys:
            if 0 < k.code < 0x110000:
                ch = chr(k.code).lower()
                if ch not in self.by_char:
                    self.by_char[ch] = k

    def key_for(self, ch: str) -> Key:
        k = self.by_char.get(ch.lower())
        if k is None:
            raise KeyError(f"no key for {ch!r}")
        return k

    def key_by_code(self, code: int) -> Key | None:
        return next((k for k in self.keys if k.code == code), None)

    @property
    def key_w(self) -> float:
        letters = [k for c, k in self.by_char.items() if c.isalpha()]
        return sum(k.w for k in letters) / max(1, len(letters))

    @staticmethod
    def from_layout_event(ev: dict) -> "Layout":
        """Builds a screen layout from the recorder's `layout` event."""
        ox = ev["screenX"] + ev["padLeft"]
        oy = ev["screenY"] + ev["padTop"] - ev["verticalCorrection"]
        keys = [Key(k["code"], k.get("label") or "", ox + k["x"], oy + k["y"], k["w"], k["h"])
                for k in ev["keys"]]
        return Layout(keys)


@dataclass
class FingerParams:
    """Defaults are guesses until the typing test calibrates them. See TYPING_SIMULATOR.md."""
    tap_sigma: float = 0.15        # tap scatter, as a fraction of key width and height
    swipe_sigma: float = 0.12      # scatter of each swipe waypoint, same units
    tap_hold_ms: tuple = (55, 95)
    swipe_speed: float = 1.3       # mean path speed, pixels per millisecond
    swipe_min_ms: int = 140
    gap_ms: tuple = (110, 230)     # lift to next touch, inside a word
    space_gap_ms: tuple = (150, 260)
    overlap: float = 0.55          # two-thumb style: second thumb starts at this fraction
    swipe_speed_keyw: float | None = None  # from `stats`; if set, overrides swipe_speed per layout
    tap_bias: dict | None = None   # from `stats`: char -> [dx, dy] in key units; "*" for any other key


@dataclass
class Stroke:
    """One finger contact: a list of (ms, x, y) points. A tap has one place and a short hold."""
    kind: str  # "tap" or "swipe"
    text: str
    points: list[tuple[float, float, float]]
    pointer: int = 0


def _scatter(rng: random.Random, key: Key, sigma: float) -> tuple[float, float]:
    x = rng.gauss(key.cx, sigma * key.w)
    y = rng.gauss(key.cy, sigma * key.h)
    # Keep the touch on the keyboard row band; a wild draw off the key is still allowed.
    return x, y


def _on_letter_area(layout: Layout, key: Key, x: float, y: float) -> bool:
    """True if the point is on the intended key or on any key of the same kind. A letter tap that
    lands on a function key (emoji, shift, symbols) would change the keyboard, not misspell a word."""
    for k in layout.keys:
        if k.x <= x < k.x + k.w and k.y <= y < k.y + k.h:
            return k is key or (k.code > 32 and key.code > 32) or k.code == key.code
    return False


def tap(rng: random.Random, layout: Layout, ch: str, p: FingerParams, t0: float) -> Stroke:
    key = layout.key_for(ch)
    bx, by = 0.0, 0.0
    if p.tap_bias and ch.isalpha():
        bx, by = p.tap_bias.get(ch.lower(), p.tap_bias.get("*", [0.0, 0.0]))
    for _ in range(20):
        x, y = _scatter(rng, key, p.tap_sigma)
        x += bx * key.w
        y += by * key.h
        if _on_letter_area(layout, key, x, y):
            break
    else:
        x, y = key.cx, key.cy
    hold = rng.uniform(*p.tap_hold_ms)
    jitter = 1.5
    pts = [(t0, x, y),
           (t0 + hold / 2, x + rng.gauss(0, jitter), y + rng.gauss(0, jitter)),
           (t0 + hold, x + rng.gauss(0, jitter), y + rng.gauss(0, jitter))]
    return Stroke("tap", ch, pts)


def _catmull_rom(pts: list[tuple[float, float]], samples_per_seg: int = 24) -> list[tuple[float, float]]:
    if len(pts) == 1:
        return pts[:]
    ext = [pts[0]] + pts + [pts[-1]]
    out = []
    for i in range(1, len(ext) - 2):
        p0, p1, p2, p3 = ext[i - 1], ext[i], ext[i + 1], ext[i + 2]
        for s in range(samples_per_seg):
            t = s / samples_per_seg
            t2, t3 = t * t, t * t * t
            out.append(tuple(
                0.5 * ((2 * p1[d]) + (-p0[d] + p2[d]) * t + (2 * p0[d] - 5 * p1[d] + 4 * p2[d] - p3[d]) * t2
                       + (-p0[d] + 3 * p1[d] - 3 * p2[d] + p3[d]) * t3) for d in (0, 1)))
    out.append(pts[-1])
    return out


def _min_jerk(u: float) -> float:
    return 10 * u ** 3 - 15 * u ** 4 + 6 * u ** 5


def swipe(rng: random.Random, layout: Layout, letters: str, p: FingerParams, t0: float) -> Stroke:
    """A swipe through the letters' keys. Repeated letters collapse to one waypoint, as a finger
    cannot pass through the same key twice in a row without leaving it."""
    way = []
    prev = None
    for ch in letters:
        if ch == prev:
            continue
        way.append(_scatter(rng, layout.key_for(ch), p.swipe_sigma))
        prev = ch
    if len(way) == 1:
        # A one-key "swipe" is a short drag on that key, so the touch layer still sees motion.
        x, y = way[0]
        way = [(x - 0.2 * layout.key_w, y), (x + 0.2 * layout.key_w, y)]
    curve = _catmull_rom(way)
    # Arc length table for even speed along the path.
    lens = [0.0]
    for a, b in zip(curve, curve[1:]):
        lens.append(lens[-1] + math.hypot(b[0] - a[0], b[1] - a[1]))
    total = lens[-1]
    dur = max(p.swipe_min_ms, total / (p.swipe_speed * rng.uniform(0.8, 1.2)))
    pts = []
    n = max(2, int(dur / SAMPLE_MS))
    j = 0
    for i in range(n + 1):
        u = i / n
        target = _min_jerk(u) * total
        while j < len(lens) - 2 and lens[j + 1] < target:
            j += 1
        seg = lens[j + 1] - lens[j]
        f = 0.0 if seg <= 0 else (target - lens[j]) / seg
        x = curve[j][0] + f * (curve[j + 1][0] - curve[j][0])
        y = curve[j][1] + f * (curve[j + 1][1] - curve[j][1])
        pts.append((t0 + u * dur, x, y))
    return Stroke("swipe", letters, pts)


# ---- Styles: how a word is split into strokes -----------------------------------------------

STYLES = ["S", "T", "S+T", "T+S", "S+S", "S/S", "mix"]


def split_word(rng: random.Random, word: str, style: str) -> list[tuple[str, str]] | None:
    """Returns [(kind, letters), ...], or None when the style does not fit the word."""
    n = len(word)
    if style == "S":
        return [("swipe", word)] if n >= 2 else None
    if style == "T":
        return [("tap", c) for c in word]
    if style == "S+T":
        if n < 3:
            return None
        k = rng.randint(1, min(3, n - 2))
        return [("swipe", word[:n - k])] + [("tap", c) for c in word[n - k:]]
    if style == "T+S":
        if n < 3:
            return None
        k = rng.randint(1, min(2, n - 2))
        return [("tap", c) for c in word[:k]] + [("swipe", word[k:])]
    if style in ("S+S", "S/S"):
        if n < 4:
            return None
        k = rng.randint(2, n - 2)
        return [("swipe", word[:k]), ("swipe", word[k:])]
    if style == "mix":
        if n < 3:
            return None
        parts, i = [], 0
        cuts = sorted(rng.sample(range(1, n), min(n - 1, rng.randint(1, 3))))
        for c in cuts + [n]:
            seg = word[i:c]
            parts.append(("swipe", seg) if len(seg) >= 2 and rng.random() < 0.7 else None)
            if parts[-1] is None:
                parts.pop()
                parts.extend(("tap", ch) for ch in seg)
            i = c
        return parts
    raise ValueError(style)


def strokes_for(rng: random.Random, layout: Layout, word: str, style: str,
                p: FingerParams, t0: float = 0.0) -> list[Stroke] | None:
    plan = split_word(rng, word, style)
    if plan is None:
        return None
    out: list[Stroke] = []
    t = t0
    if style == "S/S":
        a = swipe(rng, layout, plan[0][1], p, t)
        start_b = a.points[0][0] + p.overlap * (a.points[-1][0] - a.points[0][0])
        b = swipe(rng, layout, plan[1][1], p, start_b)
        b.pointer = 1
        return [a, b]
    for kind, letters in plan:
        s = tap(rng, layout, letters, p, t) if kind == "tap" else swipe(rng, layout, letters, p, t)
        out.append(s)
        t = s.points[-1][0] + rng.uniform(*p.gap_ms)
    return out


def space_tap(rng: random.Random, layout: Layout, p: FingerParams, t0: float) -> Stroke:
    return tap(rng, layout, " ", p, t0)


# ---- Script: strokes to the player's event lines --------------------------------------------

def to_script(strokes: list[Stroke]) -> list[str]:
    """Merges strokes into one timeline of pointer events. Overlapping strokes become a two-pointer
    gesture, with POINTER_DOWN and POINTER_UP as Android sends them."""
    events = []  # (t, order, stroke index, point index)
    for si, s in enumerate(strokes):
        for pi, (t, _, _) in enumerate(s.points):
            events.append((t, si, pi))
    events.sort()
    active: dict[int, tuple[float, float]] = {}  # pointer id -> position
    order: list[int] = []
    lines = []
    for t, si, pi in events:
        s = strokes[si]
        pid = s.pointer
        _, x, y = s.points[pi]
        last = pi == len(s.points) - 1
        if pi == 0:
            active[pid] = (x, y)
            order.append(pid)
            action = DOWN if len(active) == 1 else POINTER_DOWN
            idx = order.index(pid)
        elif last:
            active[pid] = (x, y)
            action = UP if len(active) == 1 else POINTER_UP
            idx = order.index(pid)
        else:
            active[pid] = (x, y)
            action, idx = MOVE, 0
        ptrs = " ".join(f"{p} {active[p][0]:.1f} {active[p][1]:.1f}" for p in order)
        lines.append(f"{int(round(t))} {action} {idx} {len(order)} {ptrs}")
        if last:
            del active[pid]
            order.remove(pid)
    return lines
