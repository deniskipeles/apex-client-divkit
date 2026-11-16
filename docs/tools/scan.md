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
                      │  5. OCR Path → ML Kit Text Recognition v2 │
                      │     (tools://scan/ocr image text reading) │
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

The **Universal Optical Scanner Tool** provides an unopinionated native bridge for camera-based 1D and 2D visual decoding, plus an optical character recognition (OCR) mode for reading human-readable text from images.

Operating like the W3C `BarcodeDetector` web standard, it decodes visual symbology into raw string data without any assumptions about the business domain (no products, prices, or database writes).

---

## 1. Parameters & Options

Invoked via:
- `tools://scan/scan` (symbology decoding)
- `tools://scan/ocr` (text recognition)
- `tools://ocr/read` (alias for the OCR mode)
- `div-action://terminal.tool.invoke?tool=vision.scan_code@1`

### Options (`payload.options`)

| Option | Type | Default | Description |
| :--- | :--- | :--- | :--- |
| `target_format` | `string` | `"both"` | Symbology filter constraint:<br>• `"both"` / `"all"`: Decodes both 1D and 2D codes.<br>• `"barcode"` / `"1d"`: Restricts scanning to 1D barcodes (rejects QR).<br>• `"qr"` / `"2d"`: Restricts scanning to 2D matrix codes.<br>• Specific formats: `"EAN_13"`, `"UPC_A"`, `"CODE_128"`, `"QR_CODE"`. |
| `beep` | `boolean` | `true` | Plays an audible hardware tone upon successful decode. |
| `haptics` | `boolean` | `true` | Triggers haptic tactile confirmation on scan. |
| `camera_facing`| `string` | `"back"` | Selects `"back"` or `"front"` lens. |
| `simulated_code`| `string`| `null` | Predefined input code for testing and headless CI. |
| `uri` | `string` | `null` | Bitmap reference for the `ocr` mode: `data:image/...`, `content://`, `file://`, an absolute path, or bare base64 (also accepts `image` or `base64`). |

---

## 2. Tool Output Schema

### `scan` operation — domain-agnostic optical descriptor

| Field | Type | Description |
| :--- | :--- | :--- |
| `raw_value` | `string` | The exact decoded content. |
| `format` | `string` | Symbology: `EAN_13`, `UPC_A`, `CODE_128`, `QR_CODE`, etc. |
| `type` | `string` | Classification: `"1D"` or `"2D"`. |
| `bytes_length` | `integer`| Byte size of the payload. |
| `status` | `string` | Outcome: `"SUCCESS"` or `"FORMAT_MISMATCH"`. |
| `timestamp` | `integer`| Unix epoch millisecond timestamp. |

### `ocr` operation — recognized text

| Field | Type | Description |
| :--- | :--- | :--- |
| `text` | `string` | Full recognized text, blocks joined in reading order. |
| `confidence` | `double` | Mean block confidence in `[0, 1]`. |
| `block_count` | `integer`| Number of recognized text blocks. |
| `blocks` | `array` | Per-block `{ text, bbox }` entries (`bbox` = `left,top,right,bottom`). |
| `model` | `string` | Recognizer used (`ML Kit Text Recognition v2 (Latin)`). |
| `status` | `string` | `"SUCCESS"`, `"FAILED"`, or `"UNSUPPORTED"` (no Android context). |
| `timestamp` | `integer`| Unix epoch millisecond timestamp. |

Default output mapping: `raw_value → user.scanned_code`, `format → user.scanned_format`,
`type → user.scanned_type`, `status → user.scan_status`, `timestamp → user.scan_timestamp`,
`text → user.ocr_text`, `confidence → user.ocr_confidence`, `block_count → user.ocr_block_count`.

> OCR runs fully on-device through ML Kit Text Recognition v2 (Latin model, ≈4.1 MB).
> For text that must then be embedded into a vector, use
> [`tools://vectorize/image_text`](vectorize.md), which chains OCR and embedding.

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

### OCR: Read Text from a Captured Document
```json
{
  "type": "button",
  "text": "Read Invoice Text",
  "action": {
    "url": "tools://scan/ocr",
    "payload": {
      "uri": "@{camera_capture_uri}",
      "output": {
        "text": "invoice_text",
        "confidence": "invoice_confidence"
      }
    }
  }
}
```
