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
    landed: list = field(default_factory=list)
    resets: list = field(default_factory=list)

    def as_dict(self) -> dict:
        return self.__dict__.copy()


def classify(a: Attempt, events: list[dict], landed: list[str] | None = None) -> Attempt:
    """Fills the attempt from the events recorded between its first touch and its commit.

    `landed` is the words the field gained during the attempt, from the field text before and after.
    It is the ground truth. Commit events only explain how the text got there: a word can land
    without one, when the keyboard's word state was already gone.
    """
    commits = [e for e in events if e.get("type") == "commit"]
    # A finalizer resets the session by design. Only other resets are evidence of a problem.
    resets = [e for e in events if e.get("type") == "session_reset"
              and not str(e.get("reason", "")).startswith("finalizer")]
    a.resets = [f"{r.get('reason')} ({r.get('strokes')} strokes)" for r in resets]
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

    words = landed if landed is not None else [w for c in a.committed for w in c.split()]
    a.landed = words
    if not words:
        a.verdict = "LOST"
    elif len(words) == 1 and same(words[0], a.word):
        a.verdict = "OK"
    elif len(words) > 1:
        a.verdict = "SPLIT"
    elif same(a.composing, a.word):
        a.verdict = "WRONG_COMMIT"
    else:
        a.verdict = "WRONG_DECODE"
    if words and not commits:
        a.flags.append("NO_COMMIT_EVENT")
    if resets:
        a.flags.append("SESSION_RESET")

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


def align(prompt: list[str], typed: list[str]) -> list[tuple[str, str | None, str | None]]:
    """Word alignment by edit distance, as TypingTestScore.align in the app.
    Returns (kind, prompt word, typed word) with kind MATCH, WRONG, MISSED, or EXTRA."""
    n, m = len(prompt), len(typed)
    d = [[0] * (m + 1) for _ in range(n + 1)]
    for i in range(n + 1):
        d[i][0] = i
    for j in range(m + 1):
        d[0][j] = j
    for i in range(1, n + 1):
        for j in range(1, m + 1):
            sub = d[i - 1][j - 1] + (0 if same(prompt[i - 1], typed[j - 1]) else 1)
            d[i][j] = min(sub, d[i - 1][j] + 1, d[i][j - 1] + 1)
    out = []
    i, j = n, m
    while i > 0 or j > 0:
        if i > 0 and j > 0:
            match = same(prompt[i - 1], typed[j - 1])
            if d[i][j] == d[i - 1][j - 1] + (0 if match else 1):
                out.append(("MATCH" if match else "WRONG", prompt[i - 1], typed[j - 1]))
                i -= 1
                j -= 1
                continue
        if i > 0 and d[i][j] == d[i - 1][j] + 1:
            out.append(("MISSED", prompt[i - 1], None))
            i -= 1
        else:
            out.append(("EXTRA", None, typed[j - 1]))
            j -= 1
    return out[::-1]
