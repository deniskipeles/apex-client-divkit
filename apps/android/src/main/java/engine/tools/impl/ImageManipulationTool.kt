package engine.tools.impl

import engine.tools.ToolArgs
import engine.tools.ToolContext
import engine.tools.ToolHandler
import engine.tools.ToolResult
import engine.tools.image.ImageManipulationEngine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put

/**
 * Universal Image Manipulation Tool ("vision.image_manipulation@1").
 *
 * A thin adapter over [ImageManipulationEngine]: it only normalises the caller's argument
 * shapes (single op vs. `pipeline`, `options` vs. top-level keys) and reports what the engine
 * did. All pixel work lives in the engine so the same code path serves `tools://` and
 * `div-action://terminal.tool.invoke`.
 */
class ImageManipulationTool : ToolHandler {
    override val capability: String = "vision.image_manipulation@1"
    override val requiredPermissions: Set<String> = emptySet()

    override suspend fun invoke(args: ToolArgs, ctx: ToolContext): ToolResult {
        val op = (args["op"] ?: args["action"] ?: args["operation"] ?: "process").lowercase()
        val tenantId = ctx.workspaceId

        val sourceImage = args["uri"] ?: args["image"] ?: args["base64"] ?: args["data_url"]
            ?: return ToolResult.Failed(
                capability,
                "Missing source image argument ('uri', 'base64' or 'data_url')"
            )

        val operations = resolveOperations(args, op)
        if (operations.isEmpty()) {
            return ToolResult.Failed(capability, "No operation to apply; pass 'op' or 'pipeline'")
        }

        return try {
            val result = ImageManipulationEngine.process(
                tenantId = tenantId,
                inputUriOrBase64 = sourceImage,
                operations = operations,
                encoding = (args["encoding"] ?: "uri").lowercase(),
                outputFormat = args["format"] ?: args["mime_type"] ?: "png",
                quality = args.getInt("quality", 100)
            )

            val data = mutableMapOf<String, Any?>(
                "status" to "SUCCESS",
                "uri" to result.uri,
                "image_id" to result.imageId,
                "width" to result.width,
                "height" to result.height,
                "size_bytes" to result.sizeBytes,
                "mime_type" to result.mimeType,
                "operations" to result.operationsApplied.joinToString(","),
                // Echo the source so the card can show what was processed, not only the result.
                "source_image" to sourceImage
            )
            result.base64?.let { data["base64"] = it }

            ToolResult.Success(data)
        } catch (e: ImageManipulationEngine.UndecodableSourceException) {
            ToolResult.Failed(
                capability,
                "Source is not a decodable image: ${e.reference.take(80)}"
            )
        } catch (e: Exception) {
            ToolResult.Failed(capability, e.message ?: "Image manipulation processing error")
        }
    }

    /**
     * Normalises the operation list. A `pipeline` array wins; otherwise the single-op keys
     * (`op` plus any parameters the caller supplied) become a one-step pipeline.
     */
    private fun resolveOperations(args: ToolArgs, op: String): List<JsonObject> {
        val pipeline = args["pipeline"]?.takeIf { it.isNotBlank() }?.let { raw ->
            runCatching {
                Json.parseToJsonElement(raw).jsonArray
                    .mapNotNull { it as? JsonObject }
            }.getOrNull()
        }
        if (!pipeline.isNullOrEmpty()) return pipeline

        val params = args.toMap().filterKeys {
            it !in setOf("pipeline", "output", "encoding", "format", "mime_type", "quality")
        }
        return listOf(
            buildJsonObject {
                put("op", op)
                params.forEach { (k, v) ->
                    // Preserve numbers as numbers so the engine's typed accessors see them.
                    val asNumber = v.toDoubleOrNull()
                    when {
                        asNumber != null -> put(k, asNumber)
                        v == "true" || v == "false" -> put(k, v.toBoolean())
                        else -> put(k, v)
                    }
                }
            }
        )
    }
}
