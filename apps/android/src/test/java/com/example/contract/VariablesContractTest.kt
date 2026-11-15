package com.example.contract

import engine.state.DivVariableValue
import engine.state.EngineStateBridge
import org.junit.Assert.assertThrows
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the DivKit variable namespace contract (packages/contract/docs/variables.md):
 * the engine may only write `user.*` variables.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VariablesContractTest {

    @Test
    fun userNamespacedVariableIsAccepted() {
        EngineStateBridge.pushUpdate("user.contract_probe", DivVariableValue.Str("ok"))
        assertEquals(DivVariableValue.Str("ok"), EngineStateBridge.getVariable("user.contract_probe"))
    }

    @Test
    fun nonUserNamespacedVariableIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            EngineStateBridge.pushUpdate("system.contract_probe", DivVariableValue.Str("nope"))
        }
    }

    @Test
    fun batchRejectsNonUserNamespacedVariable() {
        assertThrows(IllegalArgumentException::class.java) {
            EngineStateBridge.pushBatch(mapOf("engine.error" to DivVariableValue.Str("nope")))
        }
    }
}
