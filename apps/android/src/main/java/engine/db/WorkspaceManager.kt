package engine.db

import engine.sqlite.SqlDriver
import java.io.File

class WorkspaceManager(
    private val driverFactory: WorkspaceDriverFactory
) {
    private val idRegex = Regex("^[a-zA-Z0-9_-]{1,64}$")
    private val openDrivers = mutableMapOf<String, SqlDriver>()
    private val lock = kotlinx.coroutines.sync.Mutex()

    fun validateWorkspaceId(id: String) {
        require(idRegex.matches(id)) { "Invalid workspaceId: $id" }
    }

    suspend fun getDriver(workspaceId: String): SqlDriver {
        validateWorkspaceId(workspaceId)
        lock.lock()
        try {
            return openDrivers.getOrPut(workspaceId) {
                driverFactory.create(workspaceId)
            }
        } finally {
            lock.unlock()
        }
    }

    suspend fun <T> useDatabase(workspaceId: String, block: (WorkspaceDatabase) -> T): T {
        val driver = getDriver(workspaceId)
        return block(WorkspaceDatabase(driver))
    }

    suspend fun <T> useEngine(workspaceId: String, block: (ApexKitDatabaseEngine) -> T): T {
        val driver = getDriver(workspaceId)
        return block(ApexKitDatabaseEngine(driver))
    }

    suspend fun deleteWorkspace(workspaceId: String) {
        validateWorkspaceId(workspaceId)
        lock.lock()
        try {
            openDrivers.remove(workspaceId)?.close()
            platformDeleteWorkspaceFiles(workspaceId)
        } finally {
            lock.unlock()
        }
    }

    fun activeDatabaseFileNames(): List<String> = openDrivers.keys.map { "workspace_${it}.db" }

    /**
     * Folds the tenant WAL back into the main database and truncates it, returning the bytes
     * that disappeared from the `-wal` file. A checkpoint blocked by an active reader leaves
     * the WAL untouched and returns 0; the next refresh retries.
     */
    suspend fun checkpoint(workspaceId: String): Long {
        val driver = getDriver(workspaceId)
        val wal = File(driverPath(workspaceId) + "-wal")
        val before = wal.length()
        runCatching {
            driver.executeQuery(null, "PRAGMA wal_checkpoint(TRUNCATE);", 0, null).use { cursor ->
                cursor.next()
            }
        }
        return (before - wal.length()).coerceAtLeast(0L)
    }

    /**
     * Checkpoints every open tenant database. The key set is snapshotted under [lock] and the
     * drivers are checkpointed outside it: [getDriver] takes the same non-reentrant mutex, so
     * holding it here would deadlock.
     */
    suspend fun checkpointAll(): Long {
        lock.lock()
        val ids = try {
            openDrivers.keys.toList()
        } finally {
            lock.unlock()
        }
        var freed = 0L
        for (id in ids) {
            freed += runCatching { checkpoint(id) }.getOrDefault(0L)
        }
        return freed
    }

    /** `databases/workspace_<id>.db` as the platform resolved it for this process. */
    private fun driverPath(workspaceId: String): String {
        val ctx = currentAndroidContext?.invoke() ?: return ""
        return ctx.getDatabasePath("workspace_$workspaceId.db").absolutePath
    }
}