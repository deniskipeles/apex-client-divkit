package engine.tools.impl

import engine.tools.ToolArgs
import engine.tools.ToolContext
import engine.tools.ToolHandler
import engine.tools.ToolResult
import engine.tools.codec.Base45
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Generic text/JSON codec. It never interprets domain content; callers choose paths,
 * encodings and chunk limits.
 */
class CodecTool : ToolHandler {
    override val capability: String = "data.codec@1"

    private companion object {
        const val DEFAULT_MAX_BYTES = 500
        const val DEFAULT_MAX_CHUNKS = 32
        const val ENVELOPE_PREFIX = "C1"
    }

    override suspend fun invoke(args: ToolArgs, ctx: ToolContext): ToolResult {
        val codec = (args["codec"] ?: args["op"] ?: "base64").lowercase()
        return try {
            when (codec) {
                "base64" -> base64(args)
                "base45" -> base45(args)
                "json", "json/validate", "validate" -> jsonValidate(args)
                "json/extract", "extract" -> jsonExtract(args)
                "json/stringify", "stringify" -> jsonStringify(args)
                "json/chunk", "chunk" -> jsonChunk(args)
                "json/assemble", "assemble" -> jsonAssemble(args)
                else -> ToolResult.Failed(capability, "Unsupported codec '$codec'")
            }
        } catch (e: Exception) {
            ToolResult.Failed(capability, e.message ?: "Codec error")
        }
    }

    private fun base64(args: ToolArgs): ToolResult {
        val action = action(args)
        val text = args["text"] ?: args["value"] ?: return ToolResult.Failed(capability, "missing text argument")
        val urlSafe = args.getFlag("url_safe")
        val result = if (action == "decode") {
            val decoder = if (urlSafe) Base64.getUrlDecoder() else Base64.getDecoder()
            String(decoder.decode(text), Charsets.UTF_8)
        } else {
            val encoder = if (urlSafe) Base64.getUrlEncoder() else Base64.getEncoder()
            encoder.encodeToString(text.toByteArray(Charsets.UTF_8))
        }
        return ToolResult.Success(
            mapOf(
                "status" to "SUCCESS",
                "encoding" to "base64",
                "action" to action,
                "text" to result,
                "length" to result.length
            )
        )
    }

    private fun base45(args: ToolArgs): ToolResult {
        val action = action(args)
        val text = args["text"] ?: args["value"] ?: return ToolResult.Failed(capability, "missing text argument")
        val result = if (action == "decode") {
            String(Base45.decode(text), Charsets.UTF_8)
        } else {
            Base45.encode(text.toByteArray(Charsets.UTF_8))
        }
        return ToolResult.Success(
            mapOf(
                "status" to "SUCCESS",
                "encoding" to "base45",
                "action" to action,
                "text" to result,
                "length" to result.length
            )
        )
    }

    private fun jsonValidate(args: ToolArgs): ToolResult {
        val raw = args["json"] ?: args["text"] ?: return ToolResult.Failed(capability, "missing json argument")
        return try {
            val parsed = Json.parseToJsonElement(raw)
            ToolResult.Success(
                mapOf(
                    "status" to "SUCCESS",
                    "valid" to true,
                    "kind" to kind(parsed)
                )
            )
        } catch (e: Exception) {
            ToolResult.Success(
                mapOf(
                    "status" to "SUCCESS",
                    "valid" to false,
                    "error" to (e.message ?: "Invalid JSON")
                )
            )
        }
    }

    private fun jsonExtract(args: ToolArgs): ToolResult {
        val raw = args["json"] ?: args["text"] ?: return ToolResult.Failed(capability, "missing json argument")
        val root = Json.parseToJsonElement(raw)
        val paths = jsonArrayArg(args["paths"] ?: args["keys"]).orEmpty()
            .mapNotNull { it.jsonPrimitive.contentOrNull }
        val values = ArrayList<String>(paths.size)
        val missing = ArrayList<String>()
        for (path in paths) {
            val found = traverse(root, path)
            if (found == null || found is JsonNull) {
                values.add("")
                missing.add(path)
            } else {
                values.add(found.asText())
            }
        }

        val subtreePath = args["subtree_path"] ?: args["subtree"]
        val subtree = subtreePath?.let { path ->
            traverse(root, path)?.takeUnless { it is JsonNull }?.toString()
        }

        val data = mutableMapOf<String, Any?>(
            "status" to "SUCCESS",
            "values" to values,
            "count" to values.size,
            "missing" to missing
        )
        if (subtreePath != null) {
            data["subtree_json"] = subtree ?: ""
            data["subtree_path"] = subtreePath
        }
        return ToolResult.Success(data)
    }

    private fun jsonStringify(args: ToolArgs): ToolResult {
        val keys = jsonArrayArg(args["keys"]).orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull }
        val values = jsonArrayArg(args["values"]).orEmpty()
        val result = if (keys.isNotEmpty()) {
            buildJsonObject {
                keys.forEachIndexed { index, key ->
                    put(key, values.getOrNull(index) ?: JsonNull)
                }
            }.toString()
        } else {
            val raw = args["json"] ?: args["text"] ?: return ToolResult.Failed(capability, "missing json or keys/values")
            Json.parseToJsonElement(raw).toString()
        }
        return ToolResult.Success(
            mapOf(
                "status" to "SUCCESS",
                "json" to result,
                "length" to result.length
            )
        )
    }

    private fun jsonChunk(args: ToolArgs): ToolResult {
        val raw = args["json"] ?: args["text"] ?: return ToolResult.Failed(capability, "missing json argument")
        val encoding = (args["encoding"] ?: "base45").lowercase()
        if (encoding != "base45" && encoding != "base64") {
            return ToolResult.Failed(capability, "Unsupported chunk encoding '$encoding'")
        }
        val maxBytes = args.getInt("max_bytes", DEFAULT_MAX_BYTES).coerceIn(64, DEFAULT_MAX_BYTES)
        val maxChunks = args.getInt("max_chunks", DEFAULT_MAX_CHUNKS).coerceIn(1, DEFAULT_MAX_CHUNKS)
        val bytes = raw.toByteArray(Charsets.UTF_8)
        val transferId = UUID.randomUUID().toString().replace("-", "").take(8)
        val checksum = sha256(bytes).take(12)
        val prefix = "$ENVELOPE_PREFIX|$transferId|00|00|$checksum|"

        val chunks = ArrayList<String>()
        var offset = 0
        while (offset < bytes.size) {
            var take = minOf(maxBytes, bytes.size - offset)
            var candidate = ""
            while (take > 0) {
                val payload = encode(bytes.copyOfRange(offset, offset + take), encoding)
                candidate = prefix + payload
                if (candidate.length <= maxBytes) break
                take--
            }
            if (take <= 0) return ToolResult.Failed(capability, "max_bytes=$maxBytes is too small for the envelope")
            chunks.add(candidate)
            offset += take
        }

        if (chunks.size > maxChunks) {
            return ToolResult.Failed(
                capability,
                "Transfer needs ${chunks.size} QR chunks, maximum is $maxChunks"
            )
        }

        val total = chunks.size
        val finalized = chunks.mapIndexed { index, chunk ->
            chunk.replaceFirst(
                "$ENVELOPE_PREFIX|$transferId|00|00|",
                "$ENVELOPE_PREFIX|$transferId|${pad(index)}|${pad(total)}|"
            )
        }

        return ToolResult.Success(
            mapOf(
                "status" to "SUCCESS",
                "encoding" to encoding,
                "transfer_id" to transferId,
                "checksum" to checksum,
                "chunks" to finalized,
                "count" to total,
                "raw_bytes" to bytes.size,
                "max_bytes" to maxBytes,
                "max_chunks" to maxChunks
            )
        )
    }

    private fun jsonAssemble(args: ToolArgs): ToolResult {
        val rawChunks = jsonArrayArg(args["chunks"] ?: args["blocks"])
            ?: return ToolResult.Failed(capability, "missing chunks array")
        val chunks = rawChunks.mapNotNull { it.jsonPrimitive.contentOrNull }
        if (chunks.isEmpty()) return ToolResult.Failed(capability, "chunks array is empty")
        val encoding = (args["encoding"] ?: "base45").lowercase()
        if (encoding != "base45" && encoding != "base64") {
            return ToolResult.Failed(capability, "Unsupported chunk encoding '$encoding'")
        }

        var transferId = ""
        var checksum = ""
        var total = -1
        val byIndex = sortedMapOf<Int, String>()
        for (chunk in chunks) {
            val parts = chunk.split('|', limit = 6)
            if (parts.size != 6 || parts[0] != ENVELOPE_PREFIX) {
                return ToolResult.Failed(capability, "Malformed chunk envelope")
            }
            val index = parts[2].toIntOrNull()
                ?: return ToolResult.Failed(capability, "Malformed chunk index")
            val declaredTotal = parts[3].toIntOrNull()
                ?: return ToolResult.Failed(capability, "Malformed chunk total")
            if (total >= 0 && total != declaredTotal) {
                return ToolResult.Failed(capability, "Chunk totals do not match")
            }
            if (transferId.isNotEmpty() && transferId != parts[1]) {
                return ToolResult.Failed(capability, "Chunk transfer ids do not match")
            }
            transferId = parts[1]
            checksum = parts[4]
            total = declaredTotal
            byIndex[index] = parts[5]
        }

        val missing = (0 until total).filterNot { byIndex.containsKey(it) }
        if (missing.isNotEmpty()) {
            return ToolResult.Success(
                mapOf(
                    "status" to "PARTIAL",
                    "transfer_id" to transferId,
                    "checksum" to checksum,
                    "missing" to missing.map { pad(it) },
                    "count" to chunks.size,
                    "expected" to total
                )
            )
        }

        val bytes = ArrayList<Byte>()
        for (index in 0 until total) {
            val decoded = decode(byIndex.getValue(index), encoding)
            decoded.forEach { bytes.add(it) }
        }
        val actual = bytes.toByteArray()
        val actualChecksum = sha256(actual).take(12)
        if (actualChecksum != checksum) {
            return ToolResult.Success(
                mapOf(
                    "status" to "FAILED",
                    "transfer_id" to transferId,
                    "checksum" to checksum,
                    "checksum_ok" to false,
                    "error" to "Checksum mismatch",
                    "count" to chunks.size
                )
            )
        }

        return ToolResult.Success(
            mapOf(
                "status" to "COMPLETE",
                "encoding" to encoding,
                "transfer_id" to transferId,
                "checksum" to checksum,
                "checksum_ok" to true,
                "json" to String(actual, Charsets.UTF_8),
                "count" to total,
                "raw_bytes" to actual.size,
                "missing" to emptyList<String>()
            )
        )
    }

    private fun action(args: ToolArgs): String =
        (args["action"] ?: args["mode"] ?: "encode").lowercase().let {
            if (it == "d" || it == "dec") "decode" else if (it == "e" || it == "enc") "encode" else it
        }

    private fun jsonArrayArg(raw: String?): JsonArray? =
        raw?.let { runCatching { Json.parseToJsonElement(it).jsonArray }.getOrNull() }

    private fun traverse(root: JsonElement, path: String): JsonElement? {
        var current: JsonElement = root
        for (segment in path.split('.')) {
            current = when (val value = current) {
                is JsonObject -> value[segment] ?: return null
                is JsonArray -> segment.toIntOrNull()?.let { value.getOrNull(it) } ?: return null
                else -> return null
            }
        }
        return current
    }

    private fun JsonElement.asText(): String = when (this) {
        is JsonPrimitive -> contentOrNull ?: ""
        else -> toString()
    }

    private fun kind(el: JsonElement): String = when (el) {
        is JsonObject -> "object"
        is JsonArray -> "array"
        else -> "scalar"
    }

    private fun encode(bytes: ByteArray, encoding: String): String =
        if (encoding == "base64") Base64.getEncoder().encodeToString(bytes) else Base45.encode(bytes)

    private fun decode(text: String, encoding: String): ByteArray =
        if (encoding == "base64") Base64.getDecoder().decode(text) else Base45.decode(text)

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun pad(value: Int): String = value.toString().padStart(2, '0')
}
