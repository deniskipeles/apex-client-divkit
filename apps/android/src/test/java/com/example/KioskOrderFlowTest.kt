package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.enginehost.WorkspaceProvisioner
import engine.db.ApexKitDatabaseEngine
import engine.db.EngineRegistry
import engine.db.KioskOps
import engine.db.OpResult
import engine.db.WorkspaceDatabase
import engine.db.WorkspaceDriverFactory
import engine.db.WorkspaceManager
import engine.tools.NativeToolsSuite
import engine.tools.ToolArgs
import engine.tools.ToolContext
import engine.tools.ToolResult
import engine.tools.impl.LibSignalTool
import engine.tools.impl.NetTool
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * End-to-end coverage of the kiosk order plane, the libsignal card contract, and the LAN
 * share status that the kiosk cards depend on. These are the flows the emulator audit
 * exercised through the UI, pinned so they cannot regress silently.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class KioskOrderFlowTest {

    private val spec = WorkspaceProvisioner.defaultWorkspaces.first { it.tenantId == "apex_kiosk" }

    private fun withKioskWorkspace(block: (WorkspaceDatabase, ApexKitDatabaseEngine) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = WorkspaceManager(WorkspaceDriverFactory(context))
        runBlocking {
            manager.useDatabase(spec.tenantId) { db ->
                WorkspaceProvisioner.ensureTenantSchemaAndSeed(db, spec, EngineRegistry())
                block(db, ApexKitDatabaseEngine(db.driver))
            }
        }
    }

    @Test
    fun `adding the same sku twice merges the line and doubles the total`() = withKioskWorkspace { _, engine ->
        val cart = KioskOps.createOrder(engine, KioskOps.DIRECTION_CUSTOMER, "KSK-001")
        KioskOps.addLine(engine, cart.orderId, "P1001", "Mineral Water", 1.50)
        val afterSecond = KioskOps.addLine(engine, cart.orderId, "P1001", "Mineral Water", 1.50)

        assertEquals(1, afterSecond.lines.size)
        assertEquals(2, afterSecond.lines.single().quantity)
        assertEquals(3.00, afterSecond.total, 0.0001)
        assertEquals(2, afterSecond.itemCount)

        val reloaded = KioskOps.load(engine, cart.orderId)!!
        assertEquals(3.00, reloaded.total, 0.0001)
        assertEquals(2, reloaded.itemCount)
        assertEquals("3.00", KioskOps.format(reloaded.total))
    }

    @Test
    fun `removing a line recomputes the stored totals instead of subtracting expressions`() = withKioskWorkspace { _, engine ->
        val cart = KioskOps.createOrder(engine, KioskOps.DIRECTION_CUSTOMER, "KSK-001")
        KioskOps.addLine(engine, cart.orderId, "P1001", "Mineral Water", 1.50, quantity = 2)
        val withTwo = KioskOps.addLine(engine, cart.orderId, "P1012", "Coffee", 2.50)
        assertEquals(5.50, withTwo.total, 0.0001)

        val removed = KioskOps.removeLine(engine, cart.orderId, withTwo.lines.first { it.sku == "P1012" }.id)
        assertEquals(3.00, removed.total, 0.0001)
        assertEquals(2, removed.itemCount)
        assertEquals(1, removed.lines.size)
    }

    @Test
    fun `setting quantity to zero removes the line`() = withKioskWorkspace { _, engine ->
        val cart = KioskOps.createOrder(engine, KioskOps.DIRECTION_CUSTOMER, "KSK-001")
        val one = KioskOps.addLine(engine, cart.orderId, "P1015", "Chocolate Bar", 1.75)
        val zeroed = KioskOps.setQuantity(engine, cart.orderId, one.lines.single().id, 0)
        assertTrue(zeroed.lines.isEmpty())
        assertEquals(0.0, zeroed.total, 0.0001)
    }

    @Test
    fun `submitting an empty order fails with a readable reason`() = withKioskWorkspace { _, engine ->
        val cart = KioskOps.createOrder(engine, KioskOps.DIRECTION_OWNER, "KSK-001")
        val error = runCatching { KioskOps.submit(engine, cart.orderId) }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error!!.message!!.contains("empty", ignoreCase = true))

        KioskOps.addLine(engine, cart.orderId, "P1008", "Sandwich", 4.00)
        val submitted = KioskOps.submit(engine, cart.orderId)
        assertEquals("SUBMITTED", submitted.status)
        assertTrue(
            "a closed order must refuse further edits",
            runCatching { KioskOps.addLine(engine, cart.orderId, "P1008", "Sandwich", 4.00) }.isFailure
        )
    }

    @Test
    fun `stock guard rejects overselling and accepts the available quantity`() = withKioskWorkspace { _, engine ->
        val cart = KioskOps.createOrder(engine, KioskOps.DIRECTION_CUSTOMER, "KSK-001")
        val stock = engine.queryRecords(KioskOps.INVENTORY, limit = 50)
            .first { it.data["sku"]?.jsonPrimitive?.contentOrNull == "P1024" }
            .data["stock"]!!.jsonPrimitive.content.toInt()
        assertTrue(stock > 0)

        KioskOps.addLine(engine, cart.orderId, "P1024", "Croissant", 2.75, quantity = stock)
        val error = runCatching {
            KioskOps.addLine(engine, cart.orderId, "P1024", "Croissant", 2.75)
        }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error!!.message!!.contains("stock", ignoreCase = true))
    }

    @Test
    fun `transfer payload is deterministic and its checksum is reproducible`() = withKioskWorkspace { _, engine ->
        val cart = KioskOps.createOrder(engine, KioskOps.DIRECTION_CUSTOMER, "KSK-001")
        KioskOps.addLine(engine, cart.orderId, "P1001", "Mineral Water", 1.50, quantity = 2)
        val loaded = KioskOps.load(engine, cart.orderId)!!

        val first = KioskOps.exportPayload(loaded)
        val second = KioskOps.exportPayload(loaded)
        assertEquals(first, second)
        assertEquals(KioskOps.checksum(first), KioskOps.checksum(second))

        val payload = Json.parseToJsonElement(first).jsonObject
        assertEquals("apex.kiosk.transfer/1", payload["schema"]!!.jsonPrimitive.content)
        assertEquals("3.00", payload["total"]!!.jsonPrimitive.content)
        assertEquals(1, payload["items"]!!.jsonArray.size)
        assertEquals("2", payload["items"]!!.jsonArray[0].jsonObject["quantity"]!!.jsonPrimitive.content)
    }

    @Test
    fun `persisted order survives a second engine on the same database`() = withKioskWorkspace { db, engine ->
        val cart = KioskOps.createOrder(engine, KioskOps.DIRECTION_OWNER, "KSK-001")
        KioskOps.addLine(engine, cart.orderId, "P1012", "Coffee", 2.50)

        // A new engine instance stands in for a process restart: state must come from SQLite.
        val reopened = ApexKitDatabaseEngine(db.driver)
        val latest = KioskOps.latest(reopened, KioskOps.DIRECTION_OWNER)
        assertNotNull(latest)
        assertEquals(cart.orderId, latest!!.orderId)
        assertEquals(2.50, latest.total, 0.0001)
        assertEquals("Coffee", latest.lines.single().itemName)
        assertNull(KioskOps.latest(reopened, "NO_SUCH_DIRECTION"))
    }

    @Test
    fun `every kiosk operation used by a card is registered`() = withKioskWorkspace { db, engine ->
        val cart = KioskOps.createOrder(engine, KioskOps.DIRECTION_CUSTOMER, "KSK-001")
        val line = KioskOps.addLine(engine, cart.orderId, "P1001", "Mineral Water", 1.50).lines.single()
        val registry = EngineRegistry()
        val payloads = mapOf(
            "kiosk.create" to buildJsonObject {
                put("direction", KioskOps.DIRECTION_CUSTOMER)
                put("terminal_id", "KSK-001")
            },
            "kiosk.add_line" to buildJsonObject {
                put("order_id", cart.orderId)
                put("sku", "P1012")
                put("item_name", "Coffee")
                put("unit_price", 2.5)
                put("quantity", 1)
            },
            "kiosk.set_qty" to buildJsonObject {
                put("order_id", cart.orderId)
                put("line_id", line.id)
                put("quantity", 2)
            },
            "kiosk.remove_line" to buildJsonObject {
                put("order_id", cart.orderId)
                put("line_id", line.id)
            },
            "kiosk.clear" to buildJsonObject { put("order_id", cart.orderId) },
            "kiosk.load" to buildJsonObject { put("direction", KioskOps.DIRECTION_CUSTOMER) },
            "kiosk.export" to buildJsonObject { put("order_id", cart.orderId) },
        )
        for ((op, payload) in payloads) {
            val result = registry.execute(op, db, spec.tenantId, payload)
            assertTrue("$op must return a row", result is OpResult.Row)
        }

        // Submit is exercised on its own order: the shared cart above is emptied by `clear`.
        val submittable = KioskOps.createOrder(engine, KioskOps.DIRECTION_OWNER, "KSK-001")
        KioskOps.addLine(engine, submittable.orderId, "P1008", "Sandwich", 4.00)
        val submitted = registry.execute(
            "kiosk.submit",
            db,
            spec.tenantId,
            buildJsonObject { put("order_id", submittable.orderId) }
        )
        assertTrue(submitted is OpResult.Row)

        val unknown = runCatching { registry.execute("kiosk.nope", db, spec.tenantId, buildJsonObject { }) }
        assertTrue(unknown.exceptionOrNull() is SecurityException)
    }

    @Test
    fun `kiosk cards dispatch only registered kiosk operations`() {
        val used = kioskCardOps()
        assertTrue("kiosk cards must drive the kiosk data plane", used.isNotEmpty())
        assertEquals(
            setOf("add_line", "create", "export", "list", "load", "remove_line", "set_qty", "submit"),
            used
        )
    }

    @Test
    fun `kiosk list returns only submitted orders for the requested direction`() = withKioskWorkspace { _, engine ->
        val draft = KioskOps.createOrder(engine, KioskOps.DIRECTION_CUSTOMER, "KSK-001")
        KioskOps.addLine(engine, draft.orderId, "P1001", "Mineral Water", 1.50)
        val handedOver = KioskOps.createOrder(engine, KioskOps.DIRECTION_CUSTOMER, "KSK-001")
        KioskOps.addLine(engine, handedOver.orderId, "P1008", "Sandwich", 4.00, quantity = 2)
        KioskOps.submit(engine, handedOver.orderId)
        val receipt = KioskOps.createOrder(engine, KioskOps.DIRECTION_OWNER, "KSK-001")
        KioskOps.addLine(engine, receipt.orderId, "P1012", "Coffee", 2.50)
        KioskOps.submit(engine, receipt.orderId)

        val submitted = KioskOps.recent(engine, KioskOps.DIRECTION_CUSTOMER, "SUBMITTED", 5)
        assertEquals(listOf(handedOver.orderId), submitted.map { it.orderId })

        val rows = Json.parseToJsonElement(KioskOps.listJson(submitted)).jsonArray
        assertEquals(1, rows.size)
        assertEquals("8.00", rows[0].jsonObject["total_display"]!!.jsonPrimitive.content)
        assertEquals("2", rows[0].jsonObject["item_count"]!!.jsonPrimitive.content)
        assertEquals("SUBMITTED", rows[0].jsonObject["status"]!!.jsonPrimitive.content)
        assertEquals(handedOver.label, rows[0].jsonObject["order_label"]!!.jsonPrimitive.content)

        // The owner receipt direction is listed separately and never mixes in.
        val ownerRows = KioskOps.recent(engine, KioskOps.DIRECTION_OWNER, "SUBMITTED", 5)
        assertEquals(listOf(receipt.orderId), ownerRows.map { it.orderId })
    }

    @Test
    fun `kiosk list operation publishes rows the owner card can bind`() = withKioskWorkspace { db, engine ->
        val registry = EngineRegistry()
        val cart = KioskOps.createOrder(engine, KioskOps.DIRECTION_CUSTOMER, "KSK-001")
        KioskOps.addLine(engine, cart.orderId, "P1001", "Mineral Water", 1.50)
        KioskOps.submit(engine, cart.orderId)

        val result = registry.execute(
            "kiosk.list",
            db,
            spec.tenantId,
            buildJsonObject {
                put("direction", KioskOps.DIRECTION_CUSTOMER)
                put("status", "SUBMITTED")
            }
        ) as OpResult.Row
        assertEquals(1L, result.row!!["order_count"])
        val rows = Json.parseToJsonElement(result.row!!["orders_json"] as String).jsonArray
        assertEquals(1, rows.size)
        assertEquals("1.50", rows[0].jsonObject["total_display"]!!.jsonPrimitive.content)
    }

    @Test
    fun `removing a line publishes the unit price the undo button restores`() = withKioskWorkspace { db, engine ->
        val registry = EngineRegistry()
        val cart = KioskOps.createOrder(engine, KioskOps.DIRECTION_OWNER, "KSK-001")
        val withLine = KioskOps.addLine(engine, cart.orderId, "P1008", "Sandwich", 4.00, quantity = 2)
        val lineId = withLine.lines.single().id

        val result = registry.execute(
            "kiosk.remove_line",
            db,
            spec.tenantId,
            buildJsonObject {
                put("order_id", cart.orderId)
                put("line_id", lineId)
            }
        ) as OpResult.Row

        // The kiosk card maps these keys onto user.kiosk_undo_*; without the price the undo
        // button re-added the line at $0.00.
        assertEquals("P1008", result.row!!["removed_sku"])
        assertEquals("Sandwich", result.row!!["removed_name"])
        assertEquals(2L, result.row!!["removed_quantity"])
        assertEquals("4.00", result.row!!["removed_unit_price_display"])
        assertEquals(0.0, result.row!!["total"] as Double, 0.0001)
    }

    @Test
    fun `kiosk cards expose no tool urls or debug placeholders to the user`() {
        for (file in kioskCardFiles()) {
            val visible = visibleText(Json.parseToJsonElement(file.readText()))
            for (line in visible) {
                assertFalse("${file.name} must not print a tool url: $line", line.contains("tools://"))
                assertFalse("${file.name} must not print a None sentinel: $line", line.contains("None"))
                assertFalse("${file.name} must not print the raw direction enum: $line", line.contains("Direction:"))
            }
        }
    }

    @Test
    fun `libsignal establish clears the previous ciphertext and plaintext`(): Unit = runBlocking {
        val tool = LibSignalTool()
        val ctx = ToolContext(workspaceId = "test_libsignal_reset")
        val established = tool.invoke(
            ToolArgs(mapOf("op" to "create_session", "peer_id" to "peer_a")), ctx
        ) as ToolResult.Success

        assertEquals("ESTABLISHED", established.data["status"])
        assertEquals("", established.data["ciphertext"])
        assertEquals("", established.data["plaintext"])
        assertEquals("UNVERIFIED", established.data["verification_status"])
        val safety = established.data["safety_number"] as String
        assertEquals(60, safety.length)
        assertEquals(
            safety.chunked(5).joinToString(" "),
            established.data["safety_number_display"]
        )
    }

    @Test
    fun `libsignal verify without a session fails closed`(): Unit = runBlocking {
        val tool = LibSignalTool()
        val result = tool.invoke(
            ToolArgs(mapOf("op" to "verify_identity", "peer_id" to "never_paired")),
            ToolContext(workspaceId = "test_libsignal_no_session")
        )
        assertTrue(result is ToolResult.Failed)
        assertFalse((result as ToolResult.Failed).message.contains("VERIFIED"))
    }

    @Test
    fun `libsignal decrypt without a session or ciphertext fails`(): Unit = runBlocking {
        val tool = LibSignalTool()
        val ctx = ToolContext(workspaceId = "test_libsignal_decrypt")
        val missing = tool.invoke(ToolArgs(mapOf("op" to "decrypt", "peer_id" to "peer_a")), ctx)
        assertTrue(missing is ToolResult.Failed)

        tool.invoke(ToolArgs(mapOf("op" to "create_session", "peer_id" to "peer_a")), ctx)
        val tampered = tool.invoke(
            ToolArgs(mapOf("op" to "decrypt", "peer_id" to "peer_a", "ciphertext" to "AAAA")),
            ctx
        )
        assertTrue(tampered is ToolResult.Failed)
    }

    @Test
    fun `libsignal decrypt with a blank ciphertext fails with an actionable reason`(): Unit = runBlocking {
        val tool = LibSignalTool()
        val ctx = ToolContext(workspaceId = "test_libsignal_blank_cipher")
        tool.invoke(ToolArgs(mapOf("op" to "create_session", "peer_id" to "peer_a")), ctx)

        // The card binds user.signal_ciphertext, which is "" until Encrypt runs.
        val blank = tool.invoke(
            ToolArgs(mapOf("op" to "decrypt", "peer_id" to "peer_a", "ciphertext" to "")),
            ctx
        ) as ToolResult.Failed
        assertTrue(blank.message.contains("Encrypt"))
        assertFalse(blank.message.contains("Exception"))
    }

    @Test
    fun `libsignal encrypt with a blank payload fails instead of sealing nothing`(): Unit = runBlocking {
        val tool = LibSignalTool()
        val result = tool.invoke(
            ToolArgs(mapOf("op" to "encrypt", "peer_id" to "peer_a", "data" to "  ")),
            ToolContext(workspaceId = "test_libsignal_blank_payload")
        ) as ToolResult.Failed
        assertFalse(result.message.contains("Exception"))
        assertTrue(result.message.contains("Nothing to encrypt"))
    }

    @Test
    fun `libsignal tampered ciphertext reports a user facing reason`(): Unit = runBlocking {
        val tool = LibSignalTool()
        val ctx = ToolContext(workspaceId = "test_libsignal_tampered")
        tool.invoke(ToolArgs(mapOf("op" to "create_session", "peer_id" to "peer_a")), ctx)
        val tampered = tool.invoke(
            ToolArgs(mapOf("op" to "decrypt", "peer_id" to "peer_a", "ciphertext" to "AAAA")),
            ctx
        ) as ToolResult.Failed
        assertFalse(tampered.message.contains("Exception"))
        assertFalse(tampered.message.contains("java."))
        assertFalse(tampered.message.contains("ParseException"))
    }

    @Test
    fun `lan share without a running hub is staged not silently shared`(): Unit = runBlocking {
        val tool = NetTool()
        val result = tool.invoke(
            ToolArgs(mapOf("op" to "share", "name" to "order.json", "content" to "{}")),
            ToolContext()
        ) as ToolResult.Success
        assertEquals("STAGED", result.data["status"])
        assertEquals(false, result.data["hub_running"])
        assertTrue((result.data["warning"] as String).isNotBlank())

        tool.invoke(ToolArgs(mapOf("op" to "serve", "port" to "0")), ToolContext())
        val running = tool.invoke(
            ToolArgs(mapOf("op" to "share", "name" to "order2.json", "content" to "{}")),
            ToolContext()
        ) as ToolResult.Success
        assertEquals("SHARED", running.data["status"])
        assertEquals(true, running.data["hub_running"])
        tool.invoke(ToolArgs(mapOf("op" to "stop")), ToolContext())
    }

    @Test
    fun `libsignal is registered in the native tool suite with a demo card`() {
        val schema = NativeToolsSuite.registeredSchemas.first { it.toolId == "libsignal" }
        assertTrue(schema.supportedOperations.contains("create_session"))
        assertTrue(schema.supportedOperations.contains("verify_identity"))
    }

    // ---- helpers -----------------------------------------------------------

    private fun kioskCardFiles() =
        listOf("kiosk_customer", "kiosk_owner", "kiosk_transfer", "kiosk_lan").map {
            java.io.File("src/main/resources/demo/apex-kiosk/tools/$it.json")
        }.also { files ->
            files.forEach { assertTrue("${it.path} must exist", it.exists()) }
        }

    /** Every string that ends up in a `text` node, so card copy can be asserted directly. */
    private fun visibleText(node: kotlinx.serialization.json.JsonElement): List<String> {
        val out = mutableListOf<String>()
        when (node) {
            is JsonObject -> {
                node["text"]?.let { el ->
                    (el as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.let { out.add(it) }
                }
                node.forEach { (_, value) -> out.addAll(visibleText(value)) }
            }
            is kotlinx.serialization.json.JsonArray -> node.forEach { out.addAll(visibleText(it)) }
            else -> Unit
        }
        return out
    }

    private fun kioskCardOps(): Set<String> {
        val regex = Regex("local-db://op/kiosk\\.([a-z_]+)")
        return kioskCardFiles()
            .flatMap { file -> regex.findAll(file.readText()).map { it.groupValues[1] }.toList() }
            .toSet()
    }

    @Suppress("unused")
    private fun JsonObject.text(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull
}
