import Foundation

/// Ordered JSON model mirroring kotlinx.serialization's `JsonElement` tree.
///
/// The Android engine builds DivKit payloads and query results out of a small, ordered JSON
/// value type. `JSONSerialization` uses unordered dictionaries and collapses number types, so
/// the engine's own ordered model is mirrored here to keep field order and integer/double
/// distinction identical across platforms.
public indirect enum JsonValue: Sendable, Equatable {
    case null
    case bool(Bool)
    case int(Int64)
    case double(Double)
    case string(String)
    case array([JsonValue])
    case object([(String, JsonValue)])

    public static func == (lhs: JsonValue, rhs: JsonValue) -> Bool {
        switch (lhs, rhs) {
        case (.null, .null): return true
        case let (.bool(a), .bool(b)): return a == b
        case let (.int(a), .int(b)): return a == b
        case let (.double(a), .double(b)): return a == b
        case let (.string(a), .string(b)): return a == b
        case let (.array(a), .array(b)): return a == b
        case let (.object(a), .object(b)):
            guard a.count == b.count else { return false }
            for (l, r) in zip(a, b) where l.0 != r.0 || l.1 != r.1 { return false }
            return true
        default: return false
        }
    }
}

public extension JsonValue {
    // MARK: Object access

    subscript(key: String) -> JsonValue? {
        guard case let .object(pairs) = self else { return nil }
        return pairs.first { $0.0 == key }?.1
    }

    var isNull: Bool { if case .null = self { return true }; return false }

    var objectPairs: [(String, JsonValue)]? {
        if case let .object(pairs) = self { return pairs }
        return nil
    }

    var arrayValue: [JsonValue]? {
        if case let .array(items) = self { return items }
        return nil
    }

    // MARK: Primitive coercion (kotlinx `jsonPrimitive.contentOrNull`/`longOrNull`/…)

    /// Raw textual content of a primitive, or the compact JSON of a composite.
    var contentOrNull: String? {
        switch self {
        case .null: return nil
        case let .bool(b): return b ? "true" : "false"
        case let .int(i): return String(i)
        case let .double(d): return JsonValue.doubleString(d)
        case let .string(s): return s
        case .array, .object: return serialized
        }
    }

    var stringOrNull: String? {
        if case let .string(s) = self { return s }
        return nil
    }

    var intOrNull: Int64? {
        switch self {
        case let .int(i): return i
        case let .double(d): return d == d.rounded() ? Int64(d) : nil
        default: return nil
        }
    }

    var longOrNull: Int64? { intOrNull }

    var doubleOrNull: Double? {
        switch self {
        case let .int(i): return Double(i)
        case let .double(d): return d
        default: return nil
        }
    }

    var boolOrNull: Bool? {
        switch self {
        case let .bool(b): return b
        case let .string(s):
            if s == "true" || s == "1" { return true }
            if s == "false" || s == "0" { return false }
            return nil
        case let .int(i): return i != 0
        default: return nil
        }
    }

    /// True for a JSON string literal.
    /// Converts a runtime `Any` (as produced by tool result maps) into a typed `JsonValue`,
    /// preserving Bool/Int/Int64/Double/Float and falling back to a string.
    static func fromAny(_ value: Any) -> JsonValue {
        switch value {
        case let b as Bool: return .bool(b)
        case let i as Int: return .int(Int64(i))
        case let i as Int64: return .int(i)
        case let d as Double: return .double(d)
        case let f as Float: return .double(Double(f))
        case let s as String: return .string(s)
        case let j as JsonValue: return j
        default: return .string("\(value)")
        }
    }

    var isString: Bool { if case .string = self { return true }; return false }

    // MARK: Serialization

    /// Compact serialization with a stable key order (declaration order for objects).
    var serialized: String {
        switch self {
        case .null: return "null"
        case let .bool(b): return b ? "true" : "false"
        case let .int(i): return String(i)
        case let .double(d): return JsonValue.doubleString(d)
        case let .string(s): return JsonValue.quote(s)
        case let .array(items):
            return "[" + items.map { $0.serialized }.joined(separator: ",") + "]"
        case let .object(pairs):
            return "{" + pairs.map { JsonValue.quote($0.0) + ":" + $0.1.serialized }.joined(separator: ",") + "}"
        }
    }

    static func doubleString(_ d: Double) -> String {
        if d.isNaN || d.isInfinite { return "null" }
        if d == d.rounded(), d.magnitude < 9.007199254740992e15 {
            return String(Int64(d))
        }
        return String(d)
    }

    static func quote(_ s: String) -> String {
        var out = "\""
        for c in s.unicodeScalars {
            switch c {
            case "\"": out += "\\\""
            case "\\": out += "\\\\"
            case "\n": out += "\\n"
            case "\r": out += "\\r"
            case "\t": out += "\\t"
            default:
                if c.value < 0x20 {
                    out += String(format: "\\u%04x", c.value)
                } else {
                    out.unicodeScalars.append(c)
                }
            }
        }
        return out + "\""
    }
}

// MARK: - Parsing

public extension JsonValue {
    /// Parses a JSON document. Uses `JSONSerialization` for tokenising, then re-wraps into the
    /// ordered model. Integers stay integers; fractional values become doubles.
    static func parse(_ text: String) -> JsonValue? {
        guard let data = text.data(using: .utf8),
              let raw = try? JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed])
        else { return nil }
        return JsonValue.from(raw)
    }

    static func from(_ raw: Any) -> JsonValue {
        if raw is NSNull { return .null }
        if let s = raw as? String { return .string(s) }
        if let b = raw as? Bool { return .bool(b) }
        if let n = raw as? NSNumber {
            if String(cString: n.objCType) == "c" { return .bool(n.boolValue) }
            let d = n.doubleValue
            if d == d.rounded(), d.magnitude < 9.007199254740992e15 { return .int(Int64(d)) }
            return .double(d)
        }
        if let arr = raw as? [Any] { return .array(arr.map { JsonValue.from($0) }) }
        if let dict = raw as? [String: Any] {
            return .object(dict.keys.sorted().map { ($0, JsonValue.from(dict[$0]!)) })
        }
        return .string(String(describing: raw))
    }

    /// A JSON object's pairs as a `[String: JsonValue]` dictionary (unordered).
    var asDictionary: [String: JsonValue] {
        guard case let .object(pairs) = self else { return [:] }
        return Dictionary(pairs, uniquingKeysWith: { _, b in b })
    }
}

public extension Dictionary where Key == String, Value == JsonValue {
    /// Wraps a dictionary as a JSON object with sorted keys, matching kotlinx `JsonObject`.
    var asJsonValue: JsonValue {
        .object(keys.sorted().map { ($0, self[$0]!) })
    }
}
