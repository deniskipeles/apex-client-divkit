# Architectural Model

```
┌────────────────────────────────────────────────────────────────────────┐
│                        SDUI DOCUMENT / APP                             │
│      (Cutout Composites, AR Preview, Catalogue Cleanup)                │
│                                                                        │
│  Calls: tools://u-2-Net.tflite/segment                                 │
│  Payload: { tensor_input: "320x320x3" }                                │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│                U-2-NET SALIENT SEGMENTATION RUNTIME                    │
│                                                                        │
│  1. U-2-Netp TFLite graph (bundled, 320x320)                          │
│  2. Sigmoid saliency map normalised to an alpha matte                  │
│  3. Produces a transparent cutout PNG and a grayscale mask PNG         │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼ Generic JSON Output
┌────────────────────────────────────────────────────────────────────────┐
│  {                                                                     │
│    "uri": "file:///.../u2net_<id>_cutout.png",                         │
│    "mask_uri": "file:///.../u2net_<id>_mask.png",                      │
│    "confidence": 0.94,                                                 │
│    "algorithm": "U-2-Netp (TFLite, 320x320 salient-object matte)",     │
│    "model": "u2netp.tflite",                                           │
│    "inference_ms": 22,                                                 │
│    "status": "SUCCESS"                                                 │
│  }                                                                     │
└────────────────────────────────────────────────────────────────────────┘
```

# U-2-Net Salient Object Segmentation (`tools://u-2-Net.tflite` / `vision.remove_background@1`)

The **U-2-Net Segmentation Tool** runs the real **U-2-Netp** model on device through
TensorFlow Lite (`assets/u2netp.tflite`, 4.4 MB) and produces an alpha-matte cutout plus a
grayscale mask. If the TFLite runtime or model cannot load on a host, it falls back to the
native colour-distance matte and says so in `algorithm`/`note` rather than fabricating a
neural result.

---

## 1. Parameters & Options

Invoked via:
- `tools://u-2-Net.tflite/segment`
- `tools://u-2-Net.tflite/infer`
- `tools://u-2-Net.tflite/mask`
- `div-action://terminal.tool.invoke?tool=vision.remove_background@1`

### Options (`payload.options`)

| Option | Type | Default | Description |
| :--- | :--- | :--- | :--- |
| `uri` / `image` | `string` | — | Source image path, `file://` URI, or base64. When omitted the host image-source bridge opens the camera or picker. |
| `camera` | `boolean` | `true` | Allow the camera when acquiring a frame from the host. |
| `tensor_input` | `string` | `"320x320x3"` | Echoed model input shape. |
| `saliency_cutoff` / `threshold` | `double` | `0.45` | Echoed cutoff used by the card; the U-2-Net sigmoid already yields a soft matte. |

---

## 2. Tool Output Schema

| Field | Type | Description |
| :--- | :--- | :--- |
| `uri` | `string` | Transparent cutout PNG (same as `mask_uri` for card compatibility). |
| `mask_uri` | `string` | Grayscale alpha-matte PNG. |
| `image_id` | `string` | Generated image identifier. |
| `width`, `height` | `integer` | Output dimensions. |
| `confidence` | `double` | Mean alpha coverage (0–1). |
| `algorithm` | `string` | `U-2-Netp (TFLite, ...)` or the fallback name. |
| `model` | `string` | `u2netp.tflite`. |
| `inference_ms` | `integer` | End-to-end segmentation time in milliseconds. |
| `source_image` | `string` | Input URI/path. |
| `status` | `string` | `"SUCCESS"`. |

Default output mapping: `mask_uri → user.u2net_mask_uri`,
`confidence → user.u2net_confidence`.

---

## 3. DivKit Usage Examples

### Produce an Alpha Matte
```json
{
  "type": "button",
  "text": "Cut Out Subject",
  "action": {
    "url": "tools://u-2-Net.tflite/segment",
    "payload": {
      "options": { "saliency_cutoff": "0.45" },
      "output": { "mask_uri": "cutout_mask", "confidence": "cutout_confidence" }
    }
  }
}
```
