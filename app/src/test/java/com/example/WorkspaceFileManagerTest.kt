package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import engine.fs.WorkspaceFileManager
import engine.fs.WorkspaceFileSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Robolectric coverage for the Android-facing workspace file manager: folder discovery under
 * `files/workspaces/` and the read-only tenant database entry surfaced at each workspace root.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WorkspaceFileManagerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun createWorkspace(id: String, files: Map<String, String> = emptyMap()): File {
        val dir = File(context.filesDir, "workspaces/$id").apply { mkdirs() }
        files.forEach { (name, content) -> File(dir, name).apply { parentFile?.mkdirs() }.writeText(content) }
        return dir
    }

    private fun createDatabase(id: String, bytes: Int = 2048) {
        val db = context.getDatabasePath("workspace_$id.db")
        db.parentFile?.mkdirs()
        db.writeBytes(ByteArray(bytes) { 1 })
    }

    @Test
    fun `lists known workspaces even when the sandbox does not exist yet`() {
        val manager = WorkspaceFileManager(context)
        createWorkspace("acme_pos", mapOf("media/logo.txt" to "hi"))

        val folders = manager.listWorkspaceFolders(
            knownWorkspaceIds = listOf("acme_pos", "ghost_tenant"),
            displayNames = mapOf("acme_pos" to "Acme POS")
        )

        assertEquals(listOf("acme_pos"), folders.map { it.workspaceId })
        val acme = folders.single()
        assertEquals("Acme POS", acme.displayName)
        assertEquals(1, acme.fileCount)
        assertTrue(acme.sizeBytes >= 2L)
    }

    @Test
    fun `surfaces orphan folders left on disk after a tenant is removed`() {
        val manager = WorkspaceFileManager(context)
        createWorkspace("orphan_ws")

        val folders = manager.listWorkspaceFolders(knownWorkspaceIds = listOf("acme_pos"))
        assertEquals(listOf("orphan_ws"), folders.map { it.workspaceId })
    }

    @Test
    fun `folder entries include a read-only database entry at the workspace root`() {
        val manager = WorkspaceFileManager(context)
        createWorkspace("acme_pos", mapOf("media/pic.png" to "x", "export.json" to "{}"))
        createDatabase("acme_pos")

        val folder = manager.listWorkspaceFolders(listOf("acme_pos")).single()
        assertNotNull(folder.databasePath)

        val entries = manager.listForBrowse(folder, File(folder.dirPath))
        val names = entries.map { it.name }

        assertTrue(names.contains("media"))
        assertTrue(names.contains("export.json"))
        assertTrue(names.contains("workspace_acme_pos.db"))

        val dbEntry = entries.single { it.name == "workspace_acme_pos.db" }
        assertTrue(dbEntry.isReadOnly)
        assertFalse(dbEntry.isDirectory)
    }

    @Test
    fun `nested directory browse does not re-append the database entry`() {
        val manager = WorkspaceFileManager(context)
        createWorkspace("acme_pos", mapOf("media/inner.txt" to "hi"))
        createDatabase("acme_pos")

        val folder = manager.listWorkspaceFolders(listOf("acme_pos")).single()
        val media = File(folder.dirPath, "media")

        val entries = manager.listForBrowse(folder, media)
        assertEquals(listOf("inner.txt"), entries.map { it.name })
    }

    @Test
    fun `reports the database size in the folder total`() {
        val manager = WorkspaceFileManager(context)
        createWorkspace("acme_pos")
        createDatabase("acme_pos", bytes = 4096)

        val folder = manager.listWorkspaceFolders(listOf("acme_pos")).single()
        assertEquals(4096L, folder.sizeBytes)
        assertEquals("4.0 KB", WorkspaceFileSystem.humanSize(folder.sizeBytes))
    }
}
