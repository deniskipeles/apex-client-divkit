import Foundation

/// Platform-neutral DivKit variable value, mirroring `engine.state.DivVariableValue`.
public enum DivVariableValue: Equatable, Sendable {
    case str(String)
    case intVal(Int64)
    case bool(Bool)
    case doubleVal(Double)
    case arrayVal([DivVariableValue])

    /// Renders the value the way DivKit would display it.
    public var asDisplayString: String {
        switch self {
        case let .str(v): return v
        case let .intVal(v): return String(v)
        case let .bool(v): return v ? "true" : "false"
        case let .doubleVal(v):
            if v == Double(Int64(v)) { return String(Int64(v)) }
            return String(format: "%.2f", v)
        case let .arrayVal(values): return values.map { $0.asDisplayString }.joined(separator: ", ")
        }
    }
}

/// Cross-thread reactive bridge between background workers and the DivKit UI. Mirrors
/// `engine.state.EngineStateBridge`: only `user.*` variables may be written.
public final class EngineState: @unchecked Sendable {
    public static let shared = EngineState()

    private let lock = NSRecursiveLock()
    private var state: [String: DivVariableValue] = [:]
    private var observers: [UUID: ([String: DivVariableValue]) -> Void] = [:]

    public init() {}

    /// Variable names must be `user.*` so a compromised payload cannot overwrite engine
    /// variables (e.g. `engine.error`).
    public func pushUpdate(name: String, value: DivVariableValue) {
        precondition(name.hasPrefix("user."), "Variable must be user.*-namespaced: \(name)")
        lock.lock()
        state[name] = value
        let snapshot = state
        let callbacks = Array(observers.values)
        lock.unlock()
        for cb in callbacks { cb(snapshot) }
    }

    public func pushBatch(_ updates: [String: DivVariableValue]) {
        for key in updates.keys {
            precondition(key.hasPrefix("user."), "Variable must be user.*: \(key)")
        }
        lock.lock()
        for (k, v) in updates { state[k] = v }
        let snapshot = state
        let callbacks = Array(observers.values)
        lock.unlock()
        for cb in callbacks { cb(snapshot) }
    }

    public func getVariable(_ name: String) -> DivVariableValue? {
        lock.lock(); defer { lock.unlock() }
        return state[name]
    }

    public func snapshot() -> [String: DivVariableValue] {
        lock.lock(); defer { lock.unlock() }
        return state
    }

    public func clear() {
        lock.lock()
        state.removeAll()
        let snapshot = state
        let callbacks = Array(observers.values)
        lock.unlock()
        for cb in callbacks { cb(snapshot) }
    }

    /// Registers a state observer; returns a token that removes it when released.
    @discardableResult
    public func observe(_ callback: @escaping ([String: DivVariableValue]) -> Void) -> UUID {
        lock.lock(); defer { lock.unlock() }
        let token = UUID()
        observers[token] = callback
        return token
    }

    public func removeObserver(_ token: UUID) {
        lock.lock(); defer { lock.unlock() }
        observers.removeValue(forKey: token)
    }
}
