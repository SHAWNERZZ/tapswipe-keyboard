"""Device access for the typing simulator, over adb. Emulator only: several steps need `adb root`."""
from __future__ import annotations

import json
import os
import subprocess
import tempfile
import time
import zipfile

SDK = os.environ.get("ANDROID_HOME", "/opt/android-sdk")
ADB = os.path.join(SDK, "platform-tools", "adb")
PKG = "org.futo.inputmethod.latin.unstable"
IME = f"{PKG}/org.futo.inputmethod.latin.LatinIME"
TARGET = f"{PKG}/org.futo.inputmethod.latin.tapswipe.TypingSimTargetActivity"
APP_DIR = f"/data/data/{PKG}"
PLAYER_DEX = "/data/local/tmp/touchplayer.dex"
HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


class Device:
    def __init__(self, serial: str = "emulator-5580"):
        self.serial = serial

    def adb(self, *args: str, check: bool = True, timeout: float = 60) -> str:
        r = subprocess.run([ADB, "-s", self.serial, *args], capture_output=True, text=True, timeout=timeout)
        if check and r.returncode != 0:
            raise RuntimeError(f"adb {' '.join(args)} failed: {r.stderr.strip() or r.stdout.strip()}")
        return r.stdout

    def sh(self, cmd: str, check: bool = True, timeout: float = 60) -> str:
        return self.adb("shell", cmd, check=check, timeout=timeout)

    def root(self) -> None:
        out = subprocess.run([ADB, "-s", self.serial, "root"], capture_output=True, text=True).stdout
        if "already running as root" not in out:
            time.sleep(2)
            self.adb("wait-for-device")

    def install(self, apk: str) -> None:
        self.adb("install", "-r", "-t", apk, timeout=300)

    def set_ime(self) -> None:
        self.sh(f"ime enable {IME}")
        self.sh(f"ime set {IME}")

    def push_player(self) -> None:
        dex = os.path.join(HERE, "player", "build", "classes.dex")
        if not os.path.exists(dex):
            raise RuntimeError("player not built: run `python sim.py build-player`")
        self.adb("push", dex, PLAYER_DEX)

    def import_settings(self, backup: str) -> list[str]:
        """Copies settings from the app's own export zip into the app's data folder.

        Brings over the settings datastore and the learned touch model. Clipboard history and the
        personal dictionary are left behind on purpose: the tests need neither.
        """
        wanted = {"datastore.preferences_pb": "files/datastore/settings.preferences_pb",
                  "tapswipe-touch-model.json": "files/tapswipe-touch-model.json"}
        done = []
        self.sh(f"am force-stop {PKG}")
        uid = self.sh(f"stat -c %u {APP_DIR}").strip()
        with zipfile.ZipFile(backup) as z, tempfile.TemporaryDirectory() as tmp:
            for name, dest in wanted.items():
                if name not in z.namelist():
                    continue
                local = z.extract(name, tmp)
                remote_tmp = f"/data/local/tmp/{os.path.basename(name)}"
                self.adb("push", local, remote_tmp)
                d = f"{APP_DIR}/{os.path.dirname(dest)}"
                self.sh(f"mkdir -p {d} && cp {remote_tmp} {APP_DIR}/{dest} && "
                        f"chown -R {uid}:{uid} {APP_DIR}/files && rm {remote_tmp}")
                done.append(dest)
        # Start from an empty history dictionary. It holds only what earlier simulator runs typed
        # (the user's own history is never copied here), and the swipe decoder reads it, so runs
        # drifted as the emulator learned from its own test typing.
        self.sh(f"rm -rf {APP_DIR}/files/UserHistoryDictionary.* {APP_DIR}/cache/historyLog.json", check=False)
        done.append("history cleared")
        # Force-stopping the app makes Android fall back to another keyboard. Select ours again.
        self.set_ime()
        return done

    def start_target(self, run_id: str) -> None:
        # A fresh activity per run, so each run gets its own recording. The keyboard process stays.
        self.sh(f"am start -W --activity-clear-task -n {TARGET} --es id {run_id}")

    def set_field_text(self, run_id: str, text: str) -> None:
        """Replaces the test field's text, keeping the activity, its recording, and the keyboard."""
        import shlex
        self.sh(f"am start -n {TARGET} -f 0x20000000 --es id {run_id} --es text {shlex.quote(text)}")

    def stop_app(self) -> None:
        self.sh(f"am force-stop {PKG}")

    def recording(self, run_id: str) -> list[dict]:
        out = self.sh(f"cat {APP_DIR}/files/recordings/{run_id}.jsonl", check=False)
        events = []
        for line in out.splitlines():
            line = line.strip()
            if line:
                try:
                    events.append(json.loads(line))
                except json.JSONDecodeError:
                    pass  # a line still being written
        return events

    def player(self) -> "Player":
        """A long-lived player process. Saves app_process start-up on every word."""
        return Player(self)

    def recording_since(self, run_id: str, offset: int) -> tuple[list[dict], int]:
        """Events after byte `offset`, and the new offset. Reads only new bytes, and only whole
        lines, so a line still being written is picked up next time."""
        path = f"{APP_DIR}/files/recordings/{run_id}.jsonl"
        r = subprocess.run([ADB, "-s", self.serial, "exec-out", f"tail -c +{offset + 1} {path}"],
                           capture_output=True, timeout=60)
        data = r.stdout
        end = data.rfind(b"\n")
        if end < 0:
            return [], offset
        events = []
        for line in data[:end].splitlines():
            line = line.strip()
            if line:
                try:
                    events.append(json.loads(line))
                except json.JSONDecodeError:
                    pass
        return events, offset + end + 1

    def play(self, lines: list[str]) -> str:
        with tempfile.NamedTemporaryFile("w", suffix=".txt", delete=False) as f:
            f.write("\n".join(lines) + "\n")
            local = f.name
        try:
            self.adb("push", local, "/data/local/tmp/ts.txt")
        finally:
            os.unlink(local)
        return self.sh(f"CLASSPATH={PLAYER_DEX} app_process / TouchPlayer /data/local/tmp/ts.txt",
                       timeout=120).strip()

    def wait_for_layout(self, run_id: str, timeout: float = 15) -> dict:
        """Waits until the recording holds a layout with letter keys, and returns the newest.

        If the keyboard has not shown after a few seconds, taps the text field once. A plain tap is
        enough to focus a field, so this uses `input tap`, not the player.
        """
        end = time.time() + timeout
        tapped = False
        start = time.time()
        while time.time() < end:
            if not tapped and time.time() - start > 4:
                self.sh("input tap 540 200", check=False)
                tapped = True
            layouts = [e for e in self.recording(run_id) if e.get("type") == "layout"]
            good = [ev for ev in layouts if ev.get("viewW", 0) > 0 and
                    any(97 <= k["code"] <= 122 or 65 <= k["code"] <= 90 for k in ev["keys"])]
            if good:
                # The keyboard slides in, and a layout taken mid-slide put every touch 126 px low.
                # Accept a position only if it is still the newest, unchanged, a moment later.
                pos = (good[-1]["screenX"], good[-1]["screenY"])
                time.sleep(0.8)
                again = [ev for ev in self.recording(run_id) if ev.get("type") == "layout"
                         and ev.get("viewW", 0) > 0]
                if again and (again[-1]["screenX"], again[-1]["screenY"]) == pos:
                    return again[-1]
                continue
            time.sleep(0.5)
        raise RuntimeError("no keyboard layout in the recording; is the keyboard showing?")


class Player:
    """Runs TouchPlayer in stdin mode over one adb shell, and plays batches through it."""

    def __init__(self, dev: Device):
        self.proc = subprocess.Popen(
            [ADB, "-s", dev.serial, "shell", f"CLASSPATH={PLAYER_DEX} app_process / TouchPlayer -"],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)
        first = self.proc.stdout.readline().strip()
        if first != "ready":
            raise RuntimeError(f"player did not start: {first}")

    def play(self, lines: list[str]) -> str:
        self.proc.stdin.write("\n".join(lines) + "\ngo\n")
        self.proc.stdin.flush()
        while True:
            out = self.proc.stdout.readline()
            if not out:
                raise RuntimeError("player exited")
            out = out.strip()
            if out.startswith("done"):
                return out

    def close(self) -> None:
        try:
            self.proc.stdin.write("quit\n")
            self.proc.stdin.flush()
            self.proc.wait(timeout=5)
        except Exception:
            self.proc.kill()
