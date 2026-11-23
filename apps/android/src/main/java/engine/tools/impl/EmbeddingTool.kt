package engine.tools.impl

import engine.tools.NativeToolsSuite
import engine.tools.ToolArgs
import engine.tools.ToolContext
import engine.tools.ToolHandler
import engine.tools.ToolResult
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Text and image embedding capability ("embedding").
 *
 * Bridges the versioned handler registry to the unversioned [NativeToolsSuite] schema.
 * `vectorize` remains registered as a deprecated handler because the shared contract still
 * names it; both classes dispatch to the same `tools://embedding/<op>` operations.
 */
open class EmbeddingTool : ToolHandler {
    override val capability = "embedding"

    override suspend fun invoke(args: ToolArgs, ctx: ToolContext): ToolResult {
        val op = args["op"] ?: args["operation"] ?: "text"
        val payload = buildJsonObject {
            put("op", op)
            for (key in listOf("text", "uri", "image", "a", "b", "text_a", "text_b")) {
                args[key]?.let { put(key, it) }
            }
        }

        val outcome = NativeToolsSuite.execute(
            url = "tools://embedding/$op",
            payload = payload,
            tenantId = ctx.workspaceId
        )

        return if (!outcome.success) {
            ToolResult.Failed(capability, outcome.summary)
        } else {
            val resultMap = outcome.resultData.jsonObject.mapValues { (_, v) ->
                v.jsonPrimitive.contentOrNull ?: v.toString()
            }
            ToolResult.Success(resultMap)
        }
    }
}

/** Deprecated contract name retained so `tools.json` and old cards keep resolving. */
@Deprecated("Use EmbeddingTool / tools://embedding")
class VectorizeTool : EmbeddingTool() {
    override val capability = "vectorize"
}
