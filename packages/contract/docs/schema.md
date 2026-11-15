# Workspace Database Schema (v1)

Per-workspace SQLite schema. Copied verbatim from `WorkspaceDatabase.Schema.create()` in
`apps/android/src/main/java/engine/db/WorkspaceDatabase.kt`. Schema version: `3`.

```sql
CREATE TABLE IF NOT EXISTS collections (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL UNIQUE,
    schema TEXT,
    index_key TEXT UNIQUE
);

CREATE TABLE IF NOT EXISTS records (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    collection_id INTEGER NOT NULL,
    data TEXT NOT NULL,
    created TEXT NOT NULL,
    updated TEXT NOT NULL,
    FOREIGN KEY (collection_id) REFERENCES collections(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_records_collection ON records(collection_id, id DESC);

CREATE TABLE IF NOT EXISTS _unique_values (
    index_key TEXT NOT NULL,
    value TEXT NOT NULL,
    record_id INTEGER NOT NULL,
    PRIMARY KEY (index_key, value)
);

CREATE INDEX IF NOT EXISTS idx_unique_lookup ON _unique_values(index_key, value);
CREATE INDEX IF NOT EXISTS idx_unique_record ON _unique_values(record_id);

CREATE TABLE IF NOT EXISTS _relations (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    origin_col_id INTEGER NOT NULL,
    origin_rec_id INTEGER NOT NULL,
    target_col_id INTEGER NOT NULL,
    target_rec_id INTEGER NOT NULL,
    rel_name TEXT NOT NULL,
    properties TEXT
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_relations_unique
    ON _relations(origin_col_id, origin_rec_id, target_col_id, target_rec_id, rel_name);

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
