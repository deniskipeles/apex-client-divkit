import Foundation

/// One navigation stack entry: a stable card id plus its parsed payload.
///
/// `data` is the parsed `JsonValue` (the DivData pointer on this platform). It is immutable,
/// so pushing/popping entries is a pointer move, never a copy or re-parse.
public struct CardEntry: Equatable {
    public let id: String
    public let data: JsonValue

    public init(id: String, data: JsonValue) {
        self.id = id
        self.data = data
    }
}

/// Cross-platform card registry. Cards are resolved by id from here and never passed inline
/// as JSON through a URL scheme, so the parsed payload is shared by reference (a pointer)
/// between the navigation stack and any bottom sheet that hosts it.
public final class CardRegistry: @unchecked Sendable {
    public static let shared = CardRegistry()

    private let lock = NSLock()
    private var cards: [String: JsonValue] = [:]

    public init() {}

    public func register(id: String, data: JsonValue) {
        lock.lock(); defer { lock.unlock() }
        cards[id] = data
    }

    public func card(id: String) -> JsonValue? {
        lock.lock(); defer { lock.unlock() }
        return cards[id]
    }

    public func has(id: String) -> Bool {
        lock.lock(); defer { lock.unlock() }
        return cards[id] != nil
    }

    public func clear() {
        lock.lock(); defer { lock.unlock() }
        cards.removeAll()
    }
}
