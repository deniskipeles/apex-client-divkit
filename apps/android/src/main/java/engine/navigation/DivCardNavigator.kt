package engine.navigation

/**
 * Pointer stack + bounded LRU over card ids.
 *
 * The stack holds [CardEntry] values whose [CardEntry.data] is immutable, never copies. The
 * LRU bounds how many card ids stay resident at once; an evicted card re-renders from its
 * pointer on the next push with no re-parse. The visible card is never evicted.
 *
 * This class is intentionally free of Compose/Android imports so its stack/LRU semantics are
 * unit-testable on the JVM. The Compose host (`DivNavigationHost`) keeps the actual
 * `DivView` composition per id and consults [liveCardIds] to decide what stays composed.
 */
class DivCardNavigator(initialCards: List<CardEntry>, private val cacheLimit: Int = 8) {

    init {
        require(initialCards.isNotEmpty()) { "navigator needs at least one card" }
        require(cacheLimit >= 1) { "cacheLimit must be >= 1" }
    }

    private val entries = initialCards.toMutableList()
    private var index = initialCards.size - 1
    private val lruOrder = ArrayDeque<String>()

    init {
        initialCards.forEach { touch(it.id) }
    }

    val current: CardEntry? get() = entries.getOrNull(index)
    val currentIndex: Int get() = index
    val stack: List<CardEntry> get() = entries.toList()

    /** Card ids that should stay composed, oldest first, never exceeding [cacheLimit]. */
    val liveCardIds: List<String>
        get() = lruOrder.filter { id -> entries.any { it.id == id } }

    fun push(entry: CardEntry) {
        entries.add(entry)
        index = entries.size - 1
        touch(entry.id)
    }

    fun replace(entry: CardEntry) {
        if (entries.isEmpty()) {
            push(entry)
            return
        }
        entries[index] = entry
        touch(entry.id)
    }

    fun reset(entry: CardEntry) {
        entries.clear()
        entries.add(entry)
        index = 0
        lruOrder.clear()
        touch(entry.id)
    }

    fun pop() {
        if (index == 0) return
        entries.removeAt(entries.size - 1)
        index -= 1
        current?.let { touch(it.id) }
    }

    private fun touch(id: String) {
        lruOrder.remove(id)
        lruOrder.addLast(id)
        evictIfNeeded()
    }

    private fun evictIfNeeded() {
        while (lruOrder.size > cacheLimit) {
            val oldest = lruOrder.first()
            if (oldest == current?.id) {
                // Never evict the visible card; rotate it to the back and stop.
                lruOrder.removeFirst()
                lruOrder.addLast(oldest)
                continue
            }
            lruOrder.removeFirst()
        }
    }
}
