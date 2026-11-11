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
    sess = Session(dev, run_id, finger.FingerParams())
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
            print(f"[{n}/{total}] {st:4} {w:14} {mark} {' | '.join(a.committed)} {' '.join(a.flags)}",
                  flush=True)
    meta = {"run": run_id, "list": args.list, "styles": styles, "seed": args.seed, "words": len(words),
            "serial": args.serial, "params": finger.FingerParams().__dict__}
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
    print(json.dumps(r.as_dict() if r else None, indent=1))


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
    p.set_defaults(fn=cmd_run)
    p = sub.add_parser("replay-word")
    p.add_argument("run_dir")
    p.add_argument("index", type=int)
    p.set_defaults(fn=cmd_replay_word)
    args = ap.parse_args()
    args.fn(args)


if __name__ == "__main__":
    sys.exit(main())
