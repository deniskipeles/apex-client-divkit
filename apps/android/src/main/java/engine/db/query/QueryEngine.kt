package engine.db.query

import engine.sqlite.SqlDriver
import kotlinx.serialization.json.*

object QueryEngine {

    fun buildSql(query: ApexQuery, collectionId: Long?): Pair<String, List<Any?>> {
        val params = mutableListOf<Any?>()
        val selectClauses = mutableListOf<String>()
        val groupClauses = mutableListOf<String>()

        val isSystem = query.system || query.from.startsWith("_")
        val tableName = if (isSystem) query.from else "records"
        val wherePrefix = if (isSystem) "WHERE 1=1" else {
            params.add(collectionId ?: 0L)
            "WHERE records.collection_id = ?"
        }

        // 1. Projections
        if (query.select.isEmpty()) {
            if (isSystem) {
                selectClauses.add("*")
            } else {
                selectClauses.add("records.id AS id")
                selectClauses.add("records.data AS data")
                selectClauses.add("records.created AS created")
                selectClauses.add("records.updated AS updated")
            }
        } else {
            for (field in query.select) {
                when (field) {
                    is SelectField.Simple -> {
                        val col = if (isSystem) field.value else resolveColumn(field.value)
                        selectClauses.add("$col AS \"${field.value}\"")
                    }
                    is SelectField.Complex -> {
                        val baseCol = field.expr ?: field.field?.let { if (isSystem) it else resolveColumn(it) } ?: "*"
                        val sqlExpr = when (field.fn?.lowercase()) {
                            "count" -> "COUNT($baseCol)"
                            "sum" -> "SUM(CAST($baseCol AS REAL))"
                            "avg" -> "AVG(CAST($baseCol AS REAL))"
                            "min" -> "MIN(CAST($baseCol AS REAL))"
                            "max" -> "MAX(CAST($baseCol AS REAL))"
                            "year" -> "strftime('%Y', $baseCol)"
                            "month" -> "strftime('%m', $baseCol)"
                            "day" -> "strftime('%Y-%m-%d', $baseCol)"
                            else -> baseCol
                        }
                        if (field.`as` != null) {
                            selectClauses.add("$sqlExpr AS \"${field.`as`}\"")
                        } else {
                            selectClauses.add(sqlExpr)
                        }
                    }
                }
            }
        }

        // 2. Filter Predicate
        var whereSql = wherePrefix
        if (query.where != null) {
            val node = FilterNode.parse(query.where)
            val filterSql = node.toSql(params)
            if (filterSql != null) {
                whereSql += " AND ($filterSql)"
            }
        }

        // 3. GROUP BY
        if (query.group_by.isNotEmpty()) {
            query.group_by.forEach { g ->
                groupClauses.add(if (isSystem) g else resolveColumn(g))
            }
        }
        val groupSql = if (groupClauses.isEmpty()) "" else "GROUP BY ${groupClauses.joinToString(", ")}"

        // 4. ORDER BY
        val orderSql = if (query.sort != null) {
            val parts = query.sort.split(",").map { s ->
                val trimmed = s.trim()
                val desc = trimmed.startsWith("-")
                val clean = trimmed.removePrefix("-")
                val col = if (isSystem) clean else resolveColumn(clean)
                "$col ${if (desc) "DESC" else "ASC"}"
            }
            "ORDER BY ${parts.joinToString(", ")}"
        } else if (isSystem) {
            "ORDER BY 1 DESC"
        } else {
            "ORDER BY records.id DESC"
        }

        val limit = query.limit ?: 100
        val offset = query.offset ?: 0

        val finalSql = "SELECT ${selectClauses.joinToString(", ")} FROM $tableName $whereSql $groupSql $orderSql LIMIT $limit OFFSET $offset"
        return Pair(finalSql, params)
    }

    private fun resolveColumn(field: String): String {
        return when (field) {
            "id" -> "records.id"
            "created" -> "records.created"
            "updated" -> "records.updated"
            else -> {
                val sanitized = field.replace("'", "''")
                "json_extract(records.data, '$.$sanitized')"
            }
        }
    }

    /**
     * Executes the compiled SQL query and applies in-memory pipeline steps.
     */
    fun execute(driver: SqlDriver, query: ApexQuery, collectionId: Long?): JsonArray {
        val (sql, params) = buildSql(query, collectionId)
        val rows = if (!driver.supportsJson1 && sql.contains("json_extract", ignoreCase = true)) {
            executeInMemory(driver, query, collectionId)
        } else {
            try {
                executeSql(driver, sql, params)
            } catch (e: Exception) {
                if (!isMissingJson1(e)) throw e
                executeInMemory(driver, query, collectionId)
            }
        }

        var processed = rows.toList()
        if (query.pipeline.isNotEmpty()) {
            processed = processPipeline(processed, query.pipeline)
        }

        return JsonArray(processed)
    }

    private fun executeSql(driver: SqlDriver, sql: String, params: List<Any?>): List<JsonObject> {
        val rows = mutableListOf<JsonObject>()
        driver.executeQuery(null, sql, params.size) {
            params.forEachIndexed { index, param ->
                when (param) {
                    null -> bindNull(index)
                    is Long -> bindLong(index, param)
                    is Int -> bindLong(index, param.toLong())
                    is Double -> bindDouble(index, param)
                    is Float -> bindDouble(index, param.toDouble())
                    is Boolean -> bindLong(index, if (param) 1L else 0L)
                    else -> bindString(index, param.toString())
                }
            }
        }.use { cursor ->
            while (cursor.next().value) {
                val map = mutableMapOf<String, JsonElement>()
                for (i in 0 until cursor.columnCount) {
                    val colName = cursor.getColumnName(i).ifBlank { "col_$i" }
                    val rawStr = cursor.getString(i)
                    val value = when {
                        rawStr == null -> JsonNull
                        (rawStr.startsWith("{") && rawStr.endsWith("}")) || (rawStr.startsWith("[") && rawStr.endsWith("]")) -> {
                            try {
                                Json.parseToJsonElement(rawStr)
                            } catch (_: Exception) {
                                JsonPrimitive(rawStr)
                            }
                        }
                        rawStr.toLongOrNull() != null -> JsonPrimitive(rawStr.toLong())
                        rawStr.toDoubleOrNull() != null -> JsonPrimitive(rawStr.toDouble())
                        rawStr.equals("true", ignoreCase = true) || rawStr.equals("false", ignoreCase = true) ->
                            JsonPrimitive(rawStr.toBoolean())
                        else -> JsonPrimitive(rawStr)
                    }
                    map[colName] = value
                }
                rows.add(JsonObject(map))
            }
        }
        return rows
    }

    /**
     * Android's bundled SQLite has no JSON1 extension on some API levels (notably API 28),
     * so `json_extract` fails at runtime. Records are small enough to filter/sort in memory,
     * and doing so keeps the same result shape as the SQL path.
     */
    private fun executeInMemory(driver: SqlDriver, query: ApexQuery, collectionId: Long?): List<JsonObject> {
        if (query.system || collectionId == null) return emptyList()

        val records = mutableListOf<Pair<JsonObject, Map<String, JsonElement>>>()
        driver.executeQuery(
            null,
            "SELECT id, data, created, updated FROM records WHERE collection_id = ?",
            1
        ) { bindLong(0, collectionId) }.use { cursor ->
            while (cursor.next().value) {
                val id = cursor.getString(0) ?: ""
                val rawData = cursor.getString(1) ?: "{}"
                val data = try {
                    Json.parseToJsonElement(rawData) as? JsonObject ?: JsonObject(emptyMap())
                } catch (_: Exception) {
                    JsonObject(emptyMap())
                }
                val flat = LinkedHashMap<String, JsonElement>()
                data.forEach { (k, v) -> flat[k] = v }
                flat["id"] = JsonPrimitive(id)
                flat["created"] = JsonPrimitive(cursor.getString(2) ?: "")
                flat["updated"] = JsonPrimitive(cursor.getString(3) ?: "")
                records.add(data to flat)
            }
        }

        val predicate = FilterNode.parse(query.where)
        val filtered = records.filter { predicate.matches(it.second) }

        val sorted = if (query.sort.isNullOrBlank()) {
            filtered
        } else {
            val parts = query.sort.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            filtered.sortedWith { left, right ->
                var result = 0
                for (part in parts) {
                    val desc = part.startsWith("-")
                    val field = part.removePrefix("-")
                    result = compareJson(left.second[field], right.second[field])
                    if (result != 0) return@sortedWith if (desc) -result else result
                }
                result
            }
        }

        val offset = (query.offset ?: 0L).coerceAtLeast(0L).toInt()
        val limit = (query.limit ?: 100L).coerceAtLeast(0L).toInt()
        val page = sorted.drop(offset).take(limit)

        return page.map { (data, flat) ->
            if (query.select.isEmpty()) {
                JsonObject(
                    linkedMapOf(
                        "id" to (flat["id"] ?: JsonNull),
                        "data" to data,
                        "created" to (flat["created"] ?: JsonNull),
                        "updated" to (flat["updated"] ?: JsonNull)
                    )
                )
            } else {
                JsonObject(query.select.mapNotNull { field ->
                    when (field) {
                        is SelectField.Simple -> field.value to (flat[field.value] ?: JsonNull)
                        is SelectField.Complex -> {
                            val name = field.`as` ?: field.field ?: field.expr ?: "*"
                            name to (flat[field.field ?: ""] ?: JsonNull)
                        }
                    }
                }.toMap())
            }
        }
    }

    private fun compareJson(left: JsonElement?, right: JsonElement?): Int {
        if (left == null || left is JsonNull) return if (right == null || right is JsonNull) 0 else -1
        if (right == null || right is JsonNull) return 1
        val leftNumber = left.jsonPrimitive.contentOrNull?.toDoubleOrNull()
        val rightNumber = right.jsonPrimitive.contentOrNull?.toDoubleOrNull()
        return if (leftNumber != null && rightNumber != null) {
            leftNumber.compareTo(rightNumber)
        } else {
            left.jsonPrimitive.contentOrNull.orEmpty().compareTo(right.jsonPrimitive.contentOrNull.orEmpty())
        }
    }

    private fun isMissingJson1(error: Throwable): Boolean =
        generateSequence(error) { it.cause }.any {
            it.message?.contains("no such function: json_extract", ignoreCase = true) == true
        }

    private fun processPipeline(data: List<JsonObject>, pipeline: List<PipelineStep>): List<JsonObject> {
        var current = data
        for (step in pipeline) {
            when (step) {
                is PipelineStep.CumulativeSum -> {
                    var runningTotal = 0.0
                    current = current.map { row ->
                        val mutable = row.toMutableMap()
                        val valNum = mutable[step.field]?.jsonPrimitive?.doubleOrNull ?: 0.0
                        runningTotal += valNum
                        mutable[step.output_field] = JsonPrimitive(runningTotal)
                        JsonObject(mutable)
                    }
                }
                is PipelineStep.Pivot -> {
                    val pivotMap = mutableMapOf<String, Double>()
                    for (row in current) {
                        val k = row[step.key]?.jsonPrimitive?.contentOrNull ?: "unknown"
                        val v = row[step.value]?.jsonPrimitive?.doubleOrNull ?: 0.0
                        val existing = pivotMap[k] ?: 0.0
                        when (step.agg.lowercase()) {
                            "sum" -> pivotMap[k] = existing + v
                            "count" -> pivotMap[k] = existing + 1.0
                            "avg" -> pivotMap[k] = (existing + v) / 2.0
                        }
                    }
                    return listOf(JsonObject(pivotMap.mapValues { JsonPrimitive(it.value) }))
                }
            }
        }
        return current
    }
}