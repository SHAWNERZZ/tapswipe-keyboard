"""Pause lengths from typing test recordings: inside words, before the space, and after it.

Usage: python3 pause_stats.py <recording.jsonl>...
"""
import json, sys, statistics as st
def pct(v, p):
    v = sorted(v); return v[min(len(v)-1, int(p/100*len(v)))] if v else None
inside_tap, inside_swipe, overlap, before_space, after_space, word_total = [], [], 0, [], [], []
for path in sys.argv[1:]:
    ev = [json.loads(l) for l in open(path)]
    lay = next(e for e in ev if e.get("type") == "layout")
    keys = lay["keys"]; sx, sy = lay["screenX"], lay["screenY"]
    def key_at(x, y):
        for k in keys:
            if k["x"] <= x < k["x"] + k["w"] and k["y"] <= y < k["y"] + k["h"]:
                return k["code"]
        return None
    # strokes: pointer down -> up, with down key, path length
    open_ = {}; strokes = []
    for e in ev:
        if e.get("type") != "touch": continue
        a = e["action"]; ids = e["ids"]
        if a in ("ACTION_DOWN", "ACTION_POINTER_DOWN"):
            i = e.get("index", 0); pid = ids[i]
            open_[pid] = {"down": e["et"], "x0": e["x"][i], "y0": e["y"][i], "x1": e["x"][i], "y1": e["y"][i], "len": 0}
        elif a == "ACTION_MOVE":
            for i, pid in enumerate(ids):
                s = open_.get(pid)
                if s:
                    s["len"] += abs(e["x"][i]-s["x1"]) + abs(e["y"][i]-s["y1"]); s["x1"], s["y1"] = e["x"][i], e["y"][i]
        elif a in ("ACTION_UP", "ACTION_POINTER_UP", "ACTION_CANCEL"):
            i = e.get("index", 0); pid = ids[i]; s = open_.pop(pid, None)
            if s:
                s["up"] = e["et"]; s["key"] = key_at(s["x0"], s["y0"])
                s["swipe"] = s["len"] > 40
                strokes.append(s)
    strokes.sort(key=lambda s: s["down"])
    # split into words at space-key taps; skip words containing backspace (code -5 or similar) taps
    word = []
    for s in strokes:
        if s["key"] == 32 and not s["swipe"]:
            letters = [w for w in word if w["key"] not in (None, 32) and (w["key"] > 0 or w["swipe"])]
            if letters and all(w["key"] is None or w["key"] > 0 or w["swipe"] for w in word):
                for a, b in zip(letters, letters[1:]):
                    g = b["down"] - a["up"]
                    if g < 0: overlap += 1
                    else: (inside_swipe if (a["swipe"] or b["swipe"]) else inside_tap).append(g)
                last_up = max(w["up"] for w in letters)
                before_space.append(s["down"] - last_up)
                word_total.append(last_up - letters[0]["down"])
            word = [] ; prev_space = s
            word.append({"key": None, "swipe": False, "down": s["down"], "up": s["up"], "space": True})
            continue
        if word and word[0].get("space") and len(word) == 1:
            after_space.append(s["down"] - word[0]["up"])
            word = []
        word.append(s)
def show(name, v):
    if v: print(f"{name:34} n={len(v):4} median={st.median(v):6.0f} p90={pct(v,90):6.0f} p99={pct(v,99):6.0f} max={max(v):6.0f} ms")
show("gap inside word, taps only", inside_tap)
show("gap inside word, swipe involved", inside_swipe)
print(f"{'strokes overlapping (two thumbs)':34} n={overlap}")
show("last lift -> space touch", before_space)
show("space lift -> next word touch", after_space)
show("word duration (first down-last up)", word_total)
print()
allin = inside_tap + inside_swipe
print("delay  inside-word gaps longer (wrong split)   words where the space would beat your own space press")
for T in (200, 250, 300, 350, 400, 500, 600, 800):
    wrong = sum(g > T for g in allin); words_hit = sum(b > T for b in before_space)
    print(f"{T:5}  {wrong:3}/{len(allin)} ({100*wrong/len(allin):4.1f}%)                       {100*words_hit/len(before_space):4.0f}%")
