package com.example.contract

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import engine.db.WorkspaceDriverFactory
import engine.db.WorkspaceManager
import engine.sync.SyncEngine
import engine.sync.SyncOutcome
import engine.sync.createHttpClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the sync batch contract (packages/contract/docs/sync-batch.md) against the embedded
 * edge gateway, exercising the real WorkspaceManager + DirectSqliteDriver + OkHttp client.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SyncContractTest {

    private val endpoint = "https://edge.sdui-pos.internal"

    private fun manager(): WorkspaceManager {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return WorkspaceManager(WorkspaceDriverFactory(context))
    }

    private suspend fun insertPending(manager: WorkspaceManager, ws: String, uuid: String, payload: String) {
        manager.useDatabase(ws) { db ->
            db.driver.executeUpdate(
                null,
                "INSERT INTO pending_sync (client_uuid, op, payload, created_at) VALUES (?, ?, ?, ?)",
                4
            ) {
                bindString(0, uuid)
                bindString(1, "db.insert")
                bindString(2, payload)
                bindLong(3, 1L)
            }
        }
    }

    private suspend fun pendingCount(manager: WorkspaceManager, ws: String): Long {
        var n = 0L
        manager.useDatabase(ws) { db ->
            db.driver.executeQuery(null, "SELECT COUNT(*) FROM pending_sync", 0).use { c ->
                if (c.next().value) n = c.getLong(0) ?: 0L
            }
        }
        return n
    }

    @Test
    fun acceptedRowIsDeleted() = runBlocking {
        val manager = manager()
        insertPending(manager, "acme_pos", "u-accepted", """{"v":1}""")
        val outcome = SyncEngine(manager, createHttpClient()).sync("acme_pos", endpoint)
        assertEquals(SyncOutcome.Success, outcome)
        assertEquals(0L, pendingCount(manager, "acme_pos"))
    }

    @Test
    fun retryableRejectionKeepsRowAndSignalsRetry() = runBlocking {
        val manager = manager()
        insertPending(manager, "acme_pos", "u-retry", """{"simulate_reject":"503"}""")
        val outcome = SyncEngine(manager, createHttpClient()).sync("acme_pos", endpoint)
        assertEquals(SyncOutcome.Retry, outcome)
        assertEquals(1L, pendingCount(manager, "acme_pos"))
    }

    @Test
    fun nonRetryableRejectionDeadLettersRow() = runBlocking {
        val manager = manager()
        insertPending(manager, "acme_pos", "u-dead", """{"simulate_reject":"validation_error"}""")
        val outcome = SyncEngine(manager, createHttpClient()).sync("acme_pos", endpoint)
        assertEquals(SyncOutcome.Success, outcome)
        // Row is retained (dead-lettered via markPendingDead), not deleted.
        assertEquals(1L, pendingCount(manager, "acme_pos"))
    }
}
