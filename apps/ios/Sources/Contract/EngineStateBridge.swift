import Foundation

/// Swift mirror of the engine's DivKit variable bridge. Only `user.*` variables may be
/// written. See packages/contract/docs/variables.md.
public final class EngineStateBridge: @unchecked Sendable {
    public static let shared = EngineStateBridge()

    private let lock = NSLock()
    private var state: [String: Any] = [:]

    public init() {}

    public func pushUpdate(name: String, value: Any) {
        precondition(name.hasPrefix("user."), "Variable must be user.*-namespaced: \(name)")
        lock.lock(); defer { lock.unlock() }
        state[name] = value
    }

    public func pushBatch(_ updates: [String: Any]) {
        for key in updates.keys {
            precondition(key.hasPrefix("user."), "Variable must be user.*: \(key)")
        }
        lock.lock(); defer { lock.unlock() }
        state.merge(updates) { _, new in new }
    }

    public func getVariable(_ name: String) -> Any? {
        lock.lock(); defer { lock.unlock() }
        return state[name]
    }

    public func clear() {
        lock.lock(); defer { lock.unlock() }
        state.removeAll()
    }
}
