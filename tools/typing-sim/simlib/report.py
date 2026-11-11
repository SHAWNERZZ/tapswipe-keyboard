"""Writes a run's results as JSON and as a single self-contained HTML page."""
from __future__ import annotations

import html
import json
import os

from .score import Attempt


def write(run_dir: str, meta: dict, attempts: list[Attempt], summary: dict) -> str:
    os.makedirs(run_dir, exist_ok=True)
    with open(os.path.join(run_dir, "results.json"), "w") as f:
        json.dump({"meta": meta, "summary": summary, "attempts": [a.as_dict() for a in attempts]}, f, indent=1)

    e = html.escape
    rows = []
    for style, s in sorted(summary["by_style"].items()):
        v = ", ".join(f"{k} {n}" for k, n in sorted(s["verdicts"].items()))
        fl = ", ".join(f"{k} {n}" for k, n in sorted(s["flags"].items())) or "-"
        pen = s.get("penalty_vs_S")
        rows.append(f"<tr><td>{e(style)}</td><td>{s['n']}</td><td>{s['accuracy']*100:.1f}%</td>"
                    f"<td>{'' if pen is None else f'{pen*100:+.1f}'}</td><td>{e(v)}</td><td>{e(fl)}</td></tr>")
    fails = []
    for a in attempts:
        if a.verdict == "OK":
            continue
        plan = " + ".join(f"{k[0].upper()}:{t}" for k, t in a.plan)
        cands = ", ".join(f"{w} ({s})" for w, s in a.candidates[:4])
        fails.append(
            f"<tr><td>{e(a.word)}</td><td>{e(a.style)}</td><td>{e(plan)}</td><td>{e(a.verdict)}</td>"
            f"<td>{e(' | '.join(a.committed))}</td><td>{e(a.composing or '')}</td>"
            f"<td>{e(', '.join(a.flags))}</td><td>{e(a.probe or '')}</td><td>{e(cands)}</td>"
            f"<td>{'' if a.rank is None else a.rank}</td><td>{a.seed}</td></tr>")
    page = f"""<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Typing sim run</title><style>
:root{{--bg:#fff;--fg:#1b1b1b;--line:#ddd;--muted:#666}}
@media (prefers-color-scheme: dark){{:root{{--bg:#151515;--fg:#e8e8e8;--line:#333;--muted:#999}}}}
body{{background:var(--bg);color:var(--fg);font:14px/1.45 system-ui,sans-serif;margin:16px}}
table{{border-collapse:collapse;margin:8px 0 24px;display:block;overflow-x:auto}}
td,th{{border-bottom:1px solid var(--line);padding:4px 8px;text-align:left;white-space:nowrap}}
.muted{{color:var(--muted)}}
</style></head><body>
<h1>Typing sim run</h1>
<p class="muted">{e(json.dumps(meta))}</p>
<p><b>{summary['ok']}</b> of {summary['attempts']} words right ({summary['accuracy']*100:.1f}%).</p>
<h2>By style</h2>
<table><tr><th>Style</th><th>Words</th><th>Right</th><th>Penalty vs S (pts)</th><th>Verdicts</th><th>Flags</th></tr>
{''.join(rows)}</table>
<h2>Failures</h2>
<table><tr><th>Word</th><th>Style</th><th>Strokes</th><th>Verdict</th><th>Committed</th><th>Composing</th>
<th>Flags</th><th>After 1 backspace</th><th>Top candidates</th><th>Rank</th><th>Seed</th></tr>
{''.join(fails)}</table></body></html>"""
    path = os.path.join(run_dir, "report.html")
    with open(path, "w") as f:
        f.write(page)
    return path
