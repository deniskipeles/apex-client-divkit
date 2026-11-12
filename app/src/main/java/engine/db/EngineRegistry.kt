package engine.db

import engine.crypto.currentTimeMillis
import engine.db.auth.Claims
import kotlinx.serialization.json.*
import java.util.UUID

class EngineRegistry(
    private val clock: () -> Long = { currentTimeMillis() }
) {
    companion object {
        private const val HARD_MAX_LIMIT = 500
        private const val MAX_PAYLOAD_BYTES = 128 * 1024
    }

    fun execute(
        opName: String,
        db: WorkspaceDatabase,
        workspaceId: String,
        payload: JsonObject
    ): OpResult {
        val payloadBytes = payload.toString().encodeToByteArray()
        if (payloadBytes.size > MAX_PAYLOAD_BYTES) {
            throw SecurityException("Payload exceeds limit of ${MAX_PAYLOAD_BYTES} bytes")
        }

        val apexEngine = ApexKitDatabaseEngine(db.driver)
        val started = clock()

        return try {
            val result = when (opName) {
                // Schema Management
                "db.import_schema", "schema.import" -> {
                    val schemaObj = payload["schema"]?.jsonObject ?: payload
                    val count = apexEngine.importTenantSchemaJson(schemaObj.toString())
                    OpResult.SchemaImported(count)
                }

                "db.list_collections", "schema.collections" -> {
                    val cols = apexEngine.listCollections()
                    val rows = cols.map { mapOf("id" to it.id, "name" to it.name, "index" to (it.index ?: "")) }
                    OpResult.Rows(rows)
                }

                // Generic Record Insertion
                "db.insert", "records.create" -> {
                    val col = payload["collection"]?.jsonPrimitive?.contentOrNull
                        ?: payload["table"]?.jsonPrimitive?.contentOrNull
                        ?: error("Missing 'collection' parameter")

                    val data = payload["data"]?.jsonObject
                        ?: payload["values"]?.jsonObject
                        ?: error("Missing 'data' or 'values' object")

                    val userRole = payload["auth_role"]?.jsonPrimitive?.contentOrNull ?: "public"
                    val userUid = payload["auth_uid"]?.jsonPrimitive?.longOrNull ?: 0L
                    val claims = Claims(uid = userUid, role = userRole)

                    val newId = apexEngine.insertRecord(col, data, claims)

                    val enqueueSync = payload["enqueue_sync"]?.jsonPrimitive?.booleanOrNull ?: false
                    if (enqueueSync) {
                        val clientUuid = payload["client_uuid"]?.jsonPrimitive?.contentOrNull ?: UUID.randomUUID().toString()
                        db.driver.executeUpdate(
                            null,
                            "INSERT OR IGNORE INTO pending_sync (client_uuid, op, payload, created_at) VALUES (?, ?, ?, ?)",
                            4
                        ) {
                            bindString(0, clientUuid)
                            bindString(1, opName)
                            bindString(2, payload.toString())
                            bindLong(3, clock())
                        }
                    }
                    OpResult.RowId(newId)
                }

                // Generic Collection Query
                // Advanced Schema-Driven Collection Query (ApexQuery AST with Pipelines)
                "db.query", "records.query" -> {
                    val colName = payload["from"]?.jsonPrimitive?.contentOrNull
                        ?: payload["collection"]?.jsonPrimitive?.contentOrNull
                        ?: payload["table"]?.jsonPrimitive?.contentOrNull
                        ?: error("Missing 'from' or 'collection' parameter")

                    val col = apexEngine.getCollection(colName)
                    val colId = col?.id

                    // Parse query options, projections, order, and limit
                    val selectArray = payload["select"]?.jsonArray?.mapNotNull { el ->
                        when (el) {
                            is JsonPrimitive -> SelectField.Simple(el.content)
                            is JsonObject -> {
                                val field = el["field"]?.jsonPrimitive?.contentOrNull
                                val expr = el["expr"]?.jsonPrimitive?.contentOrNull
                                val fn = el["fn"]?.jsonPrimitive?.contentOrNull
                                val alias = el["as"]?.jsonPrimitive?.contentOrNull
                                SelectField.Complex(field = field, expr = expr, fn = fn, `as` = alias)
                            }
                            else -> null
                        }
                    } ?: emptyList()

                    val whereClause = payload["where"] ?: payload["filter"]
                    val sort = payload["sort"]?.jsonPrimitive?.contentOrNull
                        ?: payload["order_by"]?.jsonPrimitive?.contentOrNull
                    val limit = payload["limit"]?.jsonPrimitive?.longOrNull ?: 100L
                    val offset = payload["offset"]?.jsonPrimitive?.longOrNull ?: 0L

                    val apexQuery = ApexQuery(
                        from = colName,
                        select = selectArray,
                        r#where = whereClause,
                        sort = sort,
                        limit = limit,
                        offset = offset
                    )

                    val jsonResultArray = QueryEngine.execute(db.driver, apexQuery, colId)

                    // Convert to map rows for OpResult.Rows
                    val rows = jsonResultArray.map { el ->
                        val obj = el.jsonObject
                        obj.mapValues { (_, v) ->
                            when (v) {
                                is JsonPrimitive -> v.contentOrNull ?: v.toString()
                                else -> v.toString()
                            }
                        }
                    }

                    OpResult.Rows(rows)
                }

                // Query Single Record
                "db.query_one", "records.get" -> {
                    val col = payload["collection"]?.jsonPrimitive?.contentOrNull
                        ?: payload["table"]?.jsonPrimitive?.contentOrNull
                        ?: error("Missing 'collection' parameter")

                    val filters = parseFilters(payload["where"]?.jsonArray)
                    val userRole = payload["auth_role"]?.jsonPrimitive?.contentOrNull ?: "public"
                    val userUid = payload["auth_uid"]?.jsonPrimitive?.longOrNull ?: 0L
                    val claims = Claims(uid = userUid, role = userRole)

                    val records = apexEngine.queryRecords(col, filters, limit = 1, user = claims)
                    val first = records.firstOrNull()
                    val map = first?.data?.mapValues { (_, v) ->
                        when (v) {
                            is JsonPrimitive -> v.contentOrNull ?: v.toString()
                            else -> v.toString()
                        }
                    }?.plus("id" to first.id)
                    OpResult.Row(map)
                }

                // Count Records in Collection
                "db.count", "records.count" -> {
                    val col = payload["collection"]?.jsonPrimitive?.contentOrNull
                        ?: payload["table"]?.jsonPrimitive?.contentOrNull
                        ?: error("Missing 'collection' parameter")

                    val filters = parseFilters(payload["where"]?.jsonArray)
                    val userRole = payload["auth_role"]?.jsonPrimitive?.contentOrNull ?: "public"
                    val userUid = payload["auth_uid"]?.jsonPrimitive?.longOrNull ?: 0L
                    val claims = Claims(uid = userUid, role = userRole)

                    val total = apexEngine.countRecords(col, filters, claims)
                    OpResult.Count(total)
                }

                // Generic Record Deletion
                "db.delete", "records.delete" -> {
                    val col = payload["collection"]?.jsonPrimitive?.contentOrNull
                        ?: payload["table"]?.jsonPrimitive?.contentOrNull
                        ?: error("Missing 'collection' parameter")

                    val recordId = payload["id"]?.jsonPrimitive?.longOrNull
                        ?: payload["record_id"]?.jsonPrimitive?.longOrNull
                        ?: error("Missing 'id' parameter for delete operation")

                    val userRole = payload["auth_role"]?.jsonPrimitive?.contentOrNull ?: "public"
                    val userUid = payload["auth_uid"]?.jsonPrimitive?.longOrNull ?: 0L
                    val claims = Claims(uid = userUid, role = userRole)

                    val deleted = apexEngine.deleteRecord(col, recordId, claims)
                    OpResult.Count(if (deleted) 1L else 0L)
                }

                else -> throw SecurityException("Unknown operation: $opName")
            }

            db.queries.insertOpLog(
                ts = clock(),
                op = opName,
                duration_ms = clock() - started,
                rowcount = when (result) {
                    is OpResult.Rows -> result.rows.size.toLong()
                    is OpResult.Count -> result.n
                    is OpResult.RowId -> 1L
                    else -> null
                },
                error = null
            )
            result
        } catch (e: Exception) {
            db.queries.insertOpLog(
                ts = clock(),
                op = opName,
                duration_ms = clock() - started,
                rowcount = null,
                error = e.message
            )
            throw e
        }
    }

    private fun parseFilters(array: JsonArray?): List<QueryFilter> {
        if (array == null || array.isEmpty()) return emptyList()
        return array.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val field = obj["field"]?.jsonPrimitive?.contentOrNull
                ?: obj["column"]?.jsonPrimitive?.contentOrNull
                ?: return@mapNotNull null
            val op = obj["op"]?.jsonPrimitive?.contentOrNull ?: "="
            val value = obj["value"]?.jsonPrimitive?.contentOrNull ?: obj["value"].toString().trim('"')
            QueryFilter(field, op, value)
        }
    }
}