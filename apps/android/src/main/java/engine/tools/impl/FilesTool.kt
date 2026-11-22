package engine.tools.impl

import engine.db.currentAndroidContext
import engine.tools.ToolArgs
import engine.tools.ToolContext
import engine.tools.ToolHandler
import engine.tools.ToolResult
import java.io.File
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Tenant-scoped file storage ("storage.files@1" / `tools://files`).
 *
 * Unopinionated by design: it moves bytes and reports metadata. It never parses, sniffs or
 * rewrites content, so the same capability backs image output, cached JSON, exported CSV and
 * anything else a card needs to persist.
 *
 * Every path is resolved inside `files/workspaces/<tenantId>/` and rejected if it escapes,
 * so a card cannot read another workspace or reach the app's private data. Callers pass
 * workspace-relative paths (`media/capture.png`), never absolute ones.
 */
class FilesTool : ToolHandler {
    override val capability: String = "storage.files@1"

    companion object {
        private const val MAX_INLINE_READ = 8 * 1024 * 1024
        private val NAME_SAFE = Regex("[^A-Za-z0-9._-]")
    }

    override suspend fun invoke(args: ToolArgs, ctx: ToolContext): ToolResult {
        val op = (args["op"] ?: args["action"] ?: args["operation"] ?: "list").lowercase()
        val root = workspaceRoot(ctx.workspaceId)
            ?: return ToolResult.Unsupported(
                capability,
                "No Android context: file storage requires the app sandbox"
            )

        return try {
            when (op) {
                "list", "ls" -> list(root, args)
                "info", "stat" -> info(root, args)
                "read" -> read(root, args)
                "write", "put" -> write(root, args)
                "append" -> write(root, args, append = true)
                "copy", "cp" -> copy(root, args)
                "move", "mv", "rename" -> move(root, args)
                "delete", "rm" -> delete(root, args)
                "mkdir" -> mkdir(root, args)
                "exists", "has" -> exists(root, args)
                "uri" -> uri(root, args)
                else -> ToolResult.Failed(capability, "Unsupported files operation '$op'")
            }
        } catch (e: IllegalArgumentException) {
            ToolResult.Failed(capability, e.message ?: "Invalid file request")
        } catch (e: Exception) {
            ToolResult.Failed(capability, e.message ?: "File operation failed")
        }
    }

    private fun workspaceRoot(tenantId: String): File? {
        val context = currentAndroidContext?.invoke() ?: return null
        val safeTenant = tenantId.replace(NAME_SAFE, "_").ifBlank { "default" }
        return File(context.filesDir, "workspaces/$safeTenant").apply { mkdirs() }
    }

    /**
     * Resolves a workspace-relative [path] to a file inside [root].
     *
     * Rejects absolute paths and any `..` segment that would escape the sandbox. The canonical
     * check runs after resolution because `../` can only be detected once normalised.
     */
    private fun resolve(root: File, path: String?): File {
        val raw = path?.trim().orEmpty()
        require(raw.isNotEmpty()) { "Missing 'path' argument" }
        require(!raw.startsWith("/")) { "Path must be workspace-relative: '$raw'" }
        require(!raw.startsWith("file://")) {
            "Pass a workspace-relative path, not a file:// URI: '$raw'"
        }

        val rootPath = root.canonicalFile
        val target = File(rootPath, raw).canonicalFile
        val rootPrefix = rootPath.path + File.separator
        require(target.path == rootPath.path || target.path.startsWith(rootPrefix)) {
            "Path escapes the workspace sandbox: '$raw'"
        }
        return target
    }

    /** Workspace-relative path with forward slashes, so cards can round-trip it. */
    private fun relative(root: File, file: File): String =
        file.canonicalFile.relativeTo(root.canonicalFile).path.replace(File.separatorChar, '/')

    private fun list(root: File, args: ToolArgs): ToolResult {
        val dir = resolve(root, args["path"] ?: args["dir"] ?: ".")
        if (!dir.exists()) return ToolResult.Failed(capability, "No such directory: ${relative(root, dir)}")
        if (!dir.isDirectory) return ToolResult.Failed(capability, "Not a directory: ${relative(root, dir)}")

        val recursive = args.getFlag("recursive")
        val limit = args.getInt("limit", 500).coerceIn(1, 5000)
        val pattern = args["pattern"]?.takeIf { it.isNotBlank() }?.let { Regex(globToRegex(it)) }

        val entries = mutableListOf<File>()
        collect(dir, recursive, entries, limit)
        val filtered = entries
            .filter { pattern == null || pattern.matches(it.name) }
            .sortedWith(compareBy({ !it.isDirectory }, { it.name }))
            .take(limit)

        val list = buildJsonArray {
            filtered.forEach { add(entryJson(root, it)) }
        }
        val totalBytes = filtered.filter { it.isFile }.sumOf { it.length() }
        // Human-readable rendering so a card can show the listing without parsing `entries`.
        val listing = filtered.joinToString("\n") {
            "${if (it.isDirectory) "d" else "-"} ${it.name}"
        }

        return ToolResult.Success(
            mapOf(
                "status" to "SUCCESS",
                "path" to relative(root, dir).ifEmpty { "." },
                "count" to filtered.size,
                "total_bytes" to totalBytes,
                "truncated" to (entries.size >= limit),
                "entries" to list,
                "listing" to listing
            )
        )
    }

    private fun collect(dir: File, recursive: Boolean, out: MutableList<File>, limit: Int) {
        val children = dir.listFiles()?.sortedBy { it.name } ?: return
        for (child in children) {
            if (out.size >= limit) return
            out.add(child)
            if (recursive && child.isDirectory) collect(child, true, out, limit)
        }
    }

    private fun globToRegex(glob: String): String {
        val sb = StringBuilder()
        glob.forEach { c ->
            when (c) {
                '*' -> sb.append(".*")
                '?' -> sb.append('.')
                else -> sb.append(Regex.escape(c.toString()))
            }
        }
        return sb.toString()
    }

    private fun entryJson(root: File, file: File): JsonObject = buildJsonObject {
        put("name", file.name)
        put("path", relative(root, file))
        put("type", if (file.isDirectory) "directory" else "file")
        put("size_bytes", if (file.isFile) file.length() else 0L)
        put("modified_ms", file.lastModified())
    }

    private fun info(root: File, args: ToolArgs): ToolResult {
        val file = resolve(root, args["path"])
        if (!file.exists()) return ToolResult.Failed(capability, "No such file: ${args["path"]}")
        return ToolResult.Success(
            mapOf(
                "status" to "SUCCESS",
                "name" to file.name,
                "path" to relative(root, file),
                "type" to if (file.isDirectory) "directory" else "file",
                "size_bytes" to if (file.isFile) file.length() else 0L,
                "modified_ms" to file.lastModified(),
                "readable" to file.canRead(),
                "writable" to file.canWrite()
            )
        )
    }

    /**
     * Reads a file as text, or as base64 when the caller asks for it. Binary content is never
     * silently mangled into text: without `encoding=base64` a binary file is refused, because
     * a lossy string would corrupt it on the way back out.
     */
    private fun read(root: File, args: ToolArgs): ToolResult {
        val file = resolve(root, args["path"])
        if (!file.exists() || !file.isFile) {
            return ToolResult.Failed(capability, "No such file: ${args["path"]}")
        }
        if (file.length() > MAX_INLINE_READ) {
            return ToolResult.Failed(
                capability,
                "File is ${file.length()} bytes; inline reads are capped at $MAX_INLINE_READ"
            )
        }

        val bytes = file.readBytes()
        val encoding = (args["encoding"] ?: "utf8").lowercase()
        val base64 = encoding in setOf("base64", "binary")

        // `ByteArray.toString(Charset)` replaces malformed bytes with U+FFFD instead of
        // failing, which would hand back a corrupted string. Decode strictly and refuse.
        val text = if (base64) null else runCatching { decodeUtf8(bytes) }.getOrNull()

        if (!base64 && (text == null || text.contains('\u0000'))) {
            return ToolResult.Failed(
                capability,
                "File looks binary; pass encoding=base64 to read it verbatim"
            )
        }

        return ToolResult.Success(
            mapOf(
                "status" to "SUCCESS",
                "path" to relative(root, file),
                "size_bytes" to bytes.size.toLong(),
                "encoding" to if (base64) "base64" else "utf8",
                "content" to (if (base64) Base64.getEncoder().encodeToString(bytes) else text),
                "uri" to "file://${file.absolutePath}"
            )
        )
    }

    /** Decodes UTF-8 strictly, throwing on malformed input instead of substituting U+FFFD. */
    @Throws(CharacterCodingException::class)
    private fun decodeUtf8(bytes: ByteArray): String =
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes))
            .toString()

    /**
     * Writes [content] to a workspace path, creating parent directories. [content] is taken
     * verbatim: base64 when `encoding=base64`, otherwise UTF-8 text.
     */
    private fun write(root: File, args: ToolArgs, append: Boolean = false): ToolResult {
        val file = resolve(root, args["path"])
        if (file.isDirectory) return ToolResult.Failed(capability, "Path is a directory: ${args["path"]}")

        val encoding = (args["encoding"] ?: "utf8").lowercase()
        val payload = args["content"] ?: args["data"] ?: args["text"] ?: ""
        val bytes = if (encoding in setOf("base64", "binary")) {
            Base64.getDecoder().decode(payload)
        } else {
            payload.toByteArray(Charsets.UTF_8)
        }

        file.parentFile?.mkdirs()
        if (append && file.exists()) file.appendBytes(bytes) else file.writeBytes(bytes)

        return ToolResult.Success(
            mapOf(
                "status" to "SUCCESS",
                "path" to relative(root, file),
                "size_bytes" to file.length(),
                "bytes_written" to bytes.size,
                "encoding" to if (encoding in setOf("base64", "binary")) "base64" else "utf8",
                "uri" to "file://${file.absolutePath}"
            )
        )
    }

    private fun copy(root: File, args: ToolArgs): ToolResult {
        val from = resolve(root, args["from"] ?: args["source"] ?: args["path"])
        val to = resolve(root, args["to"] ?: args["destination"] ?: args["target"])
        if (!from.exists()) return ToolResult.Failed(capability, "No such source: ${args["from"]}")
        if (from.isDirectory) {
            return ToolResult.Failed(capability, "copy only handles files; use mkdir for directories")
        }
        to.parentFile?.mkdirs()
        from.copyTo(to, overwrite = args.getFlag("overwrite") || !to.exists())
        return ToolResult.Success(
            mapOf(
                "status" to "SUCCESS",
                "from" to relative(root, from),
                "path" to relative(root, to),
                "size_bytes" to to.length()
            )
        )
    }

    private fun move(root: File, args: ToolArgs): ToolResult {
        val from = resolve(root, args["from"] ?: args["source"] ?: args["path"])
        val to = resolve(root, args["to"] ?: args["destination"] ?: args["target"])
        if (!from.exists()) return ToolResult.Failed(capability, "No such source: ${args["from"]}")
        if (to.exists() && !args.getFlag("overwrite")) {
            return ToolResult.Failed(capability, "Destination exists: ${args["to"]} (pass overwrite=true)")
        }
        to.parentFile?.mkdirs()
        if (from.renameTo(to)) {
            return ToolResult.Success(
                mapOf(
                    "status" to "SUCCESS",
                    "from" to relative(root, from),
                    "path" to relative(root, to)
                )
            )
        }
        // renameTo fails across filesystems; fall back to copy + delete.
        from.copyTo(to, overwrite = true)
        from.delete()
        return ToolResult.Success(
            mapOf(
                "status" to "SUCCESS",
                "from" to relative(root, from),
                "path" to relative(root, to)
            )
        )
    }

    private fun delete(root: File, args: ToolArgs): ToolResult {
        val file = resolve(root, args["path"])
        if (!file.exists()) return ToolResult.Failed(capability, "No such file: ${args["path"]}")
        if (file.isDirectory && file.listFiles()?.isNotEmpty() == true && !args.getFlag("recursive")) {
            return ToolResult.Failed(
                capability,
                "Directory is not empty; pass recursive=true to delete it"
            )
        }
        val removed = file.deleteRecursively()
        return ToolResult.Success(
            mapOf(
                "status" to if (removed) "SUCCESS" else "FAILED",
                "path" to relative(root, file),
                "deleted" to removed
            )
        )
    }

    private fun mkdir(root: File, args: ToolArgs): ToolResult {
        val dir = resolve(root, args["path"])
        val created = dir.mkdirs()
        if (!dir.isDirectory) {
            return ToolResult.Failed(capability, "Could not create directory: ${args["path"]}")
        }
        return ToolResult.Success(
            mapOf(
                "status" to "SUCCESS",
                "path" to relative(root, dir),
                "created" to created
            )
        )
    }

    private fun exists(root: File, args: ToolArgs): ToolResult {
        val file = resolve(root, args["path"])
        return ToolResult.Success(
            mapOf(
                "status" to "SUCCESS",
                "path" to relative(root, file),
                "exists" to file.exists(),
                "type" to when {
                    !file.exists() -> "none"
                    file.isDirectory -> "directory"
                    else -> "file"
                }
            )
        )
    }

    private fun uri(root: File, args: ToolArgs): ToolResult {
        val file = resolve(root, args["path"])
        return ToolResult.Success(
            mapOf(
                "status" to if (file.exists()) "SUCCESS" else "MISSING",
                "path" to relative(root, file),
                "exists" to file.exists(),
                "uri" to "file://${file.absolutePath}"
            )
        )
    }
}
