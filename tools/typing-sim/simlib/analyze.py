"""Per-word analysis of a phone typing test recording. Pure functions over the recorder's events.

For each prompt word: how it was typed (stroke pattern, second-finger taps, overlap), what the user
saw first, what the decoder considered, and how it was corrected.
"""
from __future__ import annotations

import statistics
from collections import Counter

from .replay import ACTIONS, action_name, first_try, prompt_words
from .score import same


def _contacts(events: list[dict]) -> list[dict]:
    """One record per finger contact: pointer number, start and end time, and whether another finger
    was down at any point."""
    active: dict[int, dict] = {}
    out = []
    for e in events:
        if e.get("type") != "touch":
            continue
        act = ACTIONS.get(action_name(e["action"]))
        ids = e["ids"]
        if act in (0, 5):
            pid = ids[e.get("index", 0)] if act == 5 else ids[0]
            active[pid] = {"pid": pid, "t0": e["et"], "overlap": len(ids) > 1}
        elif act == 2:
            for pid in ids:
                if pid in active and len(ids) > 1:
                    active[pid]["overlap"] = True
        elif act in (1, 6):
            pid = ids[e.get("index", 0)] if act == 6 else ids[0]
            c = active.pop(pid, None)
            if c:
                c["t1"] = e["et"]
                c["overlap"] = c["overlap"] or len(ids) > 1
                out.append(c)
        elif act == 3:
            active.clear()
    return out


def _stroke_contacts(events: list[dict]) -> dict[int, dict]:
    """Maps each stroke event (by its time) to the contact that produced it: the contact whose lift
    came just before it."""
    contacts = sorted(_contacts(events), key=lambda c: c["t1"])
    result = {}
    j = 0
    for e in events:
        if e.get("type") != "stroke":
            continue
        while j < len(contacts) and contacts[j]["t1"] < e["t"] - 50:
            j += 1
        best = None
        for c in contacts[j:j + 4]:
            if c["t1"] <= e["t"] + 5:
                best = c
        if best is not None:
            result[e["t"]] = best
    return result


def _assign_words(events: list[dict]) -> list[int]:
    """The prompt index each event belongs to: the number of finished words in the field just before
    it. Correcting an earlier word lowers the count, so those events go to that earlier word."""
    out = []
    cur = 0
    for e in events:
        out.append(cur)
        if e.get("type") == "field":
            t = e["text"]
            toks = t.split()
            cur = len(toks) if t and t[-1].isspace() else max(0, len(toks) - 1)
    return out


def analyze(events: list[dict]) -> dict:
    prompt = prompt_words(events)
    firsts = first_try(events, len(prompt))
    owner = _assign_words(events)
    by_stroke = _stroke_contacts(events)
    rows = []
    for i in range(len(prompt)):
        seg = [e for e, o in zip(events, owner) if o == i]
        if not seg or firsts[i] is None:
            continue
        first_stroke = next((k for k, e in enumerate(seg) if e.get("type") == "stroke"), None)
        if first_stroke is None:
            continue
        first_bs = next((k for k, e in enumerate(seg) if e.get("type") == "backspace"
                         and k > first_stroke), None)
        first_fin = next((k for k, e in enumerate(seg) if e.get("type") == "finalize"
                          and k > first_stroke), None)
        cut = min(x for x in (first_bs, first_fin, len(seg)) if x is not None)
        attempt = seg[first_stroke:cut]
        strokes = [e for e in attempt if e.get("type") == "stroke"]
        start = max((k for k, e in enumerate(strokes) if e.get("strokes") == 1), default=0)
        strokes = strokes[start:]
        pattern = "".join("S" if s["kind"] == "swipe" else "T" for s in strokes)
        second_finger_taps = sum(1 for s in strokes if s["kind"] == "tap"
                                 and by_stroke.get(s["t"], {}).get("pid") == 1)
        overlap = any(by_stroke.get(s["t"], {}).get("overlap") for s in strokes)
        decodes = [e for e in attempt if e.get("type") == "decode"]
        cands = decodes[-1].get("candidates", []) if decodes else []
        rank = next((r for r, c in enumerate(cands) if same(c, prompt[i])), None)
        if first_fin is not None and (first_bs is None or first_fin < first_bs):
            commits = [e for e in seg[first_fin:first_fin + 3] if e.get("type") == "commit"]
            seen = commits[0]["text"] if commits else firsts[i]
        else:
            fields = [e["text"] for e in attempt if e.get("type") == "field"]
            seen = fields[-1].split()[-1] if fields and fields[-1].split() else None
        tiers = [e["tier"] for e in seg if e.get("type") == "backspace_tier"]
        backspaces = sum(1 for e in seg if e.get("type") == "backspace")
        fixed_by_undo = False
        if first_bs is not None:
            after = seg[first_bs:first_bs + 40]
            undo = next((e for e in after if e.get("type") == "backspace_tier"), None)
            dec = next((e for e in after if e.get("type") == "decode"), None)
            fixed_by_undo = bool(undo and undo["tier"] in ("stroke_undo", "grace_reopen")
                                 and dec and same(dec.get("text"), prompt[i]))
        rows.append({
            "index": i, "word": prompt[i], "first_try": firsts[i], "right_first": same(firsts[i], prompt[i]),
            "seen_before_fix": seen, "pattern": pattern, "second_finger_taps": second_finger_taps,
            "overlap": overlap, "candidates": cands[:4], "rank": rank, "backspaces": backspaces,
            "tiers": tiers, "fixed_by_one_undo": fixed_by_undo,
            "resets": [e["reason"] for e in seg if e.get("type") == "session_reset"
                       and not str(e.get("reason", "")).startswith("finalizer")],
        })
    return {"prompt_words": len(prompt), "rows": rows}


def note_span(events: list[dict]) -> set[int]:
    """Prompt positions covered by words the user typed that are not in the prompt list, such as a
    note to the developer. Those positions say nothing about the keyboard."""
    prompt = prompt_words(events)
    vocab = {w.lower() for w in prompt}
    longest: list[str] = []
    for e in events:
        if e.get("type") == "field":
            toks = e["text"].split()
            if len([t for t in toks if t.lower().strip(".,!?") not in vocab]) > \
                    len([t for t in longest if t.lower().strip(".,!?") not in vocab]):
                longest = toks
    span = set()
    for k, t in enumerate(longest):
        if t.lower().strip(".,!?") not in vocab and k < len(prompt):
            span.add(k)
    # Fill the gaps inside the note, so its common words ("a", "or") are excluded too.
    if span:
        span = set(range(min(span), max(span) + 1))
    return span


def report(a: dict, events: list[dict]) -> str:
    skip = note_span(events)
    rows = [r for r in a["rows"] if r["index"] not in skip]
    lines = []
    start = next((e for e in events if e.get("type") == "test_start"), {})
    end = next((e for e in events if e.get("type") == "test_end"), {})
    lines.append(f"Build {start.get('appVersion')}, {start.get('device')}, list {start.get('list')}")
    if end:
        lines.append(f"{end.get('elapsedMs', 0) / 1000:.0f} s, {end.get('wpm', 0):.1f} WPM")
    total_bs = sum(1 for e in events if e.get("type") == "backspace")
    lines.append(f"Backspace presses: {total_bs}")
    if skip:
        lines.append(f"Left out: prompt positions {min(skip)}-{max(skip)}, covered by words not in the "
                     f"prompt (a note). {len(rows)} words analysed.")

    def pct(n, d):
        return f"{n}/{d} ({100 * n / d:.0f}%)" if d else "0/0"

    right = [r for r in rows if r["right_first"]]
    lines.append(f"Right on the first try: {pct(len(right), len(rows))}")
    seen_wrong = [r for r in rows if not same(r["seen_before_fix"], r["word"])]
    lines.append(f"Words that showed a wrong word before any fix: {len(seen_wrong)}")
    undo_fixed = [r for r in rows if r["fixed_by_one_undo"]]
    lines.append(f"  of which one backspace (stroke undo) gave the right word: {len(undo_fixed)}")

    lines.append("")
    lines.append("By technique (shown word before any fix is right):")
    groups = Counter()
    ok = Counter()
    for r in rows:
        p = r["pattern"] or "?"
        kind = ("one swipe" if p == "S" else "taps only" if set(p) == {"T"} else
                "swipes only" if set(p) == {"S"} else "swipes and taps")
        for g in (kind, "second-finger tap" if r["second_finger_taps"] else None,
                  "thumbs overlapped" if r["overlap"] else "one finger at a time"):
            if g:
                groups[g] += 1
                ok[g] += same(r["seen_before_fix"], r["word"])
    for g in ["one swipe", "swipes only", "taps only", "swipes and taps", "second-finger tap",
              "thumbs overlapped", "one finger at a time"]:
        if groups[g]:
            lines.append(f"  {g:22} {pct(ok[g], groups[g])}")

    lines.append("")
    lines.append("Every word that showed a wrong word:")
    for r in seen_wrong:
        flags = []
        if r["second_finger_taps"]:
            flags.append("second-finger tap")
        if r["overlap"]:
            flags.append("overlap")
        if r["fixed_by_one_undo"]:
            flags.append("ONE UNDO FIXED IT")
        if r["resets"]:
            flags.append("reset: " + "; ".join(x[:40] for x in r["resets"]))
        rank = "not in top 4" if r["rank"] is None else f"rank {r['rank'] + 1}"
        lines.append(f"  #{r['index']:<3} {r['word']:10} saw {r['seen_before_fix']!r:12} strokes {r['pattern']:7} "
                     f"intended {rank:12} cands {r['candidates']}  bs={r['backspaces']} {' '.join(flags)}")
    return "\n".join(lines)
