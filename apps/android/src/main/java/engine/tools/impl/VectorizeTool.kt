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
 * Text & image vector embedding capability ("vectorize").
 * Bridges the versioned handler registry to the unversioned NativeToolsSuite schema.
 */
class VectorizeTool : ToolHandler {
    override val capability = "vectorize"

    override suspend fun invoke(args: ToolArgs, ctx: ToolContext): ToolResult {
        val op = args["op"] ?: args["operation"] ?: "text"
        val payload = buildJsonObject {
            put("op", op)
            args["text"]?.let { put("text", it) }
            args["uri"]?.let { put("uri", it) }
            args["image"]?.let { put("uri", it) }
            args["a"]?.let { put("a", it) }
            args["b"]?.let { put("b", it) }
        }

        val outcome = NativeToolsSuite.execute(
            url = "tools://vectorize/$op",
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
