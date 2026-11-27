package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import engine.db.WorkspaceDriverFactory
import engine.db.WorkspaceManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * A tenant `-wal` can hold tens of megabytes the Files tab never counted. The factory caps its
 * size and `WorkspaceManager.checkpoint` folds it back into the main database.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WorkspaceWalCheckpointTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `journal size limit is capped on every tenant database`() = runBlocking {
        val driver = WorkspaceDriverFactory(context).create("wal_limit_ws")
        driver.executeQuery(null, "PRAGMA journal_size_limit", 0).use { cursor ->
            assertTrue(cursor.next().value)
            assertEquals(4L * 1024 * 1024, cursor.getLong(0))
        }
        driver.close()
    }

    @Test
    fun `checkpoint truncates the wal and keeps the rows readable`() = runBlocking {
        val manager = WorkspaceManager(WorkspaceDriverFactory(context))
        val driver = manager.getDriver("wal_ws")
        driver.executeUpdate(null, "CREATE TABLE IF NOT EXISTS t (id INTEGER PRIMARY KEY, v BLOB)", 0)
        repeat(400) { index ->
            driver.executeUpdate(null, "INSERT INTO t (v) VALUES (?)", 1) {
                bindBytes(0, ByteArray(1024) { index.toByte() })
            }
        }

        val wal = File(context.getDatabasePath("workspace_wal_ws.db").absolutePath + "-wal")
        val before = wal.length()
        val freed = manager.checkpoint("wal_ws")
        val after = wal.length()

        // SQLite rounds the file up to its page size, so assert the direction, not a byte count.
        assertTrue("checkpoint must not grow the WAL", after <= before)
        if (before > 0L) assertTrue("checkpoint should truncate the WAL ($before -> $after)", after < before)
        assertEquals((before - after).coerceAtLeast(0L), freed)

        var rows = 0L
        driver.executeQuery(null, "SELECT COUNT(*) FROM t", 0).use { cursor ->
            if (cursor.next().value) rows = cursor.getLong(0) ?: 0L
        }
        assertEquals(400L, rows)
        assertTrue(manager.checkpointAll() >= 0L)
    }
}
