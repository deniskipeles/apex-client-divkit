# Screen: Keep-Awake, Screenshot & Recording (`tools://screen` / `device.screen@1`)

The **Screen capability** groups the three screen-facing operations a POS terminal needs:
holding the display awake, capturing a screenshot, and recording the terminal screen. They
share one MediaProjection permission prompt and write media into the tenant workspace.

The former `tools://keep-awake` / `device.keep_awake@1` capability is folded into
`tools://screen/keep-awake`; the old URI and capability id remain accepted as compatibility
aliases.

---

## 1. URLs

| URL | Operation | Purpose |
| :--- | :--- | :--- |
| `tools://screen/keep-awake` | `keep-awake` | Hold/release the display wake-lock. |
| `tools://screen/screenshot` | `screenshot` | Capture the current screen to PNG/JPEG. |
| `tools://screen/recording` | `recording` | Start/stop an MP4 screen recording. |
| `tools://screen/status` | `status` | Report wake-lock, recording and host support. |

`div-action://terminal.tool.invoke?tool=device.screen@1` is also accepted.

### Keep-awake options

| Option | Type | Default | Description |
| :--- | :--- | :--- | :--- |
| `action` | `string` | `"enable"` | `enable`, `disable`, or `toggle`. |
| `level` | `string` | `"screen"` | Wake level hint: `screen`, `full`, `partial`. |
| `timeout_ms` | `integer` | `0` | Auto-release timeout (`0` = indefinite). |
| `tag` | `string` | `"apexkit:screen:keep-awake"` | Diagnostics tag. |

### Screenshot options

| Option | Type | Default | Description |
| :--- | :--- | :--- | :--- |
| `format` | `string` | `"png"` | `png`, `jpg`, or `jpeg`. |
| `quality` | `integer` | `95` | JPEG/PNG compression quality (1–100). |

### Recording options

| Option | Type | Default | Description |
| :--- | :--- | :--- | :--- |
| `action` | `string` | `"start"` | `start`, `stop`, or `status`. |

> The URL path selects the operation. The `action` option selects the sub-operation for
> keep-awake and recording, e.g. `tools://screen/recording` with `{"action":"stop"}`.

---

## 2. Outputs

| Field | Type | Present for | Description |
| :--- | :--- | :--- | :--- |
| `status` | `string` | all | `ENABLED`, `DISABLED`, `CAPTURED`, `RECORDING`, `RECORDED`, `READY`, `UNSUPPORTED`. |
| `enabled` | `boolean` | keep-awake/status | Wake-lock state. |
| `recording` | `boolean` | recording/status | Recording state. |
| `recording_id` | `string` | recording/status | Stable recording id. |
| `uri` | `string` | screenshot/stop | `file://` URI of the media. |
| `path` | `string` | screenshot/stop | Absolute path. |
| `width`, `height` | `integer` | screenshot/stop | Captured pixel dimensions. |
| `bytes` | `integer` | screenshot/stop | File size. |
| `duration_ms` | `integer` | stop | Recording duration. |
| `image_id` | `string` | screenshot | Screenshot id. |
| `error` | `string` | failure | Human-readable failure reason (also mapped from `reason`). |
| `screenshot_supported` | `boolean` | status | Host installed a screenshot sink. |
| `recording_supported` | `boolean` | status | Host installed recording sinks. |

DivKit output variables: `user.screen_status`, `user.screen_keep_awake`,
`user.screen_recording`, `user.screen_recording_id`, `user.screen_media_uri`,
`user.screen_media_path`, `user.screen_width`, `user.screen_height`, `user.screen_bytes`,
`user.screen_duration_ms`, `user.screen_image_id`, `user.screen_error`.

---

## 3. Platform implementation

- **Keep-awake:** `WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON` (Android) via
  `ScreenTool.keepAwakeSink`.
- **Screenshot:** `MediaProjection` → `ImageReader` (`RGBA_8888`) → `Bitmap` → PNG/JPEG in
  `files/workspaces/<tenantId>/media`. `ImageReader.newInstance` and
  `MediaProjection.registerCallback` construct `Handler`s from the calling thread's `Looper`,
  so both run on the main thread; the frame poll and PNG encoding stay on the worker.
- **Recording:** `MediaProjection` → `MediaRecorder` (`MPEG_4` / H.264 / 8 Mbps) →
  `files/workspaces/<tenantId>/media/<id>_recording.mp4`.
- Android 14+ requires a `mediaProjection` foreground service before `getMediaProjection`;
  `ScreenProjectionService` supplies it. The permission prompt is owned by
  `ScreenCaptureHost.attach(activity)`.

With no host sink installed (headless/JVM tests), screenshot and recording return
`UNSUPPORTED`; they never fabricate a file. Keep-awake still reports the requested state so
the pure-JVM contract is testable.

---

## 4. Example

```json
{
  "type": "button",
  "text": "Capture Screenshot",
  "action": {
    "url": "tools://screen/screenshot",
    "payload": { "options": { "format": "png", "quality": "95" } }
  }
}
```

```json
{
  "type": "button",
  "text": "Stop Recording",
  "action": {
    "url": "tools://screen/recording",
    "payload": { "options": { "action": "stop" } }
  }
}
```
