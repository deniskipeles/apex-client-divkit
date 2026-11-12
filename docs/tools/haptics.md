# Architectural Model

```
┌────────────────────────────────────────────────────────────────────────┐
│                        SDUI DOCUMENT / APP                             │
│     (Form Validation, Game/Simulation, Navigation, Accessibility)      │
│                                                                        │
│  Calls: tools://haptics/perform                                        │
│  Options: { pattern: "success" } OR { timings: [0, 50, 100, 50] }      │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│                   GENERIC HAPTICS RUNTIME ENGINE                       │
│                                                                        │
│  1. Predefined Semantic Generators:                                    │
│     • Impact: click, light, medium, heavy, double_click                │
│     • Notification: success, warning, error / failure                  │
│     • Selection: tick / selection change                               │
│  2. Custom Waveform Engine (W3C navigator.vibrate equivalent):         │
│     • timings: [wait, vibrate, wait, vibrate, ...]                     │
│     • amplitudes: [0, 120, 0, 255] (1..255 dynamic pressure)           │
│  3. Operations: perform, vibrate, cancel / stop, capabilities          │
│  4. Platform Transduction:                                             │
│     • Android: VibratorManager / VibrationEffect.createWaveform        │
│     • iOS: UIImpactFeedback / UINotificationFeedbackGenerator          │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼ Generic JSON Output
┌────────────────────────────────────────────────────────────────────────┐
│  {                                                                     │
│    "pattern": "success",                                               │
│    "duration_ms": 100,                                                 │
│    "amplitude": 220,                                                   │
│    "timestamp": 1712345678901,                                         │
│    "status": "SUCCESS"                                                 │
│  }                                                                     │
└────────────────────────────────────────────────────────────────────────┘
```

# Universal Haptics Tool (`tools://haptics` / `hardware.haptics@1`)

The **Universal Haptics Tool** provides unopinionated tactile feedback for Server-Driven UI applications.

Operating like the W3C Vibration API (`navigator.vibrate`) and platform feedback generators (`UIFeedbackGenerator` and `VibrationEffect`), it generates sensory tactile pulses and custom waveforms without making assumptions about the business domain.

---

## 1. Parameters & Options

Invoked via:
- `tools://haptics/perform` (or shortcut: `tools://haptics/<pattern>`)
- `tools://haptics/stop`
- `tools://haptics/status`
- `div-action://terminal.tool.invoke?tool=hardware.haptics@1`

### Options (`payload.options`)

| Option | Type | Default | Description |
| :--- | :--- | :--- | :--- |
| `pattern` | `string` | `"click"` | Predefined semantic pattern name:<br>• Impact: `"click"`, `"light"`, `"medium"`, `"heavy"`, `"double_click"`<br>• Notification: `"success"`, `"warning"`, `"error"` / `"failure"`<br>• Selection: `"tick"` / `"selection"` |
| `duration_ms` | `integer` | *auto* | Custom duration in milliseconds for one-shot pulses (`5` to `5000`). |
| `amplitude` | `integer` | `180` | Vibration force/intensity from `1` to `255` (where hardware permits). |
| `timings` | `string` / `array` | `null` | W3C waveform pattern: `"[wait, vibrate, wait, vibrate]"` in milliseconds.<br>Example: `"[0, 40, 60, 40]"` creates a double-pulse. |
| `amplitudes` | `string` / `array` | `null` | Custom amplitude array corresponding to each vibration step (Android O+). |

---

## 2. Tool Output Schema

| Field | Type | Description |
| :--- | :--- | :--- |
| `status` | `string` | `"SUCCESS"`, `"CANCELLED"`, or `"UNSUPPORTED"`. |
| `pattern` | `string` | The resolved pattern name. |
| `duration_ms` | `integer` | Pulse duration in milliseconds. |
| `amplitude` | `integer` | Amplitude level applied (`1` to `255`). |
| `timestamp` | `integer` | Unix millisecond timestamp. |

---

## 3. DivKit SDUI Usage Examples

### Example A: Semantic Click Impact on Button Tap
```json
{
  "type": "button",
  "text": "Submit Form",
  "action": {
    "url": "tools://haptics/click"
  }
}
```

### Example B: Success Notification Waveform on Completion
```json
{
  "type": "button",
  "text": "Complete Task",
  "action": {
    "url": "tools://haptics/success",
    "payload": {
      "output": {
        "status": "task_haptic_state"
      }
    }
  }
}
```

### Example C: W3C Dual Pulse Waveform (`[0, 50, 100, 50]`)
```json
{
  "type": "button",
  "text": "Trigger Heartbeat Pulse",
  "action": {
    "url": "tools://haptics/perform",
    "payload": {
      "options": {
        "timings": "[0, 60, 80, 60]",
        "amplitude": 240
      }
    }
  }
}
```

### Example D: Cancelling Active Vibrations
```json
{
  "type": "button",
  "text": "Mute Tactile Alerts",
  "action": {
    "url": "tools://haptics/stop"
  }
}
```