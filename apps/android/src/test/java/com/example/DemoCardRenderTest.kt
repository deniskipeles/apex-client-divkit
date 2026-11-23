package com.example

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
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
                DivKit(dependencies = dependencies) {
                    DivView(
                        cardId = cardId,
                        jsonData = DemoCardRepository.encode(card),
                        onAction = onAction
                    )
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

        ids.forEach { id -> composeRule.onNodeWithTag("div_btn_$id").performClick() }
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
            composeRule.onNodeWithText("Decrypted: $plaintext").assertExists()
        } finally {
            EngineStateBridge.clear()
        }
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
            .getResourceAsStream("demo/apex-kiosk/tools/kiosk_customer.json")!!
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
                    """[{"id":"11","item_name":"Coffee","sku":"P1012","quantity":1,"line_total":2.5}]"""
                )
            )
        )
        try {
            render(card, "kiosk_customer") { _, _ -> }
            composeRule.onNodeWithText("Croissant").assertExists()
            composeRule.onNodeWithText("SKU P1024 • $2.75 • stock 25").assertExists()
            composeRule.onNodeWithText("Coffee").assertExists()
            composeRule.onNodeWithText("SKU P1012 • qty 1 • $2.5").assertExists()
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
    fun kioskTransferComposesTransferJsonFromLiveVariables() {
        val raw = javaClass.classLoader!!
            .getResourceAsStream("demo/apex-kiosk/tools/kiosk_transfer.json")!!
            .bufferedReader()
            .use { it.readText() }
        val card = Json.parseToJsonElement(raw).jsonObject
        EngineStateBridge.pushBatch(
            mapOf(
                "user.kiosk_direction" to DivVariableValue.Str("CUSTOMER_REQUEST"),
                "user.kiosk_order_id" to DivVariableValue.Str("9"),
                "user.kiosk_status" to DivVariableValue.Str("DRAFT"),
                "user.kiosk_total" to DivVariableValue.Str("2.75"),
                "user.kiosk_item_count" to DivVariableValue.Str("1"),
                "user.kiosk_lines_json" to DivVariableValue.Str(
                    """[{"sku":"P1024","quantity":1,"line_total":2.75}]"""
                )
            )
        )
        try {
            render(card, "kiosk_transfer") { _, _ -> }
            composeRule.onNodeWithTag("div_btn_create_qr_set").performClick()
            composeRule.waitForIdle()

            val composed = (EngineStateBridge.state.value["user.qr_json"] as DivVariableValue.Str).value
            val parsed = Json.parseToJsonElement(composed).jsonObject
            assertEquals("CUSTOMER_REQUEST", parsed["direction"]?.jsonPrimitive?.contentOrNull)
            assertEquals("9", parsed["order_id"]?.jsonPrimitive?.contentOrNull)
            assertEquals(2.75, parsed["total"]?.jsonPrimitive?.contentOrNull?.toDouble())
            assertEquals(1, parsed["lines"]?.let { it as JsonArray }?.size)
        } finally {
            EngineStateBridge.clear()
        }
    }

    @Test
    fun kioskScanNextQrAppendsTheScannedChunk() {
        val raw = javaClass.classLoader!!
            .getResourceAsStream("demo/apex-kiosk/tools/kiosk_transfer.json")!!
            .bufferedReader()
            .use { it.readText() }
        val card = Json.parseToJsonElement(raw).jsonObject
        val chunk = "C1|abcd1234|00|01|deadbeef1234|BB8"
        EngineStateBridge.pushBatch(
            mapOf(
                "user.scanned_code" to DivVariableValue.Str(chunk),
                "user.scanned_chunks" to DivVariableValue.ArrayVal(emptyList())
            )
        )
        try {
            render(card, "kiosk_transfer_scan") { _, _ -> }
            composeRule.onNodeWithTag("div_btn_scan_next_qr").performClick()
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
    fun kioskAssembleScannedPassesChunksAsAJsonArray() {
        val raw = javaClass.classLoader!!
            .getResourceAsStream("demo/apex-kiosk/tools/kiosk_transfer.json")!!
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
            composeRule.onNodeWithTag("div_btn_assemble_scanned").performClick()
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
