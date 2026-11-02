package engine.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask

actual class WorkspaceDriverFactory {
    actual fun create(workspaceId: String): SqlDriver {
        require(WORKSPACE_ID_REGEX.matches(workspaceId)) { "Invalid workspaceId: $workspaceId" }
        return NativeSqliteDriver(
            schema = WorkspaceDatabase.Schema,
            name = "workspace_${workspaceId}.db"
        )
    }

    companion object {
        private val WORKSPACE_ID_REGEX = Regex("^[a-zA-Z0-9_-]{1,64}$")
    }
}

actual suspend fun platformDeleteWorkspaceFiles(workspaceId: String) {
    val docs = NSFileManager.defaultManager.URLForDirectory(
        directory = NSUserDomainMask,
        inDomain = NSUserDomainMask,
        appropriateForURL = null,
        create = false,
        error = null
    ) ?: return
    val dbPath = docs.URLByAppendingPathComponent("workspace_${workspaceId}.db") ?: return
    NSFileManager.defaultManager.removeItemAtURL(dbPath, null)
    NSFileManager.defaultManager.removeItemAtPath(dbPath.path!! + "-wal", null)
    NSFileManager.defaultManager.removeItemAtPath(dbPath.path!! + "-shm", null)
    val dir = docs.URLByAppendingPathComponent("workspaces/$workspaceId")
    if (dir != null) NSFileManager.defaultManager.removeItemAtURL(dir, null)
}
