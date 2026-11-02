package engine.db

import app.cash.sqldelight.db.SqlDriver

/**
 * Each platform opens SQLite differently, so the driver is created per-platform.
 *
 * Android: AndroidSqliteDriver(schema, context, "workspace_<id>.db")
 * iOS:     NativeSqliteDriver(schema, "workspace_<id>.db")
 *
 * The database file name is constructed from a validated workspaceId
 * (see [WorkspaceManager.validateWorkspaceId]) so a malicious config cannot
 * escape the sandbox.
 */
expect class WorkspaceDriverFactory {
    fun create(workspaceId: String): SqlDriver
}
