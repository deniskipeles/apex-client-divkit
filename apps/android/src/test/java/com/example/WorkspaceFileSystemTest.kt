package com.example

import engine.fs.WorkspaceFileSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Unit tests for the pure-JVM workspace filesystem layer that backs the Files tab.
 * These run without Android or Robolectric since [WorkspaceFileSystem] touches only java.io.
 */
class WorkspaceFileSystemTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `rejects names that escape or contain separators`() {
        listOf("", "   ", ".", "..", "a/b", "a\\b", "../evil", "sub/../evil").forEach { bad ->
            assertFalse("expected unsafe: '$bad'", WorkspaceFileSystem.isSafeName(bad))
        }
        listOf("notes.txt", "media", "my folder", "a-b_c.1").forEach { good ->
            assertTrue("expected safe: '$good'", WorkspaceFileSystem.isSafeName(good))
        }
    }

    @Test
    fun `childOf blocks traversal outside the parent`() {
        val root = temp.newFolder("root")
        assertNotNull(WorkspaceFileSystem.childOf(root, "child"))
        assertNull(WorkspaceFileSystem.childOf(root, "../escape"))
        assertNull(WorkspaceFileSystem.childOf(root, "nested/../../escape"))
    }

    @Test
    fun `create rename and delete round trip`() {
        val root = temp.newFolder("root")

        val dir = WorkspaceFileSystem.createDirectory(root, "media")
        assertNotNull(dir)
        assertTrue(dir!!.isDirectory)

        // Creating a name that already exists must not clobber it.
        assertNull(WorkspaceFileSystem.createDirectory(root, "media"))

        val file = WorkspaceFileSystem.createTextFile(root, "notes", "hello")
        assertNotNull(file)
        assertEquals("notes.txt", file!!.name)
        assertEquals("hello", file.readText())

        val renamed = WorkspaceFileSystem.rename(file, "renamed.txt")
        assertNotNull(renamed)
        assertEquals("renamed.txt", renamed!!.name)
        assertFalse(file.exists())

        assertTrue(WorkspaceFileSystem.delete(renamed))
        assertFalse(renamed.exists())
    }

    @Test
    fun `rename refuses to overwrite an existing target`() {
        val root = temp.newFolder("root")
        val a = WorkspaceFileSystem.createTextFile(root, "a.txt", "a")!!
        WorkspaceFileSystem.createTextFile(root, "b.txt", "b")!!
        assertNull(WorkspaceFileSystem.rename(a, "b.txt"))
        assertEquals("a", a.readText())
    }

    @Test
    fun `listing puts directories first then sorts case-insensitively`() {
        val root = temp.newFolder("root")
        WorkspaceFileSystem.createDirectory(root, "zeta")
        WorkspaceFileSystem.createDirectory(root, "alpha")
        WorkspaceFileSystem.createTextFile(root, "Beta.txt", "b")
        WorkspaceFileSystem.createTextFile(root, "apple.txt", "a")

        val names = WorkspaceFileSystem.listEntries(root).map { it.name }
        assertEquals(listOf("alpha", "zeta", "apple.txt", "Beta.txt"), names)
    }

    @Test
    fun `directory size and human formatting aggregate nested files`() {
        val root = temp.newFolder("root")
        val nested = File(root, "nested").apply { mkdirs() }
        File(root, "top.txt").writeText("12345")
        File(nested, "deep.txt").writeText("123")

        assertEquals(8L, WorkspaceFileSystem.directorySize(root))
        assertEquals("8 B", WorkspaceFileSystem.humanSize(8L))
        assertEquals("1.5 KB", WorkspaceFileSystem.humanSize(1536L))
    }

    @Test
    fun `text preview reads content and flags binary files`() {
        val root = temp.newFolder("root")
        val text = File(root, "readme.md").apply { writeText("# Title") }
        assertEquals("# Title", WorkspaceFileSystem.readTextPreview(text))

        val binary = File(root, "blob.bin").apply { writeBytes(byteArrayOf(1, 2, 0, 4)) }
        assertNull(WorkspaceFileSystem.readTextPreview(binary))
    }

    @Test
    fun `text preview truncates at the byte cap`() {
        val root = temp.newFolder("root")
        val big = File(root, "big.txt").apply { writeText("x".repeat(10_000)) }
        val preview = WorkspaceFileSystem.readTextPreview(big, maxBytes = 512)
        assertNotNull(preview)
        assertEquals(512, preview!!.length)
    }

    @Test
    fun `entry classifies previewable extensions`() {
        val root = temp.newFolder("root")
        val json = File(root, "data.json").apply { writeText("{}") }
        val png = File(root, "pic.png").apply { writeText("x") }
        assertTrue(WorkspaceFileSystem.toEntry(json).isTextPreviewable)
        assertFalse(WorkspaceFileSystem.toEntry(png).isTextPreviewable)
        assertEquals("json", WorkspaceFileSystem.toEntry(json).extension)
    }
}
