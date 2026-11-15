# Sync Batch (v1)

Offline-first sync between the terminal and the edge gateway. Pending local operations are
batched and pushed; the server contract is idempotent by `client_uuid`.

Source of truth: `apps/android/src/main/java/engine/sync/SyncEngine.kt`.

## Request

```
POST {endpoint}/sync
Content-Type: application/json; charset=utf-8
```

```json
{
  "batch": [
    { "client_uuid": "uuid", "op": "db.insert", "payload": { } }
  ]
}
```

## Response

```json
{
  "accepted": ["uuid"],
  "rejected": [ { "uuid": "uuid", "reason": "..." } ]
}
```

- `accepted` - `client_uuid`s the server persisted; the corresponding `pending_sync` rows
  are deleted locally.
- `rejected` - rows the server refused; each carries a `reason`.

## Retry rules

A rejected row's `reason` is classified by `isRetryable(reason)`:

| Reason | Action |
| :--- | :--- |
| starts with `5` (5xx) | retry (increment `attempts`) |
| `timeout` | retry |
| `rate_limited` | retry |
| anything else (4xx, validation) | dead-letter (`markPendingDead`) |

A transport error (network failure, non-2xx HTTP status) makes the whole batch retryable.
Retried rows use exponential backoff via WorkManager. A row is dead-lettered once
`attempts` reaches `MAX_ATTEMPTS`.

## Constants

- `BATCH_SIZE = 50` - maximum rows read and pushed per call.
- `MAX_ATTEMPTS = 10` - a row with `attempts >= MAX_ATTEMPTS` is no longer selected for sync.
