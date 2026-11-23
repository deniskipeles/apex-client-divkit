# Debugging the Real Android APK (text-only, no screenshots)

This is the workflow used to find and fix the LibSignal E2EE failures, and then the
embedding-tool gaps, on the built APK. It needs only `adb`, the APK, and a device or
emulator. Everything is observed through the UIAutomator XML tree and `logcat`; no image
analysis is required.

The runnable implementation is in [`tools/android_ui_debug`](../../tools/android_ui_debug).
It turns the manual loop below into repeatable JSON scenarios.

---

## 0. The loop in one line

```
install -> clear logcat -> launch -> uiautomator dump -> parse XML -> tap bounds center
        -> re-dump -> assert -> read logcat on failure -> fix -> rebuild -> repeat
```

Every action is followed by a fresh dump. A dump is the source of truth for what the user
can see; `logcat` is the source of truth for why it failed.

---

## 1. Find the device and the APK

```bash
adb devices -l
# If the emulator is remote:
adb connect host.docker.internal:5555

APK=apps/android/build/outputs/apk/debug/ApexClient\ Terminal-universal-debug.apk
python3 -m tools.android_ui_debug apk "$APK"
# or directly:
aapt dump badging "$APK" | grep -E 'package:|launchable-activity'
```

The Apex app is `com.aistudio.sduiposengine.kmpdiv`, launcher activity
`com.example.MainActivity` (the manifest's namespace is `com.example`).

`tools/android_ui_debug` discovers `adb` from `ADB`, `ANDROID_HOME`,
`ANDROID_SDK_ROOT`, `~/.apex-toolchain/android-sdk/platform-tools/adb`, or `PATH`.

---

## 2. Install, clear, launch

```bash
adb install -r -g "$APK"          # -g grants runtime permissions on install
adb logcat -c
adb shell am start -W -n com.aistudio.sduiposengine.kmpdiv/com.example.MainActivity
```

Always clear logcat **before** launch. Otherwise a crash from a previous run is attributed
to the new one. `am start -W` waits for the launch and reports `Status: ok`; the first
frame can still take 15-25 s while the demo workspace seeds SQLite and model assets.

For a repeatable scenario, clear app data first: `adb shell pm clear <package>`. The app
reseeds the demo workspace on the next launch, and the hub is guaranteed to be the first
screen. Without this, Android may restore the last route and a scenario that assumes the
hub will scroll the wrong screen forever. The Python runner exposes this as
`{"action": "launch", "clear_data": true}`.

---

## 3. Dump and parse the screen

```bash
adb shell uiautomator dump /sdcard/ui.xml
adb exec-out cat /sdcard/ui.xml > /tmp/ui.xml
python3 - <<'PY'
import xml.etree.ElementTree as ET
root = ET.parse('/tmp/ui.xml').getroot()
for n in root.iter('node'):
    text = n.get('text') or ''
    desc = n.get('content-desc') or ''
    if text or desc:
        print(f"{text!r:60} {desc!r:30} {n.get('bounds')} clickable={n.get('clickable')}")
PY
```

Important details:

- `uiautomator dump` sometimes prints `ERROR: could not get idle state` or
  `null root node` while a screen animates. Retry 3-6 times with a short backoff. The
  Python wrapper does this; a bare shell loop that gives up on the first error will fail
  intermittently.
- The dump is a snapshot. Compose recomposes after a tap; re-dump before asserting.
- `text` and `content-desc` are separate. DivKit buttons usually expose `text`; icon-only
  buttons use `content-desc`.
- `resource-id` is often empty for Compose/DivKit. Match visible text instead.
- Bounds are `[left,top][right,bottom]`. Tap the integer center:
  `x=(left+right)//2`, `y=(top+bottom)//2`.
- Long text can be split across several nodes. Match with `contains` or a regex, not an
  exact string, for titles and status lines.

### Why XML parsing, not regex

The original manual attempt used a regex over the raw XML. Attributes are quoted with
either `'` or `"`, entities are escaped, and the dump may contain newlines inside text.
`xml.etree.ElementTree` handles all of that. Use it.

---

## 4. Scroll before tapping

A `uiautomator dump` contains only composed/visible nodes. The demo hub is a long grid of
tool cards; buttons below the fold are absent from the XML, so `find` returns nothing and
a blind tap hits the wrong element.

Swipe with a finger **up** to reveal lower content, then dump again:

```bash
adb shell input swipe 540 1600 540 500 300
```

`scroll_to` in the toolkit repeats `dump -> find -> swipe` up to `max_swipes` times. Use
`scroll_direction: "up"` to go back toward the top.

A tap on a text node does not always reach the clickable card that wraps it. If a semantic
tap leaves the screen unchanged, tap the parent's bounds center, or use an explicit
`{"action": "tap_xy", "x": 283, "y": 687}`. Then wait for a marker from the next screen
(`ACTIVE TERMINAL CAPABILITIES`) before scrolling again.

---

## 5. Wait for asynchronous work

Embedding, OCR, and LibSignal calls are asynchronous. A dump taken immediately after the
tap still shows the previous variable values. Poll the XML until the expected text
appears (or disappears), with a timeout:

```python
deadline = time.time() + 30
while time.time() < deadline:
    nodes = parse_ui_xml(adb.dump_ui())
    if find_nodes(nodes, contains="DECRYPTED"):
        break
    time.sleep(0.5)
else:
    raise AssertionError("DECRYPTED never appeared")
```

For ML Kit OCR, allow 60 s on a cold emulator. A `logcat` line such as
`PipelineManager: OCR process succeeded` confirms the model actually ran; the card may
update a little later.

---

## 6. Read the failure from logcat

```bash
adb logcat -d -b crash
adb logcat -d --pid=$(adb shell pidof -s com.aistudio.sduiposengine.kmpdiv) '*:E'
```

Look for:

- `FATAL EXCEPTION` plus `Process: com.aistudio.sduiposengine.kmpdiv` — a Java/Kotlin crash.
- `ANR in com.aistudio.sduiposengine.kmpdiv` — main-thread block.
- `E/DivKit` / `E/EngineRouter` — action dispatch errors, including tool failures.
- `UnsatisfiedLinkError` — a native library or TFLite JNI missing (unit tests cannot load it).
- `NoSuchMethodException: <init> []` — a release-only R8 keep-rule bug; reproduce with a
  debug build first, then check ProGuard rules.

The tool result is also rendered in the card. A failed tool usually sets
`user.engine_error`; dump and read that line before guessing.

---

## 7. The LibSignal case study

What the XML showed after the four buttons:

```
Status: ESTABLISHED
Ciphertext: MwgDEiEF...
Decrypted: {"order":"#1042","total":"18.50","terminal":"till-07"}
Safety number: 133650570450770696718167269354915747401852798198450987416227
Verification: VERIFIED
```

The bugs found with this loop:

1. **Long numeric action arguments were coerced to numbers.** The safety number is a
   60-digit string; `DivKitMultiplatform` parsed it as a number, lost precision, and sent
   a different value to `verify_identity`. Fix: keep long digit-only action args as text.
2. **Tool failures were reported as handled.** `EngineRouter` treated `FAILED`,
   `UNSUPPORTED`, and `DENIED` as `Handled`, so the card showed stale values and no error.
   Fix: map those statuses to the error path and publish `user.engine_error`.
3. **`verify_identity` minted a fingerprint for a nonexistent session.** Fix: refuse when
   there is no session; `setupIdentity` was also fixed.

The regression tests pin the text-preservation rule:
`DemoCardRenderTest.libsignalVerifyPayloadKeepsLongSafetyNumberAsText`.

The device regression is `tools/android_ui_debug/scenarios/libsignal.json`; it launches
with clean data, opens the demo tenant, scrolls to the LibSignal card, runs establish ->
encrypt -> decrypt -> verify, asserts the plaintext and `VERIFIED`, and checks logcat for
crashes. A full run takes about three minutes on a TCG emulator.

### The embedding case study

The embedding card initially showed `Embedding failed: com.google.mediapipe.tasks.text...`
in the card's status line. The UI XML was enough to locate the failure; `logcat` gave the
cause:

```
UnsatisfiedLinkError: ... couldn't find "libmediapipe_tasks_text_jni.so"
  at com.google.mediapipe.tasks.text.textembedder.TextEmbedder.<clinit>
  at engine.tools.NativeToolsSuite.executeEmbedding(NativeToolsSuite.kt:823)
```

MediaPipe `tasks-text` 0.10.14 ships the text JNI for arm64-v8a and armeabi-v7a only, so
the x86_64 emulator cannot load it. The APK was not broken: the arm64 split contains the
library. Two lessons:

- Check the APK's ABI libs before blaming the code:
  `python3 -m tools.android_ui_debug apk "$APK"` plus a zip listing of `lib/`.
- A missing optional native library must not turn every call into a failure. The tool now
  latches the load failure once and falls back to `HashedTextEmbedding`, a deterministic
  512-d word-unigram + character-trigram vector. The output's `model` field says
  `Hashed lexical fallback (512-d FNV-1a)`; arm devices still report `MediaPipe USE-Lite`.
  The fallback is a real lexical similarity vector, not a fabricated semantic score.

The device regression is `tools/android_ui_debug/scenarios/embedding.json`: it checks the
text vector, text-to-text cosine, and the current-vector cosine, then checks logcat.

---

## 8. Automate it with the Python toolkit

```bash
# List devices
python3 -m tools.android_ui_debug devices
python3 -m tools.android_ui_debug apk "apps/android/build/outputs/apk/debug/ApexClient Terminal-universal-debug.apk"

# One-off dump
python3 -m tools.android_ui_debug dump --limit 120

# Tap by text (no coordinates, no screenshots)
python3 -m tools.android_ui_debug tap --contains "Libsignal E2EE"

# Run the full LibSignal regression scenario
python3 -m tools.android_ui_debug run \
  tools/android_ui_debug/scenarios/libsignal.json \
  --apk "apps/android/build/outputs/apk/debug/ApexClient Terminal-universal-debug.apk"

# Embedding regression
python3 -m tools.android_ui_debug run \
  tools/android_ui_debug/scenarios/embedding.json
```

Each run writes `artifacts/<scenario>-<timestamp>/`:

- `report.json` — per-step status, timings, device, pass/fail.
- `failure_NN.xml` — the exact UI tree at the failure.
- `failure_NN.txt` — the step, selectors, and visible labelled nodes.
- `logcat_crash.txt`, `logcat_app.txt` — crash buffer and app errors.

Exit code is non-zero on failure, so the same command works in CI.

### Scenario format

```json
{
  "name": "embedding-demo",
  "package": "com.aistudio.sduiposengine.kmpdiv",
  "activity": "com.example.MainActivity",
  "steps": [
    { "action": "launch", "settle": 25, "clear_data": true },
    { "action": "wait_text", "contains": "Apex POS Terminal", "timeout": 90 },
    { "action": "tap", "text": "Apex POS Terminal", "settle": 8 },
    { "action": "wait_text", "contains": "ACTIVE TERMINAL CAPABILITIES", "timeout": 30 },
    { "action": "scroll_to", "contains": "Embeddings", "max_swipes": 15 },
    { "action": "tap", "contains": "Embeddings", "scroll": false },
    { "action": "wait_text", "contains": "TEXT & IMAGE EMBEDDINGS", "timeout": 20 },
    { "action": "tap", "text": "Embed Item Name", "scroll": true },
    { "action": "wait_text", "contains": "Source: text", "timeout": 30 },
    { "action": "assert_text", "contains": "Dim: 512" },
    { "action": "assert_no_crash" }
  ]
}
```

Selectors: `text` (exact), `contains`, `regex`, `desc`, `id`, `class`, `clickable`.
Actions: `launch`, `clear_logcat`, `tap`, `tap_xy`, `tap_text`, `tap_id`, `wait_text`,
`wait_gone`, `assert_text`, `assert_no_text`, `scroll_to`, `swipe`, `back`,
`input_text`, `assert_no_crash`, `assert_log_contains`, `assert_log_not_contains`,
`sleep`, `log`.

`tap` scrolls by default. Set `"scroll": false` when the element is known to be on
screen (for example, the catalog entry you just scrolled to). `assert_text` and
`wait_text` accept `"scroll": true` to look below the fold. `launch` accepts
`"clear_data": true`; `tap_xy` takes explicit `x`/`y` when a Compose text node does not
forward the tap to its clickable parent.

---

## 9. Checklist for a new tool or card

1. Build and install: `./gradlew assembleDebug`, `adb install -r ...`.
2. Launch, dump, and locate the card entry in the hub; `scroll_to` it.
3. Tap the card, dump, and record every button label and status line.
4. Tap each operation in order; after each tap, poll for the status line rather than
   sleeping a fixed time.
5. On failure, save the UI XML **and** `logcat -b crash` + app PID errors.
6. Add the failing path as a step in a scenario under
   `tools/android_ui_debug/scenarios/`.
7. Fix the handler, add a JVM/Robolectric test for the pure part, rebuild, rerun the
   scenario with the same JSON.
8. Update `docs/tools/<tool>.md` and the demo card if the output shape changed.

---

## 10. Pitfalls that cost time

- **Tapping before scrolling.** A node outside the viewport is not in the XML; a blind
  tap hits whatever is at those coordinates.
- **Asserting too early.** Tool work is async; a dump right after the tap shows the old
  value. Poll with a timeout.
- **Stale logcat.** Clear it before launch; otherwise old crashes look new.
- **Matching exact long strings.** Safety numbers and ciphertexts wrap or split. Use
  `contains`/regex on the label prefix, and assert the full value from the action payload
  in a JVM test.
- **Ignoring `engine_error`.** The card renders the real error; read it before reading
  the stack trace.
- **Assuming a failed tool crashed.** A `FAILED`/`UNSUPPORTED` result is a normal tool
  outcome, not an exception. The card should show it, and the scenario should assert it.
- **Using screenshots.** They are unnecessary here and unavailable in text-only CI.
  The XML tree is smaller, deterministic, and diffable.
- **Assuming one APK is representative.** Per-ABI splits differ in native libraries.
  MediaPipe text JNI is absent from x86_64; always test the ABI you are running.
- **Reusing restored navigation state.** `am start` after `force-stop` can still restore
  the last route. Use `pm clear` (or `clear_data: true`) for a scenario that starts at
  the hub.
