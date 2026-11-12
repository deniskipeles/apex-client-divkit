# Architecture Overview

```
                      ┌───────────────────────────────────────────┐
                      │            SDUI DOCUMENT / APP            │
                      │  (Warehouse, Hospital, Retail, Logistics) │
                      └─────────────────────┬─────────────────────┘
                                            │ tools://scan/scan
                                            │ options: { target_format: "both"|"barcode"|"qr" }
                                            ▼
                      ┌───────────────────────────────────────────┐
                      │         GENERIC SCANNER RUNTIME           │
                      │                                           │
                      │  1. Camera Engine (CameraX / AVFoundation)│
                      │  2. Format Classifier & Filter            │
                      │     • 1D: EAN-13, UPC-A, Code 128, etc.   │
                      │     • 2D: QR Code, DataMatrix, Aztec      │
                      │  3. Constraint Gate (target_format)       │
                      │  4. Hardware Feedback (Beeper + Haptics)  │
                      └─────────────────────┬─────────────────────┘
                                            │
                                            ▼ Emits generic payload
                      ┌───────────────────────────────────────────┐
                      │               RAW OUTPUT                  │
                      │  {                                        │
                      │    "raw_value": "9780201633610",          │
                      │    "format": "EAN_13",                    │
                      │    "type": "1D",                          │
                      │    "timestamp": 1712345678901,            │
                      │    "status": "SUCCESS"                    │
                      │  }                                        │
                      └───────────────────────────────────────────┘
```
# Universal Optical Scanner (`tools://scan` / `vision.scan_code@1`)

The **Universal Optical Scanner Tool** provides an unopinionated native bridge for camera-based 1D and 2D visual decoding.

Operating like the W3C `BarcodeDetector` web standard, it decodes visual symbology into raw string data without any assumptions about the business domain (no products, prices, or database writes).

---

## 1. Parameters & Options

Invoked via:
- `tools://scan/scan`
- `div-action://terminal.tool.invoke?tool=vision.scan_code@1`

### Options (`payload.options`)

| Option | Type | Default | Description |
| :--- | :--- | :--- | :--- |
| `target_format` | `string` | `"both"` | Symbology filter constraint:<br>• `"both"` / `"all"`: Decodes both 1D and 2D codes.<br>• `"barcode"` / `"1d"`: Restricts scanning to 1D barcodes (rejects QR).<br>• `"qr"` / `"2d"`: Restricts scanning to 2D matrix codes.<br>• Specific formats: `"EAN_13"`, `"UPC_A"`, `"CODE_128"`, `"QR_CODE"`. |
| `beep` | `boolean` | `true` | Plays an audible hardware tone upon successful decode. |
| `haptics` | `boolean` | `true` | Triggers haptic tactile confirmation on scan. |
| `camera_facing`| `string` | `"back"` | Selects `"back"` or `"front"` lens. |
| `simulated_code`| `string`| `null` | Predefined input code for testing and headless CI. |

---

## 2. Tool Output Schema

Returns a domain-agnostic optical descriptor:

| Field | Type | Description |
| :--- | :--- | :--- |
| `raw_value` | `string` | The exact decoded content. |
| `format` | `string` | Symbology: `EAN_13`, `UPC_A`, `CODE_128`, `QR_CODE`, etc. |
| `type` | `string` | Classification: `"1D"` or `"2D"`. |
| `bytes_length` | `integer`| Byte size of the payload. |
| `status` | `string` | Outcome: `"SUCCESS"` or `"FORMAT_MISMATCH"`. |
| `timestamp` | `integer`| Unix epoch millisecond timestamp. |

---

## 3. DivKit Usage Examples

### Generic Scan (Dual Mode)
```json
{
  "type": "button",
  "text": "Scan Any Code",
  "action": {
    "url": "tools://scan/scan",
    "payload": {
      "options": {
        "target_format": "both"
      },
      "output": {
        "raw_value": "scanned_text",
        "format": "scanned_format"
      }
    }
  }
}
```

### Strict 1D Barcode Scan
```json
{
  "type": "button",
  "text": "Scan Parcel Barcode",
  "action": {
    "url": "tools://scan/scan",
    "payload": {
      "options": {
        "target_format": "barcode"
      },
      "output": {
        "raw_value": "parcel_tracking_id"
      }
    }
  }
}
```