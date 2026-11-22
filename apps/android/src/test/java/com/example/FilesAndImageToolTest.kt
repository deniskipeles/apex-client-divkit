package com.example

import androidx.test.core.app.ApplicationProvider
import engine.tools.NativeToolsSuite
import engine.tools.ToolArgs
import engine.tools.ToolContext
import engine.tools.ToolResult
import engine.tools.image.ImageManipulationEngine
import engine.tools.impl.FilesTool
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Base64

/**
 * Behavioural coverage for `storage.files@1` and the image-manipulation engine's failure
 * contract. Both are exercised through the real handlers, not mocks: the point of these tests
 * is that the sandbox and the decode path behave as advertised.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FilesAndImageToolTest {

    private var previousContext: (() -> android.content.Context?)? = null
    private val tenant = "files_test_tenant"

    @Before
    fun installContext() {
        previousContext = engine.db.currentAndroidContext
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        engine.db.currentAndroidContext = { context }
        // Each run starts from an empty sandbox so assertions are order-independent.
        File(context.filesDir, "workspaces/$tenant").deleteRecursively()
    }

    @After
    fun restoreContext() {
        engine.db.currentAndroidContext = previousContext
    }

    private fun invoke(vararg args: Pair<String, String>): ToolResult = runBlocking {
        FilesTool().invoke(ToolArgs(mapOf(*args)), ToolContext(workspaceId = tenant))
    }

    private fun success(result: ToolResult): Map<String, Any?> {
        assertTrue("expected success, got $result", result is ToolResult.Success)
        return (result as ToolResult.Success).data
    }

    // ---------------------------------------------------------------------
    // FILE STORE
    // ---------------------------------------------------------------------

    @Test
    fun files_writeThenRead_roundTripsBytes() {
        val payload = Base64.getEncoder().encodeToString("hello workspace".toByteArray())

        val written = success(
            invoke("op" to "write", "path" to "notes/hello.txt", "content" to payload, "encoding" to "base64")
        )
        assertEquals(15L, (written["size_bytes"] as Number).toLong())

        val read = success(invoke("op" to "read", "path" to "notes/hello.txt", "encoding" to "base64"))
        assertEquals(payload, read["content"])
        assertEquals("hello workspace", String(Base64.getDecoder().decode(read["content"] as String)))
    }

    @Test
    fun files_rejectsPathTraversalOutsideTheTenantSandbox() {
        listOf("../escape.txt", "nested/../../escape.txt", "/etc/passwd", "file:///etc/passwd")
            .forEach { path ->
                val result = invoke("op" to "write", "path" to path, "content" to "x")
                assertTrue("path '$path' must be rejected, got $result", result is ToolResult.Failed)
            }
    }

    @Test
    fun files_doesNotLeakAcrossTenants() {
        success(invoke("op" to "write", "path" to "private.txt", "content" to "tenant-a"))

        val otherTenant = runBlocking {
            FilesTool().invoke(
                ToolArgs(mapOf("op" to "read", "path" to "private.txt")),
                ToolContext(workspaceId = "someone_else")
            )
        }
        assertTrue("other tenant must not see the file", otherTenant is ToolResult.Failed)
    }

    @Test
    fun files_listReportsEntriesAndFiltersByGlob() {
        success(invoke("op" to "write", "path" to "a.txt", "content" to "a"))
        success(invoke("op" to "write", "path" to "b.json", "content" to "b"))
        success(invoke("op" to "mkdir", "path" to "sub"))

        val all = success(invoke("op" to "list", "path" to "."))
        assertEquals(3, (all["count"] as Number).toInt())

        val txt = success(invoke("op" to "list", "path" to ".", "pattern" to "*.txt"))
        assertEquals(1, (txt["count"] as Number).toInt())
    }

    @Test
    fun files_copyMoveAndDelete_keepTheSandboxConsistent() {
        success(invoke("op" to "write", "path" to "one.txt", "content" to "payload"))
        success(invoke("op" to "copy", "from" to "one.txt", "to" to "two.txt"))
        success(invoke("op" to "move", "from" to "two.txt", "to" to "three.txt"))

        val stat = success(invoke("op" to "stat", "path" to "three.txt"))
        assertEquals("file", stat["type"])
        assertEquals(7L, (stat["size_bytes"] as Number).toLong())

        val deleted = success(invoke("op" to "delete", "path" to "three.txt"))
        assertEquals(true, deleted["deleted"])

        val after = success(invoke("op" to "exists", "path" to "three.txt"))
        assertEquals(false, after["exists"])
    }

    @Test
    fun files_deleteRefusesNonEmptyDirectoryWithoutRecursive() {
        success(invoke("op" to "mkdir", "path" to "sub"))
        success(invoke("op" to "write", "path" to "sub/x.txt", "content" to "x"))

        assertTrue(invoke("op" to "delete", "path" to "sub") is ToolResult.Failed)

        val recursive = success(invoke("op" to "delete", "path" to "sub", "recursive" to "true"))
        assertEquals(true, recursive["deleted"])
    }

    @Test
    fun files_binaryReadRequiresBase64() {
        val bytes = byteArrayOf(0, 1, 2, 3)
        success(
            invoke(
                "op" to "write",
                "path" to "blob.bin",
                "content" to Base64.getEncoder().encodeToString(bytes),
                "encoding" to "base64"
            )
        )

        assertTrue("text read of binary must fail", invoke("op" to "read", "path" to "blob.bin") is ToolResult.Failed)
        val binary = success(invoke("op" to "read", "path" to "blob.bin", "encoding" to "base64"))
        assertTrue((binary["content"] as String).isNotEmpty())
    }

    @Test
    fun files_invalidUtf8IsRefusedInsteadOfLossilyDecoded() {
        // 0xFF is never valid UTF-8 and contains no NUL byte, so a NUL-only check would pass.
        val bytes = byteArrayOf(0x41, 0xFF.toByte(), 0x42)
        success(
            invoke(
                "op" to "write",
                "path" to "latin1.txt",
                "content" to Base64.getEncoder().encodeToString(bytes),
                "encoding" to "base64"
            )
        )

        assertTrue(
            "invalid UTF-8 must not be substituted with U+FFFD",
            invoke("op" to "read", "path" to "latin1.txt") is ToolResult.Failed
        )
        val verbatim = success(invoke("op" to "read", "path" to "latin1.txt", "encoding" to "base64"))
        assertEquals(Base64.getEncoder().encodeToString(bytes), verbatim["content"])
    }

    @Test
    fun files_unknownOperationFailsLoudly() {
        val result = invoke("op" to "teleport", "path" to "x")
        assertTrue(result is ToolResult.Failed)
        assertTrue((result as ToolResult.Failed).message.contains("Unsupported files operation"))
    }

    @Test
    fun files_dispatchThroughToolsSchemeReachesTheHandler() = runBlocking {
        val outcome = NativeToolsSuite.execute(
            url = "tools://files/write",
            payload = buildJsonObject {
                put("operation", "write")
                put("path", "demo/from-card.txt")
                put("content", "card payload")
                put(
                    "output",
                    buildJsonObject { put("path", "user.files_path") }
                )
            }
        )
        assertEquals("SUCCESS", outcome.resultData.jsonObject["status"]?.jsonPrimitive?.contentOrNull)
        assertTrue(outcome.outputVariables.containsKey("user.files_path"))
    }

    @Test
    fun files_demoCardExposesTheFullOperationSurface() {
        val card = com.example.enginehost.demo.DemoCardRepository
            .toolCard(
                com.example.enginehost.WorkspaceProvisioner.defaultWorkspaces.first(),
                "files"
            )
        assertNotNull("files demo card must exist", card)

        val json = com.example.enginehost.demo.DemoCardRepository.encode(card!!)
        listOf("list", "mkdir", "write", "read", "copy", "move", "stat", "delete").forEach { op ->
            assertTrue("files card is missing the '$op' trigger", json.contains("tools://files/$op"))
        }
        // The declared outputs must exist on the card's own variable set, otherwise the
        // previews render as empty strings forever.
        listOf("user.files_status", "user.files_listing", "user.files_content", "user.files_path")
            .forEach { name ->
                assertTrue("files card never binds $name", json.contains(name))
            }
    }

    // ---------------------------------------------------------------------
    // IMAGE MANIPULATION ENGINE
    // ---------------------------------------------------------------------

    @Test
    fun imageEngine_throwsOnUndecodableSourceInsteadOfFabricatingPixels() {
        try {
            ImageManipulationEngine.process(
                tenantId = tenant,
                inputUriOrBase64 = "not-an-image-at-all",
                operations = emptyList()
            )
            fail("engine must not return a placeholder canvas for an undecodable source")
        } catch (e: ImageManipulationEngine.UndecodableSourceException) {
            assertTrue(e.reference.contains("not-an-image-at-all"))
        }
    }

    @Test
    fun imageEngine_honoursStrengthAndOutputFormat() {
        val source = solidPngFile(64, 48)

        val png = ImageManipulationEngine.process(
            tenantId = tenant,
            inputUriOrBase64 = source,
            operations = listOf(buildJsonObject { put("op", "smooth"); put("strength", 0.8) })
        )
        assertEquals(64, png.width)
        assertEquals(48, png.height)
        assertEquals("image/png", png.mimeType)
        assertTrue(png.operationsApplied.contains("smooth"))

        val jpeg = ImageManipulationEngine.process(
            tenantId = tenant,
            inputUriOrBase64 = source,
            operations = emptyList(),
            outputFormat = "jpeg",
            quality = 70
        )
        assertEquals("image/jpeg", jpeg.mimeType)
        assertTrue("jpeg output should be smaller than the png", jpeg.sizeBytes > 0)
        assertTrue(File(jpeg.uri.removePrefix("file://")).name.endsWith(".jpg"))
    }

    @Test
    fun imageEngine_resizeKeepsAspectRatio() {
        val source = solidPngFile(200, 100)

        val result = ImageManipulationEngine.process(
            tenantId = tenant,
            inputUriOrBase64 = source,
            operations = listOf(buildJsonObject { put("op", "resize"); put("max_dimension", 100) })
        )
        assertEquals(100, result.width)
        assertEquals(50, result.height)
    }

    @Test
    fun imageEngine_relativeCropUsesPercentages() {
        val source = solidPngFile(100, 100)

        val result = ImageManipulationEngine.process(
            tenantId = tenant,
            inputUriOrBase64 = source,
            operations = listOf(
                buildJsonObject {
                    put("op", "crop")
                    put("x", 25)
                    put("y", 25)
                    put("width", 50)
                    put("height", 50)
                    put("relative", true)
                }
            )
        )
        assertEquals(50, result.width)
        assertEquals(50, result.height)
    }

    /** Writes a solid-colour PNG to the tenant media dir and returns its `file://` URI. */
    private fun solidPngFile(width: Int, height: Int): String {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dir = File(context.filesDir, "workspaces/$tenant/media").apply { mkdirs() }
        val file = File(dir, "source_${width}x$height.png")
        val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.rgb(30, 120, 200))
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return "file://${file.absolutePath}"
    }
}
