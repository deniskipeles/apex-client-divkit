package engine.crypto

/**
 * Deterministic JSON canonicalisation shared across Kotlin, TypeScript, and Swift.
 *
 * Rules (must match edge and iOS implementations byte-for-byte):
 *  - Object keys sorted lexicographically
 *  - No insignificant whitespace
 *  - Integers emitted without decimal point; doubles with minimal representation
 *  - Strings JSON-escaped
 *  - Null → "null", booleans → "true"/"false"
 *
 * This is intentionally hand-written instead of relying on kotlinx.serialization's
 * output, because serialization library whitespace and ordering are not guaranteed
 * across versions and must match the edge signer exactly.
 */
object CanonicalJson {

    fun canonicalize(value: Any?): String = when (value) {
        null -> "null"
        is Boolean -> if (value) "true" else "false"
        is Int, is Long, is Short, is Byte -> value.toString()
        is Float -> canonicalizeDouble(value.toDouble())
        is Double -> canonicalizeDouble(value)
        is String -> quote(value)
        is List<*> -> value.joinToString(",", "[", "]") { canonicalize(it) }
        is Map<*, *> -> {
            val sorted = value.entries.sortedBy { it.key.toString() }
            sorted.joinToString(",", "{", "}") { (k, v) ->
                quote(k.toString()) + ":" + canonicalize(v)
            }
        }
        else -> error("Unsupported type for canonicalisation: ${value::class}")
    }

    private fun canonicalizeDouble(d: Double): String {
        if (d.isNaN() || d.isInfinite()) error("Non-finite double")
        if (d == d.toLong().toDouble() && d in Long.MIN_VALUE.toDouble()..Long.MAX_VALUE.toDouble()) {
            return d.toLong().toString()
        }
        return d.toString()
    }

    private fun quote(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(c)
            }
        }
        return sb.append('"').toString()
    }
}
