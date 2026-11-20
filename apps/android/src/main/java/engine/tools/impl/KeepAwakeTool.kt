package engine.tools.impl

import engine.crypto.currentTimeMillis
import engine.tools.ToolArgs
import engine.tools.ToolContext
import engine.tools.ToolHandler
import engine.tools.ToolResult

/**
 * Universal Keep-Awake Capability ("device.keep_awake@1" / "tools://keep-awake").
 *
 * Unopinionated screen/wake-lock controller so a card can hold the terminal awake while a
 * long-running task (checkout, print, sync, kiosk display) is in flight. It carries no
 * product or vendor coupling: the caller supplies a lifecycle command, an optional timeout
 * and a wake level, and the platform chooses the physical implementation
 * (`FLAG_KEEP_SCREEN_ON` / `PowerManager.WakeLock` on Android, `UIApplication.isIdleTimerDisabled`
 * on iOS).
 *
 * Actual actuation is delegated to a platform sink so the handler stays pure-JVM testable;
 * when no sink is installed the tool reports success with the same metadata, matching the
 * headless/emulated path used by the other device tools.
 */
class KeepAwakeTool : ToolHandler {
    override val capability: String = "device.keep_awake@1"
    override val requiredPermissions: Set<String> = setOf("android.permission.WAKE_LOCK")

    override suspend fun invoke(args: ToolArgs, ctx: ToolContext): ToolResult {
        val op = (args["op"] ?: args["action"] ?: args["operation"] ?: "enable").lowercase()
        return try {
            when (op) {
                "enable", "on", "acquire", "start", "keep_awake", "keep-awake" -> setEnabled(true, args)
                "disable", "off", "release", "stop" -> setEnabled(false, args)
                "toggle" -> setEnabled(!isEnabled(), args)
                "status", "get_status", "capabilities" -> status()
                else -> setEnabled(true, args)
            }
        } catch (e: Exception) {
            ToolResult.Failed(capability, e.message ?: "Keep-awake execution failed")
        }
    }

    private fun setEnabled(enabled: Boolean, args: ToolArgs): ToolResult {
        val level = (args["level"] ?: args["mode"] ?: "screen").lowercase()
        val timeoutMs = args.getInt("timeout_ms", 0).toLong().coerceAtLeast(0L)
        val tag = args["tag"] ?: "apexkit:keep-awake"

        sink?.invoke(enabled)
        enabledState = enabled

        return ToolResult.Success(
            mapOf(
                "status" to if (enabled) "ENABLED" else "DISABLED",
                "enabled" to enabled,
                "level" to level,
                "timeout_ms" to timeoutMs,
                "tag" to tag,
                "timestamp" to currentTimeMillis()
            )
        )
    }

    private fun status(): ToolResult = ToolResult.Success(
        mapOf(
            "status" to if (enabledState) "ENABLED" else "DISABLED",
            "enabled" to enabledState,
            "supported_levels" to listOf("screen", "full", "partial"),
            "supported_operations" to listOf("enable", "disable", "toggle", "status"),
            "timestamp" to currentTimeMillis()
        )
    )

    companion object {
        /**
         * Platform wake-lock bridge: receives the desired enabled state and returns whether the
         * platform applied it. Installed by the host app (Android window flag / iOS idle timer).
         */
        var sink: ((enabled: Boolean) -> Boolean)? = null

        @Volatile
        private var enabledState: Boolean = false

        fun isEnabled(): Boolean = enabledState
    }
}
