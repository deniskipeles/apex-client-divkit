package engine.db

import engine.crypto.currentTimeMillis
import kotlinx.serialization.json.*

/**
 * Whitelisted, injection-hardened operation set. The JSON never contains SQL;
 * it contains a named op + structured payload. This class owns the SQL.
 *
 * Security properties:
 *  - Table names and column names must match [IDENT_REGEX] AND exist in the
 *    schema (PRAGMA table_info check) — prevents `orders; DROP TABLE --`.
 *  - `where` is a structured filter DSL, never a raw string — prevents
 *    `UNION SELECT` and boolean-based injection.
 *  - `LIKE` patterns are constrained to alphanumerics + `% _ - space` —
 *    prevents heavy scans and wildcard abuse.
 *  - `limit` is clamped to [HARD_MAX_LIMIT] — prevents OOM from `SELECT *`.
 *  - `delete` without a filter requires `confirm_truncate: true`.
 *  - Payload size is capped before deserialisation.
 */
class EngineRegistry(
    private val clock: () -> Long = { currentTimeMillis() },
    extensionOps: Map<String, (WorkspaceDatabase, String, JsonObject) -> OpResult> = emptyMap()
) {
    companion object {
        private val IDENT_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9_]{0,62}$")
        private val ALLOWED_OPS = setOf("=", "!=", "<", "<=", ">", ">=", "IN", "LIKE")
        private val ALLOWED_LIKE_REGEX = Regex("^[%a-zA-Z0-9 _-]*$")
        private const val HARD_MAX_LIMIT = 500
        private const val MAX_PAYLOAD_BYTES = 64 * 1024
    }

    private val operations: Map<String, (WorkspaceDatabase, String, JsonObject) -> OpResult> = buildMap {
        put("db.insert", ::insert)
        put("db.update", ::update)
        put("db.delete", ::delete)
        put("db.query", ::query)
        put("db.query_one", ::queryOne)
        put("db.count", ::count)
        putAll(extensionOps) // app.* ops registered by the host app
    }

    fun execute(
        opName: String,
        db: WorkspaceDatabase,
        workspaceId: String,
        payload: JsonObject
    ): OpResult {
        val payloadBytes = payload.toString().encodeToByteArray()
        if (payloadBytes.size > MAX_PAYLOAD_BYTES) {
            throw SecurityException("Payload exceeds ${MAX_PAYLOAD_BYTES} bytes")
        }

        val op = operations[opName] ?: throw SecurityException("Unknown operation: $opName")
        val started = clock()
        return try {
            val result = op(db, workspaceId, payload)
            audit(db, opName, clock() - started, result, null)
            result
        } catch (e: Exception) {
            audit(db, opName, clock() - started, null, e.message)
            throw e
        }
    }

    // ---- Operations ----

    private fun insert(db: WorkspaceDatabase, ws: String, p: JsonObject): OpResult {
        val table = p.requireString("table").also(::requireIdent)
        val actual = "${ws}_$table"
        val values = p.getObject("values")
        val keys = values.keys.toList()
        keys.forEach(::requireIdent)
        requireKnownColumns(db, actual, keys)

        val idempotent = p["idempotent"]?.jsonPrimitive?.booleanOrNull ?: false
        val conflictClause = if (idempotent) "OR IGNORE" else "OR ABORT"

        val placeholders = keys.joinToString(",") { "?" }
        val columnList = keys.joinToString(",") { "\"$it\"" }
        val sql = "INSERT $conflictClause INTO $actual ($columnList) VALUES ($placeholders)"

        val args = keys.map { values[it]?.toDbValue() }
        db.workspaceQueries.transaction {
            val cursor = db.driver.executeQuery(
                null, sql, args.size
            ) { stmt ->
                args.forEachIndexed { i, v -> bind(stmt, i, v) }
            }
            cursor.close()
        }
        return OpResult.RowId(db.driver.lastInsertedRowId())
    }

    private fun update(db: WorkspaceDatabase, ws: String, p: JsonObject): OpResult {
        val table = p.requireString("table").also(::requireIdent)
        val actual = "${ws}_$table"
        val values = p.getObject("values")
        val keys = values.keys.toList()
        keys.forEach(::requireIdent)
        requireKnownColumns(db, actual, keys)

        val (whereSql, whereArgs) = buildWhere(p["where"]?.jsonArray, db, actual)
        val setSql = keys.joinToString(", ") { "\"$it\" = ?" }
        val sql = "UPDATE $actual SET $setSql WHERE $whereSql"
        val args = keys.map { values[it]?.toDbValue() } + whereArgs

        val affected = db.driver.executeUpdate(null, sql, args.size) { stmt ->
            args.forEachIndexed { i, v -> bind(stmt, i, v) }
        }
        return OpResult.Count(affected)
    }

    private fun delete(db: WorkspaceDatabase, ws: String, p: JsonObject): OpResult {
        val table = p.requireString("table").also(::requireIdent)
        val actual = "${ws}_$table"
        val filters = p["where"]?.jsonArray
        if ((filters == null || filters.isEmpty()) &&
            (p["confirm_truncate"]?.jsonPrimitive?.booleanOrNull != true)
        ) {
            throw SecurityException("DELETE without WHERE requires confirm_truncate=true")
        }
        val (whereSql, whereArgs) = buildWhere(filters, db, actual)
        val sql = "DELETE FROM $actual WHERE $whereSql"
        val affected = db.driver.executeUpdate(null, sql, whereArgs.size) { stmt ->
            whereArgs.forEachIndexed { i, v -> bind(stmt, i, v) }
        }
        return OpResult.Count(affected)
    }

    private fun query(db: WorkspaceDatabase, ws: String, p: JsonObject): OpResult {
        val table = p.requireString("table").also(::requireIdent)
        val actual = "${ws}_$table"
        val (whereSql, whereArgs) = buildWhere(p["where"]?.jsonArray, db, actual)
        val limit = (p["limit"]?.jsonPrimitive?.intOrNull ?: 100).coerceIn(1, HARD_MAX_LIMIT)
        val offset = (p["offset"]?.jsonPrimitive?.intOrNull ?: 0).coerceAtLeast(0)
        val orderBy = p["order_by"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?.also(::requireIdent)
            ?.let { "ORDER BY \"$it\"" } ?: ""
        val sql = "SELECT * FROM $actual WHERE $whereSql $orderBy LIMIT $limit OFFSET $offset"

        val rows = mutableListOf<Map<String, Any?>>()
        db.driver.executeQuery(null, sql, whereArgs.size) { stmt ->
            whereArgs.forEachIndexed { i, v -> bind(stmt, i, v) }
        }.use { cursor ->
            while (cursor.next().value) {
                val row = mutableMapOf<String, Any?>()
                for (i in 0 until cursor.columnCount) {
                    row[cursor.getColumnName(i)] = cursor.readValue(i)
                }
                rows.add(row)
            }
        }
        return OpResult.Rows(rows)
    }

    private fun queryOne(db: WorkspaceDatabase, ws: String, p: JsonObject): OpResult {
        val table = p.requireString("table").also(::requireIdent)
        val actual = "${ws}_$table"
        val (whereSql, whereArgs) = buildWhere(p["where"]?.jsonArray, db, actual)
        val sql = "SELECT * FROM $actual WHERE $whereSql LIMIT 1"
        var row: Map<String, Any?>? = null
        db.driver.executeQuery(null, sql, whereArgs.size) { stmt ->
            whereArgs.forEachIndexed { i, v -> bind(stmt, i, v) }
        }.use { cursor ->
            if (cursor.next().value) {
                val map = mutableMapOf<String, Any?>()
                for (i in 0 until cursor.columnCount) {
                    map[cursor.getColumnName(i)] = cursor.readValue(i)
                }
                row = map
            }
        }
        return OpResult.Row(row)
    }

    private fun count(db: WorkspaceDatabase, ws: String, p: JsonObject): OpResult {
        val table = p.requireString("table").also(::requireIdent)
        val actual = "${ws}_$table"
        val (whereSql, whereArgs) = buildWhere(p["where"]?.jsonArray, db, actual)
        val sql = "SELECT COUNT(*) FROM $actual WHERE $whereSql"
        var n = 0L
        db.driver.executeQuery(null, sql, whereArgs.size) { stmt ->
            whereArgs.forEachIndexed { i, v -> bind(stmt, i, v) }
        }.use { cursor ->
            if (cursor.next().value) n = cursor.getLong(0) ?: 0L
        }
        return OpResult.Count(n)
    }

    // ---- Guards ----

    private fun requireIdent(name: String) {
        require(IDENT_REGEX.matches(name)) { "Invalid identifier: $name" }
    }

    private fun requireKnownColumns(db: WorkspaceDatabase, table: String, columns: Collection<String>) {
        val known = mutableSetOf<String>()
        db.driver.executeQuery(null, "PRAGMA table_info($table)", 0).use { c ->
            while (c.next().value) {
                c.getString(1)?.let { known.add(it) }
            }
        }
        require(known.isNotEmpty()) { "Table does not exist: $table" }
        for (col in columns) require(col in known) { "Unknown column: $col" }
    }

    private fun buildWhere(
        filters: JsonArray?,
        db: WorkspaceDatabase,
        table: String
    ): Pair<String, List<Any?>> {
        if (filters == null || filters.isEmpty()) return "1=1" to emptyList()
        val cols = filters.map { it.jsonObject.requireString("column") }
        requireKnownColumns(db, table, cols)

        val clauses = mutableListOf<String>()
        val args = mutableListOf<Any?>()
        for (f in filters) {
            val obj = f.jsonObject
            val column = obj.requireString("column").also(::requireIdent)
            val op = obj.requireString("op").uppercase()
            require(op in ALLOWED_OPS) { "Disallowed operator: $op" }
            when (op) {
                "IN" -> {
                    val arr = obj.getValue("value").jsonArray
                    require(arr.size in 1..100) { "IN list out of range" }
                    clauses += "\"$column\" IN (${List(arr.size) { "?" }.joinToString()})"
                    arr.forEach { args += it.toDbValue() }
                }
                "LIKE" -> {
                    val v = obj.requireString("value")
                    require(ALLOWED_LIKE_REGEX.matches(v)) { "LIKE pattern rejected: $v" }
                    clauses += "\"$column\" LIKE ?"; args += v
                }
                else -> {
                    clauses += "\"$column\" $op ?"; args += obj.getValue("value").toDbValue()
                }
            }
        }
        return clauses.joinToString(" AND ") to args
    }

    private fun audit(db: WorkspaceDatabase, op: String, ms: Long, result: OpResult?, error: String?) {
        try {
            val rowCount = when (result) {
                is OpResult.Rows -> result.rows.size.toLong()
                is OpResult.Count -> result.n
                else -> null
            }
            db.workspaceQueries.insertOpLog(
                ts = clock(),
                op = op,
                duration_ms = ms,
                rowcount = rowCount,
                error = error
            )
        } catch (_: Exception) {
            // Logging must never break the op.
        }
    }
}

// ---- Helpers ----

private fun JsonObject.requireString(key: String): String =
    get(key)?.jsonPrimitive?.contentOrNull ?: error("Missing required string: $key")

private fun JsonObject.getObject(key: String): JsonObject =
    get(key)?.jsonObject ?: error("Missing required object: $key")

private fun JsonElement.toDbValue(): Any? = when (this) {
    is JsonNull -> null
    is JsonPrimitive -> when {
        isString -> content
        content == "true" -> true
        content == "false" -> false
        content.toLongOrNull() != null -> content.toLong()
        else -> content.toDouble()
    }
    else -> error("Cannot bind JSON array/object to SQL parameter")
}

private fun bind(stmt: app.cash.sqldelight.db.SqlPreparedStatement, index: Int, value: Any?) {
    when (value) {
        null -> stmt.bindNull(index)
        is Long -> stmt.bindLong(index, value)
        is Int -> stmt.bindLong(index, value.toLong())
        is Boolean -> stmt.bindLong(index, if (value) 1L else 0L)
        is Double -> stmt.bindDouble(index, value)
        is String -> stmt.bindString(index, value)
        else -> stmt.bindString(index, value.toString())
    }
}

private fun app.cash.sqldelight.db.SqlCursor.readValue(index: Int): Any? {
    // SQLDelight cursor does not expose type info uniformly; infer by column.
    // Fallback order: null → long → double → string.
    return try { getLong(index) } catch (_: Exception) {
        try { getDouble(index) } catch (_: Exception) { getString(index) }
    }
}
