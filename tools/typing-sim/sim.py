"""TapSwipe typing simulator. See docs/plans/TYPING_SIMULATOR.md and tools/typing-sim/README.md.

  python sim.py build-player
  python sim.py setup --apk <apk> [--settings <backup>]
  python sim.py run --list common-100 --styles S,T,S+T,T+S,S+S,S/S [--limit 50] [--seed 1]
  python sim.py replay-word <run_dir> <index>
"""
from __future__ import annotations

import argparse
import datetime
import glob
import json
import os
import random
import subprocess
import sys

from simlib import finger
from simlib.device import Device, SDK
from simlib.report import write as write_report
from simlib.runner import Session
from simlib.score import Attempt, summary

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
LISTS = os.path.join(REPO, "java", "assets-tapswipe", "typing-tests")
RUNS = os.path.join(HERE, "runs")


def read_list(name: str) -> list[str]:
    path = name if os.path.exists(name) else os.path.join(LISTS, name + ".txt")
    words = []
    with open(path) as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith("#"):
                words.extend(w for w in line.split() if w)
    return words


def cmd_build_player(_args) -> None:
    src = os.path.join(HERE, "player")
    out = os.path.join(src, "build")
    os.makedirs(out, exist_ok=True)
    jar = os.path.join(SDK, "platforms", "android-35", "android.jar")
    subprocess.run(["javac", "--release", "11", "-cp", jar, "-d", out, os.path.join(src, "TouchPlayer.java")],
                   check=True)
    d8 = sorted(glob.glob(os.path.join(SDK, "build-tools", "*", "d8")))[-1]
    subprocess.run([d8, "--min-api", "26", "--output", out, *glob.glob(os.path.join(out, "*.class"))], check=True)
    print("built", os.path.join(out, "classes.dex"))


def cmd_setup(args) -> None:
    dev = Device(args.serial)
    dev.root()
    if args.apk:
        dev.install(args.apk)
        print("installed", args.apk)
    dev.set_ime()
    dev.push_player()
    if args.settings:
        print("imported", dev.import_settings(args.settings))
    print("ready")


def cmd_run(args) -> None:
    dev = Device(args.serial)
    words = read_list(args.list)
    if args.shuffle:
        random.Random(args.seed).shuffle(words)
    if args.limit:
        words = words[:args.limit]
    styles = args.styles.split(",")
    stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    run_id = f"sim-{stamp}"
    run_dir = os.path.join(RUNS, run_id)
    params = finger.FingerParams()
    if args.params:
        with open(args.params) as f:
            for k, v in json.load(f).items():
                setattr(params, k, tuple(v) if isinstance(v, list) else v)
    sess = Session(dev, run_id, params)
    attempts: list[Attempt] = []
    rng = random.Random(args.seed)
    total = len(words) * len(styles)
    n = 0
    for w in words:
        for st in styles:
            n += 1
            seed = rng.randrange(1 << 30)
            a = sess.type_word(w.lower(), st, seed, probe=not args.no_probe)
            if a is None:
                continue
            attempts.append(a)
            mark = "ok " if a.verdict == "OK" else a.verdict
            print(f"[{n}/{total}] {st:4} {w:14} {mark} {' '.join(a.landed)} {' '.join(a.flags)} {'; '.join(a.resets)}",
                  flush=True)
    sess.close()
    meta = {"run": run_id, "list": args.list, "styles": styles, "seed": args.seed, "words": len(words),
            "serial": args.serial, "params": params.__dict__}
    s = summary(attempts)
    path = write_report(run_dir, meta, attempts, s)
    with open(os.path.join(run_dir, "recording.jsonl"), "w") as f:
        for e in dev.recording(run_id):
            f.write(json.dumps(e) + "\n")
    print(json.dumps({k: v for k, v in s.items() if k != "by_style"}))
    for st, v in sorted(s["by_style"].items()):
        print(f"  {st:4} {v['accuracy']*100:5.1f}%  {v['verdicts']}  {v['flags']}")
    print("report:", path)


def cmd_replay_word(args) -> None:
    """Types one attempt from an earlier run again, with the same seed and style."""
    with open(os.path.join(args.run_dir, "results.json")) as f:
        a = json.load(f)["attempts"][args.index]
    dev = Device(args.serial)
    stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    sess = Session(dev, f"replay-{stamp}", finger.FingerParams())
    r = sess.type_word(a["word"], a["style"], a["seed"])
    sess.close()
    print(json.dumps(r.as_dict() if r else None, indent=1))


def cmd_replay_recording(args) -> None:
    """Replays every touch of a recording (a typing test from the phone, or an earlier run) into a
    fresh field, and compares the texts word by word."""
    from simlib import replay
    from simlib.score import align
    with open(args.recording) as f:
        events = [json.loads(l) for l in f if l.strip()]
    dev = Device(args.serial)
    stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    run_id = f"replay-{stamp}"
    sess = Session(dev, run_id, finger.FingerParams())
    target = replay.Frame.of(sess.layout_event)
    lines = replay.script(events, target)
    if not lines:
        raise SystemExit("no touches with a layout in that recording")
    print(f"replaying {len(lines)} touch events, {int(lines[-1].split()[0]) / 1000:.1f} s")
    print(sess.player.play(lines))
    import time
    time.sleep(1.0)
    sess.close()
    emu = replay.final_text(dev.recording(run_id))
    phone = replay.final_text(events)
    prompt = replay.prompt_words(events)
    run_dir = os.path.join(RUNS, run_id)
    os.makedirs(run_dir, exist_ok=True)
    out = {"source": os.path.abspath(args.recording), "prompt": prompt, "phone": phone, "emulator": emu}
    reference = prompt if prompt else phone.split()
    out["phone_vs_reference"] = align(reference, phone.split())
    out["emulator_vs_reference"] = align(reference, emu.split())
    with open(os.path.join(run_dir, "replay.json"), "w") as f:
        json.dump(out, f, indent=1)
    with open(os.path.join(run_dir, "recording.jsonl"), "w") as f:
        for e in dev.recording(run_id):
            f.write(json.dumps(e) + "\n")
    ref_name = "prompt" if prompt else "phone"
    pm = sum(k == "MATCH" for k, _, _ in out["phone_vs_reference"])
    em = sum(k == "MATCH" for k, _, _ in out["emulator_vs_reference"])
    print(f"reference: {ref_name}, {len(reference)} words")
    if prompt:
        print(f"phone matched {pm}, emulator matched {em}")
    else:
        print(f"emulator matched {em} of the phone's {len(reference)} words")
    if prompt:
        from simlib.score import same as _same
        emu_events = dev.recording(run_id)
        fp = replay.first_try(events, len(prompt))
        fe = replay.first_try(emu_events, len(prompt))
        out["first_try"] = [{"word": w, "phone": a, "emulator": b} for w, a, b in zip(prompt, fp, fe)]
        print(f"first try right: phone {sum(_same(a, w) for w, a in zip(prompt, fp))}, "
              f"emulator {sum(_same(b, w) for w, b in zip(prompt, fe))} of {len(prompt)}")
        for w, a, b in zip(prompt, fp, fe):
            if _same(a, w) != _same(b, w):
                print(f"  first try differs: {w:12} phone={a!r:14} emulator={b!r}")
        with open(os.path.join(run_dir, "replay.json"), "w") as f:
            json.dump(out, f, indent=1)
    diffs = [(r, e) for r, e in zip(out["phone_vs_reference"], out["emulator_vs_reference"]) if r != e]
    for r, e in diffs[:40]:
        print(f"  {r[1] or '-':14} phone={r[2] or '-':14} emulator={e[2] or '-'}")
    print("saved", run_dir)


def cmd_replay_words(args) -> None:
    """Replays each prompt word's first attempt on its own, and compares first-try results with the
    phone. Words whose attempt holds a backspace are skipped: those touches may fix an earlier word."""
    import time
    from simlib import replay
    from simlib.runner import landed_words
    from simlib.score import same
    with open(args.recording) as f:
        events = [json.loads(l) for l in f if l.strip()]
    prompt = replay.prompt_words(events)
    if not prompt:
        raise SystemExit("not a typing test recording (no test_start)")
    phone_first = replay.first_try(events, len(prompt))
    windows = replay.word_windows(events, len(prompt))
    dev = Device(args.serial)
    stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    run_id = f"replaywords-{stamp}"
    sess = Session(dev, run_id, finger.FingerParams())
    target = replay.Frame.of(sess.layout_event)
    rows = []
    for w in windows:
        i = w["index"]
        row = {"index": i, "word": prompt[i], "phone": phone_first[i], "skipped": w["backspace"]}
        if not w["backspace"]:
            lines = replay.script(events, target, w["t_from"] + 1, w["t_to"])
            if lines:
                sess.new_events()
                sess.player.play(lines)
                time.sleep(0.5)
                after = sess.last_field_text(sess.new_events())
                if after is not None:
                    row["emulator"] = " ".join(landed_words(sess.field_text, after))
                    sess.field_text = after
        rows.append(row)
        if not row["skipped"]:
            mark = "same" if same(row.get("emulator"), row["phone"]) else "DIFF"
            print(f"#{i:3} {prompt[i]:12} phone={row['phone']!r:14} emulator={row.get('emulator')!r:14} {mark}",
                  flush=True)
    sess.close()
    compared = [r for r in rows if not r["skipped"]]
    pr = sum(same(r["phone"], r["word"]) for r in compared)
    er = sum(same(r.get("emulator"), r["word"]) for r in compared)
    print(f"compared {len(compared)} words ({len(rows) - len(compared)} skipped for backspaces): "
          f"first try right on phone {pr}, on emulator {er}")
    run_dir = os.path.join(RUNS, run_id)
    os.makedirs(run_dir, exist_ok=True)
    with open(os.path.join(run_dir, "replay-words.json"), "w") as f:
        json.dump({"source": os.path.abspath(args.recording), "rows": rows}, f, indent=1)
    print("saved", run_dir)


def cmd_analyze(args) -> None:
    """Per-word analysis of a phone typing test: technique, first shown word, decoder view, fixes."""
    from simlib import analyze
    with open(args.recording) as f:
        events = [json.loads(l) for l in f if l.strip()]
    a = analyze.analyze(events)
    print(analyze.report(a, events))
    if args.out:
        with open(args.out, "w") as f:
            json.dump(a, f, indent=1)


def cmd_stats(args) -> None:
    """Measures a recording, and prints finger model values for the emulator."""
    from simlib import calibrate
    with open(args.recording) as f:
        events = [json.loads(l) for l in f if l.strip()]
    m = calibrate.measure(events)
    params = calibrate.finger_params(m)
    print(json.dumps({"measured": m, "finger_params": params}, indent=1))
    if args.out:
        with open(args.out, "w") as f:
            json.dump(params, f, indent=1)
        print("wrote", args.out)


def cmd_compare(args) -> None:
    """Compares two runs: accuracy per style, and words whose verdict changed."""
    def load(d):
        with open(os.path.join(d, "results.json")) as f:
            return json.load(f)
    a, b = load(args.before), load(args.after)
    sa, sb = a["summary"], b["summary"]
    print(f"overall  {sa['accuracy']*100:5.1f}% -> {sb['accuracy']*100:5.1f}%  "
          f"({sa['attempts']} vs {sb['attempts']} attempts)")
    for st in sorted(set(sa["by_style"]) | set(sb["by_style"])):
        x = sa["by_style"].get(st, {}).get("accuracy")
        y = sb["by_style"].get(st, {}).get("accuracy")
        fx = "  -  " if x is None else f"{x*100:5.1f}"
        fy = "  -  " if y is None else f"{y*100:5.1f}"
        print(f"  {st:4} {fx}% -> {fy}%")
    key = lambda t: (t["word"], t["style"], t["seed"])
    before = {key(t): t for t in a["attempts"]}
    changed = 0
    for t in b["attempts"]:
        o = before.get(key(t))
        if o and (o["verdict"] != t["verdict"] or o.get("landed") != t.get("landed")):
            changed += 1
            print(f"  {t['style']:4} {t['word']:14} {o['verdict']:12} -> {t['verdict']:12} "
                  f"{' '.join(o.get('landed', []))} -> {' '.join(t.get('landed', []))}")
    if not changed:
        print("  no word changed verdict (same word, style, and seed)")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--serial", default="emulator-5580")
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("build-player").set_defaults(fn=cmd_build_player)
    p = sub.add_parser("setup")
    p.add_argument("--apk")
    p.add_argument("--settings", help="the app's settings export (.backup zip)")
    p.set_defaults(fn=cmd_setup)
    p = sub.add_parser("run")
    p.add_argument("--list", default="common-100")
    p.add_argument("--styles", default="S,T,S+T,T+S,S+S,S/S")
    p.add_argument("--limit", type=int, default=0)
    p.add_argument("--seed", type=int, default=1)
    p.add_argument("--shuffle", action="store_true")
    p.add_argument("--no-probe", action="store_true")
    p.add_argument("--params", help="finger model values from `stats --out`")
    p.set_defaults(fn=cmd_run)
    p = sub.add_parser("replay-word")
    p.add_argument("run_dir")
    p.add_argument("index", type=int)
    p.set_defaults(fn=cmd_replay_word)
    p = sub.add_parser("replay-words")
    p.add_argument("recording", help="a typing test recording from the phone")
    p.set_defaults(fn=cmd_replay_words)
    p = sub.add_parser("analyze")
    p.add_argument("recording")
    p.add_argument("--out")
    p.set_defaults(fn=cmd_analyze)
    p = sub.add_parser("stats")
    p.add_argument("recording")
    p.add_argument("--out", help="write the finger model values here, for `run --params`")
    p.set_defaults(fn=cmd_stats)
    p = sub.add_parser("compare")
    p.add_argument("before", help="a run folder")
    p.add_argument("after", help="a run folder")
    p.set_defaults(fn=cmd_compare)
    p = sub.add_parser("replay-recording")
    p.add_argument("recording", help="a .jsonl recording: a phone typing test, or a run's recording.jsonl")
    p.set_defaults(fn=cmd_replay_recording)
    args = ap.parse_args()
    args.fn(args)


if __name__ == "__main__":
    sys.exit(main())
