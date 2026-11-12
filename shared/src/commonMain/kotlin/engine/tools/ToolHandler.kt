package engine.tools

import engine.state.DivVariableValue
import engine.state.EngineStateBridge

interface ToolHandler {
    val capability: String
    val requiredPermissions: Set<String> get() = emptySet()
    suspend fun invoke(args: ToolArgs, ctx: ToolContext): ToolResult
}

class ToolArgs(private val raw: Map<String, String>) {
    operator fun get(key: String): String? = raw[key]
    fun getInt(key: String, default: Int): Int = raw[key]?.toIntOrNull() ?: default
    fun getDouble(key: String, default: Double): Double = raw[key]?.toDoubleOrNull() ?: default
    fun getFlag(key: String): Boolean = raw[key] == "true" || raw[key] == "1"
    fun toMap(): Map<String, String> = raw
}

class ToolContext(
    val workspaceId: String = "acme_pos",
    val variables: (key: String, value: String) -> Unit = { k, v ->
        val safeKey = if (k.startsWith("user.")) k else "user.$k"
        EngineStateBridge.pushUpdate(safeKey, DivVariableValue.Str(v))
    }
)

data class CapabilityManifest(val capabilities: Map<String, Int>) {
    fun has(cap: String): Boolean = capabilities.containsKey(cap)
    fun satisfies(required: List<String>): List<String> = required.filterNot(::has)
}
