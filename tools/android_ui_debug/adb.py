"""Small, dependency-free wrapper around the Android ``adb`` CLI."""

from __future__ import annotations

import os
import re
import shlex
import shutil
import subprocess
import time
from pathlib import Path
from typing import Iterable, Optional


class AdbError(RuntimeError):
    """Raised when adb is missing or a command fails."""


def find_adb() -> str:
    """Locate ``adb`` from the environment, the Apex toolchain, or ``PATH``."""
    candidates = []
    for key in ("ADB", "ANDROID_ADB"):
        if os.environ.get(key):
            candidates.append(os.environ[key])
    for key in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        if os.environ.get(key):
            candidates.append(os.path.join(os.environ[key], "platform-tools", "adb"))
    home = Path.home()
    candidates.extend(
        [
            str(home / ".apex-toolchain" / "android-sdk" / "platform-tools" / "adb"),
            str(home / "Android" / "Sdk" / "platform-tools" / "adb"),
        ]
    )
    which = shutil.which("adb")
    if which:
        candidates.append(which)
    for candidate in candidates:
        if candidate and Path(candidate).is_file() and os.access(candidate, os.X_OK):
            return candidate
    raise AdbError(
        "adb not found. Set ADB=/path/to/adb, ANDROID_HOME, or install Android platform-tools."
    )


class Adb:
    def __init__(self, serial: Optional[str] = None, adb_path: Optional[str] = None, timeout: int = 60):
        self.adb = adb_path or find_adb()
        self.serial = serial
        self.timeout = timeout

    # ------------------------------------------------------------------ basics
    def _base(self) -> list[str]:
        base = [self.adb]
        if self.serial:
            base += ["-s", self.serial]
        return base

    def run(
        self,
        *args: str,
        check: bool = True,
        timeout: Optional[int] = None,
        binary: bool = False,
        input_bytes: Optional[bytes] = None,
    ):
        cmd = self._base() + [str(a) for a in args]
        proc = subprocess.run(
            cmd,
            input=input_bytes,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=timeout or self.timeout,
        )
        if check and proc.returncode != 0:
            raise AdbError(
                f"command failed ({proc.returncode}): {' '.join(shlex.quote(c) for c in cmd)}\n"
                f"stdout: {proc.stdout.decode('utf-8', 'replace').strip()}\n"
                f"stderr: {proc.stderr.decode('utf-8', 'replace').strip()}"
            )
        return proc.stdout if binary else proc.stdout.decode("utf-8", "replace")

    def shell(self, *args: str, check: bool = True, timeout: Optional[int] = None) -> str:
        return self.run("shell", *args, check=check, timeout=timeout)

    def devices(self) -> list[dict[str, str]]:
        out = self.run("devices", "-l")
        devices = []
        for line in out.splitlines()[1:]:
            line = line.strip()
            if not line or line.startswith("*"):
                continue
            parts = line.split()
            if len(parts) >= 2:
                devices.append({"serial": parts[0], "state": parts[1], "details": " ".join(parts[2:])})
        return devices

    def connect(self, host_port: str) -> str:
        return self.run("connect", host_port).strip()

    def ensure_device(self) -> None:
        devices = self.devices()
        if not devices:
            raise AdbError("no adb device found; start an emulator or pass --connect host:port")
        if self.serial is None:
            self.serial = devices[0]["serial"]
        states = {d["serial"]: d["state"] for d in devices}
        if states.get(self.serial) != "device":
            raise AdbError(f"device {self.serial} is not ready (state={states.get(self.serial)})")

    def wait_for_boot(self, timeout: int = 120) -> None:
        deadline = time.time() + timeout
        while time.time() < deadline:
            if self.shell("getprop", "sys.boot_completed", check=False).strip() == "1":
                return
            time.sleep(2)
        raise AdbError("device did not finish booting")

    # ------------------------------------------------------------- app control
    def install(self, apk: str, replace: bool = True) -> str:
        args = ["install"]
        if replace:
            args.append("-r")
        args.append(apk)
        return self.run(*args, timeout=300)

    def uninstall(self, package: str) -> str:
        return self.run("uninstall", package, check=False)

    def clear_logcat(self) -> None:
        self.run("logcat", "-c", check=False)

    def force_stop(self, package: str) -> None:
        self.shell("am", "force-stop", package, check=False)

    def start(self, package: str, activity: Optional[str] = None) -> str:
        self.force_stop(package)
        if activity:
            component = activity if "/" in activity else f"{package}/{activity}"
            return self.shell("am", "start", "-W", "-n", component)
        return self.shell(
            "monkey", "-p", package, "-c", "android.intent.category.LAUNCHER", "1"
        )

    def clear_data(self, package: str) -> str:
        return self.shell("pm", "clear", package)

    def grant(self, package: str, permission: str) -> str:
        return self.shell("pm", "grant", package, permission, check=False)

    def revoke(self, package: str, permission: str) -> str:
        return self.shell("pm", "revoke", package, permission, check=False)

    def pidof(self, package: str) -> Optional[str]:
        pid = self.shell("pidof", package, check=False).strip()
        return pid or None

    # ---------------------------------------------------------------- UI dumps
    def dump_ui(self, remote: str = "/sdcard/window_dump.xml", retries: int = 6) -> str:
        """Dump and return the UIAutomator XML.

        ``uiautomator dump`` intermittently fails with "null root node" while a screen
        is animating. Retrying is cheaper and more reliable than a fixed sleep.
        """
        last = ""
        for attempt in range(retries):
            out = self.shell("uiautomator", "dump", remote, check=False).strip()
            last = out
            if "dumped to" in out:
                return self.run("exec-out", "cat", remote, binary=True).decode("utf-8", "replace")
            time.sleep(0.4 * (attempt + 1))
        raise AdbError(f"uiautomator dump failed after {retries} attempts: {last}")

    # --------------------------------------------------------------- gestures
    def tap(self, x: int, y: int) -> None:
        self.shell("input", "tap", str(int(x)), str(int(y)))

    def long_press(self, x: int, y: int, duration_ms: int = 800) -> None:
        self.shell("input", "swipe", str(int(x)), str(int(y)), str(int(x)), str(int(y)), str(int(duration_ms)))

    def swipe(self, x1: int, y1: int, x2: int, y2: int, duration_ms: int = 300) -> None:
        self.shell("input", "swipe", str(int(x1)), str(int(y1)), str(int(x2)), str(int(y2)), str(int(duration_ms)))

    def keyevent(self, key: str | int) -> None:
        self.shell("input", "keyevent", str(key))

    def input_text(self, text: str) -> None:
        # `input text` uses %s for spaces and runs through the device shell, so quote it.
        escaped = text.replace("%", "%%").replace(" ", "%s")
        self.shell("input", "text", shlex.quote(escaped))

    def screen_size(self) -> tuple[int, int]:
        out = self.shell("wm", "size")
        match = re.search(r"(\d+)x(\d+)", out)
        if not match:
            return (1080, 1920)
        return (int(match.group(1)), int(match.group(2)))

    # ----------------------------------------------------------------- logcat
    def logcat(
        self,
        *,
        dump: bool = True,
        buffers: Optional[Iterable[str]] = None,
        pid: Optional[str] = None,
        priority: str = "E",
        lines: int = 4000,
    ) -> str:
        args = ["logcat"]
        if dump:
            args.append("-d")
        for buffer in buffers or ():
            args += ["-b", buffer]
        if pid:
            args += ["--pid", pid]
        args += [f"*:{priority}", "-t", str(lines)]
        return self.run(*args, check=False)

    def crash_log(self) -> str:
        return self.logcat(dump=True, buffers=["crash"], lines=4000)

    def app_error_log(self, package: str) -> str:
        pid = self.pidof(package)
        return self.logcat(dump=True, pid=pid, lines=4000) if pid else self.logcat(dump=True, lines=4000)
