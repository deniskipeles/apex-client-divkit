package engine.fs

import android.content.Context
import java.io.File
import java.util.Locale

/**
 * A workspace sandbox folder plus the tenant database that belongs to it.
 *
 * [sandboxBytes] and [databaseBytes] are reported separately so the UI can answer "is this
 * workspace's footprint media or SQLite?"; [sizeBytes] is their sum. [databaseBytes] includes
 * the WAL/SHM sidecars, which is where most of an idle tenant database's bytes can live.
 */
data class WorkspaceFolder(
    val workspaceId: String,
    val displayName: String,
    val dirPath: String,
    val databasePath: String?,
    val sandboxBytes: Long,
    val databaseBytes: Long,
    val fileCount: Int,
    val lastModified: Long
) {
    val sizeBytes: Long
        get() = sandboxBytes + databaseBytes
}

/**
 * Android-facing view over the per-workspace sandbox on disk.
 *
 * Layout (all under the app's private `filesDir`):
 *  - `workspaces/<workspaceId>/`      → tenant sandbox (media, exports, …)
 *  - `databases/workspace_<id>.db`    → tenant SQLite database (+ `-wal`, `-shm`, `-journal`)
 *
 * The database files are surfaced as read-only entries because an open SQLDelight driver
 * owns their lifecycle; deleting one out from under a live connection would corrupt state.
 * A database whose sandbox folder was removed is still listed (`<id> (database only)`) so its
 * bytes stay attributable instead of vanishing from the report.
 */
class WorkspaceFileManager(private val context: Context) {

    val workspacesRoot: File
        get() = File(context.filesDir, "workspaces").apply { mkdirs() }

    private val databasesDir: File
        get() = context.getDatabasePath("placeholder").parentFile ?: File(context.filesDir, "databases")

    fun databaseFile(workspaceId: String): File = File(databasesDir, "workspace_$workspaceId.db")

    /** Main database plus every SQLite sidecar that currently exists. */
    fun databaseFiles(workspaceId: String): List<File> = DATABASE_SUFFIXES
        .map { suffix -> File(databasesDir, "workspace_$workspaceId.db$suffix") }
        .filter { it.exists() }

    /**
     * Lists workspace folders. Every provisioned [knownWorkspaceIds] entry is reported even
     * before its sandbox exists, any extra folder on disk (e.g. a deleted tenant's leftovers)
     * is included, and a tenant database without a sandbox still appears so its bytes are not
     * silently dropped from the storage report.
     */
    fun listWorkspaceFolders(
        knownWorkspaceIds: List<String>,
        displayNames: Map<String, String> = emptyMap()
    ): List<WorkspaceFolder> {
        val root = workspacesRoot
        val onDisk = root.listFiles().orEmpty().filter { it.isDirectory }.associateBy { it.name }
        val databaseIds = databasesDir.listFiles().orEmpty()
            .mapNotNull { file -> WORKSPACE_DATABASE_NAME.matchEntire(file.name)?.groupValues?.get(1) }
        val ids = (knownWorkspaceIds + onDisk.keys + databaseIds).distinct()

        return ids.mapNotNull { id ->
            val dir = onDisk[id] ?: File(root, id).takeIf { it.exists() }
            val dbFiles = databaseFiles(id)
            // A provisioned id with no sandbox and no database yet has nothing to show.
            if (dir == null && dbFiles.isEmpty()) return@mapNotNull null
            val mainDb = dbFiles.firstOrNull { it.name == "workspace_$id.db" }
            val sandboxBytes = dir?.let { WorkspaceFileSystem.directorySize(it) } ?: 0L
            WorkspaceFolder(
                workspaceId = id,
                displayName = displayNames[id] ?: if (dir == null) "$id (database only)" else id,
                dirPath = (dir ?: File(root, id)).absolutePath,
                databasePath = mainDb?.absolutePath,
                sandboxBytes = sandboxBytes,
                databaseBytes = dbFiles.sumOf { it.length() },
                fileCount = (dir?.let { countFiles(it) } ?: 0) + dbFiles.size,
                lastModified = maxOf(dir?.lastModified() ?: 0L, dbFiles.maxOfOrNull { it.lastModified() } ?: 0L)
            )
        }.sortedBy { it.workspaceId.lowercase(Locale.US) }
    }

    /** Directory entries for [folder], with the tenant database files appended as read-only entries. */
    fun entriesForFolder(folder: WorkspaceFolder): List<FsEntry> {
        val entries = WorkspaceFileSystem.listEntries(File(folder.dirPath)).toMutableList()
        databaseFiles(folder.workspaceId)
            .filter { it.exists() }
            .sortedBy { it.name }
            .forEach { entries.add(WorkspaceFileSystem.toEntry(it, readOnly = true)) }
        return WorkspaceFileSystem.sort(entries)
    }

    /**
     * Lists [dir] for the browser. At the workspace root the read-only tenant databases are
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

    private companion object {
        /** `""` is the main database; the rest are SQLite's WAL/journal sidecars. */
        val DATABASE_SUFFIXES = listOf("", "-wal", "-shm", "-journal")
        val WORKSPACE_DATABASE_NAME = Regex("""^workspace_(.+)\.db$""")
    }
}
