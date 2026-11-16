# Architectural Model

```
┌────────────────────────────────────────────────────────────────────────┐
│                        SDUI DOCUMENT / APP                             │
│      (Coupon Codes, Tracking IDs, Address Copy, Paste Targets)         │
│                                                                        │
│  Calls: tools://clipboard/copy                                         │
│  Payload: { text: "PKG-9021" }                                         │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│                    GENERIC CLIPBOARD RUNTIME                           │
│                                                                        │
│  1. Operations: copy, paste, clear                                     │
│  2. Android: ClipboardManager (ClipData.newPlainText)                  │
│  3. Headless fallback: in-memory pass-through when no Context exists    │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼ Generic JSON Output
┌────────────────────────────────────────────────────────────────────────┐
│  { "op": "copy", "text": "PKG-9021", "length": 8, "state": "ok" }       │
└────────────────────────────────────────────────────────────────────────┘
```

# System Clipboard Controller (`tools://clipboard` / `device.clipboard@1`)

The **System Clipboard Controller** is an unopinionated bridge to the OS clipboard.
It copies, pastes, and clears plain-text values without knowing what they mean.

---

## 1. Parameters & Options

Invoked via:
- `tools://clipboard/copy`
- `tools://clipboard/paste`
- `tools://clipboard/clear`
- `div-action://terminal.tool.invoke?tool=device.clipboard@1`

### Options (`payload.options`)

| Option | Type | Default | Description |
| :--- | :--- | :--- | :--- |
| `op` | `string` | `"copy"` | Operation: `copy`, `paste`, or `clear`. |
| `text` | `string` | `""` | Text to place on the clipboard (`copy`). Also accepts `content`. |

> The operation may also be supplied as the URL path segment (`tools://clipboard/paste`).

---

## 2. Tool Output Schema

| Field | Type | Description |
| :--- | :--- | :--- |
| `op` | `string` | Operation performed. |
| `text` | `string` | Clipboard contents after the operation. |
| `length` | `integer` | Character length of `text`. |
| `state` | `string` | `"ok"`. |

Default output mapping: `text → user.clipboard_content`.

---

## 3. DivKit Usage Examples

### Copy a Tracking ID
```json
{
  "type": "button",
  "text": "Copy Tracking ID",
  "action": {
    "url": "tools://clipboard/copy",
    "payload": {
      "text": "@{parcel_tracking_id}",
      "output": { "text": "clipboard_content" }
    }
  }
}
```

### Paste into a Field
```json
{
  "type": "button",
  "text": "Paste",
  "action": {
    "url": "tools://clipboard/paste",
    "payload": { "output": { "text": "pasted_text" } }
  }
}
```
