package engine.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Cross-thread reactive bridge between background workers and the DivKit UI.
 *
 * Uses a StateFlow snapshot so that reattached UIs (e.g., after process death
 * or activity recreation) immediately receive the latest state. A SharedFlow
 * with replay=0 would silently drop updates when no collector is attached —
 * which is the exact scenario a background sync produces.
 */
object EngineStateBridge {

    private val _state = MutableStateFlow<Map<String, DivVariableValue>>(emptyMap())
    val state: StateFlow<Map<String, DivVariableValue>> = _state.asStateFlow()

    /**
     * Variable names must be `user.*` to prevent a compromised payload from
     * overwriting engine/system variables (e.g., `engine.error`).
     */
    fun pushUpdate(name: String, value: DivVariableValue) {
        require(name.startsWith("user.")) { "Variable must be user.*-namespaced: $name" }
        _state.update { it + (name to value) }
    }

    fun pushBatch(updates: Map<String, DivVariableValue>) {
        updates.keys.forEach { require(it.startsWith("user.")) { "Variable must be user.*: $it" } }
        _state.update { it + updates }
    }

    fun clear() { _state.value = emptyMap() }
}

/**
 * Platform-neutral variable value so common code can push updates without
 * importing DivKit's platform-specific DivVariable types.
 */
sealed class DivVariableValue {
    data class Str(val value: String) : DivVariableValue()
    data class IntVal(val value: Long) : DivVariableValue()
    data class Bool(val value: Boolean) : DivVariableValue()
    data class DoubleVal(val value: Double) : DivVariableValue()
    data class ArrayVal(val values: List<DivVariableValue>) : DivVariableValue()
}
