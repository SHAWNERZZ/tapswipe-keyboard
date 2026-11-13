"""Start, stop, and guard the simulator's emulator.

The Android emulator (37.2.12) leaks host memory: every app process start inside it adds about
100 to 150 MB to the emulator process, and typing adds about 8 MB per word. None of it comes back.
Measured on 2026-11-12: a 4 GB phone grew to 32 GB and then hung. The leak is in the emulator, so
the harness works around it: scripts avoid app restarts, and `ensure` restarts the emulator when it
passes a memory limit or stops answering.
"""
from __future__ import annotations

import os
import subprocess
import time

from .device import ADB, SDK

AVD = "tapswipe-sim"
PORT = 5580
SERIAL = f"emulator-{PORT}"
LOG = os.environ.get("TYPING_SIM_EMULATOR_LOG", "/tmp/typing-sim-emulator.log")
DEFAULT_LIMIT_MB = 10_000


def pid() -> int | None:
    r = subprocess.run(["pgrep", "-f", f"qemu-system.*-avd {AVD}"], capture_output=True, text=True)
    pids = [int(p) for p in r.stdout.split()]
    return pids[0] if pids else None


def rss_mb() -> int | None:
    p = pid()
    if p is None:
        return None
    try:
        for line in open(f"/proc/{p}/smaps_rollup"):
            if line.startswith("Rss:"):
                return int(line.split()[1]) // 1024
    except OSError:
        return None
    return None


def booted() -> bool:
    r = subprocess.run([ADB, "-s", SERIAL, "shell", "getprop", "sys.boot_completed"],
                       capture_output=True, text=True, timeout=20)
    return r.stdout.strip() == "1"


def start(timeout: float = 600) -> None:
    """Boots the emulator in the background and waits until Android has finished booting."""
    if pid() is None:
        env = dict(os.environ, ANDROID_SDK_ROOT=SDK, ANDROID_HOME=SDK)
        # -gpu guest: the phone draws in software. The leak happens with swiftshader too, but this
        # keeps host graphics out of the picture.
        subprocess.Popen(
            [os.path.join(SDK, "emulator", "emulator"), "-avd", AVD, "-port", str(PORT), "-no-window",
             "-no-audio", "-no-boot-anim", "-gpu", "guest", "-no-snapshot", "-accel", "on"],
            stdout=open(LOG, "a"), stderr=subprocess.STDOUT, env=env, start_new_session=True)
    subprocess.run([ADB, "-s", SERIAL, "wait-for-device"], timeout=timeout)
    subprocess.run([ADB, "-s", SERIAL, "shell",
                    'while [ "$(getprop sys.boot_completed)" != 1 ]; do sleep 1; done'],
                   timeout=timeout)


def stop(timeout: float = 60) -> None:
    p = pid()
    if p is None:
        return
    subprocess.run([ADB, "-s", SERIAL, "emu", "kill"], capture_output=True, timeout=30)
    end = time.time() + timeout
    while time.time() < end and pid() is not None:
        time.sleep(1)
    if pid() is not None:
        subprocess.run(["kill", "-9", str(p)])
        time.sleep(2)


def ensure(limit_mb: int = DEFAULT_LIMIT_MB) -> bool:
    """Makes sure the emulator runs, answers, and is under the memory limit. Restarts it if not.

    Returns True when it restarted, so the caller knows to open a fresh field (and pay the cold
    start of the keyboard once).
    """
    rss = rss_mb()
    healthy = rss is not None
    if healthy:
        try:
            healthy = booted()
        except subprocess.TimeoutExpired:
            healthy = False
    if healthy and rss < limit_mb:
        return False
    reason = "not running" if rss is None else ("not answering" if not healthy else f"{rss} MB")
    print(f"  emulator: restarting ({reason})", flush=True)
    stop()
    start()
    from .device import Device
    d = Device(SERIAL)
    d.root()
    d.set_ime()
    return True
