package engine.navigation

import kotlinx.serialization.json.JsonObject

/**
 * One navigation stack entry: a stable card id plus its parsed payload.
 *
 * [data] is the parsed `JsonObject` (the DivData pointer on this platform). It is immutable,
 * so pushing/popping entries is a pointer move, never a copy or re-parse.
 */
data class CardEntry(val id: String, val data: JsonObject)

/**
 * Cross-platform card registry. Cards are resolved by id from here and never passed inline
 * as JSON through a URL scheme, so the parsed payload is shared by reference (a pointer)
 * between the navigation stack and any bottom sheet that hosts it.
 */
object CardRegistry {
    private val cards = LinkedHashMap<String, JsonObject>()

    @Synchronized
    fun register(id: String, data: JsonObject) {
        cards[id] = data
    }

    @Synchronized
    fun card(id: String): JsonObject? = cards[id]

    @Synchronized
    fun has(id: String): Boolean = cards.containsKey(id)

    @Synchronized
    fun clear() = cards.clear()
}
