#!/usr/bin/env bash
# Capture a screenshot + uiautomator dump pair for the audit evidence folder.
# Usage: audit_capture.sh <label>
set -uo pipefail
export PATH="$HOME/.apex-toolchain/android-sdk/platform-tools:$PATH"
LABEL="${1:-shot}"
TS="$(cat /tmp/TS)"
ROOT="/workspace/project/apex-client-divkit/artifacts/android-audit/$TS"
mkdir -p "$ROOT/screenshots" "$ROOT/ui-dumps"
adb exec-out screencap -p > "$ROOT/screenshots/$LABEL.png" 2>/dev/null
adb shell rm -f /sdcard/ui.xml; adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1
adb shell cat /sdcard/ui.xml > "$ROOT/ui-dumps/$LABEL.xml" 2>/dev/null
echo "screenshot: $ROOT/screenshots/$LABEL.png"
echo "ui-dump:    $ROOT/ui-dumps/$LABEL.xml"
