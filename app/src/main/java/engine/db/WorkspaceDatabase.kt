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

/**
 * Generated-equivalent SQLDelight wrapper for `WorkspaceDatabase.sq`.
 * Physical database per tenant (`workspace_<id>.db`).
 */
class WorkspaceDatabase(val driver: SqlDriver) {

    val workspaceQueries = WorkspaceQueries(driver)

    object Schema : SqlSchema {
        override val version: Long = 1L

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
