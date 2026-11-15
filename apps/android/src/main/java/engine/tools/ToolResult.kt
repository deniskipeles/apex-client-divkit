package engine.tools

/**
 * Universal Tool Result contract across all terminal capabilities.
 * Success | Unsupported | PermissionDenied | Failed
 * Guarantees that capabilities never throw across the boundary.
 */
sealed interface ToolResult {
    data class Success(val data: Map<String, Any?>) : ToolResult
    data class Unsupported(val tool: String, val reason: String) : ToolResult
    data class PermissionDenied(val tool: String, val permission: String) : ToolResult
    data class Failed(val tool: String, val message: String) : ToolResult
}
