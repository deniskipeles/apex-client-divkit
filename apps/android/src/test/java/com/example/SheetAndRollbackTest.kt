package com.example

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.example.enginehost.SduiEngineViewModel
import engine.db.CachedSchemaVersion
import engine.navigation.CardRegistry
import engine.navigation.NavOp
import engine.navigation.NavigationUrlParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The `sheet://` scheme was parsed and then swallowed: no host ever rendered the presented card,
 * so a "Sheet showcase" button would have been a dead control. These tests pin the whole path —
 * URL, registry, view-model state — and the schema-rollback ledger that sits next to it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SheetAndRollbackTest {

    @After
    fun tearDown() {
        CardRegistry.clear()
    }

    private fun resource(path: String): String =
        javaClass.classLoader!!.getResourceAsStream(path)!!.bufferedReader().use { it.readText() }

    @Test
    fun sheetUrlsParseToPresentationAndDismissal() {
        assertEquals(
            NavOp.SheetPresent("sheet_showcase"),
            NavigationUrlParser.parse("sheet://present?card_id=sheet_showcase")
        )
        assertEquals(NavOp.SheetDismiss, NavigationUrlParser.parse("sheet://dismiss"))
        assertNull(NavigationUrlParser.parse("sheet://present"))
    }

    @Test
    fun showcaseWorkspaceLinksTheSheetCardFromTheHub() {
        val hub = Json.parseToJsonElement(resource("demo/acme_pos/card.json")).jsonObject
        val urls = mutableListOf<String>()
        fun walk(node: Any?) {
            when (node) {
                is JsonObject -> {
                    (node["action"] as? JsonObject)?.get("url")?.jsonPrimitive?.contentOrNull
                        ?.let(urls::add)
                    (node["actions"] as? JsonArray)?.forEach { action ->
                        (action as? JsonObject)?.get("url")?.jsonPrimitive?.contentOrNull
                            ?.let(urls::add)
                    }
                    node.forEach { (_, value) -> walk(value) }
                }
                is JsonArray -> node.forEach { walk(it) }
            }
        }
        walk(hub)
        assertTrue(
            "the workspace hub must offer the sheet showcase: $urls",
            urls.contains("sheet://present?card_id=sheet_showcase")
        )
    }

    @Test
    fun showcaseCardIsRegisteredAndClosesItself() {
        val card = Json.parseToJsonElement(
            resource("demo/acme_pos/tools/sheet_showcase.json")
        ).jsonObject
        val raw = card.toString()
        assertTrue("showcase card must dismiss through sheet://dismiss", raw.contains("sheet://dismiss"))

        val manifest = Json.parseToJsonElement(resource("demo/acme_pos/workspace.json")).jsonObject
        val order = manifest["cards"]!!.jsonObject["order"] as JsonArray
        assertTrue(
            "sheet_showcase must be in cards.order so CardRegistry holds it",
            order.map { it.jsonPrimitive.content }.contains("sheet_showcase")
        )

        val catalog = Json.parseToJsonElement(resource("demo/acme_pos/tool_catalog.json")).jsonObject
        val entries = catalog["entries"] as JsonArray
        assertTrue(
            "the sheet showcase must be reachable from the catalog grid",
            entries.any {
                (it as JsonObject)["file"]?.jsonPrimitive?.contentOrNull == "sheet_showcase"
            }
        )
    }

    @Test
    fun presentAndDismissDriveTheSheetState() {
        CardRegistry.register("demo_sheet", JsonObject(mapOf("type" to JsonPrimitive("container"))))
        val viewModel = SduiEngineViewModel(ApplicationProvider.getApplicationContext<Context>())

        assertNull(viewModel.uiState.value.presentedSheetCardId)
        assertTrue(viewModel.presentSheet("demo_sheet"))
        assertEquals("demo_sheet", viewModel.uiState.value.presentedSheetCardId)

        viewModel.dismissSheet()
        assertNull(viewModel.uiState.value.presentedSheetCardId)
    }

    @Test
    fun unknownSheetCardFailsLoudlyInsteadOfPresenting() {
        val viewModel = SduiEngineViewModel(ApplicationProvider.getApplicationContext<Context>())

        assertFalse(viewModel.presentSheet("not_a_card"))
        assertNull(viewModel.uiState.value.presentedSheetCardId)
        assertTrue(
            viewModel.uiState.value.statusBannerMessage.orEmpty().contains("not registered")
        )
    }

    @Test
    fun rollbackActivatesTheOlderCachedVersion() {
        val viewModel = SduiEngineViewModel(ApplicationProvider.getApplicationContext<Context>())
        val tenantId = viewModel.uiState.value.activeWorkspace.tenantId

        kotlinx.coroutines.runBlocking {
            viewModel.workspaceManager.useDatabase(tenantId) { db ->
                db.workspaceQueries.cacheAndActivateSchemaVersion(
                    schemaVersion = "1.0",
                    jti = "jti-old",
                    signedEnvelope = "{}",
                    verifiedPayload = """{"card":{"log_id":"v10"}}""",
                    receivedAt = 1_000L,
                    summary = "Initial schema"
                )
                db.workspaceQueries.cacheAndActivateSchemaVersion(
                    schemaVersion = "1.1",
                    jti = "jti-new",
                    signedEnvelope = "{}",
                    verifiedPayload = """{"card":{"log_id":"v11"}}""",
                    receivedAt = 2_000L,
                    summary = "Updated schema"
                )
            }
        }
        val older = kotlinx.coroutines.runBlocking {
            viewModel.workspaceManager.useDatabase(tenantId) { db ->
                db.workspaceQueries.selectAllSchemaVersions()
            }
        }.first { it.schemaVersion == "1.0" }

        viewModel.rollbackToSchemaVersion(older)
        val mainLooper = shadowOf(Looper.getMainLooper())
        repeat(200) {
            mainLooper.idle()
            if (viewModel.uiState.value.activeSchemaVersion?.versionId == older.versionId) return@repeat
            Thread.sleep(25)
        }
        mainLooper.idle()

        val state = viewModel.uiState.value
        assertEquals("1.0", state.activeSchemaVersion?.schemaVersion)
        assertTrue(
            "the restored payload must become the live card",
            state.verifiedDivKitJson.contains("v10")
        )
        assertEquals("", state.rollbackError)
        val persisted = kotlinx.coroutines.runBlocking {
            viewModel.workspaceManager.useDatabase(tenantId) { db ->
                db.workspaceQueries.selectActiveSchemaVersion()
            }
        }
        assertEquals("the rollback must survive a relaunch", "1.0", persisted?.schemaVersion)
    }

    @Test
    fun rollbackToAMissingVersionFailsClosed() {
        val viewModel = SduiEngineViewModel(ApplicationProvider.getApplicationContext<Context>())
        val before = viewModel.uiState.value.verifiedDivKitJson
        val bogus = CachedSchemaVersion(
            versionId = -1L,
            schemaVersion = "9.9",
            jti = "missing",
            signedEnvelope = "{}",
            verifiedPayload = """{"card":{"log_id":"bogus"}}""",
            receivedAt = 0L,
            isActive = false,
            summary = "does not exist"
        )

        viewModel.rollbackToSchemaVersion(bogus)
        val mainLooper = shadowOf(Looper.getMainLooper())
        repeat(200) {
            mainLooper.idle()
            if (!viewModel.uiState.value.isRollingBack &&
                viewModel.uiState.value.rollbackError.isNotBlank()
            ) {
                return@repeat
            }
            Thread.sleep(25)
        }
        mainLooper.idle()
        val state = viewModel.uiState.value

        assertTrue(
            "rollback must report why it failed: ${state.rollbackError}",
            state.rollbackError.contains("9.9")
        )
        assertEquals("a failed rollback must not swap the live schema", before, state.verifiedDivKitJson)
        assertNull(state.activeSchemaVersion)
    }
}
