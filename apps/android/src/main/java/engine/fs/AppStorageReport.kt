package engine.fs

import android.content.Context
import java.io.File

/**
 * Every byte the app owns, split into non-overlapping buckets.
 *
 * Android Settings shows two numbers — **App** (the installed package) and **Data** (the
 * `dataDir` footprint) — so the report keeps them apart: [packageBytes] is the first,
 * [dataBytes] is the second, and the seven data buckets add up to it exactly. Nothing is
 * silently omitted; anything unrecognised lands in [otherDataBytes].
 */
data class AppStorageReport(
    val packageBytes: Long = 0L,
    val workspaceBytes: Long = 0L,
    val tenantDatabaseBytes: Long = 0L,
    val otherDatabaseBytes: Long = 0L,
    val offlineCacheBytes: Long = 0L,
    val otherFileBytes: Long = 0L,
    val cacheBytes: Long = 0L,
    val otherDataBytes: Long = 0L
) {
    val dataBytes: Long
        get() = workspaceBytes + tenantDatabaseBytes + otherDatabaseBytes +
            offlineCacheBytes + otherFileBytes + cacheBytes + otherDataBytes

    val totalBytes: Long
        get() = packageBytes + dataBytes
}

/**
 * Partitions an app data directory (plus the package files) into [AppStorageReport] buckets.
 *
 * Pure `java.io`, so a plain JVM test can drive it with a temporary directory tree. An
 * unreadable entry contributes 0 rather than throwing: a storage report must never crash the
 * Files tab.
 */
object AppStorageReportBuilder {

    fun build(dataDir: File, packageFiles: List<File>): AppStorageReport {
        val filesDir = File(dataDir, "files")
        val databasesDir = File(dataDir, "databases")
        val cacheDir = File(dataDir, "cache")
        val workspacesDir = File(filesDir, "workspaces")
        val offlineCacheDir = File(filesDir, "offline_cache")

        val otherFileBytes = (filesDir.listFiles().orEmpty())
            .filter { it.absolutePath != workspacesDir.absolutePath && it.absolutePath != offlineCacheDir.absolutePath }
            .sumOf { sizeOf(it) }

        var tenantDatabaseBytes = 0L
        var otherDatabaseBytes = 0L
        for (entry in databasesDir.listFiles().orEmpty()) {
            if (TENANT_DATABASE.matches(entry.name)) tenantDatabaseBytes += sizeOf(entry)
            else otherDatabaseBytes += sizeOf(entry)
        }

        val accounted = setOf(
            filesDir.absolutePath,
            databasesDir.absolutePath,
            cacheDir.absolutePath
        )
        val otherDataBytes = (dataDir.listFiles().orEmpty())
            .filter { it.absolutePath !in accounted }
            .sumOf { sizeOf(it) }

        return AppStorageReport(
            packageBytes = packageFiles.filter { it.exists() }.sumOf { it.length() },
            workspaceBytes = sizeOf(workspacesDir),
            tenantDatabaseBytes = tenantDatabaseBytes,
            otherDatabaseBytes = otherDatabaseBytes,
            offlineCacheBytes = sizeOf(offlineCacheDir),
            otherFileBytes = otherFileBytes,
            cacheBytes = sizeOf(cacheDir),
            otherDataBytes = otherDataBytes
        )
    }

    private fun sizeOf(file: File): Long = runCatching {
        if (file.isDirectory) WorkspaceFileSystem.directorySize(file) else file.length()
    }.getOrDefault(0L)

    /** `workspace_<id>.db` and its `-wal` / `-shm` / `-journal` sidecars. */
    private val TENANT_DATABASE = Regex("""^workspace_.+\.db(-wal|-shm|-journal)?$""")
}

/**
 * Reads the live report: the private `dataDir` tree plus the installed package files.
 *
 * Extracted native libraries are only counted when they live outside `dataDir`; when they are
 * extracted into it they are already part of [AppStorageReport.otherDataBytes].
 */
fun appStorageReport(context: Context): AppStorageReport {
    val info = context.applicationInfo
    val dataDir = File(info.dataDir)
    val packageFiles = buildList {
        info.sourceDir?.let { add(File(it)) }
        info.splitSourceDirs?.forEach { add(File(it)) }
        val nativeDir = info.nativeLibraryDir?.let { File(it) }
        if (nativeDir != null && nativeDir.exists() && !isInside(nativeDir, dataDir)) {
            addAll(nativeDir.listFiles().orEmpty())
        }
    }
    return AppStorageReportBuilder.build(dataDir, packageFiles.distinctBy { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) })
}

private fun isInside(child: File, parent: File): Boolean = runCatching {
    val childPath = child.canonicalPath
    val parentPath = parent.canonicalPath
    childPath == parentPath || childPath.startsWith(parentPath + File.separator)
}.getOrDefault(false)
