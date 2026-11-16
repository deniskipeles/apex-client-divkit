package engine.navigation

import java.net.URLDecoder

/**
 * A parsed `nav://` / `sheet://` action. Mirrors the shape pinned by
 * packages/contract/fixtures/navigation/v1 and parsed identically on every platform.
 */
sealed class NavOp {
    data class Push(val cardId: String) : NavOp()
    object Pop : NavOp()
    data class Replace(val cardId: String) : NavOp()
    data class Reset(val cardId: String) : NavOp()
    data class SheetPresent(val cardId: String) : NavOp()
    object SheetDismiss : NavOp()

    /**
     * Fixture-facing encoding: `{"op": "...", "cardId": "..."}`, `cardId` omitted for the
     * parameterless ops. Used by NavigationContractTest to compare against the shared fixtures.
     */
    fun toFixtureMap(): Map<String, String> = when (this) {
        is Push -> mapOf("op" to "push", "cardId" to cardId)
        is Pop -> mapOf("op" to "pop")
        is Replace -> mapOf("op" to "replace", "cardId" to cardId)
        is Reset -> mapOf("op" to "reset", "cardId" to cardId)
        is SheetPresent -> mapOf("op" to "sheet-present", "cardId" to cardId)
        is SheetDismiss -> mapOf("op" to "sheet-dismiss")
    }
}

/**
 * Parses navigation action URLs without depending on `android.net.Uri`, so the parser is
 * pure-JVM testable and byte-identical to `parseNavigationUrl` (web) and `NavigationUrl.parse`
 * (iOS). Returns `null` for any URL the navigation layer does not own.
 */
object NavigationUrlParser {

    fun parse(url: String): NavOp? {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd <= 0) return null
        val scheme = url.substring(0, schemeEnd).lowercase()
        val rest = url.substring(schemeEnd + 3)

        val queryStart = rest.indexOf('?')
        val host = (if (queryStart >= 0) rest.substring(0, queryStart) else rest).lowercase()
        val query = if (queryStart >= 0) rest.substring(queryStart + 1) else ""
        val params = parseQuery(query)

        fun param(key: String): String? = params[key]?.takeIf { it.isNotEmpty() }

        return when (scheme) {
            "nav" -> when (host) {
                "push" -> param("id")?.let { NavOp.Push(it) }
                "pop" -> NavOp.Pop
                "replace" -> param("id")?.let { NavOp.Replace(it) }
                "reset" -> param("id")?.let { NavOp.Reset(it) }
                else -> null
            }
            "sheet" -> when (host) {
                "present" -> param("card_id")?.let { NavOp.SheetPresent(it) }
                "dismiss" -> NavOp.SheetDismiss
                else -> null
            }
            else -> null
        }
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isEmpty()) return emptyMap()
        val out = mutableMapOf<String, String>()
        for (part in query.split('&')) {
            if (part.isEmpty()) continue
            val idx = part.indexOf('=')
            if (idx <= 0) continue
            val name = decode(part.substring(0, idx))
            val value = decode(part.substring(idx + 1))
            out[name] = value
        }
        return out
    }

    private fun decode(s: String): String =
        try {
            URLDecoder.decode(s, "UTF-8")
        } catch (_: Exception) {
            s
        }
}
