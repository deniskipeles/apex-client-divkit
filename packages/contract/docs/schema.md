# Workspace Database Schema (v4)

Per-workspace SQLite schema. Copied verbatim from `WorkspaceDatabase.Schema.create()` in
`apps/android/src/main/java/engine/db/WorkspaceDatabase.kt` (mirrored by
`apps/ios/Sources/Engine/DB/WorkspaceDatabase.swift`). Schema version: `4`.

```sql
CREATE TABLE IF NOT EXISTS collections (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL UNIQUE,
    schema TEXT,
    index_key TEXT UNIQUE
);

CREATE TABLE IF NOT EXISTS records (
    id TEXT PRIMARY KEY,
    collection_id INTEGER NOT NULL,
    data TEXT NOT NULL,
    created TEXT NOT NULL,
    updated TEXT NOT NULL,
    FOREIGN KEY (collection_id) REFERENCES collections(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_records_collection ON records(collection_id, id DESC);

-- Registry of schema-generated expression indexes.
CREATE TABLE IF NOT EXISTS _applied_indexes (
    index_name    TEXT PRIMARY KEY,
    collection_id INTEGER NOT NULL,
    kind          TEXT NOT NULL,
    fields        TEXT NOT NULL
);

-- Fallback TEXT id generator (AUTOINCREMENT needs an INTEGER key).
CREATE TABLE IF NOT EXISTS _record_id_sequence (
    id INTEGER PRIMARY KEY AUTOINCREMENT
);

-- Inter-record linkages. Endpoints are TEXT record ids with FK cascades.
CREATE TABLE IF NOT EXISTS _relations (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    origin_rec_id TEXT NOT NULL,
    target_rec_id TEXT NOT NULL,
    properties    TEXT,
    FOREIGN KEY (origin_rec_id) REFERENCES records(id) ON DELETE CASCADE,
    FOREIGN KEY (target_rec_id) REFERENCES records(id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_relations_unique ON _relations(origin_rec_id, target_rec_id);
CREATE INDEX IF NOT EXISTS idx_relations_origin ON _relations(origin_rec_id);
CREATE INDEX IF NOT EXISTS idx_relations_target ON _relations(target_rec_id);

CREATE TABLE IF NOT EXISTS _system_logs (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    level TEXT NOT NULL,
    target TEXT NOT NULL,
    message TEXT NOT NULL,
    timestamp TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS op_log (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    ts INTEGER NOT NULL,
    op TEXT NOT NULL,
    duration_ms INTEGER NOT NULL,
    rowcount INTEGER,
    error TEXT
);

CREATE TABLE IF NOT EXISTS pending_sync (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    client_uuid TEXT UNIQUE NOT NULL,
    op TEXT NOT NULL,
    payload TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    last_error TEXT
);

CREATE TABLE IF NOT EXISTS sdui_schema_versions (
    version_id INTEGER PRIMARY KEY AUTOINCREMENT,
    schema_version TEXT NOT NULL,
    jti TEXT NOT NULL,
    signed_envelope TEXT NOT NULL,
    verified_payload TEXT NOT NULL,
    received_at INTEGER NOT NULL,
    is_active INTEGER NOT NULL DEFAULT 1,
    summary TEXT NOT NULL
);
```

## Unique constraints and query indexes

Uniqueness and JSON query acceleration are enforced by SQLite expression indexes over
`records.data`, created from the collection schema rather than by a shadow table. For every
collection the engine derives:

- `idx_u_<collectionId>_<field>` — `UNIQUE` index for each field with `"unique": true`.
- `idx_c_<collectionId>_<n>` — `UNIQUE` index for each `composite_unique` field list.
- `idx_n_<collectionId>_<field>` — non-unique index for each field with `"sql_indexed": true`.

Each index is `(collection_id, json_extract(data, '$.<field>'))`, so a duplicate insert makes
SQLite raise a constraint error and the planner can satisfy `collection_id = ? AND
json_extract(data, '$.<field>') = ?` lookups from the index. `_applied_indexes` records the
indexes currently in force; `ensureIndexes` reconciles it with the schema on import, creating
missing indexes, dropping removed ones and recreating changed ones. Before creating a unique
index it scans for existing duplicates and fails with a clear validation error if any are
found.

## Record identity

`records.id` is `TEXT`, so a caller may supply its own identifier (e.g. `"customer_abc"`)
and numeric identifiers share the same representation (`"42"`). When no `id` is supplied the
engine draws the next value from `_record_id_sequence` and stores it as text.

## Relations

`_relations` links two records by their text ids. A row is materialised only when the
relation needs it:

- `cascade_on_target_delete` — deleting the target deletes the origin.
- `relation_type = many` — many-to-many edges.
- `has_properties` — the optional `<relation>_properties` object is stored in `properties`.
- `sql_indexed` — the relation is indexed for reverse lookups.

A plain single-reference relation is not materialised; its value lives in `records.data`
only. Foreign keys cascade on delete, so removing either endpoint removes the relation rows
that reference it.

## Migration (v3 → v4)

`WorkspaceDatabase.Schema.migrate` converts an existing v3 database in place:

1. `records.id` is rebuilt from `INTEGER` to `TEXT`, preserving row values via `CAST(id AS TEXT)`.
2. `_relations` is rebuilt to the four-column shape, collapsing legacy rows per
   `(origin_rec_id, target_rec_id)` and dropping `origin_col_id`, `target_col_id` and `rel_name`.
3. `_unique_values` is dropped; uniqueness is provided by the expression indexes.
4. `_record_id_sequence` is seeded above the highest numeric-looking record id.
