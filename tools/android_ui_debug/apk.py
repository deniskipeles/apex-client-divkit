"""APK discovery: package id, launcher activity, and version, without screenshots."""

from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Optional


class ApkError(RuntimeError):
    pass


@dataclass
class ApkInfo:
    apk: str
    package: str
    activity: str
    version_name: str = ""
    version_code: str = ""


_PACKAGE_RE = re.compile(r"package: name='([^']+)'")
_ACTIVITY_RE = re.compile(r"launchable-activity: name='([^']+)'")
_VERSION_NAME_RE = re.compile(r"versionName='([^']*)'")
_VERSION_CODE_RE = re.compile(r"versionCode='([^']*)'")


def find_aapt() -> Optional[str]:
    candidates = []
    for key in ("AAPT", "AAPT2"):
        if os.environ.get(key):
            candidates.append(os.environ[key])
    for key in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        if os.environ.get(key):
            build_tools = Path(os.environ[key]) / "build-tools"
            if build_tools.is_dir():
                candidates.extend(str(p) for p in sorted(build_tools.glob("*/aapt"), reverse=True))
    home = Path.home()
    build_tools = home / ".apex-toolchain" / "android-sdk" / "build-tools"
    if build_tools.is_dir():
        candidates.extend(str(p) for p in sorted(build_tools.glob("*/aapt"), reverse=True))
    which = shutil.which("aapt")
    if which:
        candidates.append(which)
    for candidate in candidates:
        if candidate and Path(candidate).is_file() and os.access(candidate, os.X_OK):
            return candidate
    return None


def parse_badging(text: str, apk: str = "") -> ApkInfo:
    package = _PACKAGE_RE.search(text)
    activity = _ACTIVITY_RE.search(text)
    if not package:
        raise ApkError(f"aapt badging output has no package name:\n{text[:2000]}")
    return ApkInfo(
        apk=apk,
        package=package.group(1),
        activity=activity.group(1) if activity else "",
        version_name=(_VERSION_NAME_RE.search(text).group(1) if _VERSION_NAME_RE.search(text) else ""),
        version_code=(_VERSION_CODE_RE.search(text).group(1) if _VERSION_CODE_RE.search(text) else ""),
    )


def inspect_apk(apk: str | Path) -> ApkInfo:
    path = str(apk)
    if not Path(path).is_file():
        raise ApkError(f"APK not found: {path}")
    aapt = find_aapt()
    if aapt:
        proc = subprocess.run([aapt, "dump", "badging", path], capture_output=True, text=True)
        if proc.returncode == 0:
            return parse_badging(proc.stdout, path)
        raise ApkError(f"aapt failed ({proc.returncode}): {proc.stderr.strip()}")
    # Fallback: apkanalyzer is part of cmdline-tools and prints one value per invocation.
    analyzer = shutil.which("apkanalyzer")
    if not analyzer:
        for key in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
            if os.environ.get(key):
                candidate = Path(os.environ[key]) / "cmdline-tools" / "latest" / "bin" / "apkanalyzer"
                if candidate.is_file():
                    analyzer = str(candidate)
                    break
    if not analyzer:
        raise ApkError("neither aapt nor apkanalyzer is available; set AAPT or ANDROID_HOME")
    def run(*args: str) -> str:
        proc = subprocess.run([analyzer, *args, path], capture_output=True, text=True)
        if proc.returncode != 0:
            raise ApkError(f"apkanalyzer {' '.join(args)} failed: {proc.stderr.strip()}")
        return proc.stdout.strip()
    return ApkInfo(
        apk=path,
        package=run("manifest", "application-id"),
        activity=run("manifest", "launch-activity"),
    )


def apk_info_json(apk: str | Path) -> str:
    return json.dumps(asdict(inspect_apk(apk)), indent=2)
