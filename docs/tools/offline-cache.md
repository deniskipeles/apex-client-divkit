# Architectural Model

```
┌────────────────────────────────────────────────────────────────────────┐
│                        SDUI DOCUMENT / APP                             │
│      (Offline POS, Flaky Networks, Airplane Mode Resilience)           │
│                                                                        │
│  Calls: tools://offline-cache/get_stats                                │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│               OFFLINE PAGE & SCHEMA CACHE RUNTIME                      │
│                                                                        │
│  1. Cache SDUI pages and schema versions per tenant                    │
│  2. Report cache statistics (page count, size)                          │
│  3. Clear cached entries on demand                                      │
│  4. Scope keyed by workspace (tenant) id                               │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼ Generic JSON Output
┌────────────────────────────────────────────────────────────────────────┐
│  {                                                                     │
│    "op": "get_stats",                                                  │
│    "schema_id": "acme_pos",                                            │
│    "cached_pages": 4,                                                  │
│    "cache_size_kb": 148,                                               │
│    "status": "CACHED_OFFLINE_READY",                                   │
│    "offline_resilience": "ACTIVE"                                      │
│  }                                                                     │
└────────────────────────────────────────────────────────────────────────┘
```

# Offline Page & Schema Cache (`tools://offline-cache` / `storage.offline_cache@1`)

The **Offline Cache Tool** caches SDUI pages and schemas for offline resilience. It is
tenant-scoped (keyed by the workspace id) and domain-agnostic: it stores and reports on
cached artifacts without interpreting them.

---

## 1. Parameters & Options

Invoked via:
- `tools://offline-cache/cache`
- `tools://offline-cache/get_stats`
- `tools://offline-cache/clear`
- `div-action://terminal.tool.invoke?tool=storage.offline_cache@1`

### Options (`payload.options`)

| Option | Type | Default | Description |
| :--- | :--- | :--- | :--- |
| `op` | `string` | `"get_stats"` | Operation: `cache`, `get_stats`, or `clear`. |
| `schema_id` | `string` | workspace id | Cache namespace; defaults to the calling tenant. |

---

## 2. Tool Output Schema

| Field | Type | Description |
| :--- | :--- | :--- |
| `op` | `string` | Operation performed. |
| `schema_id` | `string` | Cache namespace used. |
| `cached_pages` | `integer` | Number of cached pages. |
| `cache_size_kb` | `integer` | Approximate cache size in kilobytes. |
| `status` | `string` | `"CACHED_OFFLINE_READY"`. |
| `offline_resilience` | `string` | `"ACTIVE"`. |
| `state` | `string` | `"ok"`. |

Default output mapping: `status → user.offline_cache_status`,
`cached_pages → user.offline_cached_pages`.

---

## 3. DivKit Usage Examples

### Show Offline Cache Readiness
```json
{
  "type": "button",
  "text": "Cache Status",
  "action": {
    "url": "tools://offline-cache/get_stats",
    "payload": {
      "output": { "cached_pages": "cached_pages", "status": "offline_status" }
    }
  }
}
```
