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
│  1. U-2-Net quantized salient-object segmentation                      │
│  2. Saliency cutoff thresholding                                       │
│  3. Produces an alpha-matte mask (PNG)                                 │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼ Generic JSON Output
┌────────────────────────────────────────────────────────────────────────┐
│  {                                                                     │
│    "mask_uri": "content://com.example.provider/u2net_masks/mask_...",  │
│    "confidence": 0.94,                                                 │
│    "saliency_cutoff": 0.45,                                            │
│    "inference_ms": 22,                                                 │
│    "status": "SUCCESS"                                                 │
│  }                                                                     │
└────────────────────────────────────────────────────────────────────────┘
```

# U-2-Net Salient Object Segmentation (`tools://u-2-Net.tflite` / `vision.remove_background@1`)

The **U-2-Net Segmentation Tool** performs on-device salient object segmentation, producing
an alpha-matte mask. It is unopinionated: it returns a mask URI and confidence without
assuming how the mask will be used.

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
| `tensor_input` | `string` | `"320x320x3"` | Input tensor shape. |
| `saliency_cutoff` | `double` | `0.45` | Threshold above which a pixel is treated as salient foreground. |

---

## 2. Tool Output Schema

| Field | Type | Description |
| :--- | :--- | :--- |
| `mask_uri` | `string` | URI of the generated alpha-matte PNG. |
| `confidence` | `double` | Segmentation confidence. |
| `saliency_cutoff` | `double` | Cutoff applied. |
| `inference_ms` | `integer` | Inference time in milliseconds. |
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
