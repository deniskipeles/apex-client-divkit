package com.example

import engine.state.DivVariableValue
import engine.state.EngineStateBridge
import engine.state.WorkspaceVariableScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The variable rule is structural, not a vocabulary: any workspace may declare its own
 * scopes, while runtime-owned scopes stay unreachable.
 */
class WorkspaceVariableScopeTest {

    @Test
    fun `workspace declared scopes are writable`() {
        listOf(
            "user.catalog_json",
            "owner.cart_total",
            "cust.scan_state",
            "product.sku",
            "shop.receipt.total"
        ).forEach { assertTrue(it, WorkspaceVariableScope.isValid(it)) }
    }

    @Test
    fun `runtime owned scopes stay unreachable`() {
        WorkspaceVariableScope.RESERVED.forEach { scope ->
            assertFalse("$scope.x", WorkspaceVariableScope.isValid("$scope.x"))
        }
    }

    @Test
    fun `unscoped and malformed names are rejected`() {
        listOf("", "total", ".total", "1shop.total", "a b.total", "user.").forEach {
            assertFalse(it, WorkspaceVariableScope.isValid(it))
        }
    }

    @Test
    fun `bridge accepts any declared scope and rejects runtime scopes`() {
        EngineStateBridge.clear()
        EngineStateBridge.pushBatch(
            mapOf(
                "owner.cart_total" to DivVariableValue.Str("18.50"),
                "cust.history" to DivVariableValue.ArrayVal(emptyList())
            )
        )
        assertEquals("18.50", EngineStateBridge.getVariable("owner.cart_total")?.asDisplayString())

        val rejected = runCatching {
            EngineStateBridge.pushUpdate("engine.error", DivVariableValue.Str("hijacked"))
        }
        assertTrue("engine scope must be rejected", rejected.isFailure)
        assertEquals(null, EngineStateBridge.getVariable("engine.error"))
    }
}
