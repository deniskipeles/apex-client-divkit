package engine.fs

import java.io.File
import java.util.Locale

/**
 * Bounded store for the images the vision tools decode by path.
 *
 * Every OCR / classifier / background-removal / embedding call writes its source image here.
 * Keeping the newest few files and pruning the rest is what stops `cache/` from growing by a
 * megabyte or three per tool run; the legacy helpers clean up files earlier builds left at the
 * cache root.
 */
object TempImageStore {

    const val DIR = "image_source"

    private const val EXTENSION = ".jpg"
    private val LEGACY_PREFIXES = listOf("picked_", "captured_")

    /**
     * Writes [bytes] as a new file, keeping at most [keep] images in [dir]. Returns null when
     * the write fails; the caller then reports a failed capture rather than a path to nothing.
     */
    fun store(dir: File, bytes: ByteArray, prefix: String, keep: Int = 4): File? = runCatching {
        dir.mkdirs()
        // Prune first so a failed write cannot leave the directory one file over the limit.
        prune(dir, (keep - 1).coerceAtLeast(0))
        File.createTempFile("${prefix}_", EXTENSION, dir).apply { writeBytes(bytes) }
    }.getOrNull()

    /** Deletes all but the newest [keep] files in [dir], newest first. Returns bytes freed. */
    fun prune(dir: File, keep: Int): Long {
        val files = dir.listFiles()?.filter { it.isFile } ?: return 0L
        if (files.size <= keep) return 0L
        val ordered = files.sortedWith(
            compareByDescending<File> { it.lastModified() }.thenByDescending { it.name.lowercase(Locale.US) }
        )
        return ordered.drop(keep).sumOf { file ->
            val size = file.length()
            if (file.delete()) size else 0L
        }
    }

    /** Deletes every child of [dir] and returns the bytes freed. */
    fun clear(dir: File): Long {
        val files = dir.listFiles() ?: return 0L
        var freed = 0L
        for (file in files) {
            val size = if (file.isDirectory) WorkspaceFileSystem.directorySize(file) else file.length()
            val deleted = runCatching { if (file.isDirectory) file.deleteRecursively() else file.delete() }
                .getOrDefault(false)
            if (deleted) freed += size
        }
        return freed
    }

    /** Removes `picked_*.jpg` / `captured_*.jpg` written at the cache root by earlier builds. */
    fun pruneLegacy(cacheDir: File): Long {
        val files = cacheDir.listFiles() ?: return 0L
        return files.filter { file ->
            file.isFile && file.name.endsWith(EXTENSION) && LEGACY_PREFIXES.any { file.name.startsWith(it) }
        }.sumOf { file ->
            val size = file.length()
            if (file.delete()) size else 0L
        }
    }
}
