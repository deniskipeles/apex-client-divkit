package engine.db.query

import kotlinx.serialization.json.*

sealed class FilterNode {
    data class Condition(val field: String, val op: FilterOp, val value: JsonElement) : FilterNode()
    data class Group(val op: LogicOp, val children: List<FilterNode>) : FilterNode()
    object Empty : FilterNode()

    enum class FilterOp {
        EQ, NEQ, GT, GTE, LT, LTE, IN, NIN, LIKE, CONTAINS
    }

    enum class LogicOp {
        AND, OR
    }

    companion object {
        fun parse(json: JsonElement?): FilterNode {
            if (json !is JsonObject) return Empty
            val conditions = mutableListOf<FilterNode>()

            for ((key, valElement) in json) {
                if (key.equals("\$and", ignoreCase = true) || key.equals("\$or", ignoreCase = true)) {
                    val logicOp = if (key.equals("\$and", ignoreCase = true)) LogicOp.AND else LogicOp.OR
                    if (valElement is JsonArray) {
                        val children = valElement.map { parse(it) }
                        conditions.add(Group(logicOp, children))
                    }
                } else {
                    conditions.add(parseFieldCondition(key, valElement))
                }
            }

            return when {
                conditions.isEmpty() -> Empty
                conditions.size == 1 -> conditions[0]
                else -> Group(LogicOp.AND, conditions)
            }
        }

        private fun parseFieldCondition(field: String, valElement: JsonElement): FilterNode {
            if (valElement is JsonObject) {
                val entry = valElement.entries.firstOrNull()
                if (entry != null) {
                    val op = when (entry.key.lowercase()) {
                        "\$eq", "eq", "_eq" -> FilterOp.EQ
                        "\$neq", "neq", "_neq" -> FilterOp.NEQ
                        "\$gt", "gt", "_gt" -> FilterOp.GT
                        "\$gte", "gte", "_gte" -> FilterOp.GTE
                        "\$lt", "lt", "_lt" -> FilterOp.LT
                        "\$lte", "lte", "_lte" -> FilterOp.LTE
                        "\$in", "in", "_in" -> FilterOp.IN
                        "\$nin", "nin", "_nin" -> FilterOp.NIN
                        "\$like", "like", "_like" -> FilterOp.LIKE
                        "\$contains", "contains", "_contains" -> FilterOp.CONTAINS
                        else -> return Condition(field, FilterOp.EQ, valElement)
                    }
                    return Condition(field, op, entry.value)
                }
            }
            return Condition(field, FilterOp.EQ, valElement)
        }
    }

    /**
     * Translates AST node into parameterized SQL statements with safe parameter bindings.
     * Uses json_extract() to guarantee compatibility across all Android versions (API 24 to 36).
     */
    fun toSql(params: MutableList<Any?>): String? {
        return when (this) {
            is Empty -> null
            is Group -> {
                val parts = children.mapNotNull { it.toSql(params) }
                if (parts.isEmpty()) return null
                val joiner = if (op == LogicOp.AND) " AND " else " OR "
                "(${parts.joinToString(joiner)})"
            }
            is Condition -> {
                val column = formatJsonColumn(field)
                when (op) {
                    FilterOp.IN, FilterOp.NIN -> {
                        if (value !is JsonArray || value.isEmpty()) return "1=0"
                        val placeholders = value.joinToString(", ") { "?" }
                        value.forEach { params.add(jsonToSqlVal(it)) }
                        val sqlOp = if (op == FilterOp.NIN) "NOT IN" else "IN"
                        "$column $sqlOp ($placeholders)"
                    }
                    else -> {
                        val sqlOp = when (op) {
                            FilterOp.EQ -> "="
                            FilterOp.NEQ -> "!="
                            FilterOp.GT -> ">"
                            FilterOp.GTE -> ">="
                            FilterOp.LT -> "<"
                            FilterOp.LTE -> "<="
                            FilterOp.LIKE, FilterOp.CONTAINS -> "LIKE"
                            FilterOp.IN, FilterOp.NIN -> "IN"
                        }

                        var finalVal = jsonToSqlVal(value)
                        if (op == FilterOp.CONTAINS && value is JsonPrimitive) {
                            finalVal = "%${value.content}%"
                        }

                        params.add(finalVal)
                        "$column $sqlOp ?"
                    }
                }
            }
        }
    }

    /**
     * In-memory equivalent of [toSql], used on Android builds whose SQLite lacks JSON1.
     * [record] contains the flat record fields plus `id`, `created` and `updated`.
     */
    fun matches(record: Map<String, JsonElement>): Boolean = when (this) {
        is Empty -> true
        is Group -> if (op == LogicOp.AND) children.all { it.matches(record) } else children.any { it.matches(record) }
        is Condition -> matchesCondition(record[normalizeField(field)], op, value)
    }

    private fun matchesCondition(actual: JsonElement?, op: FilterOp, value: JsonElement): Boolean {
        val actualText = when (actual) {
            null, is JsonNull -> null
            is JsonPrimitive -> actual.contentOrNull ?: actual.toString()
            else -> actual.toString()
        }
        return when (op) {
            FilterOp.EQ -> valuesEqual(actualText, value)
            FilterOp.NEQ -> !valuesEqual(actualText, value)
            FilterOp.GT -> compareValues(actualText, value) > 0
            FilterOp.GTE -> compareValues(actualText, value) >= 0
            FilterOp.LT -> compareValues(actualText, value) < 0
            FilterOp.LTE -> compareValues(actualText, value) <= 0
            FilterOp.IN -> (value as? JsonArray)?.any { valuesEqual(actualText, it) } ?: false
            FilterOp.NIN -> (value as? JsonArray)?.none { valuesEqual(actualText, it) } ?: true
            FilterOp.LIKE -> likeMatch(actualText, value)
            // SQLite `LIKE` folds ASCII case; the in-memory path must agree or a search box
            // behaves differently depending on whether JSON1 is available.
            FilterOp.CONTAINS -> actualText?.contains(
                (value as? JsonPrimitive)?.contentOrNull ?: value.toString(),
                ignoreCase = true,
            ) ?: false
        }
    }

    private fun valuesEqual(actual: String?, expected: JsonElement): Boolean {
        if (actual == null) return expected is JsonNull
        val expectedText = when (expected) {
            is JsonNull -> return false
            is JsonPrimitive -> expected.contentOrNull ?: expected.toString()
            else -> expected.toString()
        }
        val actualNumber = actual.toDoubleOrNull()
        val expectedNumber = expectedText.toDoubleOrNull()
        return if (actualNumber != null && expectedNumber != null) actualNumber == expectedNumber else actual == expectedText
    }

    private fun compareValues(actual: String?, expected: JsonElement): Int {
        if (actual == null) return -1
        val expectedText = when (expected) {
            is JsonNull -> ""
            is JsonPrimitive -> expected.contentOrNull ?: expected.toString()
            else -> expected.toString()
        }
        val actualNumber = actual.toDoubleOrNull()
        val expectedNumber = expectedText.toDoubleOrNull()
        return if (actualNumber != null && expectedNumber != null) {
            actualNumber.compareTo(expectedNumber)
        } else {
            actual.compareTo(expectedText)
        }
    }

    private fun likeMatch(actual: String?, expected: JsonElement): Boolean {
        if (actual == null) return false
        val pattern = (expected as? JsonPrimitive)?.contentOrNull ?: expected.toString()
        val regex = Regex("^" + Regex.escape(pattern).replace("%", ".*").replace("_", ".") + "$", RegexOption.IGNORE_CASE)
        return regex.matches(actual)
    }

    private fun normalizeField(key: String): String = when {
        key.startsWith("@record.data.") -> key.removePrefix("@record.data.")
        key.startsWith("@record.") -> key.removePrefix("@record.")
        else -> key
    }

    private fun formatJsonColumn(key: String): String {
        val cleanKey = when {
            key.startsWith("@record.data.") -> key.removePrefix("@record.data.")
            key.startsWith("@record.") -> key.removePrefix("@record.")
            else -> key
        }

        if (cleanKey == "id") return "records.id"
        if (cleanKey == "created") return "records.created"
        if (cleanKey == "updated") return "records.updated"

        val sanitizedPath = cleanKey.replace("'", "''")
        return "json_extract(records.data, '$.$sanitizedPath')"
    }

    private fun jsonToSqlVal(v: JsonElement): Any? {
        return when (v) {
            is JsonPrimitive -> {
                when {
                    v.isString -> v.content
                    v.intOrNull != null -> v.int
                    v.longOrNull != null -> v.long
                    v.doubleOrNull != null -> v.double
                    v.booleanOrNull != null -> if (v.boolean) 1L else 0L
                    else -> v.content
                }
            }
            else -> v.toString()
        }
    }
}