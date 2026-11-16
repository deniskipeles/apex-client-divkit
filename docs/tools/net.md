# Architectural Model

```
┌────────────────────────────────────────────────────────────────────────┐
│                        SDUI DOCUMENT / APP                             │
│   (Sync Pull/Push, Receipt Upload, Peer Sharing, LAN Transfer Hub)     │
│                                                                        │
│  Calls: tools://net/get     Payload: { url: "https://..." }            │
│  OR     tools://net/serve   Payload: { port: 8089, label: "Hub" }      │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│              NETWORK TRANSFER RUNTIME (Ktor, CIO engine)               │
│                                                                        │
│  OUTBOUND CLIENT                     EMBEDDED LAN HUB (server)         │
│  • request / get / post / put        • serve  → start CIO server       │
│  • patch / delete / head             • GET /            discovery page │
│  • download                          • GET /health      liveness JSON  │
│  • upload / send                     • GET /list        shared items   │
│                                      • GET /file/{id}   download item  │
│                                      • POST /upload     receive item  │
│  Shared HttpClient + HttpTimeout     • stop / status / share / list /  │
│                                        unshare (in-memory catalogue)   │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼ Generic JSON Output
┌────────────────────────────────────────────────────────────────────────┐
│  { "status": "SUCCESS", "http_status": 200, "bytes": 1042, ... }       │
└────────────────────────────────────────────────────────────────────────┘
```

# Network Transfer (Ktor) & LAN Hub (`tools://net` / `network.transfer@1`)

The **Network Transfer Tool** is an unopinionated networking primitive built on Ktor. It
covers both halves of any peer-to-peer / hub / file-sharing feature (Xender-style
transfers): an outbound HTTP client and an embedded LAN transfer hub server.

It never assumes a content shape: request bodies and responses are exchanged as UTF-8 text
or base64, and the tool reports status codes, headers, and byte counts.

---

## 1. Parameters & Options

Invoked via:
- `tools://net/request`, `tools://net/get`, `tools://net/post`
- `tools://net/download`, `tools://net/upload`
- `tools://net/serve`, `tools://net/stop`, `tools://net/status`
- `tools://net/share`, `tools://net/list`, `tools://net/unshare`
- `div-action://terminal.tool.invoke?tool=network.transfer@1`

### Options (`payload.options`)

| Option | Type | Default | Description |
| :--- | :--- | :--- | :--- |
| `url` | `string` | — | Target URL for client operations (also accepts `endpoint`/`target`). |
| `method` | `string` | operation-derived | HTTP method for `request`: `GET`, `POST`, `PUT`, `PATCH`, `DELETE`, `HEAD`. |
| `header_*` | `string` | — | Any option prefixed `header_` is appended as a request header (`header_Authorization` → `Authorization`). |
| `content_type` | `string` | `application/octet-stream` | Request `Content-Type`. |
| `timeout_ms` | `integer` | `30000` | Request timeout (uploads/downloads default to `60000`). |
| `port` | `integer` | `8089` | Hub listen port (`0` = ephemeral). |
| `host` | `string` | `0.0.0.0` | Hub bind address. |
| `label` / `name` | `string` | `"ApexClient Transfer Hub"` | Hub display name (also used as the shared item name). |
| `id` | `string` | — | Shared item id (`unshare`). |

### Body options (`payload`)

| Field | Description |
| :--- | :--- |
| `content_base64` / `payload_b64` | Base64 request body (binary-safe). |
| `body` / `payload` / `content` | UTF-8 text request body. |
| `file_path` | Local file to read and send as the body. |

---

## 2. Tool Output Schema

### Client operations (`request`/`get`/`post`/`download`/`upload`)

| Field | Type | Description |
| :--- | :--- | :--- |
| `status` | `string` | `"SUCCESS"`. |
| `http_status` | `integer` | HTTP response status code. |
| `method` | `string` | HTTP method used. |
| `url` | `string` | Target URL. |
| `content_type` | `string` | Response `Content-Type`. |
| `bytes` / `uploaded_bytes` | `integer` | Payload size. |
| `body` | `string` | Response body when text-like (JSON/XML/text). |
| `body_b64` / `content_b64` | `string` | Base64 response body for binary content. |
| `headers` | `object` | Response headers. |
| `file_name` | `string` | Derived file name (`download`). |
| `timestamp` | `integer` | Unix epoch millisecond timestamp. |

### Hub operations (`serve`/`status`/`share`/`list`/`unshare`)

| Field | Type | Description |
| :--- | :--- | :--- |
| `status` | `string` | `SERVING`, `IDLE`, `STOPPED`, `SHARED`, `REMOVED`, `NOT_FOUND`. |
| `hub_id` | `string` | Stable hub identifier. |
| `port` | `integer` | Bound port. |
| `share_count` / `count` | `integer` | Number of shared items. |
| `items` | `array` | `{ id, name, mime, bytes }` entries (`list`). |
| `path` | `string` | Download path `/file/{id}` (`share`). |
| `urls` | `array` | Suggested hub URLs (`serve`). |

Default output mapping: `status → user.net_status`, `http_status → user.net_http_status`,
`bytes → user.net_bytes`, `port → user.net_hub_port`.

---

## 3. DivKit Usage Examples

### Fetch JSON from an Edge Endpoint
```json
{
  "type": "button",
  "text": "Sync Orders",
  "action": {
    "url": "tools://net/get",
    "payload": {
      "options": {
        "url": "https://edge.sdui-pos.internal/acme_pos/orders",
        "header_Authorization": "Bearer @{edge_token}"
      },
      "output": { "http_status": "net_http_status", "body": "orders_json" }
    }
  }
}
```

### Start a LAN Transfer Hub
```json
{
  "type": "button",
  "text": "Start Transfer Hub",
  "action": {
    "url": "tools://net/serve",
    "payload": {
      "options": { "port": "8089", "label": "Acme POS Hub" },
      "output": { "port": "hub_port", "status": "hub_status" }
    }
  }
}
```

### Share a File, then List and Unshare
```json
[
  {
    "type": "button",
    "text": "Share Menu",
    "action": {
      "url": "tools://net/share",
      "payload": {
        "name": "menu.json",
        "content": "@{menu_json}",
        "output": { "id": "shared_id" }
      }
    }
  },
  {
    "type": "button",
    "text": "Unshare",
    "action": {
      "url": "tools://net/unshare",
      "payload": { "options": { "id": "@{shared_id}" } }
    }
  }
]
```

> The hub exposes `GET /` (HTML discovery page), `GET /health`, `GET /list`,
> `GET /file/{id}`, and `POST /upload`. Nearby devices on the same network can pull
> shared items without any additional client.
