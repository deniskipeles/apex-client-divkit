package com.example

import engine.workspace.ClasspathSource
import engine.workspace.DevWorkspaceImport
import engine.workspace.MapSource
import engine.workspace.WorkspaceBundleCodec
import engine.workspace.WorkspaceCipher
import engine.workspace.WorkspaceFormatException
import engine.workspace.WorkspaceRepository
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The workspace package is the shipping unit: a folder of JSON in dev, one encrypted binary in
 * production. These tests pin the format, the cipher and the dev import/unload path.
 */
class WorkspacePackageTest {

    private val key = WorkspaceCipher.keyFrom("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")

    private fun tempDir(): File = File.createTempFile("workspace", "test").let {
        it.delete()
        it.mkdirs()
        it
    }

    @Test
    fun cipher_roundTripsAndRejectsWrongKey() {
        val plain = """{"manifest":{"id":"acme_pos"}}""".toByteArray()
        val blob = WorkspaceCipher.encrypt(plain, key)

        assertTrue(WorkspaceCipher.isEncrypted(blob))
        assertTrue(String(WorkspaceCipher.decrypt(blob, key)) == String(plain))

        val wrong = WorkspaceCipher.keyFrom("another-key-entirely")
        try {
            WorkspaceCipher.decrypt(blob, wrong)
            fail("a wrong key must not decrypt the bundle")
        } catch (expected: Exception) {
            assertTrue(expected is javax.crypto.AEADBadTagException || expected is java.security.GeneralSecurityException)
        }
    }

    @Test
    fun cipher_rejectsTamperedCiphertextAndPlaintext() {
        val blob = WorkspaceCipher.encrypt("{}".toByteArray(), key)
        val tampered = blob.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        try {
            WorkspaceCipher.decrypt(tampered, key)
            fail("tampering must fail the GCM tag")
        } catch (expected: Exception) {
            assertTrue(true)
        }
        try {
            WorkspaceCipher.decrypt("{\"plain\":true}".toByteArray(), key)
            fail("plaintext must never be accepted as a bundle")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("not an encrypted"))
        }
    }

    @Test
    fun bundledWorkspaces_loadWithCardsCollectionsAndHub() {
        val report = WorkspaceRepository.loadAll()

        assertTrue("packaged workspaces must load: ${report.failures}", report.failures.isEmpty())
        val ids = report.bundles.map { it.id }.sorted()
        assertEquals(listOf("acme_pos", "apex_kiosk", "apex_tools_test", "owner_pos"), ids)
        report.bundles.forEach { bundle ->
            assertTrue("${bundle.id} needs a hub", bundle.hub.isNotEmpty())
            assertTrue("${bundle.id} needs cards", bundle.cards.isNotEmpty())
            assertTrue("${bundle.id} needs collections", bundle.collections.isNotEmpty())
            assertTrue("${bundle.id} needs a catalog", bundle.catalog != null)
            assertNotNull("${bundle.id} needs variables", bundle.variables)
        }
    }

    @Test
    fun manifestWithoutIdIsRejected() {
        val source = MapSource(
            mapOf(
                "demo/broken/workspace.json" to """{"title":"No id"}""",
                "demo/broken/card.json" to """{"card":{}}"""
            )
        )
        try {
            WorkspaceBundleCodec.fromDirectory(source, "demo/broken")
            fail("a manifest without id must fail")
        } catch (expected: WorkspaceFormatException) {
            assertTrue(expected.message!!.contains("id"))
        }
    }

    @Test
    fun encodedBundleDecodesBackToTheSameWorkspace() {
        val bundle = WorkspaceRepository.load("acme_pos")
        val roundTripped = WorkspaceBundleCodec.decode(WorkspaceBundleCodec.encode(bundle))

        assertEquals(bundle.id, roundTripped.id)
        assertEquals(bundle.cardNames, roundTripped.cardNames)
        assertEquals(bundle.collections.size, roundTripped.collections.size)
        assertEquals(bundle.title, roundTripped.title)
    }

    @Test
    fun devImport_addsACardToTheSelectedWorkspace() {
        val filesDir = tempDir()
        val card = """{"type":"container","items":[{"type":"text","text":"Imported"}]}"""

        val outcome = DevWorkspaceImport.importCard("acme_pos", "imported_card", card, filesDir)

        assertTrue(outcome.message, outcome.ok)
        val imported = WorkspaceRepository.importedBundle("acme_pos", filesDir)
        assertNotNull(imported)
        assertTrue(imported!!.cardNames.contains("imported_card"))
        assertTrue(imported.card("imported_card")!!.toString().contains("Imported"))
    }

    @Test
    fun devImport_rejectsNonDivKitDocuments() {
        val filesDir = tempDir()
        val outcome = DevWorkspaceImport.importCard("acme_pos", "bad", """{"hello":"world"}""", filesDir)
        assertTrue(!outcome.ok)
        assertTrue(outcome.message.contains("DivKit"))
    }

    @Test
    fun devImport_collectionSchemaLandsInTheWorkspace() {
        val filesDir = tempDir()
        val schema = """
            {"schema":{"collections":[{"id":91,"name":"audit_log","schema":{"fields":{"note":{"type":"string"}}}}]}}
        """.trimIndent()

        val outcome = DevWorkspaceImport.importCollection("acme_pos", schema, filesDir)

        assertTrue(outcome.message, outcome.ok)
        val imported = WorkspaceRepository.importedBundle("acme_pos", filesDir)!!
        assertTrue(imported.collections.any { it.toString().contains("audit_log") })
    }

    @Test
    fun devImport_iconPackUnpacksPngBytes() {
        val filesDir = tempDir()
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val zip = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zos ->
                zos.putNextEntry(ZipEntry("icons.json"))
                zos.write("""{"brand_mark":"brand_mark.png"}""".toByteArray())
                zos.closeEntry()
                zos.putNextEntry(ZipEntry("brand_mark.png"))
                zos.write(png)
                zos.closeEntry()
            }
        }.toByteArray()

        val outcome = DevWorkspaceImport.importIconPack("acme_pos", zip, filesDir)

        assertTrue(outcome.message, outcome.ok)
        val imported = WorkspaceRepository.importedBundle("acme_pos", filesDir)!!
        assertTrue(imported.icons.containsKey("brand_mark"))
    }

    @Test
    fun unload_dropsTheImportedCopy() {
        val filesDir = tempDir()
        DevWorkspaceImport.importCard(
            "acme_pos",
            "temp_card",
            """{"type":"text","text":"x"}""",
            filesDir
        )
        assertNotNull(WorkspaceRepository.importedBundle("acme_pos", filesDir))

        val outcome = DevWorkspaceImport.unload("acme_pos", filesDir)

        assertTrue(outcome.message, outcome.ok)
        assertNull(WorkspaceRepository.importedBundle("acme_pos", filesDir))
        assertTrue(WorkspaceRepository.importedIds(filesDir).isEmpty())
    }

    @Test
    fun packagedWorkspacesCarrySeedRowsSoNoKotlinSeedIsNeeded() {
        val inventory = WorkspaceRepository.load("acme_pos").collections
            .first { it.toString().contains("\"inventory\"") }
        val seed = inventory["seed"]
        assertNotNull("inventory must ship seed rows", seed)
        assertTrue(seed.toString().contains("ACM-101"))
    }

    @Test
    fun packagedCollectionsCarryIdsAndFieldUids() {
        WorkspaceRepository.index(ClasspathSource()).forEach { id ->
            WorkspaceRepository.load(id).collections.forEach { collection ->
                val name = collection["name"]?.toString() ?: "<unnamed>"
                assertNotNull("$id/$name must carry a collection id", collection["id"])
                val fields = collection["schema"]?.jsonObject?.get("fields")?.jsonObject
                assertNotNull("$id/$name must declare schema.fields", fields)
                fields!!.forEach { (field, spec) ->
                    assertNotNull("$id/$name.$field must carry a field uid", spec.jsonObject["uid"])
                }
                val canonical = collection["schema"]?.jsonObject?.get("canonical")?.jsonObject
                if (canonical != null) {
                    assertNotNull("$id/$name canonical view must list its fields", canonical["fields"])
                }
            }
        }
    }

    @Test
    fun indexListsEveryPackagedWorkspace() {
        val ids = WorkspaceRepository.index(ClasspathSource())
        assertEquals(listOf("acme_pos", "apex_kiosk", "apex_tools_test", "owner_pos"), ids)
    }
}
