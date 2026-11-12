package engine.db

import app.cash.sqldelight.db.SqlDriver

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
}