# Architectural Model

```
┌────────────────────────────────────────────────────────────────────────┐
│                        SDUI DOCUMENT / APP                             │
│      (Visual Similarity, Duplicate Images, Catalogue Indexing)         │
│                                                                        │
│  Calls: div-action://terminal.tool.invoke?tool=vision.embed_image@1    │
│  Options: { image_id: "img_1a2b3c4d" }                                 │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│                 IMAGE EMBEDDING RUNTIME (INT8)                         │
│                                                                        │
│  1. MobileNetV4 / sekokuva feature extractor                           │
│  2. Produces a 512-dimension embedding vector                          │
│  3. Returns a small preview sample of the vector                       │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼ Generic JSON Output
┌────────────────────────────────────────────────────────────────────────┐
│  {                                                                     │
│    "image_id": "img_1a2b3c4d",                                         │
│    "dim": 512,                                                         │
│    "vector_sample": "0.1023,-0.4471,0.8890,...",                       │
│    "state": "ok"                                                       │
│  }                                                                     │
└────────────────────────────────────────────────────────────────────────┘
```

# Image Embedding (`vision.embed_image@1`)

The **Image Embedding Tool** generates a fixed-dimension embedding vector for a captured
image, suitable for visual similarity and duplicate detection. It is unopinionated: it
returns a vector (and a preview sample) without assuming how it is indexed or compared.

> This capability has no dedicated `tools://` schema alias. It is invoked directly through
> the versioned registry (`div-action://terminal.tool.invoke?tool=vision.embed_image@1`)
> and is available to `Provenance.Publisher` callers via the publisher allowlist.

---

## 1. Parameters & Options

Invoked via:
- `div-action://terminal.tool.invoke?tool=vision.embed_image@1`

### Options (`payload.options`)

| Option | Type | Default | Description |
| :--- | :--- | :--- | :--- |
| `image` | `string` | — | Image reference (required). Also accepts `image_id`. |

---

## 2. Tool Output Schema

| Field | Type | Description |
| :--- | :--- | :--- |
| `image_id` | `string` | The image reference that was embedded. |
| `dim` | `integer` | Descriptor dimensionality (`1000`). |
| `vector_b64` | `string` | Base64 of little-endian float32 descriptor components. |
| `model` | `string` | `"MobileNetV4-small INT8"`. |
| `state` | `string` | `"ok"`. |

A missing `image`/`image_id` argument returns a `Failed` result
(`"missing image argument"`).

The bundled MobileNetV4 graph exposes a single 1000-value `class_logits` tensor. This is a
class-score descriptor, not a trained metric-learning embedding; use `tools://embedding/image_text`
for semantic text-based image search.

---

## 3. DivKit Usage Examples

### Embed a Captured Image
```json
{
  "type": "button",
  "text": "Embed Image",
  "action": {
    "url": "div-action://terminal.tool.invoke?tool=vision.embed_image@1",
    "payload": {
      "options": { "image": "@{camera_capture_uri}" },
      "output": { "dim": "image_vector_dim" }
    }
  }
}
```
