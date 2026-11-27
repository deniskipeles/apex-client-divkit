package com.example

import com.yandex.divkit.multiplatform.evalExpr
import engine.state.DivVariableValue
import org.junit.Assert.assertEquals
import org.junit.Test

class DivKitExpressionTest {
    @Test
    fun nestedDictVariablesResolve() {
        val variables = mapOf(
            "cust.recent_order" to DivVariableValue.DictVal(
                mapOf(
                    "short_id" to DivVariableValue.Str("ORD-7"),
                    "total" to DivVariableValue.IntVal(1250)
                )
            )
        )
        assertEquals("ORD-7", evalExpr("@{cust.recent_order.short_id}", variables, emptyMap()))
        assertEquals("1250", evalExpr("@{cust.recent_order.total}", variables, emptyMap()))
        assertEquals("", evalExpr("@{cust.recent_order.missing}", variables, emptyMap()))
    }
}
