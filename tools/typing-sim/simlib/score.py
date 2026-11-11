"""Scores one typed word from the recorder events of its attempt. Pure functions.

Verdicts (see docs/plans/TYPING_SIMULATOR.md):
  OK            committed text is the intended word
  WRONG_DECODE  the composing text was already wrong before the finalizer
  WRONG_COMMIT  the composing text was right, but a different word was committed
  SPLIT         the attempt committed more than one word
  LOST          nothing was committed
Flags:
  BACKSPACE_RECOVERS  one backspace after the commit brought back the intended word (bug A)
  LAST_STROKE_FLIP    the decode of every stroke but the last was the intended word
"""
from __future__ import annotations

from dataclasses import dataclass, field

PUNCT = ".,!?;:"


def norm(s: str | None) -> str:
    return (s or "").strip().lower().rstrip(PUNCT)


def same(a: str | None, b: str | None) -> bool:
    return norm(a) == norm(b) and norm(a) != ""


@dataclass
class Attempt:
    word: str
    style: str
    plan: list
    seed: int
    verdict: str = ""
    committed: list = field(default_factory=list)
    composing: str | None = None
    autocorrection: str | None = None
    verbatim: bool | None = None
    candidates: list = field(default_factory=list)
    rank: int | None = None
    readings: dict = field(default_factory=dict)
    flags: list = field(default_factory=list)
    probe: str | None = None
    script_lines: int = 0

    def as_dict(self) -> dict:
        return self.__dict__.copy()


def classify(a: Attempt, events: list[dict]) -> Attempt:
    """Fills the attempt from the events recorded between its first touch and its commit."""
    commits = [e for e in events if e.get("type") == "commit"]
    finals = [e for e in events if e.get("type") == "finalize"]
    decodes = [e for e in events if e.get("type") == "decode"]
    a.committed = [c.get("text", "") for c in commits]
    if finals:
        a.composing = finals[-1].get("typedWord")
        a.autocorrection = finals[-1].get("autoCorrection")
        a.verbatim = finals[-1].get("verbatim")
    if decodes:
        last = decodes[-1]
        a.candidates = list(zip(last.get("candidates", []), last.get("scores", [])))
        for i, (w, _) in enumerate(a.candidates):
            if same(w, a.word):
                a.rank = i
                break
        for d in decodes:
            if d.get("covered", -1) > 0 and d.get("text"):
                a.readings[d["covered"]] = d["text"]

    if not commits:
        a.verdict = "LOST"
    elif len(commits) > 1 or " " in commits[-1].get("text", "").strip():
        a.verdict = "OK" if same(" ".join(a.committed), a.word) else "SPLIT"
    elif same(commits[-1].get("text"), a.word):
        a.verdict = "OK"
    elif same(a.composing, a.word):
        a.verdict = "WRONG_COMMIT"
    else:
        a.verdict = "WRONG_DECODE"

    strokes = len(a.plan)
    if a.verdict != "OK" and strokes > 1:
        before = a.readings.get(strokes - 1)
        final = a.readings.get(strokes)
        if same(before, a.word) and not same(final, a.word):
            a.flags.append("LAST_STROKE_FLIP")
    return a


def probe_result(a: Attempt, field_text: str) -> None:
    """Records what one backspace after the commit left in the field."""
    words = field_text.split()
    a.probe = words[-1] if words else ""
    if same(a.probe, a.word):
        a.flags.append("BACKSPACE_RECOVERS")


def summary(attempts: list[Attempt]) -> dict:
    by_style: dict[str, dict] = {}
    for a in attempts:
        s = by_style.setdefault(a.style, {"n": 0, "ok": 0, "verdicts": {}, "flags": {}})
        s["n"] += 1
        s["ok"] += a.verdict == "OK"
        s["verdicts"][a.verdict] = s["verdicts"].get(a.verdict, 0) + 1
        for f in a.flags:
            s["flags"][f] = s["flags"].get(f, 0) + 1
    for s in by_style.values():
        s["accuracy"] = s["ok"] / s["n"] if s["n"] else 0.0
    base = by_style.get("S", {}).get("accuracy")
    if base is not None:
        # Style penalty: accuracy lost against one plain swipe of the same words.
        for name, s in by_style.items():
            s["penalty_vs_S"] = round(base - s["accuracy"], 4)
    total = len(attempts)
    ok = sum(a.verdict == "OK" for a in attempts)
    return {"attempts": total, "ok": ok, "accuracy": ok / total if total else 0.0, "by_style": by_style}
