"""Where did each letter of a known word land? Aligns a word's taps and swipes to its letters.

Used for calibration: the typing test knows the intended word, so every stroke can be attributed,
two-thumb words included, which the keyboard's own learner has to skip.

Method, per word attempt:
1. Strokes are grouped by pointer (0 left stream, 1 right stream). Each stream keeps its time order.
2. Every assignment of the word's letters to the two streams is tried (order kept within each).
   Within a stream, a dynamic program splits the stream's letters across its strokes: a tap takes
   exactly one letter and must match its key code; a swipe takes a run of one or more letters.
3. A swipe's cost for a run of letters is a dynamic time warping of its path against those letters'
   key centers. The lowest total cost wins.
4. For each letter, the landing point is: a tap's touch point; a swipe's first point for its first
   letter, last point for its last letter, and for a letter in between, the path point nearest the
   key center within the stretch the warping gave it.

All positions are in key units relative to the key center: (0, 0) is dead center, +1 is a key over.
"""
from __future__ import annotations

import itertools
import math

from .replay import ACTIONS, action_name

RESAMPLE = 24
MAX_COST_PER_POINT = 0.9
MAX_OFFSET = 0.8  # keys  # mean distance, in keys, past which an alignment is rejected as a misfit


def contacts(events):
    """Finger contacts in order: pointer, start, end, and points (view-local pixels)."""
    active, out = {}, []
    for e in events:
        if e.get("type") != "touch":
            continue
        act = ACTIONS.get(action_name(e["action"]))
        ids, n = e["ids"], len(e["ids"])
        if act in (0, 5):
            pid = ids[e.get("index", 0)] if act == 5 else ids[0]
            i = ids.index(pid)
            active[pid] = {"pid": pid, "t0": e["et"], "pts": [(e["x"][i], e["y"][i])]}
        elif act == 2:
            for k in range(len(e.get("ht", []))):
                for i, pid in enumerate(ids):
                    if pid in active:
                        active[pid]["pts"].append((e["hx"][k * n + i], e["hy"][k * n + i]))
            for i, pid in enumerate(ids):
                if pid in active:
                    active[pid]["pts"].append((e["x"][i], e["y"][i]))
        elif act in (1, 6):
            pid = ids[e.get("index", 0)] if act == 6 else ids[0]
            c = active.pop(pid, None)
            if c:
                i = ids.index(pid)
                c["pts"].append((e["x"][i], e["y"][i]))
                c["t1"] = e["et"]
                out.append(c)
        elif act == 3:
            active.clear()
    return out


class Keys:
    """Letter key geometry from a recorded layout, in key units."""

    def __init__(self, layout):
        self.lay = layout
        self.keys = {}
        for k in layout["keys"]:
            c = k["code"]
            if 65 <= c <= 90 or 97 <= c <= 122:
                self.keys[chr(c).lower()] = k

    def to_keyspace(self, x, y, ch):
        """A view-local point, as an offset from letter ch's center, in key units."""
        k = self.keys[ch]
        kx = x - self.lay["padLeft"]
        ky = y - self.lay["padTop"] + self.lay["verticalCorrection"]
        return (kx - (k["x"] + k["w"] / 2)) / k["w"], (ky - (k["y"] + k["h"] / 2)) / k["h"]

    def center(self, ch):
        k = self.keys[ch]
        return (k["x"] + k["w"] / 2 + self.lay["padLeft"],
                k["y"] + k["h"] / 2 + self.lay["padTop"] - self.lay["verticalCorrection"])

    def unit(self):
        k = next(iter(self.keys.values()))
        return k["w"], k["h"]


def resample(pts, n=RESAMPLE):
    if len(pts) == 1:
        return pts * n
    d = [0.0]
    for a, b in zip(pts, pts[1:]):
        d.append(d[-1] + math.hypot(b[0] - a[0], b[1] - a[1]))
    total = d[-1] or 1.0
    out, j = [], 0
    for i in range(n):
        target = total * i / (n - 1)
        while j < len(d) - 2 and d[j + 1] < target:
            j += 1
        seg = (d[j + 1] - d[j]) or 1.0
        f = (target - d[j]) / seg
        out.append((pts[j][0] + f * (pts[j + 1][0] - pts[j][0]), pts[j][1] + f * (pts[j + 1][1] - pts[j][1])))
    return out


def dtw(path, letters, keys):
    """Warps path points onto letters in order. Returns (mean cost in keys, per-letter point ranges)."""
    kw, kh = keys.unit()
    centers = [keys.center(c) for c in letters]
    n, m = len(path), len(letters)
    if m > n:
        return math.inf, None
    inf = math.inf
    cost = [[inf] * m for _ in range(n)]
    back = [[None] * m for _ in range(n)]

    def dist(p, c):
        return math.hypot((p[0] - c[0]) / kw, (p[1] - c[1]) / kh)

    cost[0][0] = dist(path[0], centers[0])
    for i in range(1, n):
        for j in range(m):
            stay = cost[i - 1][j]
            move = cost[i - 1][j - 1] if j > 0 else inf
            best, frm = (stay, j) if stay <= move else (move, j - 1)
            if best < inf:
                cost[i][j] = best + dist(path[i], centers[j])
                back[i][j] = frm
    if cost[n - 1][m - 1] == inf:
        return inf, None
    ranges = [[None, None] for _ in range(m)]
    j = m - 1
    for i in range(n - 1, -1, -1):
        ranges[j][0] = i if ranges[j][0] is None or i < ranges[j][0] else ranges[j][0]
        ranges[j][1] = i if ranges[j][1] is None else max(ranges[j][1], i)
        if i > 0:
            j = back[i][j]
    return cost[n - 1][m - 1] / n, ranges


def split_stream(strokes, letters, keys):
    """Splits one stream's letters across its strokes, in order. Returns (cost, [(stroke, run)])."""
    S, L = len(strokes), len(letters)
    memo = {}

    def go(s, l):
        if s == S:
            return (0.0, []) if l == L else (math.inf, None)
        key = (s, l)
        if key in memo:
            return memo[key]
        st = strokes[s]
        best = (math.inf, None)
        if st["kind"] == "tap":
            if l < L and letters[l] == st["char"]:
                c, rest = go(s + 1, l + 1)
                if c < best[0]:
                    best = (c, [(st, letters[l:l + 1])] + rest)
        else:
            for k in range(1, L - l + 1):
                run = letters[l:l + k]
                c0, _ = dtw(st["path"], run, keys)
                if c0 == math.inf:
                    continue
                c, rest = go(s + 1, l + k)
                if c0 + c < best[0]:
                    best = (c0 + c, [(st, run)] + rest)
        memo[key] = best
        return best

    return go(0, 0)


def align_word(word, strokes, keys):
    """Best attribution of the word's letters to its strokes. Returns (cost, [(stroke, run)])."""
    word = [c for c in word.lower() if c in keys.keys]
    left = [s for s in strokes if s["pid"] != 1]
    right = [s for s in strokes if s["pid"] == 1]
    best = (math.inf, None)
    if not right:
        return split_stream(left, word, keys)
    for mask in itertools.product((0, 1), repeat=len(word)):
        lw = [c for c, b in zip(word, mask) if b == 0]
        rw = [c for c, b in zip(word, mask) if b == 1]
        if (left and not lw) or (right and not rw):
            continue
        cl, al = split_stream(left, lw, keys)
        if cl == math.inf:
            continue
        cr, ar = split_stream(right, rw, keys)
        if cl + cr < best[0]:
            best = (cl + cr, al + ar)
    return best


def landings(word, strokes, keys):
    """[(letter, kind, dx, dy)] for each letter of the word, or [] if no alignment fits."""
    cost, plan = align_word(word, strokes, keys)
    if plan is None or cost == math.inf:
        return []
    swipes = sum(1 for st, _ in plan if st["kind"] == "swipe")
    if swipes and cost / swipes > MAX_COST_PER_POINT:
        return []
    out = []
    for st, run in plan:
        if st["kind"] == "tap":
            x, y = st["raw"][0]
            out.append((run[0], "tap") + keys.to_keyspace(x, y, run[0]))
            continue
        raw = st["raw"]
        if len(run) == 1:
            x, y = min(raw, key=lambda p: sum(v * v for v in keys.to_keyspace(p[0], p[1], run[0])))
            out.append((run[0], "swipe-single") + keys.to_keyspace(x, y, run[0]))
            continue
        _, ranges = dtw(st["path"], run, keys)
        for idx, ch in enumerate(run):
            if idx == 0:
                x, y = raw[0]
                kind = "swipe-start"
            elif idx == len(run) - 1:
                x, y = raw[-1]
                kind = "swipe-end"
            else:
                a, b = ranges[idx]
                seg = st["path"][a:b + 1]
                x, y = min(seg, key=lambda p: sum(v * v for v in keys.to_keyspace(p[0], p[1], ch)))
                kind = "swipe-mid"
            out.append((ch, kind) + keys.to_keyspace(x, y, ch))
    return out


def collect(events):
    """Landings for every prompt word's first attempt in a typing test recording.

    Returns (landings, stats): landings is [(word, letter, kind, dx, dy)].
    """
    from .analyze import _assign_words
    from .replay import prompt_words
    prompt = prompt_words(events)
    owner = _assign_words(events)
    layouts = [e for e in events if e.get("type") == "layout" and e.get("viewW", 0) > 0]
    keys = Keys(layouts[-1])
    cons = sorted(contacts(events), key=lambda c: c["t1"])
    out, stats = [], {"words": 0, "aligned": 0, "rejected": 0, "two_stream": 0}
    for i, word in enumerate(prompt):
        seg = [e for e, o in zip(events, owner) if o == i]
        strokes_ev = []
        for e in seg:
            if e.get("type") == "stroke":
                if e.get("strokes") == 1:
                    strokes_ev = []
                strokes_ev.append(e)
            if e.get("type") in ("backspace", "finalize") and strokes_ev:
                break
        if not strokes_ev:
            continue
        stats["words"] += 1
        strokes = []
        used = set()
        for s in strokes_ev:
            # The contact whose lift came just before this stroke event.
            c = None
            for k, cc in enumerate(cons):
                if k in used or cc["t1"] > s["t"] + 5:
                    continue
                if s["t"] - cc["t1"] <= 60:
                    c = (k, cc)
            if c is None:
                strokes = []
                break
            used.add(c[0])
            cc = c[1]
            st = {"kind": s["kind"], "pid": cc["pid"], "raw": cc["pts"], "path": resample(cc["pts"])}
            if s["kind"] == "tap":
                st["char"] = chr(s["code"]).lower()
            strokes.append(st)
        if not strokes:
            continue
        if any(s["pid"] == 1 for s in strokes):
            stats["two_stream"] += 1
        cost, plan = align_word(word, strokes, keys)
        if plan is None:
            stats["no_fit"] = stats.get("no_fit", 0) + 1
            continue
        got = landings(word, strokes, keys)
        if got:
            stats["aligned"] += 1
            # Middle-of-swipe letters are often never drawn: the lexicon fills them in. Keep taps
            # and swipe endpoints, and drop anything over MAX_OFFSET from its key, which cannot have
            # been aimed at it.
            out.extend((word,) + g for g in got
                       if g[1] != "swipe-mid" and abs(g[2]) <= MAX_OFFSET and abs(g[3]) <= MAX_OFFSET)
        else:
            stats["too_costly"] = stats.get("too_costly", 0) + 1
    return out, stats
