# Embedding (`tools://embedding`)

The **Embedding Tool** turns text, images, or OCR-derived text into L2-normalized numeric
vectors and compares packed vectors with cosine similarity. It is domain-agnostic: it does
not assume a catalogue, an index, or a ranking policy.

`tools://vectorize/*` and the `vector` / `embed` / `embed-text` / `embed-image` aliases
remain supported for existing cards. The versioned contract id is still `vectorize`; the
Android handler is `embedding`.

---

## 1. Parameters & Options

Invoked via:
- `tools://embedding/text`
- `tools://embedding/image`
- `tools://embedding/image_text`
- `tools://embedding/text_similarity`
- `tools://embedding/similarity`
- `div-action://terminal.tool.invoke?tool=embedding`
- Legacy: `tools://vectorize/*`, `div-action://terminal.tool.invoke?tool=vectorize`

> Standalone text extraction (OCR without embedding) lives in the optical scanner:
> [`tools://scan/ocr`](scan.md). `tools://embedding/image_text` chains OCR and embedding.

### Options (`payload.options`)

| Option | Type | Default | Description |
| :--- | :--- | :--- | :--- |
| `normalize` | `boolean` | `true` | Vectors are L2-normalized. |
| `include_ocr_text` | `boolean` | `true` | For `image_text`, include recognized text in the output. |

### Payload by operation (`payload`)

| Operation | Input fields | Description |
| :--- | :--- | :--- |
| `text` | `text` | Non-blank string to embed with MediaPipe USE-Lite, or a deterministic 512-d lexical fallback when the MediaPipe text JNI is absent (e.g. x86_64 emulators). |
| `image` | `uri` | Image reference: `data:image/...`, `content://`, `file://`, absolute path, or bare base64. Uses the MobileNetV4 INT8 1000-d class-score descriptor. |
| `image_text` | `uri` | Image reference; ML Kit OCR text is embedded with USE-Lite. |
| `text_similarity` | `a`, `b` (or `text_a`, `text_b`) | Two strings; both are embedded and compared. |
| `similarity` | `a`, `b` | Two packed (base64 float32) vectors to compare. |

Missing, blank, undecodable, or dimension-mismatched inputs return `status: "FAILED"` with
an `error` field. They never return a zero vector or a fabricated score.

---

## 2. Tool Output Schema

| Field | Type | Description |
| :--- | :--- | :--- |
| `vector_b64` | `string` | Base64 of little-endian float32 embedding (`text` / `image` / `image_text`). |
| `dim` | `integer` | Embedding dimensionality (512 for USE-Lite, 1000 for the image descriptor). |
| `source_type` | `string` | `"text"`, `"image"`, `"image_text"`, `"text_similarity"`, or `"similarity"`. |
| `ocr_text` | `string` | Recognized text (`image_text`). |
| `ocr_confidence` | `double` | Mean OCR confidence (`image_text`). |
| `score` | `double` | Cosine similarity in `[-1, 1]` (`text_similarity` / `similarity`). |
| `method` | `string` | `"cosine"` (`text_similarity` / `similarity`). |
| `model` | `string` | Model or descriptor used. Text reports `MediaPipe USE-Lite` or `Hashed lexical fallback (512-d FNV-1a)`; image reports the MobileNetV4 descriptor. |
| `status` | `string` | `"SUCCESS"` or `"FAILED"`. |

Default output mapping: `vector_b64 → user.vector_b64`, `dim → user.vector_dim`,
`source_type → user.vector_source`, `ocr_text → user.ocr_text`,
`score → user.vector_similarity`.

MediaPipe 0.10.14 ships no `libmediapipe_tasks_text_jni.so` for x86_64. The tool does
not fail there: `TextVectorizer` catches the load failure once and falls back to
`HashedTextEmbedding`, a deterministic word-unigram + character-trigram hashing vector.
It is a real lexical similarity vector (shared vocabulary raises the cosine score), not a
semantic model, and the `model` field says so. On arm64/armv7 the MediaPipe model is used.

The image path is a **class-score descriptor**, not a metric-learning embedding. It is
stable for near-duplicate and coarse visual similarity; for semantic image search, OCR the
image and embed the text (`image_text`) or supply your own embedding model.

---

## 3. DivKit Usage Examples

### Embed Free Text
```json
{
  "type": "button",
  "text": "Embed Query",
  "action": {
    "url": "tools://embedding/text",
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
    "url": "tools://embedding/image_text",
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

### Compare Two Texts
```json
{
  "type": "button",
  "text": "Match Menu Item",
  "action": {
    "url": "tools://embedding/text_similarity",
    "payload": {
      "a": "Wagyu Smash Burger",
      "b": "@{user.menu_item_name}",
      "output": { "score": "match_score" }
    }
  }
}
```

### Compare Packed Vectors
```json
{
  "type": "button",
  "text": "Compare",
  "action": {
    "url": "tools://embedding/similarity",
    "payload": {
      "a": "@{user.vector_b64}",
      "b": "@{user.other_vector_b64}",
      "output": { "score": "similarity" }
    }
  }
}
```
