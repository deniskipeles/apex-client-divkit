package engine.tools.impl

import engine.crypto.currentTimeMillis
import engine.tools.ToolArgs
import engine.tools.ToolContext
import engine.tools.ToolHandler
import engine.tools.ToolResult

/**
 * Universal Hardware Tone & Acoustic Audio Capability ("hardware.beeper@1" / "device.beeper@1").
 * Unopinionated audio synthesizer contract supporting raw frequency tones, waveforms,
 * semantic sound profiles, and tone sequences.
 *
 * Actual acoustic emission is a native concern supplied by the host handler
 * (Android AudioTrack, iOS AVAudioEngine); this common implementation only defines
 * the shared, domain-free request/response contract.
 */
class BeeperTool : ToolHandler {
    override val capability: String = "hardware.beeper@1"
    override val requiredPermissions: Set<String> = emptySet()

    override suspend fun invoke(args: ToolArgs, ctx: ToolContext): ToolResult {
        val op = (args["op"] ?: args["action"] ?: args["operation"] ?: "tone").lowercase()
        return try {
            when (op) {
                "stop", "mute", "cancel" ->
                    ToolResult.Success(mapOf("status" to "MUTED", "timestamp" to currentTimeMillis()))
                "status", "capabilities" -> ToolResult.Success(
                    mapOf(
                        "sample_rate" to 44100,
                        "supported_waveforms" to listOf("sine", "square", "triangle", "sawtooth"),
                        "min_frequency" to 20,
                        "max_frequency" to 20000,
                        "status" to "READY"
                    )
                )
                else -> executeTone(args, op)
            }
        } catch (e: Exception) {
            ToolResult.Failed(capability, e.message ?: "Audio tone synthesis failed")
        }
    }

    private fun executeTone(args: ToolArgs, operation: String): ToolResult {
        val profile = (args["profile"] ?: args["preset"] ?: args["tone"] ?: operation).lowercase()
        val volume = args.getDouble("volume", 0.85).coerceIn(0.0, 1.0)
        val waveformStr = (args["waveform"] ?: "sine").lowercase()

        // Sequence Mode (e.g., "880:60,1760:100")
        val sequenceRaw = args["sequence"] ?: args["melody"]
        if (!sequenceRaw.isNullOrBlank()) {
            val steps = parseSequence(sequenceRaw)
            return ToolResult.Success(
                mapOf(
                    "status" to "SUCCESS",
                    "mode" to "sequence",
                    "steps" to steps.size,
                    "waveform" to waveformStr,
                    "timestamp" to currentTimeMillis()
                )
            )
        }

        val (frequencyHz, durationMs) = when (profile) {
            "click", "blip" -> 1800.0 to 30
            "beep" -> 1600.0 to 70
            "chime", "notice" -> return ToolResult.Success(
                mapOf("status" to "SUCCESS", "profile" to "chime", "waveform" to waveformStr)
            )
            "success" -> return ToolResult.Success(
                mapOf("status" to "SUCCESS", "profile" to "success", "waveform" to waveformStr)
            )
            "warning", "alert" -> 2400.0 to 160
            "error", "buzz" -> 440.0 to 200
            else -> args.getDouble("frequency_hz", 1200.0) to args.getInt("duration_ms", 120)
        }

        return ToolResult.Success(
            mapOf(
                "status" to "SUCCESS",
                "frequency_hz" to frequencyHz,
                "duration_ms" to durationMs,
                "volume" to volume,
                "waveform" to waveformStr,
                "timestamp" to currentTimeMillis()
            )
        )
    }

    private fun parseSequence(raw: String): List<Pair<Double, Int>> {
        return raw.split(",")
            .mapNotNull { step ->
                val parts = step.trim().split(":")
                if (parts.size == 2) {
                    val freq = parts[0].toDoubleOrNull() ?: 1000.0
                    val dur = parts[1].toIntOrNull() ?: 80
                    freq to dur
                } else null
            }.takeIf { it.isNotEmpty() } ?: listOf(1200.0 to 100)
    }
}
