package engine.fs

import android.content.Context
import java.io.File
import java.util.Locale

/** A workspace sandbox folder plus the tenant database that belongs to it. */
data class WorkspaceFolder(
    val workspaceId: String,
    val displayName: String,
    val dirPath: String,
    val databasePath: String?,
    val sizeBytes: Long,
    val fileCount: Int,
    val lastModified: Long
)

/**
 * Android-facing view over the per-workspace sandbox on disk.
 *
 * Layout (all under the app's private `filesDir`):
 *  - `workspaces/<workspaceId>/`      → tenant sandbox (media, exports, …)
 *  - `databases/workspace_<id>.db`    → tenant SQLite database
 *
 * The database file is surfaced as a read-only entry because an open SQLDelight driver
 * owns its lifecycle; deleting it out from under a live connection would corrupt state.
 */
class WorkspaceFileManager(private val context: Context) {

    val workspacesRoot: File
        get() = File(context.filesDir, "workspaces").apply { mkdirs() }

    private val databasesDir: File
        get() = context.getDatabasePath("placeholder").parentFile ?: File(context.filesDir, "databases")

    fun databaseFile(workspaceId: String): File = File(databasesDir, "workspace_$workspaceId.db")

    /**
     * Lists workspace folders. Every provisioned [knownWorkspaceIds] entry is reported even
     * before its sandbox exists, and any extra folder on disk (e.g. a deleted tenant's
     * leftovers) is included so it can still be inspected and cleaned up.
     */
    fun listWorkspaceFolders(
        knownWorkspaceIds: List<String>,
        displayNames: Map<String, String> = emptyMap()
    ): List<WorkspaceFolder> {
        val root = workspacesRoot
        val onDisk = root.listFiles().orEmpty().filter { it.isDirectory }.associateBy { it.name }
        val ids = (knownWorkspaceIds + onDisk.keys).distinct()

        return ids.mapNotNull { id ->
            val dir = onDisk[id] ?: File(root, id).takeIf { it.exists() } ?: return@mapNotNull null
            val db = databaseFile(id).takeIf { it.exists() }
            WorkspaceFolder(
                workspaceId = id,
                displayName = displayNames[id] ?: id,
                dirPath = dir.absolutePath,
                databasePath = db?.absolutePath,
                sizeBytes = WorkspaceFileSystem.directorySize(dir) + (db?.length() ?: 0L),
                fileCount = countFiles(dir),
                lastModified = maxOf(dir.lastModified(), db?.lastModified() ?: 0L)
            )
        }.sortedBy { it.workspaceId.lowercase(Locale.US) }
    }

    /** Directory entries for [folder], with the tenant database appended as a read-only entry. */
    fun entriesForFolder(folder: WorkspaceFolder): List<FsEntry> {
        val entries = WorkspaceFileSystem.listEntries(File(folder.dirPath)).toMutableList()
        folder.databasePath?.let { path ->
            val dbFile = File(path)
            if (dbFile.exists()) entries.add(WorkspaceFileSystem.toEntry(dbFile, readOnly = true))
        }
        return WorkspaceFileSystem.sort(entries)
    }

    /**
     * Lists [dir] for the browser. At the workspace root the read-only tenant database is
     * appended; deeper directories are plain listings.
     */
    fun listForBrowse(folder: WorkspaceFolder, dir: File): List<FsEntry> =
        if (dir.absolutePath == folder.dirPath) {
            entriesForFolder(folder)
        } else {
            WorkspaceFileSystem.listEntries(dir)
        }

    private fun countFiles(dir: File): Int {
        val children = dir.listFiles() ?: return 0
        return children.sumOf { if (it.isDirectory) countFiles(it) else 1 }
    }
}
