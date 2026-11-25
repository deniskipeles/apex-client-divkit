package engine.db

import engine.crypto.currentTimeMillis
import engine.db.auth.Claims
import engine.db.query.*
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
                        where = whereClause,
                        sort = sort,
                        limit = limit,
                        offset = offset
                    )

                    val jsonResultArray = QueryEngine.execute(db.driver, apexQuery, colId)

                    // Convert to map rows for OpResult.Rows
                    val rows = jsonResultArray.map { el -> flattenRecordRow(el.jsonObject) }

                    OpResult.Rows(rows)
                }

                // Generic Record Update (id-only, shallow JSON merge)
                "db.update", "records.update" -> {
                    val col = payload["collection"]?.jsonPrimitive?.contentOrNull
                        ?: payload["table"]?.jsonPrimitive?.contentOrNull
                        ?: error("Missing 'collection' parameter")

                    val recordId = payload["id"]?.jsonPrimitive?.contentOrNull
                        ?: payload["record_id"]?.jsonPrimitive?.contentOrNull
                        ?: error("Missing 'id' parameter for update operation")

                    val data = payload["data"]?.jsonObject
                        ?: payload["values"]?.jsonObject
                        ?: error("Missing 'data' or 'values' object")

                    val userRole = payload["auth_role"]?.jsonPrimitive?.contentOrNull ?: "public"
                    val userUid = payload["auth_uid"]?.jsonPrimitive?.longOrNull ?: 0L
                    val claims = Claims(uid = userUid, role = userRole)

                    val updatedId = apexEngine.updateRecord(col, recordId, data, claims)
                    OpResult.RowId(updatedId)
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

                    val recordId = payload["id"]?.jsonPrimitive?.contentOrNull
                        ?: payload["record_id"]?.jsonPrimitive?.contentOrNull
                        ?: error("Missing 'id' parameter for delete operation")

                    val userRole = payload["auth_role"]?.jsonPrimitive?.contentOrNull ?: "public"
                    val userUid = payload["auth_uid"]?.jsonPrimitive?.longOrNull ?: 0L
                    val claims = Claims(uid = userUid, role = userRole)

                    SchemaOps.deleteRefusal(apexEngine, col, recordId)?.let { refusal ->
                        throw IllegalStateException(refusal)
                    }
                    val deleted = apexEngine.deleteRecord(col, recordId, claims)
                    OpResult.Count(if (deleted) 1L else 0L)
                }

                // Generic, schema-interpreting operations. None of these know what a product
                // or an order is: validation, roll-ups, lookups, delete guards and transfer
                // packing all read their rules from the collection schema.
                "db.upsert", "records.upsert" -> {
                    val collection = payload["collection"]?.jsonPrimitive?.contentOrNull
                        ?: payload["table"]?.jsonPrimitive?.contentOrNull
                        ?: error("Missing 'collection' parameter")
                    val data = (payload["data"] ?: payload["values"])?.jsonObject
                        ?: error("Missing 'data' parameter")
                    val result = SchemaOps.upsert(
                        apexEngine,
                        collection,
                        data,
                        payload["id"]?.jsonPrimitive?.contentOrNull
                    )
                    OpResult.Row(
                        flattenRecord(result.data) + mapOf(
                            "id" to result.id,
                            "ok" to result.ok,
                            "errors_json" to JsonObject(
                                result.errors.mapValues { JsonPrimitive(it.value) }
                            ).toString(),
                            "error_count" to result.errors.size
                        )
                    )
                }

                "db.append_child" -> {
                    val collection = payload["collection"]?.jsonPrimitive?.contentOrNull
                        ?: error("Missing 'collection' parameter")
                    val parentId = payload["parent_id"]?.jsonPrimitive?.contentOrNull
                        ?: error("Missing 'parent_id' parameter")
                    val data = (payload["data"] ?: payload["values"])?.jsonObject
                        ?: error("Missing 'data' parameter")
                    val result = SchemaOps.appendChild(apexEngine, collection, parentId, data)
                    OpResult.Row(
                        flattenRecord(result.child) + result.parent + mapOf(
                            "parent_id" to result.parentId,
                            "child_id" to result.childId
                        )
                    )
                }

                "db.set_child_qty" -> {
                    val collection = payload["collection"]?.jsonPrimitive?.contentOrNull
                        ?: error("Missing 'collection' parameter")
                    val id = payload["id"]?.jsonPrimitive?.contentOrNull
                        ?: error("Missing 'id' parameter")
                    val quantity = payload["quantity"]?.jsonPrimitive?.intOrNull
                        ?: error("Missing 'quantity' parameter")
                    val result = SchemaOps.setChildQuantity(apexEngine, collection, id, quantity)
                    OpResult.Row(
                        flattenRecord(result.child) + result.parent + mapOf(
                            "parent_id" to result.parentId,
                            "child_id" to id,
                            "undo_json" to result.removedJson
                        )
                    )
                }

                "db.remove_child" -> {
                    val collection = payload["collection"]?.jsonPrimitive?.contentOrNull
                        ?: error("Missing 'collection' parameter")
                    val id = payload["id"]?.jsonPrimitive?.contentOrNull
                        ?: error("Missing 'id' parameter")
                    val result = SchemaOps.removeChild(apexEngine, collection, id)
                    OpResult.Row(
                        flattenRecord(result.child) + result.parent + mapOf(
                            "parent_id" to result.parentId,
                            "undo_json" to result.removedJson
                        )
                    )
                }

                "db.commit" -> {
                    val collection = payload["collection"]?.jsonPrimitive?.contentOrNull
                        ?: payload["table"]?.jsonPrimitive?.contentOrNull
                        ?: error("Missing 'collection' parameter")
                    val id = payload["id"]?.jsonPrimitive?.contentOrNull
                        ?: payload["record_id"]?.jsonPrimitive?.contentOrNull
                        ?: error("Missing 'id' parameter")
                    OpResult.Row(SchemaOps.commit(apexEngine, collection, id))
                }

                "lookup.resolve", "records.resolve" -> {
                    val collection = payload["collection"]?.jsonPrimitive?.contentOrNull
                        ?: error("Missing 'collection' parameter")
                    val value = payload["value"]?.jsonPrimitive?.contentOrNull
                        ?: payload["raw"]?.jsonPrimitive?.contentOrNull
                        ?: error("Missing 'value' parameter")
                    val hit = SchemaOps.lookup(
                        apexEngine,
                        collection,
                        value,
                        payload["field"]?.jsonPrimitive?.contentOrNull
                    )
                    if (hit == null) {
                        OpResult.Row(
                            mapOf(
                                "ok" to false,
                                "value" to value,
                                "reason" to "No record matches $value"
                            )
                        )
                    } else {
                        OpResult.Row(
                            flattenRecord(hit) + mapOf(
                                "ok" to true,
                                "value" to value,
                                "record_json" to hit.toString()
                            )
                        )
                    }
                }

                "doc.pack", "doc.chunk" -> {
                    val collection = payload["collection"]?.jsonPrimitive?.contentOrNull
                        ?: error("Missing 'collection' parameter")
                    val id = payload["id"]?.jsonPrimitive?.contentOrNull
                        ?: payload["record_id"]?.jsonPrimitive?.contentOrNull
                        ?: error("Missing 'id' parameter")
                    val chunkBytes = payload["chunk_bytes"]?.jsonPrimitive?.intOrNull
                    val packed = SchemaOps.pack(apexEngine, collection, id, chunkBytes)
                    OpResult.Row(
                        mapOf(
                            "id" to packed.headerId,
                            "payload_json" to packed.payload,
                            "checksum" to packed.checksum,
                            "chunks_json" to buildJsonArray { packed.chunks.forEach { add(it) } }.toString(),
                            "chunk_count" to packed.chunks.size,
                            "chunk_bytes" to packed.chunkBytes
                        )
                    )
                }

                "doc.append" -> {
                    val chunks = readChunks(payload)
                    val chunk = payload["chunk"]?.jsonPrimitive?.contentOrNull
                        ?: error("Missing 'chunk' parameter")
                    val result = SchemaOps.append(chunks, chunk)
                    OpResult.Row(
                        mapOf(
                            "accepted" to result.accepted,
                            "corrupt" to result.corrupt,
                            "reason" to (result.reason ?: ""),
                            "chunks_json" to buildJsonArray { result.chunks.forEach { add(it) } }.toString(),
                            "chunk_count" to result.chunks.size
                        )
                    )
                }

                "doc.unpack", "doc.assemble" -> {
                    val chunks = readChunks(payload)
                    val checksum = payload["checksum"]?.jsonPrimitive?.contentOrNull
                    val result = SchemaOps.unpack(chunks, checksum)
                    val parsed = result.payload?.let {
                        runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull()
                    }
                    OpResult.Row(
                        (parsed?.let { flattenRecord(it) } ?: emptyMap()) + mapOf(
                            "ok" to result.ok,
                            "payload_json" to (result.payload ?: ""),
                            "checksum_ok" to result.checksumOk,
                            "missing_json" to buildJsonArray { result.missing.forEach { add(it) } }.toString(),
                            "reason" to (result.reason ?: ""),
                            "chunk_count" to chunks.size
                        )
                    )
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

    /**
     * `db.query` over the record store projects `records.data` as one nested JSON column, so a
     * flat map would leave `@{item.item_name}` empty while `@{item.id}` still resolves. Merge the
     * `data` object up into the row so json_list bindings can address the stored fields directly.
     */
    private fun flattenRecordRow(obj: JsonObject): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        obj.forEach { (k, v) ->
            if (k == "data" && v is JsonObject) {
                v.forEach { (field, fieldValue) -> out[field] = fieldValue.asScalar() }
            } else {
                out[k] = v.asScalar()
            }
        }
        return out
    }


    private fun readChunks(payload: JsonObject): List<String> {
        val raw = payload["chunks_json"] ?: payload["chunks"]
        val array = when (raw) {
            is JsonArray -> raw
            is JsonPrimitive -> runCatching { Json.parseToJsonElement(raw.content) as JsonArray }.getOrNull()
            else -> null
        } ?: return emptyList()
        return array.mapNotNull { it.jsonPrimitive.contentOrNull?.takeIf { text -> text.isNotBlank() } }
    }

    /** Row shape expected by the output mapping: scalars stay scalars, nested values stay JSON. */
    private fun flattenRecord(data: JsonObject): Map<String, Any?> =
        data.mapValues { (_, value) ->
            when (value) {
                is JsonPrimitive -> when {
                    value.isString -> value.content
                    value.content == "true" || value.content == "false" -> value.content.toBoolean()
                    value.doubleOrNull != null -> value.doubleOrNull as Any
                    else -> value.content
                }
                else -> value.toString()
            }
        }

    private fun JsonElement.asScalar(): Any? = when (this) {
        is JsonPrimitive -> contentOrNull
        else -> toString()
    }


    private fun JsonObject.number(field: String): Double {
        val el = this[field] as? JsonPrimitive ?: return 0.0
        return el.doubleOrNull ?: el.contentOrNull?.toDoubleOrNull() ?: 0.0
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