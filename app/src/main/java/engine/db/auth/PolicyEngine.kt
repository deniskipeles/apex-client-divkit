package engine.db.auth

import kotlinx.serialization.json.*

data class Claims(
    val uid: Long = 0L,
    val sub: String = "anonymous",
    val role: String = "public",
    val scope: String = "root"
)

object PolicyEngine {

    fun compileToSql(policy: String, user: Claims? = null): String {
        val rule = policy.trim()
        if (rule.isEmpty() || rule.equals("public", ignoreCase = true)) return "1=1"
        if (user?.role.equals("admin", ignoreCase = true)) return "1=1"
        if (rule.equals("admin", ignoreCase = true)) return if (user?.role == "admin") "1=1" else "1=0"
        if (rule.equals("auth", ignoreCase = true)) return if (user != null && user.role != "public") "1=1" else "1=0"

        // Handle OR expression: "admin || staff || owner:user_id"
        if (rule.contains("||")) {
            val clauses = rule.split("||").map { compileToSql(it.trim(), user) }
            return "(${clauses.joinToString(" OR ")})"
        }

        // Handle AND expression: "auth && role:staff"
        if (rule.contains("&&")) {
            val clauses = rule.split("&&").map { compileToSql(it.trim(), user) }
            return "(${clauses.joinToString(" AND ")})"
        }

        // Handle owner:field_name
        if (rule.startsWith("owner:")) {
            val field = rule.removePrefix("owner:").replace("'", "''")
            return if (user != null && user.uid > 0) {
                "json_extract(records.data, '$.$field') = '${user.uid}'"
            } else {
                "1=0"
            }
        }

        // Handle JSON Object policy
        if (rule.startsWith("{")) {
            return try {
                val element = Json.parseToJsonElement(rule)
                jsonConditionToSql(element, user)
            } catch (_: Exception) {
                "1=0"
            }
        }

        // Simple role equality match
        if (user != null && rule.equals(user.role, ignoreCase = true)) {
            return "1=1"
        }

        return "1=0"
    }

    private fun jsonConditionToSql(json: JsonElement, user: Claims?): String {
        if (json !is JsonObject) return "1=1"
        val clauses = mutableListOf<String>()

        for ((key, value) in json) {
            when {
                key.equals("\$or", ignoreCase = true) && value is JsonArray -> {
                    val sub = value.map { jsonConditionToSql(it, user) }
                    clauses.add("(${sub.joinToString(" OR ")})")
                }
                key.equals("\$and", ignoreCase = true) && value is JsonArray -> {
                    val sub = value.map { jsonConditionToSql(it, user) }
                    clauses.add("(${sub.joinToString(" AND ")})")
                }
                key.equals("@request.auth.role", ignoreCase = true) -> {
                    val targetRole = value.jsonPrimitive.contentOrNull ?: ""
                    if (user?.role == targetRole) clauses.add("1=1") else clauses.add("1=0")
                }
                key.equals("@request.auth.id", ignoreCase = true) -> {
                    clauses.add("1=1")
                }
                else -> {
                    val col = "json_extract(records.data, '$.$key')"
                    val resolvedValue = if (value is JsonPrimitive && value.content == "@request.auth.id") {
                        user?.uid?.toString() ?: "NULL"
                    } else if (value is JsonPrimitive) {
                        value.content.replace("'", "''")
                    } else {
                        value.toString()
                    }
                    clauses.add("$col = '$resolvedValue'")
                }
            }
        }
        return if (clauses.isEmpty()) "1=1" else clauses.joinToString(" AND ")
    }
}