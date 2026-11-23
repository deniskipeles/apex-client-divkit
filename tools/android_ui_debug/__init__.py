"""ADB + UIAutomator debugging toolkit for the Apex Android APK.

No third-party dependencies: the package only needs the Android platform-tools
``adb`` binary and Python 3.9+.
"""

from .adb import Adb, AdbError
from .apk import ApkError, ApkInfo, inspect_apk
from .ui import Bounds, UiNode, find_nodes, parse_ui_xml
from .scenario import ScenarioFailure, ScenarioRunner

__all__ = [
    "Adb",
    "AdbError",
    "ApkError",
    "ApkInfo",
    "inspect_apk",
    "Bounds",
    "UiNode",
    "find_nodes",
    "parse_ui_xml",
    "ScenarioFailure",
    "ScenarioRunner",
]
