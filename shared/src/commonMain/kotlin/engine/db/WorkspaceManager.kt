package engine.db

import app.cash.sqldelight.db.SqlDriver

/**
 * Owns physical isolation between tenants.
 *
 * Invariants:
 *  - One SQLite file per workspaceId.
 *  - One directory per workspaceId.
 *  - workspaceId is validated against [WORKSPACE_ID_REGEX] at every entry point
 *    so filesystem escape (`../../`) is impossible even with compromised config.
 */
class WorkspaceManager(
    private val driverFactory: WorkspaceDriverFactory
) {
    private val idRegex = Regex("^[a-zA-Z0-9_-]{1,64}$")
    private val openDrivers = mutableMapOf<String, SqlDriver>()
    private val lock = kotlinx.coroutines.sync.Mutex()

    init {
        // Driver creation must happen on a background thread on Android
        // (SQLiteDatabase.openOrCreateDatabase does disk I/O).
    }

    fun validateWorkspaceId(id: String) {
        require(idRegex.matches(id)) { "Invalid workspaceId: $id" }
    }

    /** Opens or reuses a driver for the workspace. Caller owns closing on app teardown. */
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

    fun getDatabase(workspaceId: String): WorkspaceDatabase {
        // Must be called from a coroutine with a driver already acquired.
        // Kept as a convenience; production code should use [useDatabase].
        val driver = openDrivers[workspaceId]
            ?: error("Driver not opened for $workspaceId — call getDriver first")
        return WorkspaceDatabase(driver)
    }

    /** Runs [block] with a database bound to [workspaceId], closing nothing. */
    suspend fun <T> useDatabase(workspaceId: String, block: (WorkspaceDatabase) -> T): T {
        val driver = getDriver(workspaceId)
        return block(WorkspaceDatabase(driver))
    }

    /** Nuclear option — deletes the SQLite file and workspace directory. */
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
}

/** Platform-specific file deletion (SQLite file, -wal, -shm, files dir). */
expect suspend fun platformDeleteWorkspaceFiles(workspaceId: String)
