# Generic Text & JSON Codec (`tools://codec` / `data.codec@1`)

The codec tool is the terminal's unopinionated text and JSON primitive. It performs
Base64/Base45 transforms and JSON validation, extraction, stringification and QR-sized
chunking without knowing anything about the caller's domain.

It is intentionally not a receipt, order, or payload format. The caller chooses paths,
encodings, byte limits and chunk limits.

---

## 1. Invocation URIs

| URI | Operation | Purpose |
| :--- | :--- | :--- |
| `tools://codec/base64` | `base64` | Base64 encode or decode text. |
| `tools://codec/base45` | `base45` | RFC 9285 Base45 encode or decode text. |
| `tools://codec/json/validate` | `json/validate` | Parse JSON and report validity. |
| `tools://codec/json/extract` | `json/extract` | Read values by dot path and return a subtree. |
| `tools://codec/json/stringify` | `json/stringify` | Build JSON from keys/values or normalise raw JSON. |
| `tools://codec/json/chunk` | `json/chunk` | Split JSON into checksummed, numbered transfer envelopes. |
| `tools://codec/json/assemble` | `json/assemble` | Reassemble chunk envelopes and verify the checksum. |
| `div-action://terminal.tool.invoke?tool=data.codec@1` | `invoke` | Versioned capability invoke. |

Aliases `codecs`, `encoding`, `text.codec` and `text.codec@1` resolve to the same handler.

---

## 2. Operations

### 2.1 Base64 and Base45

```json
{
  "url": "tools://codec/base64",
  "payload": {
    "action": "encode",
    "text": "hello",
    "output": {
      "text": "user.codec_text",
      "length": "user.codec_length"
    }
  }
}
```

`action` accepts `encode`/`e` or `decode`/`d` (default `encode`). `url_safe: true` uses the
URL-safe Base64 alphabet. Base45 follows RFC 9285 and ignores CR/LF inserted between chunks.

### 2.2 JSON validation

```json
{
  "url": "tools://codec/json/validate",
  "payload": { "json": "{\"order_id\":\"42\"}" }
}
```

Returns `valid: true|false`, `kind` (`object`, `array`, `scalar`) and, on failure, `error`.
Invalid JSON is a successful codec call with `valid: false`; it is not a tool failure.

### 2.3 JSON extraction

```json
{
  "url": "tools://codec/json/extract",
  "payload": {
    "json": "{\"order\":{\"id\":42,\"lines\":[]}}",
    "paths": ["order.id", "order.lines", "missing.field"],
    "subtree_path": "order"
  }
}
```

Paths are dot-separated and may index arrays by number (`lines.0.sku`). Missing values are
returned as empty strings and listed in `missing`. `subtree_path` additionally returns the
selected node as `subtree_json`.

### 2.4 JSON stringification

```json
{
  "url": "tools://codec/json/stringify",
  "payload": {
    "keys": ["order_id", "total"],
    "values": ["42", 18.5]
  }
}
```

With `keys`, a JSON object is built in key order. Without `keys`, `json`/`text` is parsed
and re-serialised. Values are passed through as JSON values, so numbers stay numbers.

### 2.5 Chunking for QR transfer

```json
{
  "url": "tools://codec/json/chunk",
  "payload": {
    "json": "{\"order_id\":\"42\",\"note\":\"hello\"}",
    "encoding": "base45",
    "max_bytes": 500,
    "max_chunks": 32
  }
}
```

Each chunk is a self-describing envelope:

```
C1|<transfer_id>|<index>|<total>|<sha256-prefix>|<payload>
```

`max_bytes` caps the encoded envelope length (64–500 characters). `max_chunks` caps the
transfer size (1–32). If the payload cannot fit, the call fails with a message stating the
required chunk count. `encoding` is `base45` (default) or `base64`.

The response contains `chunks`, `count`, `transfer_id`, `checksum`, `raw_bytes`,
`max_bytes` and `max_chunks`. `user.codec_chunks` receives the array so a card can render
one QR per chunk with `tools://code-generate`.

### 2.6 Assembling chunks

```json
{
  "url": "tools://codec/json/assemble",
  "payload": {
    "chunks": ["C1|…|00|02|…", "C1|…|01|02|…"],
    "encoding": "base45"
  }
}
```

`assemble` sorts by index, rejects mismatched transfer ids or totals, and verifies the
SHA-256 prefix before returning `json`. A missing index returns `status: "PARTIAL"` with
the missing list. A checksum mismatch returns `status: "FAILED"` and `checksum_ok: false`;
it is a failed tool call, not silent corruption.

---

## 3. Output Variables

| Field | Variable | Meaning |
| :--- | :--- | :--- |
| `status` | `user.codec_status` | `SUCCESS`, `PARTIAL`, `COMPLETE` or `FAILED`. |
| `text` | `user.codec_text` | Encoded or decoded text. |
| `encoding` | `user.codec_encoding` | `base64` or `base45`. |
| `length` | `user.codec_length` | Text length. |
| `valid` | `user.codec_valid` | JSON validity flag. |
| `error` | `user.codec_error` | Parse or checksum error. |
| `values` | `user.codec_values` | Extracted values, in path order. |
| `count` | `user.codec_count` | Extracted value count or chunk count. |
| `missing` | `user.codec_missing` | Missing paths or chunk indexes. |
| `json` | `user.codec_json` | Stringified or reassembled JSON. |
| `subtree_json` | `user.codec_subtree_json` | Selected subtree. |
| `chunks` | `user.codec_chunks` | Transfer envelopes. |
| `transfer_id` | `user.codec_transfer_id` | Transfer identifier. |
| `checksum` | `user.codec_checksum` | SHA-256 prefix. |
| `checksum_ok` | `user.codec_checksum_ok` | Assembly checksum result. |
| `raw_bytes` | `user.codec_raw_bytes` | Payload size before encoding. |

---

## 4. Notes

- `json/chunk` measures the encoded envelope, not the raw payload, so the value is safe to
  use as a QR character budget.
- Chunk envelopes are generic. They do not contain a schema version or business meaning;
  callers that need one should include it inside the JSON payload.
- The tool is pure Kotlin and runs on plain JVM tests.

## Conformance cases

Contract: `apps/dev-server/tests/tools/definitions.ts` (`toolId: codec`).

- `json/validate` on a document that does not parse returns `status: INVALID`,
  `valid: false` and the parser message. It previously reported `SUCCESS` with
  `valid: false`, so a caller reading only the status was told the document was fine.
  `INVALID` is listed in `NativeToolsSuite.FAILURE_STATUSES`, so `success` is `false`.
- `json/extract` takes `paths` as an array at the payload root (flat `options` values can
  only carry strings) and reports `count`/`missing`.
- `json/stringify` takes `keys` + `values` arrays, or a `json`/`text` document.
- `json/chunk` emits `C1|<transfer>|<index>|<total>|<checksum>|<payload>` envelopes;
  `json/assemble` accepts them in any order, reports `PARTIAL` with `missing` indices when
  one is absent, and `FAILED` with `checksum_ok: false` when a chunk is corrupt. Duplicate
  indices never complete a set.
