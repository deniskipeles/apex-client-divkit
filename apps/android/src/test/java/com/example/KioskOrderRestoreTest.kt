package com.example

import com.example.enginehost.SduiEngineViewModel
import engine.state.DivVariableValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The kiosk cards hold no state of their own: every `user.kiosk_*` value lives in the engine
 * bridge, which is in-memory. A cold launch therefore rendered `NO_ORDER` while SQLite still
 * held the order, and the workspace header claimed `ITEMS 0 / $0.00`.
 *
 * These pin the mapping that hydrates a card from the persisted row.
 */
class KioskOrderRestoreTest {

    private fun row(vararg pairs: Pair<String, Any?>): Map<String, Any?> = linkedMapOf(*pairs)

    @Test
    fun `a persisted order is republished when its card opens`() {
        val updates = SduiEngineViewModel.kioskSeedVariables(
            "tool_kiosk_customer",
            row(
                "id" to "9",
                "status" to "submitted",
                "direction" to "CUSTOMER_REQUEST",
                "total_display" to "3.00",
                "subtotal_display" to "3.00",
                "item_count" to 2L,
                "line_count" to 1L,
                "lines_json" to """[{"item_name":"Mineral Water"}]""",
                "checksum" to "abc123"
            )
        )

        assertEquals(DivVariableValue.Str("9"), updates["user.kiosk_order_id"])
        assertEquals(DivVariableValue.Str("9"), updates["user.kiosk_order_label"])
        assertEquals(DivVariableValue.Str("submitted"), updates["user.kiosk_status"])
        assertEquals(DivVariableValue.Str("3.00"), updates["user.kiosk_total"])
        assertEquals(DivVariableValue.Str("2"), updates["user.kiosk_item_count"])
        assertEquals(DivVariableValue.Str("1"), updates["user.kiosk_line_count"])
        assertEquals(
            DivVariableValue.Str("""[{"item_name":"Mineral Water"}]"""),
            updates["user.kiosk_lines_json"]
        )
    }

    /**
     * Roll-ups are JSON numbers, so SQLite hands back `4.0` for a whole count. The workspace
     * header rendered `ITEMS 4.0`; integral values must read as integers.
     */
    @Test
    fun `whole roll-up numbers are seeded without a fraction`() {
        val updates = SduiEngineViewModel.kioskSeedVariables(
            "tool_kiosk_customer",
            row(
                "id" to "9",
                "status" to "draft",
                "item_count" to 4.0,
                "line_count" to 2.0,
                "total" to 11.5,
                "total_display" to "11.50"
            )
        )

        assertEquals(DivVariableValue.Str("4"), updates["user.kiosk_item_count"])
        assertEquals(DivVariableValue.Str("2"), updates["user.kiosk_line_count"])
        assertEquals(DivVariableValue.Str("11.50"), updates["user.kiosk_total"])
    }

    @Test
    fun `a reopened card drops the previous session's undo slot`() {
        val updates = SduiEngineViewModel.kioskSeedVariables(
            "tool_kiosk_customer",
            row("id" to "9", "status" to "draft")
        )

        assertEquals(DivVariableValue.Str(""), updates["user.kiosk_notice"])
        assertEquals(DivVariableValue.Str(""), updates["user.kiosk_undo_sku"])
        assertEquals(DivVariableValue.Str(""), updates["user.kiosk_undo_label"])
    }

    @Test
    fun `an empty database leaves the card defaults untouched`() {
        // No row: every kiosk card clears the shared projection instead of inheriting the
        // order the previously opened card left behind.
        val hub = SduiEngineViewModel.kioskSeedVariables("hub_apex_kiosk", null)
        assertEquals(DivVariableValue.Str(""), hub["user.kiosk_order_id"])
        assertEquals(DivVariableValue.Str(""), hub["user.kiosk_total"])
        val owner = SduiEngineViewModel.kioskSeedVariables("tool_kiosk_owner", emptyMap())
        assertEquals(DivVariableValue.Str(""), owner["user.kiosk_order_id"])
        assertEquals(DivVariableValue.Str(""), owner["user.kiosk_lines_json"])
        assertTrue(
            "a non-kiosk card must not touch the kiosk namespace",
            SduiEngineViewModel.kioskSeedVariables("tool_libsignal", null).isEmpty()
        )
        val customer = SduiEngineViewModel.kioskSeedVariables("tool_kiosk_customer", null)
        assertEquals(DivVariableValue.Str(""), customer["user.kiosk_order_id"])
        assertEquals(DivVariableValue.Str(""), customer["user.kiosk_status"])
    }

    @Test
    fun `a blank id is not restored as an order`() {
        val updates = SduiEngineViewModel.kioskSeedVariables(
            "tool_kiosk_customer",
            row("id" to "", "status" to "draft")
        )

        // No order id is restored: the blank seed writes an explicit empty value so the card
        // cannot inherit the order the previously opened card left in the shared namespace.
        assertEquals(DivVariableValue.Str(""), updates["user.kiosk_order_id"])
        assertEquals(DivVariableValue.Str(""), updates["user.kiosk_status"])
    }
}
