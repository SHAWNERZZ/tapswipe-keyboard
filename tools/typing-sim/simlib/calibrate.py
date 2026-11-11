"""Measures how a person types, from a recording, to set the finger model.

Positions are in key units (fractions of a key's width and height) and speeds in key widths per
millisecond, so numbers from a phone carry over to the emulator's different screen.
"""
from __future__ import annotations

import math
import statistics
from collections import Counter

from .replay import ACTIONS


def _gestures(events: list[dict]) -> list[dict]:
    """Splits touches into one record per finger contact: start and end time, and its points."""
    active: dict[int, dict] = {}
    done: list[dict] = []
    for e in events:
        if e.get("type") != "touch":
            continue
        act = ACTIONS.get(e["action"])
        ids, xs, ys = e["ids"], e["x"], e["y"]
        if act in (0, 5):
            i = e.get("index", 0) if act == 5 else 0
            active[ids[i]] = {"t0": e["et"], "pts": [(e["et"], xs[i], ys[i])], "overlap": len(ids) > 1}
        elif act == 2:
            n = len(ids)
            for k, ht in enumerate(e.get("ht", [])):
                for i, pid in enumerate(ids):
                    if pid in active:
                        active[pid]["pts"].append((ht, e["hx"][k * n + i], e["hy"][k * n + i]))
            for i, pid in enumerate(ids):
                if pid in active:
                    active[pid]["pts"].append((e["et"], xs[i], ys[i]))
                    if n > 1:
                        active[pid]["overlap"] = True
        elif act in (1, 6):
            i = e.get("index", 0) if act == 6 else 0
            g = active.pop(ids[i], None)
            if g:
                g["pts"].append((e["et"], xs[i], ys[i]))
                g["t1"] = e["et"]
                g["overlap"] = g["overlap"] or len(ids) > 1
                done.append(g)
        elif act == 3:
            active.clear()
    return done


def _attach_strokes(events: list[dict], gestures: list[dict]) -> None:
    """Gives each gesture the keyboard's own reading of it: the `stroke` event right after its lift.
    Gestures with no stroke event were keys such as space or backspace."""
    strokes = [e for e in events if e.get("type") == "stroke"]
    j = 0
    for g in sorted(gestures, key=lambda g: g["t1"]):
        while j < len(strokes) and strokes[j]["t"] < g["t1"]:
            j += 1
        if j < len(strokes) and strokes[j]["t"] - g["t1"] <= 50:
            g["stroke"] = strokes[j]
            j += 1


def _layout_at(events: list[dict]):
    """Yields (event, newest settled layout) pairs in order."""
    lay = None
    for e in events:
        if e.get("type") == "layout" and e.get("viewW", 0) > 0:
            lay = e
        yield e, lay


def measure(events: list[dict]) -> dict:
    gestures = _gestures(events)
    _attach_strokes(events, gestures)
    # The layout in force at each gesture's start.
    layouts = []
    for e, lay in _layout_at(events):
        if e.get("type") == "touch" and lay is not None:
            layouts.append((e["et"], lay))

    def layout_for(t):
        best = None
        for lt, lay in layouts:
            if lt <= t:
                best = lay
            else:
                break
        return best

    tap_dx, tap_dy, holds, speeds, durs = [], [], [], [], []
    key_w_px = []
    for g in gestures:
        st = g.get("stroke")
        lay = layout_for(g["t0"])
        if st is None or lay is None:
            continue
        keys = lay["keys"]
        letters = [k for k in keys if 65 <= k["code"] <= 90 or 97 <= k["code"] <= 122]
        kw = statistics.median(k["w"] for k in letters) if letters else 100
        key_w_px.append(kw)
        def kb(x, y):
            return x - lay["padLeft"], y - lay["padTop"] + lay["verticalCorrection"]
        if st["kind"] == "tap":
            code = st.get("code", 0)
            key = next((k for k in keys if k["code"] in (code, code - 32, code + 32)), None)
            if key is None:
                continue
            _, x, y = g["pts"][0]
            kx, ky = kb(x, y)
            tap_dx.append((kx - (key["x"] + key["w"] / 2)) / key["w"])
            tap_dy.append((ky - (key["y"] + key["h"] / 2)) / key["h"])
            holds.append(g["t1"] - g["t0"])
        else:
            pts = g["pts"]
            length = sum(math.hypot(b[1] - a[1], b[2] - a[2]) for a, b in zip(pts, pts[1:]))
            dur = max(1, g["t1"] - g["t0"])
            durs.append(dur)
            speeds.append(length / dur / kw)

    # Gaps inside a word: lift of one stroke to touch of the next, while the stroke count rises.
    gaps = []
    word_gestures = sorted((g for g in gestures if "stroke" in g), key=lambda g: g["t0"])
    for a, b in zip(word_gestures, word_gestures[1:]):
        if b["stroke"].get("strokes", 0) == a["stroke"].get("strokes", 0) + 1 and b["t0"] > a["t1"]:
            gaps.append(b["t0"] - a["t1"])

    # Habits: the stroke pattern of each finished word.
    styles = Counter()
    kinds = []
    for e in events:
        if e.get("type") == "stroke":
            if e.get("strokes") == 1:
                kinds = []
            kinds.append("S" if e["kind"] == "swipe" else "T")
        elif e.get("type") == "finalize" and kinds:
            styles[_style_name(kinds)] += 1
            kinds = []
    overlap = sum(1 for g in gestures if g.get("overlap"))

    def summ(v):
        if not v:
            return None
        return {"n": len(v), "median": round(statistics.median(v), 4),
                "mean": round(statistics.fmean(v), 4),
                "sd": round(statistics.pstdev(v), 4) if len(v) > 1 else 0.0}

    return {
        "taps": {"dx_keys": summ(tap_dx), "dy_keys": summ(tap_dy), "hold_ms": summ(holds)},
        "swipes": {"speed_keyw_per_ms": summ(speeds), "duration_ms": summ(durs)},
        "gaps_in_word_ms": summ(gaps),
        "word_styles": dict(styles.most_common()),
        "overlapping_contacts": overlap,
        "phone_key_width_px": round(statistics.median(key_w_px), 1) if key_w_px else None,
    }


def _style_name(kinds: list[str]) -> str:
    """Names a stroke pattern in the simulator's style codes where one fits."""
    s = "".join(kinds)
    if s == "S":
        return "S"
    if set(s) == {"T"}:
        return "T"
    if s == "SS":
        return "S+S"
    if s.startswith("S") and set(s[1:]) == {"T"}:
        return "S+T"
    if s.endswith("S") and set(s[:-1]) == {"T"}:
        return "T+S"
    return "mix:" + s


def finger_params(m: dict) -> dict:
    """Turns measurements into FingerParams values. Swipe speed stays in key widths per millisecond
    (`swipe_speed_keyw`); the run converts it with the emulator's own key width."""
    out = {}
    t = m["taps"]
    if t["dx_keys"] and t["dy_keys"]:
        # The model scatters by one sigma for both axes. Use the larger, as the safer test.
        out["tap_sigma"] = round(max(t["dx_keys"]["sd"], t["dy_keys"]["sd"]), 3)
    if t["hold_ms"]:
        h = t["hold_ms"]
        out["tap_hold_ms"] = [max(20, round(h["median"] - h["sd"])), round(h["median"] + h["sd"])]
    sp = m["swipes"]["speed_keyw_per_ms"]
    if sp:
        out["swipe_speed_keyw"] = round(sp["median"], 5)
    g = m["gaps_in_word_ms"]
    if g:
        out["gap_ms"] = [max(20, round(g["median"] - g["sd"])), round(g["median"] + g["sd"])]
    return out
