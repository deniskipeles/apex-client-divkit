# Architectural Model

```
┌────────────────────────────────────────────────────────────────────────┐
│                        SDUI DOCUMENT / APP                             │
│   (Checkout in progress, kiosk display, long sync, print spooler)      │
│                                                                        │
│  Calls: tools://keep-awake/enable                                      │
│  Payload: { level: "screen", timeout_ms: 300000 }                      │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│                    KEEP-AWAKE RUNTIME                                  │
│                                                                        │
│  1. Operations: enable, disable, toggle, status                        │
│  2. Android: WindowManager FLAG_KEEP_SCREEN_ON (+ WAKE_LOCK perm)      │
│  3. iOS: UIApplication.shared.isIdleTimerDisabled                      │
│  4. Headless fallback: in-memory state, same result shape              │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼ Generic JSON Output
┌────────────────────────────────────────────────────────────────────────┐
│  { "status": "ENABLED", "enabled": true, "level": "screen", ... }      │
└────────────────────────────────────────────────────────────────────────┘
```

# Keep-Awake / Screen Wake-Lock Controller (`tools://keep-awake` / `device.keep_awake@1`)

The **Keep-Awake Controller** is an unopinionated device capability that holds the terminal
awake while a long-running task is in flight. It carries no product or vendor coupling: the
caller supplies a lifecycle command, an optional timeout, and a wake level, and the platform
chooses the physical implementation.

---

## 1. Parameters & Options

Invoked via:
- `tools://keep-awake/enable`
- `tools://keep-awake/disable`
- `tools://keep-awake/toggle`
- `tools://keep-awake/status`
- `div-action://terminal.tool.invoke?tool=device.keep_awake@1`

### Options (`payload.options`)

| Option | Type | Default | Description |
| :--- | :--- | :--- | :--- |
| `op` | `string` | `"enable"` | Operation: `enable`, `disable`, `toggle`, or `status`. |
| `level` | `string` | `"screen"` | Wake level hint: `screen`, `full`, or `partial`. |
| `timeout_ms` | `integer` | `0` | Optional auto-release timeout in milliseconds (`0` = indefinite). |
| `tag` | `string` | `"apexkit:keep-awake"` | Caller tag for diagnostics / wake-lock accounting. |

> The operation may also be supplied as the URL path segment (`tools://keep-awake/disable`).

---

## 2. Tool Output Schema

| Field | Type | Description |
| :--- | :--- | :--- |
| `status` | `string` | `ENABLED` or `DISABLED`. |
| `enabled` | `boolean` | Current wake-lock state after the operation. |
| `level` | `string` | Wake level applied (`enable`/`disable`/`toggle`). |
| `timeout_ms` | `integer` | Requested timeout (`enable`/`disable`/`toggle`). |
| `tag` | `string` | Caller tag. |
| `supported_levels` | `array` | Available levels (`status`). |
| `supported_operations` | `array` | Available operations (`status`). |
| `timestamp` | `integer` | Epoch milliseconds. |

Default output mapping: `status → user.keep_awake_status`, `enabled → user.keep_awake_enabled`.

---

## 3. Platform Mapping

| Platform | Implementation |
| :--- | :--- |
| Android | `WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON` (requires `android.permission.WAKE_LOCK`). |
| iOS | `UIApplication.shared.isIdleTimerDisabled`. |
| Desktop | No physical screen; reports the same result shape (host may map onto an idle-inhibitor). |
| Headless | In-memory state, identical metadata. |

---

## 4. DivKit Usage Examples

### Hold the screen awake during checkout
```json
{
  "type": "button",
  "text": "Start Checkout",
  "action": {
    "url": "tools://keep-awake/enable",
    "payload": {
      "level": "screen",
      "timeout_ms": 300000,
      "output": { "status": "keep_awake_status" }
    }
  }
}
```

### Release the wake-lock when finished
```json
{
  "type": "button",
  "text": "Finish",
  "action": {
    "url": "tools://keep-awake/disable",
    "payload": { "output": { "enabled": "keep_awake_enabled" } }
  }
}
```
