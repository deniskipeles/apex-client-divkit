package engine.tools.impl

import engine.tools.ToolArgs
import engine.tools.ToolContext
import engine.tools.ToolHandler
import engine.tools.ToolResult
import engine.tools.image.ImageManipulationEngine
import kotlinx.serialization.json.*

/**
 * Universal Image Manipulation Tool ("vision.image_manipulation@1").
 * Handles background removal, edge-preserving smoothing, tone adjustment,
 * and geometrical transforms using OS models and zero-weight native algorithms.
 */
class ImageManipulationTool : ToolHandler {
    override val capability: String = "vision.image_manipulation@1"
    override val requiredPermissions: Set<String> = emptySet()

    override suspend fun invoke(args: ToolArgs, ctx: ToolContext): ToolResult {
        val op = (args["op"] ?: args["action"] ?: args["operation"] ?: "process").lowercase()
        val tenantId = ctx.workspaceId

        val sourceImage = args["uri"] ?: args["image"] ?: args["base64"] ?: args["data_url"]
            ?: return ToolResult.Failed(capability, "Missing source image argument ('uri' or 'base64')")

        val encoding = (args["encoding"] ?: "uri").lowercase()

        // Build list of operations
        val operationsList = mutableListOf<JsonObject>()
        val pipelineRaw = args["pipeline"]

        if (!pipelineRaw.isNullOrBlank()) {
            try {
                val parsed = Json.parseToJsonElement(pipelineRaw).jsonArray
                parsed.forEach { (it as? JsonObject)?.let { obj -> operationsList.add(obj) } }
            } catch (_: Exception) {}
        }

        if (operationsList.isEmpty()) {
            // Single operation fallback
            val singleStep = buildJsonObject {
                put("op", op)
                args.toMap().forEach { (k, v) -> put(k, v) }
            }
            operationsList.add(singleStep)
        }

        return try {
            val result = ImageManipulationEngine.process(
                tenantId = tenantId,
                inputUriOrBase64 = sourceImage,
                operations = operationsList,
                encoding = encoding
            )

            val data = mutableMapOf<String, Any?>(
                "status" to "SUCCESS",
                "uri" to result.uri,
                "image_id" to result.imageId,
                "width" to result.width,
                "height" to result.height,
                "size_bytes" to result.sizeBytes,
                "mime_type" to result.mimeType,
                "operations" to result.operationsApplied.joinToString(",")
            )
            result.base64?.let { data["base64"] = it }

            ToolResult.Success(data)
        } catch (e: Exception) {
            ToolResult.Failed(capability, e.message ?: "Image manipulation processing error")
        }
    }
}