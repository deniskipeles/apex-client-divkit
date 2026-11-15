import Foundation

/// Deterministic JSON canonicalisation. Byte-for-byte compatible with
/// `engine.crypto.CanonicalJson` (Kotlin) and the Web implementation.
/// See packages/contract/docs/canonical-json.md.
public enum CanonicalJson {

    public static func canonicalize(_ value: Any?) -> String {
        switch value {
        case nil, is NSNull:
            return "null"
        case let b as Bool:
            return b ? "true" : "false"
        case let i as Int:
            return String(i)
        case let i as Int64:
            return String(i)
        case let d as Double:
            return canonicalizeDouble(d)
        case let s as String:
            return quote(s)
        case let a as [Any?]:
            return "[" + a.map { canonicalize($0) }.joined(separator: ",") + "]"
        case let m as [String: Any?]:
            return canonicalizeMap(m)
        default:
            fatalError("Unsupported type for canonicalisation: \(type(of: value!))")
        }
    }

    private static func canonicalizeMap(_ map: [String: Any?]) -> String {
        let sorted = map.keys.sorted()
        return "{" + sorted.map { key in
            quote(key) + ":" + canonicalize(map[key] ?? nil)
        }.joined(separator: ",") + "}"
    }

    private static func canonicalizeDouble(_ d: Double) -> String {
        if d.isNaN || d.isInfinite { fatalError("Non-finite double") }
        if d == Double(Int64(d)) {
            return String(Int64(d))
        }
        return String(d)
    }

    private static func quote(_ s: String) -> String {
        var out = "\""
        for c in s {
            switch c {
            case "\"": out += "\\\""
            case "\\": out += "\\\\"
            case "\n": out += "\\n"
            case "\r": out += "\\r"
            case "\t": out += "\\t"
            default: out.append(c)
            }
        }
        return out + "\""
    }

    /// Converts a `JSONSerialization` value into the canonicalisation input model
    /// (NSNumber booleans become `Bool`, integral doubles collapse to `Int64`).
    public static func normalize(_ value: Any) -> Any? {
        if value is NSNull { return nil }
        if let num = value as? NSNumber {
            if String(cString: num.objCType) == "c" { return num.boolValue }
            let d = num.doubleValue
            if d == Double(Int64(d)) && abs(d) < 9.007199254740992e15 { return Int64(d) }
            return d
        }
        if let s = value as? String { return s }
        if let arr = value as? [Any] { return arr.map { normalize($0) } }
        if let dict = value as? [String: Any] {
            return dict.mapValues { normalize($0) }
        }
        return value
    }
}
