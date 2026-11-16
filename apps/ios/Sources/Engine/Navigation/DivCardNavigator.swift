import Foundation

/// Pointer stack + bounded LRU over card ids.
///
/// The stack holds `CardEntry` values whose `data` is immutable, never copies. The LRU bounds
/// how many card views stay resident at once; an evicted card re-renders from its pointer on
/// the next push with no re-parse. The visible card is never evicted.
///
/// `View` is the cached view type. On the app target it is `DivView`; on Linux (CI) it is an
/// opaque `AnyObject`, which keeps the navigator's stack/LRU semantics testable everywhere.
public final class DivCardNavigator<View: AnyObject> {
    public private(set) var stack: [CardEntry]
    public private(set) var index: Int
    public let cacheLimit: Int

    private var viewCache: [String: View] = [:]
    private var lruOrder: [String] = []

    public init(initialCards: [CardEntry], cacheLimit: Int = 8) {
        precondition(!initialCards.isEmpty, "navigator needs at least one card")
        precondition(cacheLimit >= 1, "cacheLimit must be >= 1")
        self.stack = initialCards
        self.index = initialCards.count - 1
        self.cacheLimit = cacheLimit
        initialCards.forEach { touch($0.id) }
    }

    public var current: CardEntry? {
        stack.indices.contains(index) ? stack[index] : nil
    }

    public var liveCardIds: [String] {
        lruOrder.filter { id in stack.contains { $0.id == id } }
    }

    public func push(_ entry: CardEntry) {
        stack.append(entry)
        index = stack.count - 1
        touch(entry.id)
    }

    public func replace(_ entry: CardEntry) {
        guard !stack.isEmpty else {
            push(entry)
            return
        }
        stack[index] = entry
        touch(entry.id)
    }

    public func reset(_ entry: CardEntry) {
        stack = [entry]
        index = 0
        lruOrder.removeAll()
        touch(entry.id)
    }

    public func pop() {
        guard index > 0 else { return }
        stack.removeLast()
        index -= 1
        touch(current?.id)
    }

    // MARK: - View cache

    /// Returns the cached view for `entry.id`, or builds one with `make` and caches it.
    /// Reuse (not rebuild) is what preserves scroll, focus, and animations across push/pop.
    public func view(for entry: CardEntry, make: () -> View) -> View {
        if let cached = viewCache[entry.id] {
            touch(entry.id)
            return cached
        }
        let created = make()
        viewCache[entry.id] = created
        touch(entry.id)
        return created
    }

    public func cachedView(id: String) -> View? {
        viewCache[id]
    }

    private func touch(_ id: String?) {
        guard let id else { return }
        lruOrder.removeAll { $0 == id }
        lruOrder.append(id)
        evictIfNeeded()
    }

    private func evictIfNeeded() {
        while lruOrder.count > cacheLimit {
            let oldest = lruOrder.removeFirst()
            if oldest == current?.id {
                // Never evict the visible card; rotate it to the back and stop.
                lruOrder.append(oldest)
                continue
            }
            viewCache.removeValue(forKey: oldest)
        }
    }
}
