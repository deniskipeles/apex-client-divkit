package com.example

import engine.fs.TempImageStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The vision-tool image cache must stay bounded; it is the only unbounded write in `cache/`. */
class TempImageStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val dir: File by lazy { File(temp.root, TempImageStore.DIR) }

    @Test
    fun `store keeps at most the requested number of images`() {
        repeat(5) { index ->
            val file = TempImageStore.store(dir, ByteArray(10) { index.toByte() }, "picked", keep = 4)
            assertTrue(file != null && file.exists())
            file!!.setLastModified(1_000L + index)
        }

        assertEquals(4, dir.listFiles()!!.size)
    }

    @Test
    fun `prune deletes the oldest files first`() {
        val old = File(dir, "old.jpg").apply { parentFile?.mkdirs(); writeBytes(ByteArray(3)); setLastModified(1L) }
        val mid = File(dir, "mid.jpg").apply { writeBytes(ByteArray(3)); setLastModified(2L) }
        val new = File(dir, "new.jpg").apply { writeBytes(ByteArray(3)); setLastModified(3L) }

        val freed = TempImageStore.prune(dir, keep = 1)

        assertEquals(6L, freed)
        assertFalse(old.exists())
        assertFalse(mid.exists())
        assertTrue(new.exists())
    }

    @Test
    fun `pruneLegacy removes only the old root-level captures`() {
        val root = temp.root
        val picked = File(root, "picked_123.jpg").apply { writeBytes(ByteArray(4)) }
        val captured = File(root, "captured_456.jpg").apply { writeBytes(ByteArray(5)) }
        val unrelated = File(root, "keep.json").apply { writeText("{}") }
        val nested = File(root, TempImageStore.DIR).apply { mkdirs() }
        val nestedImage = File(nested, "picked_789.jpg").apply { writeBytes(ByteArray(6)) }

        val freed = TempImageStore.pruneLegacy(root)

        assertEquals(9L, freed)
        assertFalse(picked.exists())
        assertFalse(captured.exists())
        assertTrue(unrelated.exists())
        assertTrue(nestedImage.exists())
    }

    @Test
    fun `clear empties the directory and reports the bytes freed`() {
        File(dir, "a.jpg").apply { parentFile?.mkdirs(); writeBytes(ByteArray(10)) }
        File(dir, "nested/b.jpg").apply { parentFile?.mkdirs(); writeBytes(ByteArray(5)) }

        val freed = TempImageStore.clear(dir)

        assertEquals(15L, freed)
        assertEquals(0, dir.listFiles()!!.size)
    }
}
