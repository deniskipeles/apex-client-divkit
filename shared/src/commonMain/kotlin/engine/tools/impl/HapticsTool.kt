package engine.tools.impl

import engine.tools.ToolArgs
import engine.tools.ToolContext
import engine.tools.ToolHandler
import engine.tools.ToolResult

/**
 * Common HapticsTool capability ("device.haptics@1").
 */
class HapticsTool : ToolHandler {
    override val capability: String = "device.haptics@1"
    override val requiredPermissions: Set<String> = setOf("android.permission.VIBRATE")

    override suspend fun invoke(args: ToolArgs, ctx: ToolContext): ToolResult {
        val pattern = args["pattern"] ?: args["type"] ?: args["mode"] ?: "click"
        val durationMs = args.getInt("duration_ms", defaultDuration(pattern)).toLong()
        val amplitude = args.getInt("amplitude", defaultAmplitude(pattern)).coerceIn(1, 255)

        return ToolResult.Success(
            mapOf(
                "state" to "ok",
                "pattern" to pattern,
                "duration_ms" to durationMs,
                "amplitude" to amplitude
            )
        )
    }

    private fun defaultDuration(pattern: String): Int = when (pattern.lowercase()) {
        "tick", "selection" -> 10
        "click", "light" -> 20
        "double_click" -> 40
        "heavy", "heavy_click" -> 60
        "warning" -> 80
        "error", "buzz" -> 120
        "success" -> 50
        else -> 30
    }

    private fun defaultAmplitude(pattern: String): Int = when (pattern.lowercase()) {
        "tick", "selection" -> 90
        "click", "light" -> 150
        "heavy", "heavy_click" -> 255
        "error", "buzz" -> 240
        "warning" -> 200
        "success" -> 210
        else -> 180
    }
}
