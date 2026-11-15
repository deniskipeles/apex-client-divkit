package engine.tools.impl

import engine.tools.ToolArgs
import engine.tools.ToolContext
import engine.tools.ToolHandler
import engine.tools.ToolResult
import engine.tools.code.CodeGeneratorEngine

/**
 * Barcode / QR code generator capability ("vision.generate_code@1").
 * Encodes arbitrary content into a 1D or 2D symbology and renders it to an image
 * (plus an inspectable module matrix) using the zero-dependency ZXing writer.
 */
class CodeGenerateTool : ToolHandler {
    override val capability: String = "vision.generate_code@1"
    override val requiredPermissions: Set<String> = emptySet()

    override suspend fun invoke(args: ToolArgs, ctx: ToolContext): ToolResult {
        val content = args["content"] ?: args["text"] ?: args["data"] ?: args["value"]
            ?: return ToolResult.Failed(capability, "Missing content argument ('content' or 'text')")

        val format = args["format"] ?: args["symbology"] ?: args["type"] ?: "qr"

        return try {
            val result = CodeGeneratorEngine.generate(
                tenantId = ctx.workspaceId,
                content = content,
                formatLabel = format,
                width = args.getInt("width", 512),
                height = args.getInt("height", 512),
                margin = args.getInt("margin", 4),
                ecLevel = args["ec_level"] ?: args["error_correction"],
                fgColorHex = args["fg_color"] ?: args["foreground"],
                bgColorHex = args["bg_color"] ?: args["background"],
                encoding = args["encoding"] ?: "uri"
            )

            val data = mutableMapOf<String, Any?>(
                "status" to "SUCCESS",
                "code_id" to result.codeId,
                "content" to result.content,
                "format" to result.format,
                "kind" to result.kind,
                "width" to result.width,
                "height" to result.height,
                "size_bytes" to result.sizeBytes,
                "matrix" to result.rowMatrix.joinToString("\n"),
                "matrix_rows" to result.rowMatrix.size,
                "mime_type" to result.mimeType,
                "uri" to result.uri
            )
            result.base64?.let { data["base64"] = it }

            ToolResult.Success(data)
        } catch (e: Exception) {
            ToolResult.Failed(capability, e.message ?: "Code generation error")
        }
    }
}
