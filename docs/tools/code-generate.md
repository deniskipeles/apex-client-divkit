# Architectural Model

```
┌────────────────────────────────────────────────────────────────────────┐
│                        SDUI DOCUMENT / APP                             │
│   (Loyalty Cards, Order Pickup, Table QR, Product Labels, Shipping)    │
│                                                                        │
│  Calls: tools://code-generate/generate                                 │
│  Payload: { content: "https://pos.example/order/42", format: "qr" }    │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│                     CODE GENERATION RUNTIME (ZXing)                    │
│                                                                        │
│  1. Symbology Resolver  → format alias → BarcodeFormat                 │
│  2. Encoder            → MultiFormatWriter (QR, DataMatrix, Aztec,     │
│                          PDF417, EAN, UPC, Code 128/39/93, ITF, ...)   │
│  3. Module Matrix      → dark/light grid (inspectable text form)       │
│  4. Renderer           → ARGB_8888 bitmap (fg/bg colors, margin)       │
│  5. Media Sandbox      → PNG written to workspaces/<tenant>/media/     │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼ Generic JSON Output
┌────────────────────────────────────────────────────────────────────────┐
│  {                                                                     │
│    "status": "SUCCESS",                                                │
│    "uri": "file://.../media/code_9f3c1a2b.png",                        │
│    "content": "https://pos.example/order/42",                          │
│    "format": "QR_CODE",                                                │
│    "kind": "2D",                                                       │
│    "matrix": "1111111...",                                             │
│    "size_bytes": 1842                                                  │
│  }                                                                     │
└────────────────────────────────────────────────────────────────────────┘
```

# Barcode & QR Code Generator (`tools://code-generate` / `vision.generate_code@1`)

The **Barcode & QR Code Generator Tool** is the encode-side counterpart to the
[Universal Optical Scanner](scan.md). It turns arbitrary string content into a 1D
barcode or 2D matrix symbology and renders it to an image on-device, with no network
round-trip and no external service. It is domain-agnostic: it produces a rendered
symbol and its module matrix without assuming what the content means.

Rendering is backed by [ZXing](https://github.com/zxing/zxing) `MultiFormatWriter`,
a pure-JVM library with no Play Services dependency, so it works on every device and
in headless unit tests.

---

## 1. Parameters & Options

Invoked via:
- `tools://code-generate/generate`
- `tools://code-generate/qr`
- `tools://code-generate/barcode`
- `div-action://terminal.tool.invoke?tool=vision.generate_code@1`

### Payload fields

| Field | Type | Default | Description |
| :--- | :--- | :--- | :--- |
| `content` | `string` | — | **Required.** The value to encode (also accepts `text`, `data`, or `value`). |
| `format` | `string` | `"qr"` | Target symbology (see alias table below). Also accepts `symbology` or `type`. |
| `width` | `integer` | `512` | Output width in pixels, clamped to `64..2048`. |
| `height` | `integer` | `512` | Output height in pixels for 1D symbols, clamped to `32..2048`. Ignored for 2D (square). |
| `margin` | `integer` | `4` | Quiet-zone margin in modules, clamped to `0..16`. |
| `ec_level` | `string` | `"M"` | QR/Aztec error correction: `"L"`, `"M"`, `"Q"`, `"H"`. Also accepts `error_correction`. |
| `fg_color` | `string` | `"#000000"` | Module (dark) color as an Android `Color.parseColor` string. Also accepts `foreground`. |
| `bg_color` | `string` | `"#FFFFFF"` | Background (light) color. Also accepts `background`. |
| `encoding` | `string` | `"uri"` | `"uri"` returns only the file URI; `"base64"`/`"both"` also inline the PNG as base64. |

### Supported symbologies (`format` aliases)

| `format` value(s) | `BarcodeFormat` | Kind |
| :--- | :--- | :--- |
| `qr`, `qrcode`, `qr_code`, `auto`, `both`, `any` | `QR_CODE` | 2D |
| `datamatrix`, `data-matrix`, `dm` | `DATA_MATRIX` | 2D |
| `aztec` | `AZTEC` | 2D |
| `pdf417`, `pdf-417` | `PDF_417` | 2D |
| `ean13`, `ean-13` | `EAN_13` | 1D |
| `ean8`, `ean-8` | `EAN_8` | 1D |
| `upca`, `upc-a` | `UPC_A` | 1D |
| `upce`, `upc-e` | `UPC_E` | 1D |
| `code128`, `code-128` | `CODE_128` | 1D |
| `code39`, `code-39` | `CODE_39` | 1D |
| `code93`, `code-93` | `CODE_93` | 1D |
| `itf`, `itf-14`, `interleaved-2-of-5` | `ITF` | 1D |
| `codabar` | `CODABAR` | 1D |

Unknown values fall back to `QR_CODE`.

---

## 2. Tool Output Schema

| Field | Type | Description |
| :--- | :--- | :--- |
| `status` | `string` | `"SUCCESS"` or `"FAILED"`. |
| `code_id` | `string` | Generated identifier, e.g. `code_9f3c1a2b7d10`. |
| `content` | `string` | The encoded value. |
| `format` | `string` | Resolved symbology name, e.g. `QR_CODE`, `EAN_13`. |
| `kind` | `string` | `"1D"` (linear) or `"2D"` (matrix). |
| `uri` | `string` | `file://` URI of the rendered PNG in the tenant media sandbox. |
| `width` / `height` | `integer` | Rendered pixel dimensions. |
| `size_bytes` | `integer` | PNG byte size. |
| `mime_type` | `string` | `"image/png"`. |
| `matrix` | `string` | Row-major module grid, `1` = dark / `0` = light, rows separated by `\n`. |
| `matrix_rows` | `integer` | Number of matrix rows. |
| `base64` | `string` | Inline PNG (`encoding` = `base64` or `both` only). |

Default output mapping: `status → user.code_status`, `uri → user.code_image_uri`,
`content → user.code_content`, `format → user.code_format`, `kind → user.code_kind`,
`code_id → user.code_id`, `matrix → user.code_matrix`.

> The module `matrix` is emitted so a document can render a code as a pure DivKit grid
> (e.g. a `div-grid` of `div-text` cells) without any image decoding, and so headless
> tests can assert symbol structure without inspecting pixels.

---

## 3. DivKit Usage Examples

### Generate a QR Code for Order Pickup
```json
{
  "type": "button",
  "text": "Show Pickup QR",
  "action": {
    "url": "tools://code-generate/generate",
    "payload": {
      "content": "https://pos.example/order/@{order_id}",
      "format": "qr",
      "width": "384",
      "ec_level": "M",
      "output": {
        "uri": "pickup_qr_uri",
        "code_id": "pickup_qr_id"
      }
    }
  }
}
```

### Render a Product Barcode
```json
{
  "type": "button",
  "text": "Print Shelf Label",
  "action": {
    "url": "tools://code-generate/barcode",
    "payload": {
      "content": "@{sku_ean13}",
      "format": "EAN_13",
      "width": "400",
      "height": "160",
      "fg_color": "#111111",
      "bg_color": "#FFFFFF",
      "output": {
        "uri": "label_uri",
        "kind": "label_kind"
      }
    }
  }
}
```

### Inline a Loyalty Code as Base64
```json
{
  "type": "button",
  "text": "Embed Loyalty Code",
  "action": {
    "url": "tools://code-generate/qr",
    "payload": {
      "content": "@{loyalty_token}",
      "encoding": "both",
      "output": {
        "uri": "loyalty_qr_uri",
        "format": "loyalty_qr_format"
      }
    }
  }
}
```
