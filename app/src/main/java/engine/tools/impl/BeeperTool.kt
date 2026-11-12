package engine.tools.impl

import engine.crypto.currentTimeMillis
import engine.tools.ToolArgs
import engine.tools.ToolContext
import engine.tools.ToolHandler
import engine.tools.ToolResult
import engine.tools.audio.AudioToneSynthesizer

/**
 * Universal Hardware Tone & Acoustic Audio Capability ("hardware.beeper@1" / "device.beeper@1").
 * Unopinionated audio synthesizer supporting raw frequency tones, waveforms,
 * semantic sound profiles, and tone sequences.
 */
class BeeperTool : ToolHandler {
    override val capability: String = "hardware.beeper@1"
    override val requiredPermissions: Set<String> = emptySet()

    override suspend fun invoke(args: ToolArgs, ctx: ToolContext): ToolResult {
        val op = (args["op"] ?: args["action"] ?: args["operation"] ?: "tone").lowercase()

        return try {
            when (op) {
                "stop", "mute", "cancel" -> {
                    AudioToneSynthesizer.stopActiveTone()
                    ToolResult.Success(mapOf("status" to "MUTED", "timestamp" to currentTimeMillis()))
                }
                "status", "capabilities" -> {
                    ToolResult.Success(
                        mapOf(
                            "sample_rate" to 44100,
                            "supported_waveforms" to listOf("sine", "square", "triangle", "sawtooth"),
                            "min_frequency" to 20,
                            "max_frequency" to 20000,
                            "status" to "READY"
                        )
                    )
                }
                else -> executeTone(args, op)
            }
        } catch (e: Exception) {
            ToolResult.Failed(capability, e.message ?: "Audio tone synthesis failed")
        }
    }

    private fun executeTone(args: ToolArgs, operation: String): ToolResult {
        val profile = (args["profile"] ?: args["preset"] ?: args["tone"] ?: operation).lowercase()
        val volume = args.getDouble("volume", 0.85).toFloat().coerceIn(0.0f, 1.0f)
        val waveformStr = (args["waveform"] ?: "sine").lowercase()
        val waveform = when (waveformStr) {
            "square" -> AudioToneSynthesizer.Waveform.SQUARE
            "triangle" -> AudioToneSynthesizer.Waveform.TRIANGLE
            "sawtooth" -> AudioToneSynthesizer.Waveform.SAWTOOTH
            else -> AudioToneSynthesizer.Waveform.SINE
        }

        // Sequence Mode (e.g., "880:60,1760:100")
        val sequenceRaw = args["sequence"] ?: args["melody"]
        if (!sequenceRaw.isNullOrBlank()) {
            val steps = parseSequence(sequenceRaw)
            AudioToneSynthesizer.playSequence(steps, volume, waveform)
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

        // Determine Frequency & Duration from profile or explicit parameters
        val (frequencyHz, durationMs) = when (profile) {
            "click", "blip" -> Pair(1800.0, 30)
            "beep" -> Pair(1600.0, 70)
            "chime", "notice" -> {
                AudioToneSynthesizer.playSequence(listOf(Pair(880.0, 60), Pair(1760.0, 90)), volume, waveform)
                return ToolResult.Success(
                    mapOf("status" to "SUCCESS", "profile" to "chime", "waveform" to waveformStr)
                )
            }
            "success" -> {
                AudioToneSynthesizer.playSequence(listOf(Pair(1046.5, 50), Pair(1318.5, 50), Pair(1567.98, 90)), volume, waveform)
                return ToolResult.Success(
                    mapOf("status" to "SUCCESS", "profile" to "success", "waveform" to waveformStr)
                )
            }
            "warning", "alert" -> Pair(2400.0, 160)
            "error", "buzz" -> Pair(440.0, 200)
            else -> {
                val f = args.getDouble("frequency_hz", 1200.0)
                val d = args.getInt("duration_ms", 120)
                Pair(f, d)
            }
        }

        AudioToneSynthesizer.playTone(frequencyHz, durationMs, volume, waveform)

        return ToolResult.Success(
            mapOf(
                "status" to "SUCCESS",
                "frequency_hz" to frequencyHz,
                "duration_ms" to durationMs,
                "volume" to volume.toDouble(),
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
                    Pair(freq, dur)
                } else null
            }.takeIf { it.isNotEmpty() } ?: listOf(Pair(1200.0, 100))
    }
}