"""Would a calibrated offset per key fix words? Offline proof before building the calibration test.

1. Fit per-key tap offsets (key units) from a training recording. A key with few taps is pulled
   toward the mean of its keyboard half: offset = (n * key_median + PRIOR * half_mean) / (n + PRIOR).
2. Write a copy of a test recording whose touch points are moved by minus the offset of the nearest
   letter key. Moving the touches that way is what a keyboard that moved its keys by the offset
   would see.

Usage: python3 offset_experiment.py <train.jsonl> <test.jsonl> <out.jsonl> [--scale 1.0]
"""
import argparse
import json
import statistics
import sys
import os

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from simlib import calibrate  # noqa: E402

PRIOR = 5.0


def load(path):
    return [json.loads(l) for l in open(path) if l.strip()]


def letter_keys(layout):
    return [k for k in layout["keys"] if 65 <= k["code"] <= 90 or 97 <= k["code"] <= 122]


def fit(events):
    """Per letter: [dx, dy] in key units, shrunk toward its keyboard half."""
    g = calibrate._gestures(events)
    calibrate._attach_strokes(events, g)
    layout = [e for e in events if e.get("type") == "layout" and e.get("viewW", 0) > 0][-1]
    keys = letter_keys(layout)
    per_key, halves = {}, {"L": [], "R": []}
    for x in g:
        s = x.get("stroke")
        if not s or s["kind"] != "tap":
            continue
        key = next((k for k in keys if chr(k["code"]).lower() == chr(s["code"]).lower()), None)
        if key is None:
            continue
        _, px, py = x["pts"][0]
        kx, ky = px - layout["padLeft"], py - layout["padTop"] + layout["verticalCorrection"]
        d = ((kx - (key["x"] + key["w"] / 2)) / key["w"], (ky - (key["y"] + key["h"] / 2)) / key["h"])
        per_key.setdefault(chr(key["code"]).lower(), []).append(d)
        halves["L" if key["x"] + key["w"] / 2 < layout["kbW"] / 2 else "R"].append(d)
    half_mean = {h: (statistics.median(a for a, _ in v), statistics.median(b for _, b in v)) if v else (0, 0)
                 for h, v in halves.items()}
    out = {}
    for k in keys:
        ch = chr(k["code"]).lower()
        h = "L" if k["x"] + k["w"] / 2 < layout["kbW"] / 2 else "R"
        v = per_key.get(ch, [])
        n = len(v)
        mx = statistics.median(a for a, _ in v) if v else 0.0
        my = statistics.median(b for _, b in v) if v else 0.0
        out[ch] = [(n * mx + PRIOR * half_mean[h][0]) / (n + PRIOR),
                   (n * my + PRIOR * half_mean[h][1]) / (n + PRIOR)]
    return out, half_mean


def tap_samples(events):
    """(time, letter, dx, dy, half) for every tap, in key units."""
    g = calibrate._gestures(events)
    calibrate._attach_strokes(events, g)
    layout = [e for e in events if e.get("type") == "layout" and e.get("viewW", 0) > 0][-1]
    keys = letter_keys(layout)
    out = []
    for x in g:
        s = x.get("stroke")
        if not s or s["kind"] != "tap":
            continue
        key = next((k for k in keys if chr(k["code"]).lower() == chr(s["code"]).lower()), None)
        if key is None:
            continue
        _, px, py = x["pts"][0]
        kx, ky = px - layout["padLeft"], py - layout["padTop"] + layout["verticalCorrection"]
        half = "L" if key["x"] + key["w"] / 2 < layout["kbW"] / 2 else "R"
        out.append((x["t1"], chr(key["code"]).lower(),
                    (kx - (key["x"] + key["w"] / 2)) / key["w"], (ky - (key["y"] + key["h"] / 2)) / key["h"], half))
    return sorted(out), layout


def offsets_from(samples, layout):
    """Shrunk per-key offsets from a list of tap samples."""
    halves = {"L": [], "R": []}
    per_key = {}
    for _, ch, dx, dy, h in samples:
        halves[h].append((dx, dy))
        per_key.setdefault(ch, []).append((dx, dy))
    hm = {h: (statistics.median(a for a, _ in v), statistics.median(b for _, b in v)) if v else (0.0, 0.0)
          for h, v in halves.items()}
    out = {}
    for k in letter_keys(layout):
        ch = chr(k["code"]).lower()
        h = "L" if k["x"] + k["w"] / 2 < layout["kbW"] / 2 else "R"
        v = per_key.get(ch, [])
        n = len(v)
        mx = statistics.median(a for a, _ in v) if v else 0.0
        my = statistics.median(b for _, b in v) if v else 0.0
        out[ch] = [(n * mx + PRIOR * hm[h][0]) / (n + PRIOR), (n * my + PRIOR * hm[h][1]) / (n + PRIOR)]
    return out


def correct_causal(events, window, scale):
    """Corrects each touch with offsets fitted only from the last `window` taps before it."""
    samples, layout = tap_samples(events)
    cache = {}
    out = []
    for e in events:
        if e.get("type") != "touch":
            out.append(e)
            continue
        t = e["et"]
        prior = [s for s in samples if s[0] < t][-window:]
        key = len(prior)
        if key not in cache:
            cache[key] = offsets_from(prior, layout) if len(prior) >= 10 else {}
        out.extend(correct([layout, e], cache[key], scale)[1:])
    return out


def correct(events, offsets, scale):
    """Moves every touch point on the letter rows by minus its nearest letter key's offset."""
    layout = None
    out = []
    for e in events:
        if e.get("type") == "layout" and e.get("viewW", 0) > 0:
            layout = e
        if e.get("type") != "touch" or layout is None:
            out.append(e)
            continue
        keys = letter_keys(layout)
        bottom = max(k["y"] + k["h"] for k in keys)

        def move(x, y):
            kx, ky = x - layout["padLeft"], y - layout["padTop"] + layout["verticalCorrection"]
            if ky > bottom:
                return x, y
            k = min(keys, key=lambda k: (k["x"] + k["w"] / 2 - kx) ** 2 + (k["y"] + k["h"] / 2 - ky) ** 2)
            dx, dy = offsets.get(chr(k["code"]).lower(), (0, 0))
            return x - scale * dx * k["w"], y - scale * dy * k["h"]

        e = dict(e)
        n = len(e["ids"])
        pts = [move(x, y) for x, y in zip(e["x"], e["y"])]
        e["x"], e["y"] = [p[0] for p in pts], [p[1] for p in pts]
        if "ht" in e:
            hx, hy = list(e["hx"]), list(e["hy"])
            for i in range(len(hx)):
                hx[i], hy[i] = move(hx[i], hy[i])
            e["hx"], e["hy"] = hx, hy
        out.append(e)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("train")
    ap.add_argument("test")
    ap.add_argument("out")
    ap.add_argument("--scale", type=float, default=1.0)
    ap.add_argument("--causal", type=int, default=0, help="fit from the last N taps before each touch")
    a = ap.parse_args()
    if a.causal:
        with open(a.out, "w") as f:
            for e in correct_causal(load(a.test), a.causal, a.scale):
                f.write(json.dumps(e) + "\n")
        print("wrote", a.out, "(causal, window", a.causal, ")")
        return
    offsets, halves = fit(load(a.train))
    print("half medians (dx, dy) in keys:", {h: tuple(round(v, 3) for v in m) for h, m in halves.items()})
    print("per key dy:", {k: round(v[1], 2) for k, v in sorted(offsets.items())})
    with open(a.out, "w") as f:
        for e in correct(load(a.test), offsets, a.scale):
            f.write(json.dumps(e) + "\n")
    print("wrote", a.out)


if __name__ == "__main__":
    main()
