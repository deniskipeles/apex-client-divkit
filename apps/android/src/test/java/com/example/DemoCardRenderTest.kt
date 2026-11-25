package com.example

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import engine.state.DivVariableValue
import engine.state.EngineStateBridge
import com.example.enginehost.WorkspaceProvisioner
import com.example.enginehost.demo.DemoCardRepository
import com.example.ui.theme.MyApplicationTheme
import com.yandex.divkit.multiplatform.DivKit
import com.yandex.divkit.multiplatform.DivKitDependencies
import com.yandex.divkit.multiplatform.DivView
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Renders the built-in showcase cards through the real Compose `DivView` renderer and clicks
 * their buttons, so the card JSON is exercised the way the app exercises it.
 *
 * This is the smoke check that would otherwise need a handset: a malformed div, an unresolvable
 * `@{...}` expression, or a button with no action surfaces here instead of on a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DemoCardRenderTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val spec = WorkspaceProvisioner.defaultWorkspaces.first()

    private val dependencies by lazy {
        DivKitDependencies(RuntimeEnvironment.getApplication())
    }

    private fun buttonIds(card: JsonObject): List<String> {
        val ids = mutableListOf<String>()
        fun walk(node: Any?) {
            when (node) {
                is JsonObject -> {
                    val type = (node["type"] as? JsonPrimitive)?.contentOrNull
                    if (type == "button") {
                        (node["id"] as? JsonPrimitive)?.contentOrNull?.let { ids.add(it) }
                    }
                    node.values.forEach { walk(it) }
                }
                is JsonArray -> node.forEach { walk(it) }
            }
        }
        walk(card)
        return ids
    }

    private fun render(card: JsonObject, cardId: String, onAction: suspend (String, String?) -> Unit) {
        composeRule.setContent {
            MyApplicationTheme {
                // Same shape as the workspace host: the card lives inside a vertical scroller,
                // so buttons below the fold are reachable through performScrollTo().
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    DivKit(dependencies = dependencies) {
                        DivView(
                            cardId = cardId,
                            jsonData = DemoCardRepository.encode(card),
                            onAction = onAction
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun renderAndClick(cardName: String, minimumButtons: Int = 1) {
        val card = DemoCardRepository.toolCard(spec, cardName)
            ?: error("missing demo card asset for '$cardName'")
        val ids = buttonIds(card)
        assertTrue("card '$cardName' exposes no buttons, so nothing is clickable", ids.isNotEmpty())
        assertTrue(
            "card '$cardName' exposes ${ids.size} buttons, expected at least $minimumButtons",
            ids.size >= minimumButtons
        )

        val dispatched = mutableListOf<String>()
        render(card, DemoCardRepository.toolCardId(cardName)) { url, _ -> dispatched.add(url) }

        ids.forEach { id ->
            composeRule.onNodeWithTag("div_btn_$id").performScrollTo().performClick()
        }
        composeRule.waitForIdle()

        assertTrue("clicking every button on '$cardName' dispatched no actions", dispatched.isNotEmpty())
        assertTrue(
            "card '$cardName' dispatched a malformed action: $dispatched",
            dispatched.all { it.contains("://") }
        )
    }

    @Test
    fun imageManipulationCardRendersAndDispatches() =
        renderAndClick("image_manipulation", minimumButtons = 8)

    @Test
    fun filesCardRendersAndDispatches() = renderAndClick("files")

    @Test
    fun netCardRendersAndDispatches() = renderAndClick("net")

    @Test
    fun embeddingCardRendersAndDispatches() = renderAndClick("embedding", minimumButtons = 5)

    @Test
    fun libsignalCardRendersAndDispatches() = renderAndClick("libsignal")

    @Test
    fun libsignalCardRendersDecryptedPlaintext() {
        val plaintext = """{"order":"#1042","total":"18.50","terminal":"till-07"}"""
        EngineStateBridge.pushBatch(
            mapOf(
                "user.signal_status" to DivVariableValue.Str("DECRYPTED"),
                "user.signal_ciphertext" to DivVariableValue.Str("MwgDEiEF"),
                "user.signal_plaintext" to DivVariableValue.Str(plaintext)
            )
        )
        try {
            val card = DemoCardRepository.toolCardDocument(spec, "libsignal")
                ?: error("missing libsignal tool card")
            render(card, DemoCardRepository.toolCardId("libsignal")) { _, _ -> }
            composeRule.onNodeWithText(plaintext).assertExists()
        } finally {
            EngineStateBridge.clear()
        }
    }

    @Test
    fun libsignalSafetyNumberIsStackedUnderItsLabel() {
        val card = Json.parseToJsonElement(
            javaClass.classLoader!!
                .getResourceAsStream("demo/acme_pos/tools/libsignal.json")!!
                .bufferedReader()
                .use { it.readText() }
        ).jsonObject
        // Side by side with a weighted label, DivKit measures the 60-digit value at its
        // intrinsic width and the row grows to most of the screen height.
        val holder = containerHolding(card, "Safety number")!!
        assertEquals("vertical", holder["orientation"]?.jsonPrimitive?.contentOrNull)
        val texts = holder["items"]!!.jsonArray.map {
            it.jsonObject["text"]?.jsonPrimitive?.contentOrNull ?: ""
        }
        assertTrue(texts.contains("Safety number"))
        assertTrue(texts.any { it.contains("signal_fingerprint_display") })
    }

    private fun containerHolding(node: JsonElement, text: String): JsonObject? {
        if (node is JsonObject) {
            val items = node["items"] as? JsonArray
            if (items?.any {
                    (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull == text
                } == true
            ) {
                return node
            }
        }
        val children = when (node) {
            is JsonObject -> node.values
            is JsonArray -> node
            else -> emptyList()
        }
        children.forEach { child -> containerHolding(child, text)?.let { return it } }
        return null
    }

    @Test
    fun libsignalVerifyPayloadKeepsLongSafetyNumberAsText() {
        val safety = "133650570450770696718167269354915747401852798198450987416227"
        EngineStateBridge.pushUpdate("user.signal_fingerprint", DivVariableValue.Str(safety))
        try {
            val card = DemoCardRepository.toolCardDocument(spec, "libsignal")
                ?: error("missing libsignal tool card")
            var verifyPayload: String? = null
            render(card, DemoCardRepository.toolCardId("libsignal")) { url, payload ->
                if (url.contains("verify_identity")) verifyPayload = payload
            }
            composeRule.onNodeWithTag("div_btn_tool_libsignal_verify_identity").performClick()
            composeRule.waitForIdle()
            val json = Json.parseToJsonElement(verifyPayload ?: error("verify action not dispatched"))
                .jsonObject
            assertEquals(safety, json["safety_number"]?.jsonPrimitive?.contentOrNull)
        } finally {
            EngineStateBridge.clear()
        }
    }

    @Test
    fun kioskCustomerCardRendersCatalogAndOrderLines() {
        val raw = javaClass.classLoader!!
            .getResourceAsStream("demo/apex_kiosk/tools/kiosk_customer.json")!!
            .bufferedReader()
            .use { it.readText() }
        val card = Json.parseToJsonElement(raw).jsonObject
        EngineStateBridge.pushBatch(
            mapOf(
                "user.kiosk_order_id" to DivVariableValue.Str("9"),
                "user.kiosk_status" to DivVariableValue.Str("DRAFT"),
                "user.kiosk_total" to DivVariableValue.Str("2.75"),
                "user.kiosk_item_count" to DivVariableValue.Str("1"),
                "user.kiosk_direction" to DivVariableValue.Str("CUSTOMER_REQUEST"),
                "user.catalog_json" to DivVariableValue.Str(
                    """[{"id":"6","item_name":"Croissant","sku":"P1024","price":2.75,"stock":25}]"""
                ),
                "user.kiosk_lines_json" to DivVariableValue.Str(
                    """[{"id":"11","item_name":"Coffee","sku":"P1012","quantity":1,""" +
                        """"unit_price":"2.50","line_total":"2.50"}]"""
                )
            )
        )
        try {
            render(card, "kiosk_customer") { _, _ -> }
            composeRule.onNodeWithText("Croissant").assertExists()
            composeRule.onNodeWithText("P1024 · $2.75 · 25 in stock").assertExists()
            composeRule.onNodeWithText("Coffee").assertExists()
            composeRule.onNodeWithText("1 × $2.50").assertExists()
            composeRule.onNodeWithText("Line $2.50").assertExists()
        } finally {
            EngineStateBridge.clear()
        }
    }

    @Test
    fun setVariableActionResolvesNestedExpressions() {
        val card = Json.parseToJsonElement(
            """
            {
              "type": "container",
              "items": [
                {
                  "type": "button",
                  "id": "compose_payload",
                  "text": "Compose",
                  "actions": [
                    {
                      "type": "set_variable",
                      "variable_name": "user.composed",
                      "value": {
                        "type": "string",
                        "value": "{\"order\":\"@{user.order_id}\",\"total\":@{user.total}}"
                      }
                    }
                  ]
                }
              ]
            }
            """.trimIndent()
        ).jsonObject
        EngineStateBridge.pushBatch(
            mapOf(
                "user.order_id" to DivVariableValue.Str("42"),
                "user.total" to DivVariableValue.Str("18.5")
            )
        )
        try {
            render(card, "set_variable") { _, _ -> }
            composeRule.onNodeWithTag("div_btn_compose_payload").performClick()
            composeRule.waitForIdle()
            assertEquals(
                DivVariableValue.Str("""{"order":"42","total":18.5}"""),
                EngineStateBridge.state.value["user.composed"]
            )
        } finally {
            EngineStateBridge.clear()
        }
    }

    @Test
    fun kioskTransferBuildsTheQrSetFromThePersistedOrder() {
        val raw = javaClass.classLoader!!
            .getResourceAsStream("demo/apex_kiosk/tools/kiosk_transfer.json")!!
            .bufferedReader()
            .use { it.readText() }
        val card = Json.parseToJsonElement(raw).jsonObject
        EngineStateBridge.pushBatch(
            mapOf(
                "user.kiosk_order_id" to DivVariableValue.Str("9"),
                "user.kiosk_payload_json" to DivVariableValue.Str("""{"schema":"apex.kiosk.transfer/1","total":"2.75"}""")
            )
        )
        val urls = mutableListOf<String>()
        val payloads = mutableListOf<String?>()
        try {
            render(card, "kiosk_transfer") { url, payload ->
                urls.add(url)
                payloads.add(payload)
            }
            composeRule.onNodeWithTag("div_btn_kiosk_build_qr_set").performScrollTo().performClick()
            composeRule.waitForIdle()

            // The card must read the persisted order, pack it, chunk it, then render chunk 0.
            val order = listOf(
                "local-db://op/db.query",
                "local-db://op/doc.pack",
                "tools://codec/json/chunk",
                "tools://code-generate/generate"
            )
            assertEquals(order, urls)

            val chunkPayload = Json.parseToJsonElement(payloads[2]!!).jsonObject
            assertEquals(
                """{"schema":"apex.kiosk.transfer/1","total":"2.75"}""",
                chunkPayload["json"]?.jsonPrimitive?.contentOrNull
            )
            val qrPayload = Json.parseToJsonElement(payloads[3]!!).jsonObject
            assertEquals("QR_CODE", qrPayload["format"]?.jsonPrimitive?.contentOrNull)
            assertEquals(
                "user.qr_img_current",
                qrPayload["output"]?.jsonObject?.get("uri")?.jsonPrimitive?.contentOrNull
            )
        } finally {
            EngineStateBridge.clear()
        }
    }

    @Test
    fun kioskScanNextQrAppendsTheScannedChunk() {
        val raw = javaClass.classLoader!!
            .getResourceAsStream("demo/apex_kiosk/tools/kiosk_transfer.json")!!
            .bufferedReader()
            .use { it.readText() }
        val card = Json.parseToJsonElement(raw).jsonObject
        val chunk = "C1|abcd1234|00|01|deadbeef1234|BB8"
        EngineStateBridge.pushBatch(
            mapOf(
                "user.qr_chunks" to DivVariableValue.ArrayVal(listOf(DivVariableValue.Str(chunk))),
                "user.qr_index" to DivVariableValue.IntVal(0L),
                "user.scanned_chunks" to DivVariableValue.ArrayVal(emptyList())
            )
        )
        try {
            render(card, "kiosk_transfer_scan") { _, _ -> }
            composeRule.onNodeWithTag("div_btn_kiosk_scan_current").performScrollTo().performClick()
            composeRule.waitForIdle()

            assertEquals(
                DivVariableValue.ArrayVal(listOf(DivVariableValue.Str(chunk))),
                EngineStateBridge.state.value["user.scanned_chunks"]
            )
        } finally {
            EngineStateBridge.clear()
        }
    }

    @Test
    fun kioskAssembleClearsStaleResultSoAFailedTransferCannotReadAsMatch() {
        val card = Json.parseToJsonElement(
            javaClass.classLoader!!
                .getResourceAsStream("demo/apex_kiosk/tools/kiosk_transfer.json")!!
                .bufferedReader()
                .use { it.readText() }
        ).jsonObject
        // A previous successful assemble left MATCH plus a reconstructed payload behind.
        EngineStateBridge.pushBatch(
            mapOf(
                "user.scanned_chunks" to DivVariableValue.ArrayVal(
                    listOf(DivVariableValue.Str("C1|corrupt|00|01|deadbeef|!!!"))
                ),
                "user.receipt_checksum_ok" to DivVariableValue.Bool(true),
                "user.receipt_json" to DivVariableValue.Str("""{"schema":"apex.kiosk.transfer/1"}"""),
                "user.receipt_status" to DivVariableValue.Str("COMPLETE")
            )
        )
        val payloads = mutableListOf<String?>()
        try {
            render(card, "kiosk_transfer_assemble") { _, payload -> payloads.add(payload) }
            composeRule.onNodeWithTag("div_btn_kiosk_assemble_scanned").performScrollTo().performClick()
            composeRule.waitForIdle()

            val state = EngineStateBridge.state.value
            assertEquals(DivVariableValue.Bool(false), state["user.receipt_checksum_ok"])
            assertEquals(DivVariableValue.Str(""), state["user.receipt_json"])

            // The failure reason must reach the card, otherwise a failed assemble shows no cause.
            val assemble = Json.parseToJsonElement(
                payloads.first { it?.contains("chunks") == true }!!
            ).jsonObject
            assertEquals(
                "user.receipt_error",
                assemble["output"]?.jsonObject?.get("error")?.jsonPrimitive?.contentOrNull
            )
        } finally {
            EngineStateBridge.clear()
        }
    }

    @Test
    fun kioskAssembleScannedPassesChunksAsAJsonArray() {
        val raw = javaClass.classLoader!!
            .getResourceAsStream("demo/apex_kiosk/tools/kiosk_transfer.json")!!
            .bufferedReader()
            .use { it.readText() }
        val card = Json.parseToJsonElement(raw).jsonObject
        val chunk = "C1|abcd1234|00|01|deadbeef1234|BB8"
        EngineStateBridge.pushBatch(
            mapOf("user.scanned_chunks" to DivVariableValue.ArrayVal(listOf(DivVariableValue.Str(chunk))))
        )
        val payloads = mutableListOf<String?>()
        try {
            render(card, "kiosk_transfer_assemble") { _, payload -> payloads.add(payload) }
            composeRule.onNodeWithTag("div_btn_kiosk_assemble_scanned").performScrollTo().performClick()
            composeRule.waitForIdle()

            val assemblePayload = payloads.firstOrNull { it?.contains("chunks") == true }
            assertTrue("assemble action did not dispatch", assemblePayload != null)
            val chunks = Json.parseToJsonElement(assemblePayload!!).jsonObject["chunks"] as JsonArray
            assertEquals(1, chunks.size)
            assertEquals(chunk, chunks[0].jsonPrimitive.content)
        } finally {
            EngineStateBridge.clear()
        }
    }

    @Test
    fun divTimerStartsAndMutatesVariables() {
        val card = Json.parseToJsonElement(
            """
            {
              "type": "container",
              "items": [
                {
                  "type": "button",
                  "id": "start_timer",
                  "text": "Start",
                  "actions": [{ "url": "div-action://timer?id=t&action=start" }]
                }
              ],
              "_timers": [
                {
                  "id": "t",
                  "tick_interval": 50,
                  "tick_actions": [
                    {
                      "type": "set_variable",
                      "variable_name": "user.ticks",
                      "value": { "type": "integer", "value": "@{user.ticks + 1}" }
                    }
                  ]
                }
              ]
            }
            """.trimIndent()
        ).jsonObject
        EngineStateBridge.pushBatch(mapOf("user.ticks" to DivVariableValue.IntVal(0)))
        try {
            render(card, "timer") { _, _ -> }
            composeRule.onNodeWithTag("div_btn_start_timer").performClick()
            composeRule.waitUntil(2_000) {
                ((EngineStateBridge.state.value["user.ticks"] as? DivVariableValue.IntVal)?.value ?: 0) > 0
            }
        } finally {
            EngineStateBridge.clear()
        }
    }

    @Test
    fun visibilityGoneRemovesTheNodeFromTheLayout() {
        // Regression: the renderer ignored `visibility`, so a card could not hide an empty QR
        // preview or retire the submit/quantity controls of an already-submitted order.
        val card = Json.parseToJsonElement(
            """
            {
              "type": "container",
              "items": [
                { "type": "text", "text": "Always visible" },
                { "type": "text", "text": "Statically hidden", "visibility": "gone" },
                {
                  "type": "text",
                  "text": "Conditionally hidden",
                  "visibility": "@{if(user.hide_flag == 'yes', 'gone', 'visible')}"
                }
              ]
            }
            """.trimIndent()
        ).jsonObject
        EngineStateBridge.pushBatch(mapOf("user.hide_flag" to DivVariableValue.Str("yes")))
        try {
            render(card, "visibility_probe") { _, _ -> }
            composeRule.onNodeWithText("Always visible").assertExists()
            composeRule.onNodeWithText("Statically hidden").assertDoesNotExist()
            composeRule.onNodeWithText("Conditionally hidden").assertDoesNotExist()
        } finally {
            EngineStateBridge.clear()
        }
    }

    @Test
    fun imageNodeKeepsContentDescriptionAsAccessibilityLabelOnly() {
        // Regression: the renderer painted content_description as a caption across the bottom
        // of the bitmap, which covered the kiosk receipt QR code.
        val card = Json.parseToJsonElement(
            """
            {
              "type": "container",
              "items": [
                {
                  "type": "image",
                  "image_url": "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==",
                  "height": { "type": "fixed", "value": 120 },
                  "content_description": "Receipt QR code"
                }
              ]
            }
            """.trimIndent()
        ).jsonObject
        render(card, "image_description") { _, _ -> }
        composeRule.onNodeWithContentDescription("Receipt QR code").assertExists()
        composeRule.onNodeWithText("Receipt QR code").assertDoesNotExist()
    }

    @Test
    fun everyToolCardRendersWithoutRenderError() {
        val cards = DemoCardRepository.toolCards(spec)
        assertTrue("no tool cards loaded from resources", cards.isNotEmpty())

        composeRule.setContent {
            MyApplicationTheme {
                DivKit(dependencies = dependencies) {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        cards.forEachIndexed { index, card ->
                            DivView(
                                cardId = "render_probe_$index",
                                jsonData = DemoCardRepository.encode(card),
                                onAction = { _, _ -> }
                            )
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()

        cards.indices.forEach { index ->
            composeRule.onNodeWithTag("divkit_root_render_probe_$index").assertExists()
        }
    }
}
