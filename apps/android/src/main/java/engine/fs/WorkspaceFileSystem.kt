package engine.fs

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale

/**
 * A single entry (file or directory) surfaced by the file manager.
 *
 * [path] is always absolute so entries can be resolved without re-walking the tree.
 * [isReadOnly] marks entries the UI must not mutate — currently the tenant SQLite
 * databases, which live outside the workspace sandbox and are owned by open drivers.
 */
data class FsEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModified: Long,
    val isReadOnly: Boolean = false
) {
    val extension: String
        get() = name.substringAfterLast('.', "").lowercase(Locale.US)

    val isTextPreviewable: Boolean
        get() = !isDirectory && extension in TEXT_EXTENSIONS

    companion object {
        val TEXT_EXTENSIONS = setOf(
            "txt", "json", "md", "log", "csv", "xml", "yml", "yaml", "kt", "js", "html", "properties"
        )
    }
}

/**
 * Pure-JVM filesystem operations backing the workspace file manager.
 *
 * Every mutating helper validates the target name through [childOf], which canonicalises
 * both sides and rejects any name that would escape the parent directory. Keeping this
 * free of Android APIs makes it directly unit-testable on a plain temp directory.
 */
object WorkspaceFileSystem {

    /** Read cap for text previews; larger files are truncated in the UI. */
    const val MAX_PREVIEW_BYTES = 64 * 1024

    private val INVALID_NAME_CHARS = Regex("""[/\\\u0000]""")

    fun isSafeName(name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed == "." || trimmed == "..") return false
        return !INVALID_NAME_CHARS.containsMatchIn(trimmed)
    }

    /** Resolves [name] under [parent], or null when the name is unsafe or escapes [parent]. */
    fun childOf(parent: File, name: String): File? {
        if (!isSafeName(name)) return null
        val child = File(parent, name.trim())
        val parentPath = runCatching { parent.canonicalFile.path }.getOrNull() ?: return null
        val childPath = runCatching { child.canonicalFile.path }.getOrNull() ?: return null
        val expected = parentPath + File.separator + child.name
        return if (childPath == expected) child else null
    }

    fun toEntry(file: File, readOnly: Boolean = false): FsEntry = FsEntry(
        name = file.name,
        path = file.absolutePath,
        isDirectory = file.isDirectory,
        sizeBytes = if (file.isDirectory) directorySize(file) else file.length(),
        lastModified = file.lastModified(),
        isReadOnly = readOnly
    )

    /** Lists [dir], directories first, then files, each group ordered case-insensitively. */
    fun listEntries(dir: File): List<FsEntry> {
        val children = dir.listFiles() ?: return emptyList()
        return sort(children.map { toEntry(it) })
    }

    fun sort(entries: List<FsEntry>): List<FsEntry> = entries.sortedWith(
        compareByDescending<FsEntry> { it.isDirectory }.thenBy { it.name.lowercase(Locale.US) }
    )

    fun directorySize(dir: File): Long {
        val children = dir.listFiles() ?: return 0L
        var total = 0L
        for (child in children) {
            total += if (child.isDirectory) directorySize(child) else child.length()
        }
        return total
    }

    fun humanSize(bytes: Long): String = when {
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
        else -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
    }

    fun createDirectory(parent: File, name: String): File? {
        val target = childOf(parent, name) ?: return null
        if (target.exists()) return null
        return if (target.mkdirs()) target else null
    }

    /** Creates a text file, appending `.txt` when [name] carries no extension. */
    fun createTextFile(parent: File, name: String, content: String): File? {
        val withExtension = if (name.contains('.')) name else "$name.txt"
        val target = childOf(parent, withExtension) ?: return null
        if (target.exists()) return null
        return runCatching {
            target.parentFile?.mkdirs()
            target.writeText(content)
            target
        }.getOrNull()
    }

    fun rename(file: File, newName: String): File? {
        val parent = file.parentFile ?: return null
        val target = childOf(parent, newName) ?: return null
        if (target.absolutePath == file.absolutePath) return null
        if (target.exists()) return null
        return if (file.renameTo(target)) target else null
    }

    fun delete(file: File): Boolean =
        if (file.isDirectory) file.deleteRecursively() else file.delete()

    /**
     * Reads up to [maxBytes] of [file] as UTF-8, or null when the file is missing or
     * looks binary (a NUL byte in the sampled prefix).
     */
    fun readTextPreview(file: File, maxBytes: Int = 64 * 1024): String? {
        if (!file.isFile) return null
        return runCatching {
            val buffer = ByteArrayOutputStream()
            file.inputStream().use { input ->
                val chunk = ByteArray(8 * 1024)
                while (buffer.size() < maxBytes) {
                    val read = input.read(chunk, 0, minOf(chunk.size, maxBytes - buffer.size()))
                    if (read <= 0) break
                    buffer.write(chunk, 0, read)
                }
            }
            val bytes = buffer.toByteArray()
            if (bytes.any { it == 0.toByte() }) null else String(bytes, Charsets.UTF_8)
        }.getOrNull()
    }
}
