# Architectural Model

```
┌────────────────────────────────────────────────────────────────────────┐
│                        SDUI DOCUMENT / APP                             │
│      (Product Recognition, Stock Take, Visual Search)                  │
│                                                                        │
│  Calls: tools://MobileNetV4-small/classify                             │
│  Payload: { image_id: "img_snapshot_live" }                            │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│                ON-DEVICE IMAGE CLASSIFIER (INT8)                       │
│                                                                        │
│  1. MobileNetV4-small quantized feature extractor                      │
│  2. Top-K ranking with a minimum-confidence gate                       │
│  3. Emits label + SKU + confidence (no domain catalogue baked in)       │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼ Generic JSON Output
┌────────────────────────────────────────────────────────────────────────┐
│  {                                                                     │
│    "prediction_label": "Wagyu Smash Burger (Entree)",                  │
│    "prediction_sku": "ACM-101",                                        │
│    "confidence": 0.94,                                                 │
│    "candidates": "ACM-101:0.94|ACM-102:0.78"                           │
│  }                                                                     │
└────────────────────────────────────────────────────────────────────────┘
```

# MobileNetV4 Image Classifier (`tools://MobileNetV4-small` / `vision.identify_product@1`)

The **MobileNetV4 Image Classifier** performs on-device INT8 image classification and SKU
recognition. It is unopinionated: it returns ranked labels and confidences, and the calling
document decides how to use them.

---

## 1. Parameters & Options

Invoked via:
- `tools://MobileNetV4-small/classify`
- `tools://MobileNetV4-small/detect`
- `tools://MobileNetV4-small/predict`
- `div-action://terminal.tool.invoke?tool=vision.identify_product@1`

### Options (`payload.options`)

| Option | Type | Default | Description |
| :--- | :--- | :--- | :--- |
| `top_k` | `integer` | `3` | Maximum number of candidates to return. |
| `min_confidence` | `double` | `0.40` | Minimum confidence for a candidate to be accepted. |

### Payload (`payload`)

| Field | Type | Description |
| :--- | :--- | :--- |
| `image_id` | `string` | Reference to the captured image (also accepts `image`). |

---

## 2. Tool Output Schema

| Field | Type | Description |
| :--- | :--- | :--- |
| `prediction_label` | `string` | Top-ranked label. |
| `prediction_sku` | `string` | Top-ranked SKU/code. |
| `confidence` | `double` | Confidence of the top prediction. |
| `candidates` | `string` | Pipe-separated `SKU:confidence` list of accepted candidates. |
| `tenant_id` | `string` | Calling tenant. |
| `status` | `string` | `"SUCCESS"`. |
| `timestamp` | `integer` | Unix epoch millisecond timestamp. |

Default output mapping: `prediction_label → user.ai_prediction_label`,
`prediction_sku → user.ai_prediction_sku`, `confidence → user.ai_confidence`.

---

## 3. DivKit Usage Examples

### Classify a Captured Photo
```json
{
  "type": "button",
  "text": "Identify Product",
  "action": {
    "url": "tools://MobileNetV4-small/classify",
    "payload": {
      "image_id": "@{camera_capture_uri}",
      "options": { "top_k": "3", "min_confidence": "0.4" },
      "output": {
        "prediction_label": "ai_label",
        "prediction_sku": "ai_sku",
        "confidence": "ai_confidence"
      }
    }
  }
}
```
