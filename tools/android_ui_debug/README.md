# android_ui_debug

Text-only ADB + UIAutomator harness for the Apex Android APK. No third-party Python
packages.

## Quick start

```bash
# from the repository root
python3 -m tools.android_ui_debug devices
python3 -m tools.android_ui_debug apk "apps/android/build/outputs/apk/debug/ApexClient Terminal-universal-debug.apk"
python3 -m tools.android_ui_debug dump --limit 120

python3 -m tools.android_ui_debug run \
  tools/android_ui_debug/scenarios/libsignal.json \
  --apk "apps/android/build/outputs/apk/debug/ApexClient Terminal-universal-debug.apk"

# The scenarios clear app data first, so they always start at the tenant hub.
python3 -m tools.android_ui_debug run tools/android_ui_debug/scenarios/embedding.json
```

Use the APK whose ABI matches the device. The x86_64 emulator has no MediaPipe text JNI;
the embedding tool falls back to a lexical vector there.

The full methodology, including the LibSignal case study and a new-tool checklist, is in
[`docs/testing/android-apk-debugging.md`](../../docs/testing/android-apk-debugging.md).

## Layout

| Path | Purpose |
| :--- | :--- |
| `apk.py` | `aapt`/`apkanalyzer` introspection: package id, launcher activity, version. |
| `adb.py` | `adb` discovery, install/launch, UI dumps with retries, taps/swipes, logcat. |
| `ui.py` | UIAutomator XML parser, bounds math, selector matching. |
| `scenario.py` | JSON scenario runner with polling, scrolling, artifacts, and crash checks. |
| `__main__.py` | `devices`, `apk`, `dump`, `tap`, `run` subcommands. |
| `scenarios/libsignal.json` | The regression flow that proved the E2EE fix on device. |
| `scenarios/embedding.json` | Text, text-similarity, and vector-similarity ops. |
| `scenarios/embedding_image.json` | MobileNetV4 image descriptor (1000-d). |
| `scenarios/embedding_image_text.json` | ML Kit OCR + text embedding, with an OCR assertion. |
| `scenarios/smoke.json` | Launch and no-crash check. |
| `tests/` | Parser and scenario-config unit tests (`python3 -m unittest discover`). |

## Scenario artifacts

Every `run` creates `artifacts/<name>-<timestamp>/` with `report.json`, the failing UI
XML, a labelled-node summary, and both crash and app-error logcat buffers. A failed step
exits non-zero, so it can gate CI.

Scenario actions include `tap` (scrolls by default), `tap_xy`, `wait_text`, `assert_text`
(`"scroll": true` looks below the fold), `scroll_to`, `swipe`, `back`, and
`launch` (`"clear_data": true` gives a deterministic first screen).
