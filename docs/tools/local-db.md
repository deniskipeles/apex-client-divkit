# Local Database Bridge (`local-db://op/<name>`)

The **Local Database Bridge** is the SDUI write/read path to each tenant's isolated SQLite
workspace. DivKit actions target `local-db://op/<operation>` and the [`EngineRouter`](../../app/src/main/java/engine/router/EngineRouter.kt)
executes the operation through the [`EngineRegistry`](../../app/src/main/java/engine/db/EngineRegistry.kt),
then publishes the result into reactive `user.*` DivKit variables through `EngineStateBridge`.

This is a **URL scheme**, not a `tools://` tool: it is the first-class data plane that the
DivKit document uses for its own persistence, while `tools://` hosts device capabilities.
Both are dispatched by the same router.

---

## 1. URI Grammar

```
local-db://op/<operation>
```

| Part | Description |
| :--- | :--- |
| `local-db://` | Scheme handled by `EngineRouter` (never leaves the device). |
| `op/` | Fixed path prefix. |
| `<operation>` | A whitelisted registry operation (`db.insert`, `db.query`, `db.count`, …). |

The router strips `local-db://op/` and passes the remainder as the operation name. Unknown
operations throw `SecurityException("Unknown operation: …")`, surfaced as an action error.

---

## 2. Operations

Operations are resolved by the registry's `when (opName)` whitelist. Several operations have
`records.*` aliases.

| Operation | Alias | Result type | Required payload keys |
| :--- | :--- | :--- | :--- |
| `db.import_schema` | `schema.import` | `SchemaImported` | `schema` (object) or the payload itself is the schema |
| `db.list_collections` | `schema.collections` | `Rows` | — |
| `db.insert` | `records.create` | `RowId` | `collection` \| `table`, `data` \| `values` |
| `db.query` | `records.query` | `Rows` | `from` \| `collection` \| `table` |
| `db.query_one` | `records.get` | `Row` | `collection` \| `table` |
| `db.count` | `records.count` | `Count` | `collection` \| `table` |
| `db.delete` | `records.delete` | `Count` | `collection` \| `table`, `id` \| `record_id` |

### Operation details

- **`db.import_schema`** — imports a tenant schema JSON (collections + fields) and returns the
  number of collections created.
- **`db.list_collections`** — returns one row per collection: `{ id, name, index }`.
- **`db.insert`** — inserts a record from `data`/`values`. Accepts `auth_role` (default
  `public`) and `auth_uid` (default `0`) for claim-based writes. Set `enqueue_sync: true` to
  also append the mutation to the `pending_sync` outbox (with an optional `client_uuid`).
  Returns the new row id.
- **`db.query`** — runs an ApexQuery AST against a collection. Supports `select` (simple field
  names or `{ field, expr, fn, as }` objects), `where`/`filter`, `sort`/`order_by`, `limit`
  (default `100`), and `offset`.
- **`db.query_one`** — returns the first matching record (with its `id`) or an empty row.
- **`db.count`** — returns the number of records matching the `where` filter.
- **`db.delete`** — deletes one record by numeric `id`; returns `1` when a row was removed.

### Filters

`where` is an array of `{ field | column, op, value }` objects (`op` defaults to `=`).
Example: `{"field": "status", "op": "=", "value": "OPEN"}`.

### Limits

- Payloads larger than **128 KB** are rejected with `SecurityException`.
- Each operation runs under a **5 s** timeout (`OP_TIMEOUT_MS`).
- Every call is logged to the `op_log` table with duration, rowcount, and error.

---

## 3. Output Mapping

Result values are written to `user.*` variables named by the action's `payload.output`
object. For `db.query`, a special `json_list_var` key publishes the whole result set as a JSON
string for a DivKit `json_list` block.

| Result type | Mapping |
| :--- | :--- |
| `Rows` | `output[column] → user.*` (array of column values); `"*"`/`"_json"` → JSON string of all rows; `json_list_var` → JSON string |
| `Row` | `output[column] → user.*` (scalar) |
| `RowId` | `output.id → user.*` (integer) |
| `Count` | `output.count → user.*` (integer) |
| `Scalar` | `output.value → user.*` |
| `SchemaImported` | `output.collections → user.*` (integer) |

Output variable names **must** match `^user\.[a-zA-Z][a-zA-Z0-9_.]{0,62}$` or the call fails.

Errors are written to `user.engine_error`, and to `payload.error_variable` when supplied.

---

## 4. DivKit Usage Examples

### Insert an order and refresh the ledger
```json
{
  "type": "button",
  "text": "Add Order",
  "actions": [
    {
      "url": "local-db://op/db.insert",
      "payload": {
        "table": "orders",
        "data": { "item_name": "Wagyu Smash Burger", "sku": "ACM-101", "price": 16.50, "qty": 1 },
        "enqueue_sync": true,
        "output": { "id": "user.last_order_id" }
      }
    },
    { "url": "local-db://op/db.query", "payload": { "from": "orders", "json_list_var": "user.orders_json" } },
    { "url": "local-db://op/db.count", "payload": { "table": "orders", "output": { "count": "user.order_count" } } }
  ]
}
```

### Filtered query with projection and sort
```json
{
  "url": "local-db://op/db.query",
  "payload": {
    "from": "orders",
    "select": ["item_name", "sku", "price"],
    "where": [{ "field": "status", "op": "=", "value": "OPEN" }],
    "sort": "price DESC",
    "limit": 25,
    "output": { "item_name": "user.open_items" }
  }
}
```

### Delete a row by id
```json
{
  "url": "local-db://op/db.delete",
  "payload": {
    "table": "orders",
    "id": "@{item.id}",
    "output": { "count": "user.last_deleted_count" }
  }
}
```

---

## 5. Related

- The router also handles `hardware://<capability>` (capability-gated host actions),
  `tools://<toolId>/<operation>` (native tools), and `workspace://clear_all` (workspace reset).
- Tenant schemas and seed data are provisioned in
  [`WorkspaceProvisioner`](../../app/src/main/java/com/example/enginehost/WorkspaceProvisioner.kt).
- The `local-db://` examples above are exercised live by the built-in demo tenant's catalog
  grid and order ledger.
