package com.example

import com.example.enginehost.SduiEngineUiState
import com.example.enginehost.SduiOnFlyBuilder
import com.example.enginehost.WorkspaceProvisioner
import com.example.enginehost.demo.DemoCardRepository
import engine.workspace.ClasspathSource
import engine.workspace.MapSource
import engine.workspace.WorkspaceBinarySource
import engine.workspace.WorkspaceBundleCodec
import engine.workspace.WorkspaceCipher
import engine.workspace.WorkspaceRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cards are read from the workspace **bundle**, not from the plaintext `demo/` folder.
 *
 * A production APK ships no plaintext card files under `demo/<id>/` at all: the workspace arrives
 * as `assets/demo/<id>/workspace.bin` (AES-256-GCM). When the card layer only knew how to read
 * classpath resources it rendered `{"card":{"log_id":"empty"}}` — a card with no states, which is
 * a blank white screen — for every workspace in every prod build.
 */
class WorkspaceCardBundleSourceTest {

    private val key = ByteArray(32) { (it + 1).toByte() }
    private val marker = "BUNDLE_ONLY_MARKER"

    @After
    fun tearDown() = WorkspaceRepository.clearPackagedCache()

    private fun workspaceFiles(id: String = "bundle_ws"): Map<String, String> = mapOf(
        "demo/$id/workspace.json" to """
            {
              "format": "apex.workspace/1",
              "id": "$id",
              "title": "Bundle Workspace",
              "subtitle": "cards come from the encrypted bundle",
              "terminal": "TERM-9",
              "accent": "#0F766E",
              "mode": "HYBRID",
              "capabilities": ["haptics"],
              "cards": { "dir": "tools", "order": ["scanner", "codec"] }
            }
        """.trimIndent(),
        "demo/$id/card.json" to """
            {
              "card": {
                "log_id": "hub_${'$'}{tenantId}",
                "variables": [
                  { "name": "user.title", "type": "string", "value": "${'$'}{displayName} hub" }
                ],
                "states": [
                  {
                    "state_id": 0,
                    "div": {
                      "type": "container",
                      "orientation": "vertical",
                      "tool_catalog_after": 0,
                      "items": [
                        { "type": "text", "text": "$marker ${'$'}{terminalCode}" }
                      ]
                    }
                  }
                ]
              }
            }
        """.trimIndent(),
        "demo/$id/tool_catalog.json" to """
            {
              "title": "Workflows",
              "subtitle": "bundle catalog",
              "entries": [ { "file": "scanner", "label": "Scanner", "icon": "scanner" } ]
            }
        """.trimIndent(),
        "demo/$id/tools/scanner.json" to """
            { "type": "container", "items": [ { "type": "text", "text": "scanner $marker" } ] }
        """.trimIndent(),
        "demo/$id/tools/codec.json" to """
            { "type": "container", "items": [ { "type": "text", "text": "codec" } ] }
        """.trimIndent()
    )

    private fun encryptedBundleFiles(id: String = "bundle_ws"): Pair<Map<String, String>, ByteArray> {
        val files = workspaceFiles(id)
        val bundle = WorkspaceBundleCodec.fromDirectory(MapSource(files), "demo/$id")
        val blob = WorkspaceCipher.encrypt(
            WorkspaceBundleCodec.encode(bundle).toByteArray(),
            key
        )
        // Prod ships only the binary: no card.json, no tools/.
        return mapOf("demo/$id/workspace.bin" to "") to blob
    }

    @Test
    fun prodBundleRendersTheHubAndSplicesTheCatalog() {
        val (sourceFiles, blob) = encryptedBundleFiles()
        val binary = object : WorkspaceBinarySource {
            override fun readBytes(path: String): ByteArray? =
                if (path == "demo/bundle_ws/workspace.bin") blob else null
        }

        val bundle = WorkspaceRepository.packagedBundle(
            id = "bundle_ws",
            source = MapSource(sourceFiles),
            mode = WorkspaceRepository.MODE_PROD,
            workspaceKey = key,
            binarySource = binary
        )
        assertNotNull("the encrypted bundle must load without plaintext resources", bundle)

        val spec = WorkspaceProvisioner.run { bundle!!.toSpec() }
        val hub = DemoCardRepository.hubCard(spec, bundle)
        assertNotNull("the hub card must come from the bundle", hub)
        val hubText = hub.toString()
        assertTrue("the bundle document must be rendered", hubText.contains(marker))
        assertTrue("the catalog is spliced into the hub", hubText.contains("nav_scanner"))
        assertFalse("placeholders must be substituted", hubText.contains("\${"))
        assertTrue("displayName placeholder is filled", hubText.contains("Bundle Workspace"))
    }

    @Test
    fun prodBundleServesEveryToolCardAndTheStack() {
        val (sourceFiles, blob) = encryptedBundleFiles()
        val binary = object : WorkspaceBinarySource {
            override fun readBytes(path: String): ByteArray? =
                if (path == "demo/bundle_ws/workspace.bin") blob else null
        }
        val bundle = WorkspaceRepository.packagedBundle(
            id = "bundle_ws",
            source = MapSource(sourceFiles),
            mode = WorkspaceRepository.MODE_PROD,
            workspaceKey = key,
            binarySource = binary
        )!!
        val spec = WorkspaceProvisioner.run { bundle.toSpec() }

        val scanner = DemoCardRepository.toolCard(spec, "scanner", bundle)
        assertNotNull(scanner)
        assertTrue(scanner.toString().contains(marker))

        val stack = DemoCardRepository.cardStack(spec, bundle)
        assertEquals(
            listOf("hub_bundle_ws", "tool_scanner", "tool_codec"),
            stack.map { it.first }
        )
        val document = DemoCardRepository.toolCardDocument(spec, "scanner", bundle)
        assertNotNull(document)
        assertTrue(
            "a pushed card carries the hub's user.* declarations",
            document.toString().contains("user.title")
        )
    }

    @Test
    fun aMissingBundleStillProducesAVisibleCard() {
        val orphan = WorkspaceProvisioner.defaultWorkspaces.first().copy(
            tenantId = "no_such_workspace",
            demoRoot = "demo/no_such_workspace"
        )
        assertNull(DemoCardRepository.hubCard(orphan))

        val document = Json.parseToJsonElement(SduiOnFlyBuilder.buildShowcaseCard(orphan)).jsonObject
        val states = document["card"]?.jsonObject?.get("states")?.jsonArray
        assertNotNull("a blank card must never be rendered instead", states)
        val text = states!!.toString()
        assertTrue(text.contains("Workspace card unavailable"))
        assertTrue(text.contains("no_such_workspace"))
        assertFalse("the old empty fallback must be gone", text.contains("\"log_id\":\"empty\""))
    }

    @Test
    fun theDevBuildStillResolvesCardsForEveryPackagedWorkspace() {
        // Dev resolves through the same bundle path (plaintext folders on the classpath), so this
        // pins that the refactor did not break the channel that used to work.
        WorkspaceProvisioner.defaultWorkspaces.forEach { spec ->
            val hub = DemoCardRepository.hubCard(spec)
            assertNotNull("no hub card for ${spec.tenantId}", hub)
            val stack = DemoCardRepository.cardStack(spec)
            assertTrue("no cards registered for ${spec.tenantId}", stack.size > 1)
            assertEquals(DemoCardRepository.hubCardId(spec), stack.first().first)
            val hubText = hub.toString()
            assertFalse(
                "unsubstituted placeholder in ${spec.tenantId}",
                hubText.contains("\${")
            )
        }
    }

    @Test
    fun packagedBundleIsCachedPerIdAndMode() {
        val (sourceFiles, blob) = encryptedBundleFiles()
        val binary = object : WorkspaceBinarySource {
            var reads = 0
            override fun readBytes(path: String): ByteArray? {
                reads++
                return if (path == "demo/bundle_ws/workspace.bin") blob else null
            }
        }
        repeat(3) {
            WorkspaceRepository.packagedBundle(
                id = "bundle_ws",
                source = MapSource(sourceFiles),
                mode = WorkspaceRepository.MODE_PROD,
                workspaceKey = key,
                binarySource = binary
            )
        }
        assertEquals("the bundle must be read once, not once per card", 1, binary.reads)
    }

    @Test
    fun aWrongKeyFailsClosedInsteadOfRenderingSomething() {
        val (sourceFiles, _) = encryptedBundleFiles()
        val wrongKey = ByteArray(32) { 7 }
        val binary = object : WorkspaceBinarySource {
            override fun readBytes(path: String): ByteArray? {
                val bundle = WorkspaceBundleCodec.fromDirectory(
                    MapSource(workspaceFiles()),
                    "demo/bundle_ws"
                )
                return WorkspaceCipher.encrypt(
                    WorkspaceBundleCodec.encode(bundle).toByteArray(),
                    key
                )
            }
        }
        val bundle = WorkspaceRepository.packagedBundle(
            id = "bundle_ws",
            source = MapSource(sourceFiles),
            mode = WorkspaceRepository.MODE_PROD,
            workspaceKey = wrongKey,
            binarySource = binary
        )
        assertNull("an undecryptable bundle must not yield a card source", bundle)
    }


    @Test
    fun anEmptyWorkspaceListFallsBackToAPlaceholderInsteadOfCrashing() {
        // `initialWorkspaces.first()` threw NoSuchElementException before the first frame whenever
        // no bundle could be read (wrong/stale encryption key, missing asset), so the app died
        // instead of showing the launcher's empty state.
        assertTrue(WorkspaceProvisioner.firstOrEmpty(emptyList()).tenantId.isEmpty())
        val state = SduiEngineUiState(workspaces = emptyList())
        assertEquals(
            WorkspaceProvisioner.emptyWorkspace,
            state.activeWorkspace
        )
        assertTrue(state.workspaces.isEmpty())
    }

    @Test
    fun aFailedBundleScanReportsWhy() {
        val report = WorkspaceRepository.loadAll(
            source = MapSource(mapOf("demo/workspaces.json" to """{"workspaces":["ghost_ws"]}"""))
        )
        assertTrue(report.bundles.isEmpty())
        assertEquals("ghost_ws", report.failures.single().id)
        assertTrue(report.failures.single().reason.isNotBlank())
    }

    private fun JsonObject.firstItemText(): String =
        this["card"]!!.jsonObject["states"]!!.jsonArray[0]
            .jsonObject["div"]!!.jsonObject["items"]!!.jsonArray[0]
            .jsonObject["text"]!!.jsonPrimitive.content
}
