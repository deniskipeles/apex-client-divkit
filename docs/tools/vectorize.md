# Architectural Model

```
┌────────────────────────────────────────────────────────────────────────┐
│                        SDUI DOCUMENT / APP                             │
│   (Semantic Search, Duplicate Detection, Product Matching, RAG)        │
│                                                                        │
│  Calls: tools://vectorize/text                                         │
│  Payload: { text: "wagyu smash burger" }                               │
│  OR     tools://vectorize/image_text                                   │
│  Payload: { uri: "content://.../receipt.jpg" }                         │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│                  UNIFIED VECTOR EMBEDDING RUNTIME                      │
│                                                                        │
│  1. Text Path    → MediaPipe USE-Lite (quantized, on-device)           │
│  2. Image Path   → MobileNetV4-small INT8 feature extractor            │
│  3. OCR Path     → ML Kit Text Recognition v2 (Latin)                  │
│  4. image_text   → OCR then embed the recognized text                  │
│  5. L2 Normalization (unit-length vectors)                             │
│  6. Cosine Similarity helper (compare two packed vectors)              │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼ Generic JSON Output
┌────────────────────────────────────────────────────────────────────────┐
│  {                                                                     │
│    "vector_b64": "AACAP8ZP...",   // little-endian float32, base64     │
│    "dim": 128,                                                         │
│    "source_type": "text",                                              │
│    "status": "SUCCESS"                                                 │
│  }                                                                     │
└────────────────────────────────────────────────────────────────────────┘
```

# Universal Vector Embedding Tool (`tools://vectorize`)

The **Universal Vector Embedding Tool** turns text, images, or OCR-derived text into
L2-normalized embedding vectors using quantized on-device models. It is domain-agnostic:
it produces numeric vectors and similarity scores without assuming what they represent
(no catalogue, no index, no ranking policy).

---

## 1. Parameters & Options

Invoked via:
- `tools://vectorize/text`
- `tools://vectorize/image`
- `tools://vectorize/image_text`
- `tools://vectorize/ocr`
- `tools://vectorize/similarity`
- `div-action://terminal.tool.invoke?tool=vectorize`

### Options (`payload.options`)

| Option | Type | Default | Description |
| :--- | :--- | :--- | :--- |
| `normalize` | `boolean` | `true` | L2-normalizes the emitted vector to unit length. |
| `include_ocr_text` | `boolean` | `true` | For `image_text`, include the recognized text in the output. |

### Payload by operation (`payload`)

| Operation | Input field | Description |
| :--- | :--- | :--- |
| `text` | `text` | Raw string to embed. |
| `image` | `uri` | Bitmap reference: `data:image/...`, `content://`, `file://`, absolute path, or bare base64. |
| `image_text` | `uri` | Bitmap reference; OCR text is extracted and embedded. |
| `ocr` | `uri` | Bitmap reference; returns recognized text only (no vector). |
| `similarity` | `a`, `b` | Two packed (base64 float32) vectors to compare. |

---

## 2. Tool Output Schema

| Field | Type | Description |
| :--- | :--- | :--- |
| `vector_b64` | `string` | Base64 of little-endian float32 embedding (`text`/`image`/`image_text`). |
| `dim` | `integer` | Embedding dimensionality. |
| `source_type` | `string` | `"text"`, `"image"`, or `"image_text"`. |
| `ocr_text` | `string` | Recognized text (`image_text`). |
| `ocr_confidence` | `double` | Mean OCR confidence (`image_text`). |
| `text` | `string` | Recognized text (`ocr`). |
| `confidence` | `double` | Mean OCR confidence (`ocr`). |
| `block_count` | `integer` | OCR text block count (`ocr`). |
| `score` | `double` | Cosine similarity in `[-1, 1]` (`similarity`). |
| `method` | `string` | `"cosine"` (`similarity`). |
| `model` | `string` | Model used for the chosen path. |
| `status` | `string` | `"SUCCESS"`. |

Default output mapping: `vector_b64 → user.vector_b64`, `dim → user.vector_dim`,
`source_type → user.vector_source`, `ocr_text → user.ocr_text`,
`score → user.vector_similarity`.

---

## 3. DivKit Usage Examples

### Embed Free Text
```json
{
  "type": "button",
  "text": "Embed Query",
  "action": {
    "url": "tools://vectorize/text",
    "payload": {
      "text": "@{search_query}",
      "output": { "vector_b64": "query_vector", "dim": "vector_dim" }
    }
  }
}
```

### OCR + Embed a Captured Receipt
```json
{
  "type": "button",
  "text": "Index Receipt",
  "action": {
    "url": "tools://vectorize/image_text",
    "payload": {
      "uri": "@{camera_capture_uri}",
      "options": { "include_ocr_text": "true" },
      "output": {
        "vector_b64": "receipt_vector",
        "ocr_text": "receipt_text"
      }
    }
  }
}
```
