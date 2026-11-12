"""Offline ranking experiment over replayed typing tests.

For each replayed word, takes the decoder's last high-beam result (top 4 with total, shape and
language-model scores) and asks: under a re-ranking rule, which candidate would win? Counts words
the rule fixes and words it breaks, against the intended prompt word.

Usage: python3 rerank_experiment.py runs/replaywords-A runs/replaywords-B ...
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from simlib.score import same  # noqa: E402


def cases(run_dir):
    """(intended word, last decode_detail) for every replayed word with a decoder result. Rows are
    paired with the emulator's finished words by count of landed words, so a merge or split does
    not shift the rest. Words in a note (not in the prompt) are left out."""
    from simlib.analyze import note_span
    meta = json.load(open(os.path.join(run_dir, "replay-words.json")))
    src = [json.loads(l) for l in open(meta["source"]) if l.strip()]
    skip = note_span(src)
    if skip:
        skip = set(range(min(skip) - 1, max(skip) + 1))  # the note's first word may be a prompt word
    rows = [r for r in meta["rows"] if not r["skipped"]]
    events = [json.loads(l) for l in open(os.path.join(run_dir, "recording.jsonl"))]
    finished_words = []  # (word, last decode_detail before it was finished)
    current, finished = None, 0
    for e in events:
        if e.get("type") == "decode_detail":
            current = e
        elif e.get("type") == "field":
            t = e["text"]
            toks = t.split()
            done = len(toks) if t and t[-1].isspace() else max(0, len(toks) - 1)
            while finished < done:
                finished_words.append((toks[finished] if finished < len(toks) else "", current))
                finished += 1
                current = None
    out, j = [], 0
    for r in rows:
        n = max(1, len((r.get("emulator") or "").split()))
        chunk = finished_words[j:j + n]
        j += n
        if n == 1 and chunk and chunk[0][1] is not None and r["index"] not in skip \
                and same(chunk[0][0], r.get("emulator")):
            out.append((r["word"], chunk[0][1]))
    return out


def pick(d, rule, k):
    words, total, shape, lm = d["words"], d["score"], d["ctc"], d["lm"]
    multi = d["left"] + d["right"] > 1
    best, best_s = None, None
    for i, w in enumerate(words):
        s = total[i]
        rest = total[i] - shape[i] - lm[i]
        if rule == "R1" and multi:
            s = total[i] + k * rest
        elif rule == "R2":
            lower_close = any(x[:1].islower() and total[i] - total[j] < k
                              for j, x in enumerate(words) if j != i)
            if w[:1].isupper() and lower_close:
                s = total[i] - k
        elif rule == "R3":
            s = total[i] + k * lm[i]
        if best_s is None or s > best_s:
            best, best_s = w, s
    return best


def main(dirs):
    data = []
    for d in dirs:
        data.extend(cases(d))
    base_right = sum(same(d["words"][0], w) for w, d in data)
    print(f"{len(data)} words with a decoder result; baseline right {base_right}")
    in_top4 = sum(any(same(x, w) for x in d["words"]) for w, d in data)
    print(f"intended word anywhere in the top 4: {in_top4}")
    for rule, ks in (("R1", [0.1, 0.2, 0.3, 0.5, 0.8]), ("R2", [0.5, 1.0, 2.0, 3.0]),
                     ("R3", [0.5, 1.0, 2.0])):
        for k in ks:
            fixed = broke = 0
            fixed_words, broke_words = [], []
            for w, d in data:
                was = same(d["words"][0], w)
                now = same(pick(d, rule, k), w)
                if now and not was:
                    fixed += 1
                    fixed_words.append(f"{w}<-{d['words'][0]}")
                if was and not now:
                    broke += 1
                    broke_words.append(f"{w}->{pick(d, rule, k)}")
            print(f"{rule} k={k:<4} fixes {fixed:2} breaks {broke:2}   fixed {fixed_words[:6]}  broke {broke_words[:6]}")


if __name__ == "__main__":
    main(sys.argv[1:])
