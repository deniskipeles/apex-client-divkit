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
