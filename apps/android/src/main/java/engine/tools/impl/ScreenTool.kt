package engine.tools.impl

import engine.crypto.currentTimeMillis
import engine.tools.ToolArgs
import engine.tools.ToolContext
import engine.tools.ToolHandler
import engine.tools.ToolResult

/**
 * Screen capability ("device.screen@1" / "tools://screen/{keep-awake,recording,screenshot}").
 *
 * Groups the three screen-facing operations a POS terminal needs:
 *  - keep-awake: hold the display on during a long-running task;
 *  - screenshot: capture the current terminal screen to workspace media;
 *  - recording: start/stop an MP4 screen recording.
 *
 * Hardware access lives behind platform sinks so the handler stays pure-JVM testable. The
 * Android host installs sinks backed by `ScreenCaptureHost` (MediaProjection). With no host
 * the operations return UNSUPPORTED instead of inventing a file.
 */
class ScreenTool : ToolHandler {
    override val capability: String = "device.screen@1"
    override val requiredPermissions: Set<String> = setOf("android.permission.WAKE_LOCK")

    data class Screenshot(
        val path: String,
        val uri: String,
        val width: Int,
        val height: Int,
        val bytes: Long,
        val imageId: String
    )

    data class RecordingStart(
        val id: String,
        val startedAt: Long,
        val width: Int,
        val height: Int
    )

    data class RecordingStop(
        val path: String,
        val uri: String,
        val durationMs: Long,
        val width: Int,
        val height: Int,
        val bytes: Long,
        val id: String
    )

    override suspend fun invoke(args: ToolArgs, ctx: ToolContext): ToolResult {
        val operation = (args["op"] ?: args["operation"] ?: "status").lowercase()
        return try {
            when (operation) {
                "keep-awake", "keep_awake", "keepawake", "wakelock" -> keepAwake(args)
                "enable", "on", "acquire", "start", "hold" -> keepAwake(args, "enable")
                "disable", "off", "release", "stop", "drop" -> keepAwake(args, "disable")
                "toggle" -> keepAwake(args, "toggle")
                "recording", "record", "screen-recording", "screen_recording" -> recording(args, ctx)
                "start-recording", "start_recording", "record-start" -> recording(args, ctx, "start")
                "stop-recording", "stop_recording", "record-stop" -> recording(args, ctx, "stop")
                "screenshot", "screen-shot", "capture-screen", "capture_screen" -> screenshot(args, ctx)
                "status", "get_status", "capabilities" -> status()
                else -> ToolResult.Failed(capability, "unsupported screen operation '$operation'")
            }
        } catch (e: Exception) {
            ToolResult.Failed(capability, e.message ?: "Screen operation failed")
        }
    }

    private fun keepAwake(args: ToolArgs, forced: String? = null): ToolResult {
        val requested = forced
            ?: (args["action"] ?: args["subop"] ?: args["state"] ?: "enable").lowercase()
        val enabled = when (requested) {
            "disable", "off", "release", "stop", "false", "0" -> false
            "toggle" -> !enabledState
            else -> true
        }
        val level = (args["level"] ?: args["mode"] ?: "screen").lowercase()
        val timeoutMs = args.getInt("timeout_ms", 0).toLong().coerceAtLeast(0L)
        val tag = args["tag"] ?: "apexkit:screen:keep-awake"

        val applied = keepAwakeSink?.invoke(enabled) ?: true
        if (applied) enabledState = enabled

        return ToolResult.Success(
            mapOf(
                "status" to if (enabled) "ENABLED" else "DISABLED",
                "enabled" to enabled,
                "level" to level,
                "timeout_ms" to timeoutMs,
                "tag" to tag,
                "applied" to applied,
                "timestamp" to currentTimeMillis()
            )
        )
    }

    private suspend fun screenshot(args: ToolArgs, ctx: ToolContext): ToolResult {
        val sink = screenshotSink
            ?: return ToolResult.Unsupported(capability, "no screen-capture host installed")
        val format = (args["format"] ?: "png").lowercase()
        val quality = args.getInt("quality", 95).coerceIn(1, 100)
        val shot = sink(ctx.workspaceId, format, quality)
            ?: return ToolResult.Failed(capability, "screen capture was cancelled or produced no frame")
        return ToolResult.Success(
            mapOf(
                "status" to "CAPTURED",
                "uri" to shot.uri,
                "path" to shot.path,
                "width" to shot.width,
                "height" to shot.height,
                "bytes" to shot.bytes,
                "image_id" to shot.imageId,
                "format" to format,
                "timestamp" to currentTimeMillis()
            )
        )
    }

    private suspend fun recording(args: ToolArgs, ctx: ToolContext, forced: String? = null): ToolResult {
        val action = (forced
            ?: args["action"] ?: args["subop"] ?: args["state"] ?: "start").lowercase()
        return when (action) {
            "start", "begin", "on" -> {
                if (recordingState) {
                    return ToolResult.Success(
                        mapOf(
                            "status" to "RECORDING",
                            "recording" to true,
                            "recording_id" to (recordingId ?: ""),
                            "started_at" to (recordingStartedAt ?: 0L)
                        )
                    )
                }
                val sink = recordingStartSink
                    ?: return ToolResult.Unsupported(capability, "no screen-recording host installed")
                val started = sink(ctx.workspaceId)
                    ?: return ToolResult.Failed(capability, "screen-recording permission was denied")
                recordingState = true
                recordingId = started.id
                recordingStartedAt = started.startedAt
                ToolResult.Success(
                    mapOf(
                        "status" to "RECORDING",
                        "recording" to true,
                        "recording_id" to started.id,
                        "started_at" to started.startedAt,
                        "width" to started.width,
                        "height" to started.height
                    )
                )
            }
            "stop", "end", "off" -> {
                if (!recordingState) {
                    return ToolResult.Success(
                        mapOf("status" to "IDLE", "recording" to false)
                    )
                }
                val sink = recordingStopSink
                    ?: return ToolResult.Unsupported(capability, "no screen-recording host installed")
                val stopped = sink()
                    ?: return ToolResult.Failed(capability, "recording stop failed")
                recordingState = false
                recordingId = null
                recordingStartedAt = null
                ToolResult.Success(
                    mapOf(
                        "status" to "RECORDED",
                        "recording" to false,
                        "recording_id" to stopped.id,
                        "uri" to stopped.uri,
                        "path" to stopped.path,
                        "duration_ms" to stopped.durationMs,
                        "width" to stopped.width,
                        "height" to stopped.height,
                        "bytes" to stopped.bytes,
                        "timestamp" to currentTimeMillis()
                    )
                )
            }
            "status" -> ToolResult.Success(recordingStatus())
            else -> ToolResult.Failed(capability, "unsupported recording action '$action'")
        }
    }

    private fun recordingStatus(): Map<String, Any?> = mapOf(
        "status" to if (recordingState) "RECORDING" else "IDLE",
        "recording" to recordingState,
        "recording_id" to recordingId,
        "started_at" to recordingStartedAt,
        "supported_actions" to listOf("start", "stop", "status")
    )

    private fun status(): ToolResult = ToolResult.Success(
        mapOf(
            "status" to when {
                recordingState -> "RECORDING"
                enabledState -> "ENABLED"
                else -> "READY"
            },
            "enabled" to enabledState,
            "recording" to recordingState,
            "recording_id" to recordingId,
            "screenshot_supported" to (screenshotSink != null),
            "recording_supported" to (recordingStartSink != null && recordingStopSink != null),
            "supported_levels" to listOf("screen", "full", "partial"),
            "supported_operations" to listOf("keep-awake", "recording", "screenshot", "status"),
            "timestamp" to currentTimeMillis()
        )
    )

    companion object {
        /** Platform wake-lock bridge; returns whether the platform applied the state. */
        var keepAwakeSink: ((enabled: Boolean) -> Boolean)? = null

        /** Captures the terminal screen and persists it to workspace media. */
        var screenshotSink: (suspend (tenantId: String, format: String, quality: Int) -> Screenshot?)? = null

        /** Starts a screen recording and returns its id and dimensions. */
        var recordingStartSink: (suspend (tenantId: String) -> RecordingStart?)? = null

        /** Stops the active recording and returns the persisted MP4 metadata. */
        var recordingStopSink: (suspend () -> RecordingStop?)? = null

        @Volatile private var enabledState: Boolean = false
        @Volatile private var recordingState: Boolean = false
        @Volatile private var recordingId: String? = null
        @Volatile private var recordingStartedAt: Long? = null

        fun isEnabled(): Boolean = enabledState
        fun isRecording(): Boolean = recordingState
    }
}
