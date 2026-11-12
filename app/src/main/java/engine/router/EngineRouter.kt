package engine.router

import engine.config.EngineConfig
import engine.db.EngineRegistry
import engine.db.OpResult
import engine.db.WorkspaceManager
import engine.state.DivVariableValue
import engine.state.EngineStateBridge
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*

/**
 * Routes DivKit actions to the engine. Intercepts only the URL schemes this
 * engine owns; everything else falls through to DivKit's default handling.
 *
 * Schemes:
 *  - `local-db://op/<name>` → engine registry (database operations)
 *  - `hardware://<capability>` → host-provided capability handlers
 *  - `workspace://clear_all` → nuclear workspace reset
 *  - `https://` → left to DivKit's built-in network action
 */
class EngineRouter(
    private val config: EngineConfig,
    private val workspaceManager: WorkspaceManager,
    private val registry: EngineRegistry,
    private val activeWorkspace: () -> String = { config.tenantId },
    private val hardwareDispatcher: (suspend (capability: String, url: String, payload: JsonObject?) -> Boolean)? = null
) {
    companion object {
        private val VAR_NAME_REGEX = Regex("^user\\.[a-zA-Z][a-zA-Z0-9_.]{0,62}$")
        private const val OP_TIMEOUT_MS = 5_000L
    }

    sealed class ActionOutcome {
        object Handled : ActionOutcome()
        object Unhandled : ActionOutcome()
        data class Error(val message: String) : ActionOutcome()
    }

    /**
     * Entry point invoked by the platform DivKit action handler adapter.
     * All engine work runs on Dispatchers.Default / IO inside the caller.
     */
    suspend fun handle(url: String, payload: JsonObject?): ActionOutcome = when {
        url.startsWith("div-action://") -> handleDivAction(url, payload)
        url.startsWith("local-db://op/") -> handleLocalDb(url, payload ?: JsonObject(emptyMap()))
        url.startsWith("hardware://") -> handleHardware(url, payload)
        url.startsWith("tools://") -> handleTools(url, payload ?: JsonObject(emptyMap()))
        url == "workspace://clear_all" -> handleClearAll()
        else -> ActionOutcome.Unhandled
    }

    private suspend fun handleDivAction(url: String, payload: JsonObject?): ActionOutcome {
        val ws = activeWorkspace()
        val ctx = engine.tools.ToolContext(workspaceId = ws)
        val result = engine.tools.TerminalApp.router.handle(
            raw = url,
            provenanceType = engine.tools.Provenance.Publisher,
            ctx = ctx
        )
        return when (result) {
            is engine.tools.ToolResult.Success -> ActionOutcome.Handled
            is engine.tools.ToolResult.Unsupported -> {
                emitError("Unsupported tool: ${result.reason}", payload ?: JsonObject(emptyMap()))
                ActionOutcome.Error("Unsupported tool: ${result.reason}")
            }
            is engine.tools.ToolResult.PermissionDenied -> {
                emitError("Permission denied: ${result.permission}", payload ?: JsonObject(emptyMap()))
                ActionOutcome.Error("Permission denied: ${result.permission}")
            }
            is engine.tools.ToolResult.Failed -> {
                emitError("Tool failed: ${result.message}", payload ?: JsonObject(emptyMap()))
                ActionOutcome.Error("Tool failed: ${result.message}")
            }
            null -> ActionOutcome.Unhandled
        }
    }

    private suspend fun handleTools(url: String, payload: JsonObject): ActionOutcome {
        val ws = activeWorkspace()
        return try {
            engine.tools.NativeToolsSuite.execute(url, payload, tenantId = ws)
            ActionOutcome.Handled
        } catch (e: Exception) {
            val msg = e.message ?: "Tool execution failed"
            emitError(msg, payload)
            ActionOutcome.Error(msg)
        }
    }

    private suspend fun handleLocalDb(url: String, payload: JsonObject): ActionOutcome {
        val opName = url.removePrefix("local-db://op/")
        val ws = activeWorkspace()

        return try {
            val result = withTimeout(OP_TIMEOUT_MS) {
                workspaceManager.useDatabase(ws) { db ->
                    registry.execute(opName, db, ws, payload)
                }
            }
            emitResult(result, payload)
            ActionOutcome.Handled
        } catch (e: Exception) {
            val msg = e.message ?: "Operation failed"
            emitError(msg, payload)
            ActionOutcome.Error(msg)
        }
    }

    private suspend fun handleHardware(url: String, payload: JsonObject?): ActionOutcome {
        val host = url.removePrefix("hardware://").substringBefore('/')
        if (host !in config.capabilities) {
            emitError("Capability not granted: $host", payload ?: JsonObject(emptyMap()))
            return ActionOutcome.Error("Capability not granted: $host")
        }
        if (hardwareDispatcher != null && hardwareDispatcher.invoke(host, url, payload)) {
            return ActionOutcome.Handled
        }
        // Host app registers capability handlers externally; returning Unhandled
        // lets the platform layer dispatch to them.
        return ActionOutcome.Unhandled
    }

    private suspend fun handleClearAll(): ActionOutcome {
        workspaceManager.deleteWorkspace(activeWorkspace())
        EngineStateBridge.clear()
        return ActionOutcome.Handled
    }

    private fun emitResult(result: OpResult, payload: JsonObject) {
        val output = payload["output"]?.jsonObject
        when (result) {
            is OpResult.Rows -> {
                // Also support `json_list_var` or `output.*` mapping for DivKit json_list block
                val jsonArrayStr = buildJsonArray {
                    result.rows.forEach { row ->
                        addJsonObject {
                            row.forEach { (k, v) ->
                                when (v) {
                                    null -> put(k, JsonNull)
                                    is Long -> put(k, v)
                                    is Int -> put(k, v)
                                    is Double -> put(k, v)
                                    is Boolean -> put(k, v)
                                    else -> put(k, v.toString())
                                }
                            }
                        }
                    }
                }.toString()
                payload["json_list_var"]?.jsonPrimitive?.contentOrNull?.let { listVar ->
                    requireVar(listVar)
                    EngineStateBridge.pushUpdate(listVar, DivVariableValue.Str(jsonArrayStr))
                }
                if (output != null) {
                    for ((column, varEl) in output) {
                        val varName = varEl.jsonPrimitive.contentOrNull ?: continue
                        requireVar(varName)
                        if (column == "*" || column == "_json") {
                            EngineStateBridge.pushUpdate(varName, DivVariableValue.Str(jsonArrayStr))
                        } else {
                            val values = result.rows.map { row ->
                                val v = row[column]
                                DivVariableValue.Str(v?.toString() ?: "")
                            }
                            EngineStateBridge.pushUpdate(varName, DivVariableValue.ArrayVal(values))
                        }
                    }
                }
            }
            is OpResult.Row -> {
                if (output != null) {
                    for ((column, varEl) in output) {
                        val varName = varEl.jsonPrimitive.contentOrNull ?: continue
                        requireVar(varName)
                        EngineStateBridge.pushUpdate(
                            varName, toDivValue(result.row?.get(column))
                        )
                    }
                }
            }
            is OpResult.RowId -> output?.get("id")?.jsonPrimitive?.contentOrNull?.let { v ->
                requireVar(v)
                EngineStateBridge.pushUpdate(v, DivVariableValue.IntVal(result.id))
            }
            is OpResult.Count -> output?.get("count")?.jsonPrimitive?.contentOrNull?.let { v ->
                requireVar(v)
                EngineStateBridge.pushUpdate(v, DivVariableValue.IntVal(result.n))
            }
            is OpResult.Scalar -> output?.get("value")?.jsonPrimitive?.contentOrNull?.let { v ->
                requireVar(v)
                EngineStateBridge.pushUpdate(v, toDivValue(result.value))
            }
            is OpResult.SchemaImported -> output?.get("collections")?.jsonPrimitive?.contentOrNull?.let { v ->
                requireVar(v)
                EngineStateBridge.pushUpdate(v, DivVariableValue.IntVal(result.collectionsCount.toLong()))
            }
            OpResult.Empty -> Unit
        }
    }

    private fun emitError(message: String, payload: JsonObject) {
        EngineStateBridge.pushUpdate("user.engine_error", DivVariableValue.Str(message))
        payload["error_variable"]?.jsonPrimitive?.contentOrNull?.let { v ->
            if (VAR_NAME_REGEX.matches(v)) {
                EngineStateBridge.pushUpdate(v, DivVariableValue.Str(message))
            }
        }
    }

    private fun requireVar(name: String) {
        require(VAR_NAME_REGEX.matches(name)) {
            "Output variable must be user.*-namespaced: $name"
        }
    }

    private fun toDivValue(v: Any?): DivVariableValue = when (v) {
        null -> DivVariableValue.Str("")
        is Boolean -> DivVariableValue.Bool(v)
        is Int -> DivVariableValue.IntVal(v.toLong())
        is Long -> DivVariableValue.IntVal(v)
        is Double -> DivVariableValue.DoubleVal(v)
        else -> DivVariableValue.Str(v.toString())
    }
}
