# Architectural Model

```
┌────────────────────────────────────────────────────────────────────────┐
│                        SDUI DOCUMENT / APP                             │
│     (Telemedicine, Identity Verification, Warehouse, Field Audit)      │
│                                                                        │
│  Calls: tools://camera/capture                                         │
│  Options: { facing: "back", encoding: "both", max_dimension: 1280 }    │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│                   GENERIC CAMERA RUNTIME (THE BROWSER)                 │
│                                                                        │
│  1. Camera Sensor (Android CameraX ImageCapture / iOS AVCapturePhoto)  │
│  2. Tenant-Isolated Storage (`workspaces/<tenantId>/media/`)           │
│  3. Image Pipeline (Exif orientation, compression, scale constraints)  │
│  4. Encoders (File URI, Raw Base64, Data URL)                          │
│  5. Hardware Controls (Torch / Flashlight, Lens Selector)              │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼ Generic JSON Output
┌────────────────────────────────────────────────────────────────────────┐
│  {                                                                     │
│    "uri": "content://com.example.provider/media/cap_1712345678901.jpg",│
│    "mime_type": "image/jpeg",                                          │
│    "width": 1280,                                                      │
│    "height": 960,                                                      │
│    "size_bytes": 248910,                                               │
│    "facing": "back",                                                   │
│    "base64": "/9j/4AAQSkZJRg...",                                      │
│    "timestamp": 1712345678901,                                         │
│    "status": "SUCCESS"                                                 │
│  }                                                                     │
└────────────────────────────────────────────────────────────────────────┘
```

# Universal Hardware Camera Tool (`tools://camera` / `hardware.camera@1`)

The **Universal Camera Tool** provides an unopinionated bridge for camera frame capture, image transcoding, flashlight control, and tenant-isolated media storage.

Operating like the W3C `MediaDevices.getUserMedia()` and `ImageCapture` web standards, it captures visual frames without making any assumptions about the business domain (identity verification, logistics damage reports, medical imaging, or retail).

---

## 1. Parameters & Options

Invoked via:
- `tools://camera/capture`
- `tools://camera/torch`
- `tools://camera/status`
- `div-action://terminal.tool.invoke?tool=hardware.camera@1`

### Options (`payload.options`)

| Option | Type | Default | Description |
| :--- | :--- | :--- | :--- |
| `facing` | `string` | `"back"` | Lens selection: `"back"` (environment) or `"front"` (user). |
| `quality` | `integer` | `85` | Compression quality (`10` to `100`). |
| `format` | `string` | `"image/jpeg"` | Image encoding: `"image/jpeg"`, `"image/png"`, `"image/webp"`. |
| `encoding` | `string` | `"uri"` | Return format: `"uri"`, `"base64"`, `"data_url"`, or `"both"`. |
| `max_dimension`| `integer` | `1920` | Scales max width/height to avoid high memory overhead. |
| `torch` | `boolean` | `false` | Enables flashlight during capture. |
| `simulated_base64`| `string`| `null` | Predefined base64 string for testing on headless emulators. |

---

## 2. Tool Output Schema

| Field | Type | Description |
| :--- | :--- | :--- |
| `uri` | `string` | Sandboxed local file URI (`file://.../workspaces/<tenantId>/media/<id>.jpg`). |
| `image_id` | `string` | Unique media identifier (e.g., `img_4a8f9c1b2e3d`). |
| `mime_type` | `string` | Image MIME type (e.g., `image/jpeg`). |
| `width` | `integer` | Image pixel width. |
| `height` | `integer` | Image pixel height. |
| `size_bytes` | `integer` | Total file size in bytes. |
| `facing` | `string` | Lens used (`"back"` or `"front"`). |
| `base64` | `string?` | Base64-encoded image (only if requested via `encoding`). |
| `data_url` | `string?` | RFC 2397 Data URL (e.g., `data:image/jpeg;base64,...`). |
| `timestamp` | `integer` | Unix millisecond timestamp. |
| `status` | `string` | `"SUCCESS"` or `"FAILED"`. |

---

## 3. DivKit SDUI Usage Examples

### Example A: Patient / User Avatar Capture (Front Lens, Data URL)
```json
{
  "type": "button",
  "text": "Take Profile Photo",
  "action": {
    "url": "tools://camera/capture",
    "payload": {
      "options": {
        "facing": "front",
        "max_dimension": 600,
        "encoding": "data_url"
      },
      "output": {
        "data_url": "user_avatar_preview",
        "uri": "user_avatar_file_path"
      }
    }
  }
}
```

### Example B: High-Res Document / Receipt Inspection (Back Lens, Torch)
```json
{
  "type": "button",
  "text": "Capture Document Inspection",
  "action": {
    "url": "tools://camera/capture",
    "payload": {
      "options": {
        "facing": "back",
        "quality": 95,
        "max_dimension": 1920,
        "torch": "true",
        "encoding": "uri"
      },
      "output": {
        "uri": "captured_document_uri",
        "size_bytes": "file_size"
      }
    }
  }
}
```

### Example C: Flashlight Toggle
```json
{
  "type": "button",
  "text": "Toggle Flashlight",
  "action": {
    "url": "tools://camera/torch",
    "payload": {
      "options": {
        "state": "on"
      },
      "output": {
        "torch_state": "is_torch_active"
      }
    }
  }
}
```