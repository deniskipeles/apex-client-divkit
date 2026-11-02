package engine.db

import android.content.Context
import app.cash.sqldelight.android.AndroidSqliteDriver
import app.cash.sqldelight.db.SqlDriver

actual class WorkspaceDriverFactory(private val context: Context) {
    actual fun create(workspaceId: String): SqlDriver {
        require(WORKSPACE_ID_REGEX.matches(workspaceId)) { "Invalid workspaceId: $workspaceId" }
        return AndroidSqliteDriver(
            schema = WorkspaceDatabase.Schema,
            context = context,
            name = "workspace_${workspaceId}.db"
        )
    }

    companion object {
        private val WORKSPACE_ID_REGEX = Regex("^[a-zA-Z0-9_-]{1,64}$")
    }
}

actual suspend fun platformDeleteWorkspaceFiles(workspaceId: String) {
    val ctx = currentAndroidContext?.invoke()
        ?: error("Android context not initialised")
    ctx.deleteDatabase("workspace_${workspaceId}.db")
    val dir = java.io.File(ctx.filesDir, "workspaces/$workspaceId")
    dir.deleteRecursively()
}

// Set by the host Application/Activity before engine use.
internal var currentAndroidContext: (() -> Context?)? = null
