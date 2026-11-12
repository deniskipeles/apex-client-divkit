# Architectural Model

```
┌────────────────────────────────────────────────────────────────────────┐
│                        SDUI DOCUMENT / APP                             │
│       (Product Photos, ID Badges, Document Scanning, Avatars)          │
│                                                                        │
│  Calls: tools://image-manipulation/process                             │
│  Payload: {                                                            │
│    "uri": "@{camera_capture_uri}",                                     │
│    "pipeline": [                                                       │
│      { "op": "remove_background" },                                    │
│      { "op": "smooth", "type": "bilateral", "radius": 8 }              │
│    ]                                                                   │
│  }                                                                     │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│                  NATIVE IMAGE MANIPULATION RUNTIME                     │
│                                                                        │
│  1. Background Removal Engine:                                         │
│     • Android: OS-level Google Play Services Subject Segmentation (0MB)│
│     • iOS: Apple Vision VNGenerateForegroundInstanceMaskRequest (0MB)  │
│     • Fallback: Algorithmic Chroma-Key & Luminance Thresholding        │
│  2. Smoothing & Filtering Engine (0MB, High-Performance Canvas/PCM):   │
│     • Bilateral edge-preserving smoothing                              │
│     • Gaussian convolution & Fast StackBlur                            │
│  3. Color Transform Matrix:                                            │
│     • ColorMatrix contrast, brightness, saturation, grayscale          │
│  4. Geometry & Resampling:                                             │
│     • High-fidelity bilinear scaling, aspect-ratio cropping, rotation  │
│  5. Storage Sandbox:                                                   │
│     • Origin-isolated output: `workspaces/<tenantId>/media/proc_*.png` │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼ Output Descriptor
┌────────────────────────────────────────────────────────────────────────┐
│  {                                                                     │
│    "uri": "file://.../workspaces/<tenantId>/media/proc_4a8f9c.png",    │
│    "width": 1280,                                                      │
│    "height": 720,                                                      │
│    "mime_type": "image/png",                                           │
│    "operations_applied": ["remove_background", "smooth"],              │
│    "status": "SUCCESS"                                                 │
│  }                                                                     │
└────────────────────────────────────────────────────────────────────────┘
```

# Universal Image Manipulation Tool (`tools://image-manipulation` / `vision.image_manipulation@1`)

The **Universal Image Manipulation Tool** provides unopinionated, on-device image processing using native OS models (such as Google Play Services Subject Segmentation and Apple Vision Framework, which reside on the OS with a **0 MB app footprint**).

It supports background removal, edge-preserving smoothing, color/tone adjustment, thresholding, and geometric cropping/resizing.

---

## 1. Parameters & Options

Invoked via:
- `tools://image-manipulation/process`
- `tools://image-manipulation/remove_background`
- `tools://image-manipulation/smooth`
- `tools://image-manipulation/adjust_color`
- `div-action://terminal.tool.invoke?tool=vision.image_manipulation@1`

### Parameters (`payload`)

| Parameter | Type | Default | Description |
| :--- | :--- | :--- | :--- |
| `uri` / `image` | `string` | *required* | Source image file URI (`file://...`), `content://...`, or Base64 Data URL. |
| `pipeline` | `array` | `null` | Array of step objects executed in sequence (e.g. `[{"op":"remove_background"},{"op":"smooth"}]`). |
| `encoding` | `string` | `"uri"` | Return format: `"uri"`, `"base64"`, or `"both"`. |

### Operation Step Options

| Operation (`op`) | Arguments | Description |
| :--- | :--- | :--- |
| `remove_background` | `background_color` (hex), `threshold` (`0.0` to `1.0`) | Extracts foreground subject onto transparent PNG or specified solid color. |
| `smooth` | `radius` (`1` to `40`), `type` (`"bilateral"`, `"box"`, `"gaussian"`) | Smoothes skin and texture while preserving high-contrast edges. |
| `adjust_color` | `brightness` (`-1.0` to `1.0`), `contrast` (`0.0` to `2.0`), `saturation` (`0.0` to `2.0`), `grayscale` (`bool`) | Color and tone adjustment matrix. |
| `threshold` | `threshold` (`0` to `255`) | High-contrast black/white binarization for document scanning. |
| `crop` | `x`, `y`, `width`, `height` | Bounding box crop. |
| `resize` | `width`, `height` | High-fidelity bilinear scaling. |

---

## 2. Tool Output Schema

| Field | Type | Description |
| :--- | :--- | :--- |
| `uri` | `string` | Sandboxed output file URI (`file://.../workspaces/<tenantId>/media/proc_<id>.png`). |
| `image_id` | `string` | Unique media identifier. |
| `width` | `integer` | Pixel width of the processed image. |
| `height` | `integer` | Pixel height of the processed image. |
| `size_bytes` | `integer` | Output PNG size in bytes. |
| `mime_type` | `string` | Image MIME type (`"image/png"`). |
| `operations` | `string` | Comma-separated list of operations applied. |
| `base64` | `string?` | Base64-encoded image (only if requested via `encoding`). |
| `status` | `string` | `"SUCCESS"` or `"FAILED"`. |

---

## 3. DivKit SDUI Usage Examples

### Example A: Remove Background from Captured Photo
```json
{
  "type": "button",
  "text": "Extract Subject Cutout",
  "action": {
    "url": "tools://image-manipulation/remove_background",
    "payload": {
      "uri": "@{captured_photo_uri}",
      "output": {
        "uri": "cutout_image_uri",
        "status": "cutout_status"
      }
    }
  }
}
```

### Example B: Combined Background Removal + Bilateral Smoothing Pipeline
```json
{
  "type": "button",
  "text": "Enhance & Clean Avatar",
  "action": {
    "url": "tools://image-manipulation/process",
    "payload": {
      "uri": "@{user_avatar_uri}",
      "pipeline": [
        {
          "op": "remove_background",
          "background_color": "#FFFFFF"
        },
        {
          "op": "smooth",
          "type": "bilateral",
          "radius": 8
        }
      ],
      "output": {
        "uri": "enhanced_avatar_uri"
      }
    }
  }
}
```

### Example C: Document Binarization (Receipt / Form OCR Prep)
```json
{
  "type": "button",
  "text": "Binarize Document for OCR",
  "action": {
    "url": "tools://image-manipulation/threshold",
    "payload": {
      "uri": "@{scanned_document_uri}",
      "threshold": 130,
      "output": {
        "uri": "binarized_document_uri"
      }
    }
  }
}
```