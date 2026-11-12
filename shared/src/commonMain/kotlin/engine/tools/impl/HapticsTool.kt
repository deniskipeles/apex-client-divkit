package engine.tools.impl

import engine.crypto.currentTimeMillis
import engine.tools.ToolArgs
import engine.tools.ToolContext
import engine.tools.ToolHandler
import engine.tools.ToolResult

/**
 * Universal Hardware Haptics Capability ("hardware.haptics@1" / "device.haptics@1").
 * Unopinionated tactile actuator contract supporting predefined semantic effects,
 * customizable one-shot pulses, W3C waveform timing arrays, and cancellation.
 *
 * Platform transduction (Android VibratorManager, iOS UIFeedbackGenerator) is
 * supplied by the host's native handler; this common implementation only defines
 * the shared, domain-free request/response contract.
 */
class HapticsTool : ToolHandler {
    override val capability: String = "hardware.haptics@1"
    override val requiredPermissions: Set<String> = setOf("android.permission.VIBRATE")

    override suspend fun invoke(args: ToolArgs, ctx: ToolContext): ToolResult {
        val op = args["op"] ?: args["action"] ?: args["operation"] ?: "perform"
        return try {
            when (op.lowercase()) {
                "stop", "cancel" -> executeCancel()
                "status", "capabilities", "get_status" -> executeStatus()
                else -> executePerform(args)
            }
        } catch (e: Exception) {
            ToolResult.Failed(capability, e.message ?: "Haptics execution failed")
        }
    }

    private fun executePerform(args: ToolArgs): ToolResult {
        val pattern = (args["pattern"] ?: args["type"] ?: args["mode"] ?: "click").lowercase()
        val durationMs = args.getInt("duration_ms", defaultDuration(pattern)).toLong()
        val amplitude = args.getInt("amplitude", defaultAmplitude(pattern)).coerceIn(1, 255)
        val timingsRaw = args["timings"] ?: args["waveform"] ?: args["pattern_array"]

        if (!timingsRaw.isNullOrBlank()) {
            // W3C navigator.vibrate([wait, vib, wait, vib...]) waveform pattern
            val timings = parseTimings(timingsRaw)
            return ToolResult.Success(
                mapOf(
                    "status" to "SUCCESS",
                    "mode" to "waveform",
                    "steps" to timings.size,
                    "total_duration_ms" to timings.sum(),
                    "amplitude" to amplitude,
                    "timestamp" to currentTimeMillis()
                )
            )
        }

        return ToolResult.Success(
            mapOf(
                "status" to "SUCCESS",
                "pattern" to pattern,
                "duration_ms" to durationMs,
                "amplitude" to amplitude,
                "timestamp" to currentTimeMillis()
            )
        )
    }

    private fun executeCancel(): ToolResult = ToolResult.Success(
        mapOf(
            "status" to "CANCELLED",
            "timestamp" to currentTimeMillis()
        )
    )

    private fun executeStatus(): ToolResult = ToolResult.Success(
        mapOf(
            "has_vibrator" to true,
            "has_amplitude_control" to true,
            "supported_patterns" to listOf("click", "double_click", "heavy", "tick", "success", "warning", "error", "waveform"),
            "status" to "READY"
        )
    )

    private fun defaultDuration(pattern: String): Int = when (pattern) {
        "tick", "selection" -> 10
        "click", "light" -> 20
        "medium" -> 35
        "double_click" -> 45
        "heavy", "heavy_click" -> 60
        "warning" -> 80
        "error", "failure", "buzz" -> 120
        "success" -> 50
        else -> 25
    }

    private fun defaultAmplitude(pattern: String): Int = when (pattern) {
        "tick", "selection" -> 90
        "click", "light" -> 140
        "medium" -> 180
        "heavy", "heavy_click" -> 255
        "error", "failure", "buzz" -> 255
        "warning" -> 200
        "success" -> 210
        else -> 180
    }

    private fun parseTimings(raw: String): LongArray {
        val clean = raw.trim().removePrefix("[").removeSuffix("]")
        return clean.split(",")
            .mapNotNull { it.trim().toLongOrNull() }
            .toLongArray()
            .takeIf { it.isNotEmpty() } ?: longArrayOf(0, 40)
    }
}
