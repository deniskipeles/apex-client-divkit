# Architectural Model

```
┌────────────────────────────────────────────────────────────────────────┐
│                        SDUI DOCUMENT / APP                             │
│      (Receipt Printing, Kitchen Tickets, Labels, End-of-Day Reports)   │
│                                                                        │
│  Calls: tools://print/print                                            │
│  Payload: { text: "ACME POS\nOrder #1042\nTotal 24.50",                │
│             options: { align: "center", cut: "true" } }                │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│                 BLUETOOTH PRINTER / POS BRIDGE                         │
│                                                                        │
│  1. Discovery  → bonded + discoverable peers (name heuristics)         │
│  2. Pairing    → createBond() by MAC address                           │
│  3. Connection → RFCOMM SPP socket (00001101-0000-1000-8000-00805F9B34FB)│
│  4. Rendering  → ESC/POS byte stream (init, align, bold, wrap, feed,   │
│                  cut)                                                   │
│  5. Write      → socket.outputStream (off the main thread)             │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼ Generic JSON Output
┌────────────────────────────────────────────────────────────────────────┐
│  { "status": "SUCCESS", "mode": "bluetooth_rfcomm",                    │
│    "address": "AA:BB:CC:DD:EE:FF", "bytes": 142 }                      │
└────────────────────────────────────────────────────────────────────────┘
```

# Bluetooth Printer / POS Bridge (`tools://print` / `device.printer@1`)

The **Bluetooth Printer Tool** scans for bonded and discoverable Bluetooth thermal printers
and POS devices, connects over RFCOMM SPP, and prints ESC/POS receipts. It is unopinionated:
the caller supplies text and layout options, and the tool renders and writes the bytes.

---

## 1. Parameters & Options

Invoked via:
- `tools://print/scan`
- `tools://print/pair`
- `tools://print/connect`
- `tools://print/disconnect`
- `tools://print/print`
- `tools://print/status`
- `div-action://terminal.tool.invoke?tool=device.printer@1`

### Options (`payload.options`)

| Option | Type | Default | Description |
| :--- | :--- | :--- | :--- |
| `address` | `string` | — | Target MAC address (also accepts `mac`/`device`). Required for `pair`/`connect`/`disconnect`; optional for `print` (uses an open connection). |
| `all` / `include_all` | `boolean` | `false` | Include non-printer bonded devices in `scan`. |
| `discover` / `start_discovery` | `boolean` | `false` | Also start a Bluetooth discovery sweep. |
| `align` | `string` | `"left"` | Text alignment: `left`, `center`, `right`. |
| `bold` | `boolean` | `false` | Emit bold-on/off ESC/POS commands. |
| `width` | `integer` | `32` | Characters per line before wrapping (16–80). |
| `feed_lines` | `integer` | `3` | Line feeds after the body (0–10). |
| `cut` | `boolean` | `false` | Emit a paper-cut command. |
| `timeout_ms` | `integer` | `12000` | Connection timeout. |

### Body options (`payload`)

| Field | Description |
| :--- | :--- |
| `text` | Text to print (split on newlines). |
| `lines` | Line list, split on `\n`, `,`, or `|`. |
| `receipt` | Alias for `text`. |

---

## 2. Tool Output Schema

| Field | Type | Description |
| :--- | :--- | :--- |
| `status` | `string` | `SUCCESS`, `BLUETOOTH_OFF`, `CONNECTED`, `ALREADY_CONNECTED`, `DISCONNECTED`, `NOT_CONNECTED`, `PAIRING`, `ALREADY_PAIRED`, `PAIR_FAILED`, `READY`. |
| `address` | `string` | Target MAC address. |
| `name` | `string` | Device name. |
| `mode` | `string` | `bluetooth_rfcomm`, `queued`, or `emulated`. |
| `bytes` | `integer` | ESC/POS payload size. |
| `preview_hex` | `string` | First bytes of the payload as hex (when queued/emulated). |
| `count` | `integer` | Number of discovered printers (`scan`). |
| `devices` | `array` | `{ name, address, bonded, bond_state, printer_like, type }`. |
| `bonded_count` | `integer` | Bonded device count (`scan`). |
| `discovery_started` | `boolean` | Whether a discovery sweep started. |
| `bonded_printers` | `integer` | Bonded printer count (`status`). |
| `connections` | `array` | Currently open MAC addresses (`status`). |
| `timestamp` | `integer` | Unix epoch millisecond timestamp. |

Default output mapping: `status → user.print_status`, `count → user.print_device_count`,
`bytes → user.print_bytes`.

---

## 3. Permissions

| Android version | Permissions |
| :--- | :--- |
| 12+ (API 31+) | `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN` (runtime) |
| ≤ 11 (API ≤ 30) | `BLUETOOTH`, `BLUETOOTH_ADMIN` (+ `ACCESS_FINE_LOCATION` for discovery) |

When a required permission is missing, the tool returns a `PermissionDenied` result rather
than throwing.

---

## 4. DivKit Usage Examples

### Discover Bonded Printers
```json
{
  "type": "button",
  "text": "Find Printers",
  "action": {
    "url": "tools://print/scan",
    "payload": {
      "options": { "all": "false", "discover": "true" },
      "output": { "count": "printer_count" }
    }
  }
}
```

### Connect, Print a Receipt, then Disconnect
```json
[
  {
    "type": "button",
    "text": "Connect",
    "action": {
      "url": "tools://print/connect",
      "payload": { "options": { "address": "@{printer_mac}" } }
    }
  },
  {
    "type": "button",
    "text": "Print Receipt",
    "action": {
      "url": "tools://print/print",
      "payload": {
        "options": {
          "address": "@{printer_mac}",
          "align": "center",
          "cut": "true",
          "width": "32"
        },
        "text": "ACME POS\nOrder #1042\n----------------\nTotal      24.50\n",
        "output": { "status": "print_status", "bytes": "print_bytes" }
      }
    }
  },
  {
    "type": "button",
    "text": "Disconnect",
    "action": {
      "url": "tools://print/disconnect",
      "payload": { "options": { "address": "@{printer_mac}" } }
    }
  }
]
```
