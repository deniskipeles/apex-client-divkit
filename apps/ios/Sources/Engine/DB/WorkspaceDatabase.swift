import Foundation

/// Telemetry row for the engine op log. Mirrors `engine.db.OpLogEntry`.
public struct OpLogEntry: Sendable {
    public let id: Int64
    public let ts: Int64
    public let op: String
    public let durationMs: Int64
    public let rowcount: Int64?
    public let error: String?
}

/// A queued outbox row awaiting sync. Mirrors `engine.db.PendingSyncEntry`.
public struct PendingSyncEntry: Sendable {
    public let id: Int64
    public let clientUuid: String
    public let op: String
    public let payload: String
    public let createdAt: Int64
    public let attempts: Int64
    public let lastError: String?
}

/// A cached, signed SDUI schema version. Mirrors `engine.db.CachedSchemaVersion`.
public struct CachedSchemaVersion: Sendable {
    public let versionId: Int64
    public let schemaVersion: String
    public let jti: String
    public let signedEnvelope: String
    public let verifiedPayload: String
    public let receivedAt: Int64
    public let isActive: Bool
    public let summary: String

    public var changeSummary: String { summary }
    public var envelopeJti: String { jti }
    public var divkitJson: String { verifiedPayload }
}

/// Workspace database handle plus its generic queries. Mirrors
/// `engine.db.WorkspaceDatabase`.
public final class WorkspaceDatabase {
    public let driver: SqlDriver
    public let queries: GenericDatabaseQueries

    public init(driver: SqlDriver) {
        self.driver = driver
        self.queries = GenericDatabaseQueries(driver: driver)
    }

    /// Alias used by the sync engine and UI telemetry.
    public var workspaceQueries: GenericDatabaseQueries { queries }

    /// Workspace DDL, mirroring `WorkspaceDatabase.Schema` (version 4).
    public struct Schema: SqlSchema {
        public static let versionValue: Int64 = 4

        public init() {}

        public var version: Int64 { Schema.versionValue }

        public func create(_ driver: SqlDriver) {
            let statements = [
                """
                CREATE TABLE IF NOT EXISTS collections (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL UNIQUE,
                    schema TEXT,
                    index_key TEXT UNIQUE
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS records (
                    id TEXT PRIMARY KEY,
                    collection_id INTEGER NOT NULL,
                    data TEXT NOT NULL,
                    created TEXT NOT NULL,
                    updated TEXT NOT NULL,
                    FOREIGN KEY (collection_id) REFERENCES collections(id) ON DELETE CASCADE
                )
                """,
                "CREATE INDEX IF NOT EXISTS idx_records_collection ON records(collection_id, id DESC)",
                """
                CREATE TABLE IF NOT EXISTS _applied_indexes (
                    index_name    TEXT PRIMARY KEY,
                    collection_id INTEGER NOT NULL,
                    kind          TEXT NOT NULL,
                    fields        TEXT NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS _record_id_sequence (
                    id INTEGER PRIMARY KEY AUTOINCREMENT
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS _relations (
                    id            INTEGER PRIMARY KEY AUTOINCREMENT,
                    origin_rec_id TEXT NOT NULL,
                    target_rec_id TEXT NOT NULL,
                    properties    TEXT,
                    FOREIGN KEY (origin_rec_id) REFERENCES records(id) ON DELETE CASCADE,
                    FOREIGN KEY (target_rec_id) REFERENCES records(id) ON DELETE CASCADE
                )
                """,
                "CREATE UNIQUE INDEX IF NOT EXISTS idx_relations_unique ON _relations(origin_rec_id, target_rec_id)",
                "CREATE INDEX IF NOT EXISTS idx_relations_origin ON _relations(origin_rec_id)",
                "CREATE INDEX IF NOT EXISTS idx_relations_target ON _relations(target_rec_id)",
                """
                CREATE TABLE IF NOT EXISTS _system_logs (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    level TEXT NOT NULL,
                    target TEXT NOT NULL,
                    message TEXT NOT NULL,
                    timestamp TEXT NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS op_log (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    ts INTEGER NOT NULL,
                    op TEXT NOT NULL,
                    duration_ms INTEGER NOT NULL,
                    rowcount INTEGER,
                    error TEXT
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS pending_sync (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    client_uuid TEXT UNIQUE NOT NULL,
                    op TEXT NOT NULL,
                    payload TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    attempts INTEGER NOT NULL DEFAULT 0,
                    last_error TEXT
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS sdui_schema_versions (
                    version_id INTEGER PRIMARY KEY AUTOINCREMENT,
                    schema_version TEXT NOT NULL,
                    jti TEXT NOT NULL,
                    signed_envelope TEXT NOT NULL,
                    verified_payload TEXT NOT NULL,
                    received_at INTEGER NOT NULL,
                    is_active INTEGER NOT NULL DEFAULT 1,
                    summary TEXT NOT NULL
                )
                """
            ]
            for sql in statements {
                driver.executeUpdate(identifier: nil, sql: sql, parameters: 0, binders: nil)
            }
        }

        public func migrate(_ driver: SqlDriver, oldVersion: Int64, newVersion: Int64) {
            if oldVersion >= version { return }

            // Create any missing tables first; existing tables are left untouched so the
            // rebuild steps below can convert their contents.
            create(driver)

            // 1. records.id: INTEGER -> TEXT (globally-unique text identity).
            if let idType = columnType(driver, table: "records", column: "id"), idType.uppercased().contains("INT") {
                driver.executeUpdate(identifier: nil, sql: "ALTER TABLE records RENAME TO _records_legacy", parameters: 0, binders: nil)
                driver.executeUpdate(identifier: nil, sql: """
                    CREATE TABLE records (
                        id TEXT PRIMARY KEY,
                        collection_id INTEGER NOT NULL,
                        data TEXT NOT NULL,
                        created TEXT NOT NULL,
                        updated TEXT NOT NULL,
                        FOREIGN KEY (collection_id) REFERENCES collections(id) ON DELETE CASCADE
                    )
                    """, parameters: 0, binders: nil)
                driver.executeUpdate(identifier: nil, sql: "INSERT INTO records (id, collection_id, data, created, updated) SELECT CAST(id AS TEXT), collection_id, data, created, updated FROM _records_legacy", parameters: 0, binders: nil)
                driver.executeUpdate(identifier: nil, sql: "DROP TABLE _records_legacy", parameters: 0, binders: nil)
                driver.executeUpdate(identifier: nil, sql: "CREATE INDEX IF NOT EXISTS idx_records_collection ON records(collection_id, id DESC)", parameters: 0, binders: nil)
            }

            // 2. _relations: drop origin_col_id/target_col_id/rel_name; TEXT endpoints + FK.
            if hasColumn(driver, table: "_relations", column: "rel_name") || hasColumn(driver, table: "_relations", column: "origin_col_id") {
                driver.executeUpdate(identifier: nil, sql: "ALTER TABLE _relations RENAME TO _relations_legacy", parameters: 0, binders: nil)
                driver.executeUpdate(identifier: nil, sql: """
                    CREATE TABLE _relations (
                        id            INTEGER PRIMARY KEY AUTOINCREMENT,
                        origin_rec_id TEXT NOT NULL,
                        target_rec_id TEXT NOT NULL,
                        properties    TEXT,
                        FOREIGN KEY (origin_rec_id) REFERENCES records(id) ON DELETE CASCADE,
                        FOREIGN KEY (target_rec_id) REFERENCES records(id) ON DELETE CASCADE
                    )
                    """, parameters: 0, binders: nil)
                driver.executeUpdate(identifier: nil, sql: """
                    INSERT INTO _relations (origin_rec_id, target_rec_id, properties)
                    SELECT CAST(origin_rec_id AS TEXT), CAST(target_rec_id AS TEXT), MAX(properties)
                    FROM _relations_legacy
                    GROUP BY CAST(origin_rec_id AS TEXT), CAST(target_rec_id AS TEXT)
                    """, parameters: 0, binders: nil)
                driver.executeUpdate(identifier: nil, sql: "DROP TABLE _relations_legacy", parameters: 0, binders: nil)
                driver.executeUpdate(identifier: nil, sql: "CREATE UNIQUE INDEX IF NOT EXISTS idx_relations_unique ON _relations(origin_rec_id, target_rec_id)", parameters: 0, binders: nil)
                driver.executeUpdate(identifier: nil, sql: "CREATE INDEX IF NOT EXISTS idx_relations_origin ON _relations(origin_rec_id)", parameters: 0, binders: nil)
                driver.executeUpdate(identifier: nil, sql: "CREATE INDEX IF NOT EXISTS idx_relations_target ON _relations(target_rec_id)", parameters: 0, binders: nil)
            }

            // 3. Remove the application-maintained uniqueness table.
            driver.executeUpdate(identifier: nil, sql: "DROP TABLE IF EXISTS _unique_values", parameters: 0, binders: nil)

            // 4. Seed the fallback id sequence above the highest numeric-looking record id.
            var sequencePopulated = false
            let seqCursor = driver.executeQuery(identifier: nil, sql: "SELECT COUNT(*) FROM _record_id_sequence", parameters: 0, binders: nil)
            if seqCursor.next() { sequencePopulated = (seqCursor.getLong(index: 0) ?? 0) > 0 }
            if !sequencePopulated {
                driver.executeUpdate(identifier: nil, sql: """
                    INSERT INTO _record_id_sequence (id)
                    SELECT COALESCE(MAX(CAST(id AS INTEGER)), 0)
                    FROM records
                    WHERE id GLOB '[0-9]*' AND id NOT GLOB '*[^0-9]*'
                    """, parameters: 0, binders: nil)
            }
        }

        private func columnType(_ driver: SqlDriver, table: String, column: String) -> String? {
            var type: String?
            let cursor = driver.executeQuery(identifier: nil, sql: "PRAGMA table_info(\(table))", parameters: 0, binders: nil)
            while cursor.next() {
                if (cursor.getString(index: 1) ?? "") == column {
                    type = cursor.getString(index: 2)
                    break
                }
            }
            return type
        }

        private func hasColumn(_ driver: SqlDriver, table: String, column: String) -> Bool {
            columnType(driver, table: table, column: column) != nil
        }
    }
}

/// Generic workspace queries. Mirrors `engine.db.GenericDatabaseQueries`.
public final class GenericDatabaseQueries {
    private let driver: SqlDriver

    public init(driver: SqlDriver) {
        self.driver = driver
    }

    public func insertOpLog(ts: Int64, op: String, durationMs: Int64, rowcount: Int64?, error: String?) {
        driver.executeUpdate(
            identifier: nil,
            sql: "INSERT INTO op_log (ts, op, duration_ms, rowcount, error) VALUES (?, ?, ?, ?, ?)",
            parameters: 5
        ) { st in
            st.bindLong(index: 0, long: ts)
            st.bindString(index: 1, string: op)
            st.bindLong(index: 2, long: durationMs)
            if let rowcount { st.bindLong(index: 3, long: rowcount) } else { st.bindNull(index: 3) }
            if let error { st.bindString(index: 4, string: error) } else { st.bindNull(index: 4) }
        }
    }

    public func selectRecentOpLogs(limit: Int = 50) -> [OpLogEntry] {
        var list: [OpLogEntry] = []
        let cursor = driver.executeQuery(
            identifier: nil,
            sql: "SELECT id, ts, op, duration_ms, rowcount, error FROM op_log ORDER BY id DESC LIMIT \(limit)",
            parameters: 0,
            binders: nil
        )
        while cursor.next() {
            list.append(OpLogEntry(
                id: cursor.getLong(index: 0) ?? 0,
                ts: cursor.getLong(index: 1) ?? 0,
                op: cursor.getString(index: 2) ?? "",
                durationMs: cursor.getLong(index: 3) ?? 0,
                rowcount: cursor.getLong(index: 4),
                error: cursor.getString(index: 5)
            ))
        }
        return list
    }

    public func selectAllPendingSync() -> [PendingSyncEntry] {
        var list: [PendingSyncEntry] = []
        let cursor = driver.executeQuery(
            identifier: nil,
            sql: "SELECT id, client_uuid, op, payload, created_at, attempts, last_error FROM pending_sync ORDER BY id DESC LIMIT 100",
            parameters: 0,
            binders: nil
        )
        while cursor.next() {
            list.append(PendingSyncEntry(
                id: cursor.getLong(index: 0) ?? 0,
                clientUuid: cursor.getString(index: 1) ?? "",
                op: cursor.getString(index: 2) ?? "",
                payload: cursor.getString(index: 3) ?? "{}",
                createdAt: cursor.getLong(index: 4) ?? 0,
                attempts: cursor.getLong(index: 5) ?? 0,
                lastError: cursor.getString(index: 6)
            ))
        }
        return list
    }

    @discardableResult
    public func cacheAndActivateSchemaVersion(
        schemaVersion: String,
        jti: String,
        signedEnvelope: String,
        verifiedPayload: String,
        receivedAt: Int64,
        summary: String
    ) -> Int64 {
        driver.executeUpdate(identifier: nil, sql: "UPDATE sdui_schema_versions SET is_active = 0", parameters: 0, binders: nil)
        driver.executeUpdate(
            identifier: nil,
            sql: """
            INSERT INTO sdui_schema_versions
            (schema_version, jti, signed_envelope, verified_payload, received_at, is_active, summary)
            VALUES (?, ?, ?, ?, ?, 1, ?)
            """,
            parameters: 6
        ) { st in
            st.bindString(index: 0, string: schemaVersion)
            st.bindString(index: 1, string: jti)
            st.bindString(index: 2, string: signedEnvelope)
            st.bindString(index: 3, string: verifiedPayload)
            st.bindLong(index: 4, long: receivedAt)
            st.bindString(index: 5, string: summary)
        }
        return driver.lastInsertedRowId()
    }

    public func selectActiveSchemaVersion() -> CachedSchemaVersion? {
        var result: CachedSchemaVersion?
        let cursor = driver.executeQuery(
            identifier: nil,
            sql: """
            SELECT version_id, schema_version, jti, signed_envelope, verified_payload, received_at, is_active, summary
            FROM sdui_schema_versions
            WHERE is_active = 1
            ORDER BY version_id DESC LIMIT 1
            """,
            parameters: 0,
            binders: nil
        )
        if cursor.next() { result = Self.row(cursor) }
        return result
    }

    public func selectAllSchemaVersions() -> [CachedSchemaVersion] {
        var list: [CachedSchemaVersion] = []
        let cursor = driver.executeQuery(
            identifier: nil,
            sql: """
            SELECT version_id, schema_version, jti, signed_envelope, verified_payload, received_at, is_active, summary
            FROM sdui_schema_versions
            ORDER BY version_id DESC
            """,
            parameters: 0,
            binders: nil
        )
        while cursor.next() { list.append(Self.row(cursor)) }
        return list
    }

    public func activateSchemaVersionById(_ versionId: Int64) {
        driver.executeUpdate(identifier: nil, sql: "UPDATE sdui_schema_versions SET is_active = 0", parameters: 0, binders: nil)
        driver.executeUpdate(
            identifier: nil,
            sql: "UPDATE sdui_schema_versions SET is_active = 1 WHERE version_id = ?",
            parameters: 1
        ) { st in st.bindLong(index: 0, long: versionId) }
    }

    public func deletePendingSync(clientUuid: String) {
        driver.executeUpdate(
            identifier: nil,
            sql: "DELETE FROM pending_sync WHERE client_uuid = ?",
            parameters: 1
        ) { st in st.bindString(index: 0, string: clientUuid) }
    }

    public func incrementPendingAttempts(clientUuid: String) {
        driver.executeUpdate(
            identifier: nil,
            sql: "UPDATE pending_sync SET attempts = attempts + 1 WHERE client_uuid = ?",
            parameters: 1
        ) { st in st.bindString(index: 0, string: clientUuid) }
    }

    public func markPendingDead(reason: String, clientUuid: String) {
        driver.executeUpdate(
            identifier: nil,
            sql: "UPDATE pending_sync SET attempts = attempts + 1, last_error = ? WHERE client_uuid = ?",
            parameters: 2
        ) { st in
            st.bindString(index: 0, string: reason)
            st.bindString(index: 1, string: clientUuid)
        }
    }

    private static func row(_ c: SqlCursor) -> CachedSchemaVersion {
        CachedSchemaVersion(
            versionId: c.getLong(index: 0) ?? 0,
            schemaVersion: c.getString(index: 1) ?? "1.0",
            jti: c.getString(index: 2) ?? "",
            signedEnvelope: c.getString(index: 3) ?? "",
            verifiedPayload: c.getString(index: 4) ?? "",
            receivedAt: c.getLong(index: 5) ?? 0,
            isActive: (c.getLong(index: 6) ?? 0) == 1,
            summary: c.getString(index: 7) ?? ""
        )
    }
}
