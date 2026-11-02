package engine.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema

data class OpLogEntry(
    val id: Long,
    val ts: Long,
    val op: String,
    val durationMs: Long,
    val rowcount: Long?,
    val error: String?
)

data class PendingSyncEntry(
    val id: Long,
    val clientUuid: String,
    val op: String,
    val payload: String,
    val createdAt: Long,
    val attempts: Long,
    val lastError: String?
)

data class CachedSchemaVersion(
    val versionId: Long,
    val schemaVersion: String,
    val jti: String,
    val signedEnvelope: String,
    val verifiedPayload: String,
    val receivedAt: Long,
    val isActive: Boolean,
    val summary: String
) {
    val changeSummary: String get() = summary
    val envelopeJti: String get() = jti
    val divkitJson: String get() = verifiedPayload
}

/**
 * Generated-equivalent SQLDelight wrapper for `WorkspaceDatabase.sq`.
 * Physical database per tenant (`workspace_<id>.db`).
 */
class WorkspaceDatabase(val driver: SqlDriver) {

    val workspaceQueries = WorkspaceQueries(driver)

    object Schema : SqlSchema {
        override val version: Long = 2L

        override fun create(driver: SqlDriver) {
            driver.executeUpdate(
                null,
                """
                CREATE TABLE IF NOT EXISTS op_log (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    ts INTEGER NOT NULL,
                    op TEXT NOT NULL,
                    duration_ms INTEGER NOT NULL,
                    rowcount INTEGER,
                    error TEXT
                )
                """.trimIndent(),
                0
            )
            driver.executeUpdate(
                null,
                "CREATE INDEX IF NOT EXISTS idx_op_log_ts ON op_log(ts)",
                0
            )
            driver.executeUpdate(
                null,
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
                """.trimIndent(),
                0
            )
            driver.executeUpdate(
                null,
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
                """.trimIndent(),
                0
            )
            driver.executeUpdate(
                null,
                "CREATE INDEX IF NOT EXISTS idx_sdui_schema_active ON sdui_schema_versions(is_active, version_id DESC)",
                0
            )
        }

        override fun migrate(driver: SqlDriver, oldVersion: Long, newVersion: Long) {
            create(driver)
        }
    }
}

class WorkspaceQueries(private val driver: SqlDriver) {

    fun transaction(block: () -> Unit) {
        block()
    }

    fun ensureSchemaCacheTable() {
        driver.executeUpdate(
            null,
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
            """.trimIndent(),
            0
        )
    }

    fun insertOpLog(ts: Long, op: String, duration_ms: Long, rowcount: Long?, error: String?) {
        driver.executeUpdate(
            null,
            "INSERT INTO op_log (ts, op, duration_ms, rowcount, error) VALUES (?, ?, ?, ?, ?)",
            5
        ) {
            bindLong(0, ts)
            bindString(1, op)
            bindLong(2, duration_ms)
            if (rowcount != null) bindLong(3, rowcount) else bindNull(3)
            if (error != null) bindString(4, error) else bindNull(4)
        }
    }

    fun insertPendingSync(client_uuid: String, op: String, payload: String, created_at: Long) {
        driver.executeUpdate(
            null,
            "INSERT OR IGNORE INTO pending_sync (client_uuid, op, payload, created_at) VALUES (?, ?, ?, ?)",
            4
        ) {
            bindString(0, client_uuid)
            bindString(1, op)
            bindString(2, payload)
            bindLong(3, created_at)
        }
    }

    fun deletePendingSync(client_uuid: String) {
        driver.executeUpdate(
            null,
            "DELETE FROM pending_sync WHERE client_uuid = ?",
            1
        ) {
            bindString(0, client_uuid)
        }
    }

    fun incrementPendingAttempts(client_uuid: String) {
        driver.executeUpdate(
            null,
            "UPDATE pending_sync SET attempts = attempts + 1 WHERE client_uuid = ?",
            1
        ) {
            bindString(0, client_uuid)
        }
    }

    fun markPendingDead(last_error: String?, client_uuid: String) {
        driver.executeUpdate(
            null,
            "UPDATE pending_sync SET attempts = attempts + 1, last_error = ? WHERE client_uuid = ?",
            2
        ) {
            if (last_error != null) bindString(0, last_error) else bindNull(0)
            bindString(1, client_uuid)
        }
    }

    fun cacheAndActivateSchemaVersion(
        schemaVersion: String,
        jti: String,
        signedEnvelope: String,
        verifiedPayload: String,
        receivedAt: Long,
        summary: String
    ): Long {
        ensureSchemaCacheTable()
        driver.executeUpdate(null, "UPDATE sdui_schema_versions SET is_active = 0", 0)
        driver.executeUpdate(
            null,
            """
            INSERT INTO sdui_schema_versions
            (schema_version, jti, signed_envelope, verified_payload, received_at, is_active, summary)
            VALUES (?, ?, ?, ?, ?, 1, ?)
            """.trimIndent(),
            6
        ) {
            bindString(0, schemaVersion)
            bindString(1, jti)
            bindString(2, signedEnvelope)
            bindString(3, verifiedPayload)
            bindLong(4, receivedAt)
            bindString(5, summary)
        }
        return driver.lastInsertedRowId()
    }

    fun activateSchemaVersionById(versionId: Long) {
        ensureSchemaCacheTable()
        driver.executeUpdate(null, "UPDATE sdui_schema_versions SET is_active = 0", 0)
        driver.executeUpdate(
            null,
            "UPDATE sdui_schema_versions SET is_active = 1 WHERE version_id = ?",
            1
        ) {
            bindLong(0, versionId)
        }
    }

    fun selectActiveSchemaVersion(): CachedSchemaVersion? {
        ensureSchemaCacheTable()
        var result: CachedSchemaVersion? = null
        driver.executeQuery(
            null,
            """
            SELECT version_id, schema_version, jti, signed_envelope, verified_payload, received_at, is_active, summary
            FROM sdui_schema_versions
            WHERE is_active = 1
            ORDER BY version_id DESC LIMIT 1
            """.trimIndent(),
            0
        ).use { c ->
            if (c.next().value) {
                result = CachedSchemaVersion(
                    versionId = c.getLong(0) ?: 0L,
                    schemaVersion = c.getString(1) ?: "1.0",
                    jti = c.getString(2) ?: "",
                    signedEnvelope = c.getString(3) ?: "",
                    verifiedPayload = c.getString(4) ?: "",
                    receivedAt = c.getLong(5) ?: 0L,
                    isActive = (c.getLong(6) ?: 0L) == 1L,
                    summary = c.getString(7) ?: ""
                )
            }
        }
        return result
    }

    fun selectAllSchemaVersions(): List<CachedSchemaVersion> {
        ensureSchemaCacheTable()
        val list = mutableListOf<CachedSchemaVersion>()
        driver.executeQuery(
            null,
            """
            SELECT version_id, schema_version, jti, signed_envelope, verified_payload, received_at, is_active, summary
            FROM sdui_schema_versions
            ORDER BY version_id DESC
            """.trimIndent(),
            0
        ).use { c ->
            while (c.next().value) {
                list.add(
                    CachedSchemaVersion(
                        versionId = c.getLong(0) ?: 0L,
                        schemaVersion = c.getString(1) ?: "1.0",
                        jti = c.getString(2) ?: "",
                        signedEnvelope = c.getString(3) ?: "",
                        verifiedPayload = c.getString(4) ?: "",
                        receivedAt = c.getLong(5) ?: 0L,
                        isActive = (c.getLong(6) ?: 0L) == 1L,
                        summary = c.getString(7) ?: ""
                    )
                )
            }
        }
        return list
    }

    fun selectRecentOpLogs(limit: Int = 50): List<OpLogEntry> {
        val list = mutableListOf<OpLogEntry>()
        driver.executeQuery(
            null,
            "SELECT id, ts, op, duration_ms, rowcount, error FROM op_log ORDER BY id DESC LIMIT $limit",
            0
        ).use { c ->
            while (c.next().value) {
                list.add(
                    OpLogEntry(
                        id = c.getLong(0) ?: 0L,
                        ts = c.getLong(1) ?: 0L,
                        op = c.getString(2) ?: "",
                        durationMs = c.getLong(3) ?: 0L,
                        rowcount = try { c.getLong(4) } catch (_: Exception) { null },
                        error = c.getString(5)
                    )
                )
            }
        }
        return list
    }

    fun selectAllPendingSync(): List<PendingSyncEntry> {
        val list = mutableListOf<PendingSyncEntry>()
        driver.executeQuery(
            null,
            "SELECT id, client_uuid, op, payload, created_at, attempts, last_error FROM pending_sync ORDER BY id DESC LIMIT 100",
            0
        ).use { c ->
            while (c.next().value) {
                list.add(
                    PendingSyncEntry(
                        id = c.getLong(0) ?: 0L,
                        clientUuid = c.getString(1) ?: "",
                        op = c.getString(2) ?: "",
                        payload = c.getString(3) ?: "{}",
                        createdAt = c.getLong(4) ?: 0L,
                        attempts = c.getLong(5) ?: 0L,
                        lastError = c.getString(6)
                    )
                )
            }
        }
        return list
    }
}
