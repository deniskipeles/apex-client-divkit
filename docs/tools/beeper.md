# Architectural Model

```
┌────────────────────────────────────────────────────────────────────────┐
│                        SDUI DOCUMENT / APP                             │
│     (Kiosk Alerts, Navigation Signals, Form Auditing, Accessibility)   │
│                                                                        │
│  Calls: tools://beeper/tone                                            │
│  Options: { frequency_hz: 1760, duration_ms: 120, waveform: "sine" }   │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│                   GENERIC AUDIO TONE RUNTIME ENGINE                    │
│                                                                        │
│  1. Pure PCM Waveform Synthesizer (44.1 kHz, 16-bit):                  │
│     • Waveforms: sine, square, triangle, sawtooth                      │
│     • Attack / Decay Anti-Click Envelope (5ms cosine smoothing)        │
│  2. Semantic Profile Presets:                                          │
│     • beep (1600 Hz, 60ms)                                             │
│     • chime (double rising tones: 880 Hz -> 1760 Hz)                   │
│     • success (triad chord or pleasant chime)                          │
│     • warning (2200 Hz alert pulse)                                    │
│     • error (low 440 Hz reject buzz)                                   │
│  3. Custom Frequency Sequence / Melodies:                              │
│     • sequence: "880:80,1320:80,1760:120"                              │
│  4. Operations: play, tone, chime, stop, status                       │
│  5. Platform Transduction:                                             │
│     • Android: Non-blocking PCM AudioTrack stream                      │
│     • iOS: AVAudioEngine / AVTonePlayer                                │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼ Generic JSON Output
┌────────────────────────────────────────────────────────────────────────┐
│  {                                                                     │
│    "frequency_hz": 1760.0,                                             │
│    "duration_ms": 120,                                                 │
│    "volume": 0.85,                                                     │
│    "waveform": "sine",                                                 │
│    "timestamp": 1712345678901,                                         │
│    "status": "SUCCESS"                                                 │
│  }                                                                     │
└────────────────────────────────────────────────────────────────────────┘
```

# Universal Audio Tone Synthesizer (`tools://beeper` / `hardware.beeper@1`)

The **Universal Audio Synthesizer Tool** provides an unopinionated acoustic signal generator for Server-Driven UI applications.

Operating like the W3C Web Audio API (`AudioContext` / `OscillatorNode`), it synthesizes mathematical waveforms (sine, square, triangle, sawtooth) and tone sequences directly to the hardware audio stream without making any business domain assumptions.

---

## 1. Parameters & Options

Invoked via:
- `tools://beeper/tone` (or `tools://audio/tone`)
- `tools://beeper/play`
- `tools://beeper/stop`
- `tools://beeper/status`
- `div-action://terminal.tool.invoke?tool=hardware.beeper@1`

### Options (`payload.options`)

| Option | Type | Default | Description |
| :--- | :--- | :--- | :--- |
| `frequency_hz` | `number` | `1200` | Audible frequency in Hertz (`20` to `20000`). |
| `duration_ms` | `integer` | `120` | Duration of the tone in milliseconds (`10` to `5000`). |
| `waveform` | `string` | `"sine"` | Waveform type: `"sine"`, `"square"`, `"triangle"`, `"sawtooth"`. |
| `volume` | `number` | `0.85` | Gain level from `0.0` (mute) to `1.0` (max). |
| `profile` | `string` | `null` | Predefined acoustic profile: `"beep"`, `"chime"`, `"success"`, `"warning"`, `"error"`. |
| `sequence` | `string` | `null` | Multi-tone melody: `"freq:dur,freq:dur"` (e.g. `"880:60,1760:100"`). |

---

## 2. Tool Output Schema

| Field | Type | Description |
| :--- | :--- | :--- |
| `status` | `string` | `"SUCCESS"`, `"MUTED"`, or `"FAILED"`. |
| `frequency_hz`| `number` | The executed frequency in Hertz. |
| `duration_ms` | `integer`| Tone duration in milliseconds. |
| `waveform` | `string` | Waveform synthesis mode. |
| `timestamp` | `integer`| Unix epoch millisecond timestamp. |

---

## 3. DivKit SDUI Usage Examples

### Example A: Crisp Feedback Blip (Button Press)
```json
{
  "type": "button",
  "text": "Click Me",
  "action": {
    "url": "tools://beeper/tone",
    "payload": {
      "options": {
        "frequency_hz": 1800,
        "duration_ms": 30
      }
    }
  }
}
```

### Example B: Success Chime Arpeggio
```json
{
  "type": "button",
  "text": "Complete Transaction",
  "action": {
    "url": "tools://beeper/play",
    "payload": {
      "options": {
        "profile": "success"
      },
      "output": {
        "status": "audio_feedback_status"
      }
    }
  }
}
```

### Example C: Custom Melodic Warning Sequence
```json
{
  "type": "button",
  "text": "Trigger Alert",
  "action": {
    "url": "tools://beeper/play",
    "payload": {
      "options": {
        "sequence": "2200:100,0:50,2200:100",
        "waveform": "square"
      }
    }
  }
}
```

### Example D: Audio Cancellation / Mute
```json
{
  "type": "button",
  "text": "Mute Audio",
  "action": {
    "url": "tools://beeper/stop"
  }
}
```