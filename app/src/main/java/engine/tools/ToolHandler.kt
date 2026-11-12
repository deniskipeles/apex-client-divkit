package engine.tools

import engine.state.DivVariableValue
import engine.state.EngineStateBridge

/**
 * Common Tool Handler interface.
 * Each capability is versioned (e.g. "device.haptics@1", "vision.scan_code@1").
 */
interface ToolHandler {
    val capability: String
    val requiredPermissions: Set<String> get() = emptySet()
    suspend fun invoke(args: ToolArgs, ctx: ToolContext): ToolResult
}

/**
 * Type-safe parameter accessor for tool invocation arguments.
 */
class ToolArgs(private val raw: Map<String, String>) {
    operator fun get(key: String): String? = raw[key]
    fun getInt(key: String, default: Int): Int = raw[key]?.toIntOrNull() ?: default
    fun getDouble(key: String, default: Double): Double = raw[key]?.toDoubleOrNull() ?: default
    fun getFlag(key: String): Boolean = raw[key] == "true" || raw[key] == "1"
    fun toMap(): Map<String, String> = raw
}

/**
 * Execution context scoped per workspace without introducing workspace-specific routing.
 */
class ToolContext(
    val workspaceId: String = "acme_pos",
    val variables: (key: String, value: String) -> Unit = { k, v ->
        val safeKey = if (k.startsWith("user.")) k else "user.$k"
        EngineStateBridge.pushUpdate(safeKey, DivVariableValue.Str(v))
    }
)

/**
 * Advertised manifest of all capabilities the terminal ships.
 */
data class CapabilityManifest(val capabilities: Map<String, Int>) {
    fun has(cap: String): Boolean = capabilities.containsKey(cap)
    fun satisfies(required: List<String>): List<String> = required.filterNot(::has)
}
