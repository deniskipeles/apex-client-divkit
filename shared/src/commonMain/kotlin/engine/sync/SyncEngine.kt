package engine.sync

import engine.db.WorkspaceManager
import engine.state.DivVariableValue
import engine.state.EngineStateBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/**
 * Pushes `pending_sync` rows to the edge.
 *
 * Server contract (must be idempotent by client_uuid):
 * ```
 * POST {edgeEndpoint}/sync
 * Body: { "batch": [{ "client_uuid": "...", "op": "db.insert", "payload": {...} }] }
 * Response: { "accepted": ["uuid"], "rejected": [{"uuid": "...", "reason": "..."}] }
 * ```
 *
 * Retry policy:
 *  - Network / 5xx / rate-limited → retry with exponential backoff (WorkManager)
 *  - 4xx → poison row, increment attempts, dead-letter at MAX_ATTEMPTS
 */
class SyncEngine(
    private val workspaceManager: WorkspaceManager,
    private val http: HttpClient
) {
    companion object {
        private const val BATCH_SIZE = 50
        private const val MAX_ATTEMPTS = 10
    }

    suspend fun sync(workspaceId: String, endpoint: String): SyncOutcome = withContext(Dispatchers.IO) {
        EngineStateBridge.pushUpdate("user.sync_status", DivVariableValue.Str("syncing"))
        val batch = readBatch(workspaceId)
        if (batch.isEmpty()) {
            EngineStateBridge.pushUpdate("user.sync_status", DivVariableValue.Str("idle"))
            return@withContext SyncOutcome.Success
        }
        return@withContext try {
            val response = pushBatch(endpoint, batch)
            val accepted = response["accepted"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
            val rejected = response["rejected"]?.jsonArray ?: JsonArray(emptyList())
            var shouldRetry = false
            workspaceManager.useDatabase(workspaceId) { db ->
                accepted.forEach { uuid -> db.workspaceQueries.deletePendingSync(uuid) }
                rejected.forEach { el ->
                    val obj = el.jsonObject
                    val uuid = obj["uuid"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                    val reason = obj["reason"]?.jsonPrimitive?.contentOrNull ?: "rejected"
                    if (isRetryable(reason)) {
                        db.workspaceQueries.incrementPendingAttempts(uuid)
                        shouldRetry = true
                    } else {
                        db.workspaceQueries.markPendingDead(reason, uuid)
                    }
                }
            }
            EngineStateBridge.pushUpdate(
                "user.sync_status",
                DivVariableValue.Str(if (shouldRetry) "retrying" else "synced")
            )
            if (shouldRetry) SyncOutcome.Retry else SyncOutcome.Success
        } catch (e: Exception) {
            EngineStateBridge.pushUpdate("user.sync_status", DivVariableValue.Str("offline_queued"))
            SyncOutcome.Retry
        }
    }

    private suspend fun readBatch(workspaceId: String): List<SyncRow> {
        return workspaceManager.useDatabase(workspaceId) { db ->
            val rows = mutableListOf<SyncRow>()
            db.driver.executeQuery(
                null,
                "SELECT id, client_uuid, op, payload FROM pending_sync " +
                    "WHERE attempts < $MAX_ATTEMPTS ORDER BY id LIMIT $BATCH_SIZE",
                0
            ).use { c ->
                while (c.next().value) {
                    rows.add(
                        SyncRow(
                            id = c.getLong(0) ?: 0L,
                            clientUuid = c.getString(1) ?: "",
                            op = c.getString(2) ?: "",
                            payload = c.getString(3) ?: "{}"
                        )
                    )
                }
            }
            rows
        }
    }

    private suspend fun pushBatch(endpoint: String, batch: List<SyncRow>): JsonObject {
        val body = buildJsonObject {
            putJsonArray("batch") {
                batch.forEach { row ->
                    addJsonObject {
                        put("client_uuid", row.clientUuid)
                        put("op", row.op)
                        put("payload", Json.parseToJsonElement(row.payload))
                    }
                }
            }
        }
        val resp = http.post("$endpoint/sync") {
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        if (!resp.status.isSuccess()) error("Sync HTTP ${resp.status.value}")
        return Json.parseToJsonElement(resp.bodyAsText()).jsonObject
    }

    private fun isRetryable(reason: String): Boolean =
        reason.startsWith("5") || reason == "timeout" || reason == "rate_limited"
}

enum class SyncOutcome { Success, Retry }

data class SyncRow(val id: Long, val clientUuid: String, val op: String, val payload: String)
