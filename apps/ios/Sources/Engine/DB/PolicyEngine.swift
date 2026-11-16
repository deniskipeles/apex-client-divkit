import Foundation

/// Request claims. Mirrors `engine.db.auth.Claims`.
public struct Claims: Sendable {
    public var uid: Int64
    public var sub: String
    public var role: String
    public var scope: String

    public init(uid: Int64 = 0, sub: String = "anonymous", role: String = "public", scope: String = "root") {
        self.uid = uid
        self.sub = sub
        self.role = role
        self.scope = scope
    }
}

/// Compiles row-level-security policies to a SQL predicate. Mirrors
/// `engine.db.auth.PolicyEngine`.
public enum PolicyEngine {

    public static func compileToSql(_ policy: String, user: Claims? = nil) -> String {
        let rule = policy.trimmingCharacters(in: .whitespaces)
        if rule.isEmpty || rule.caseInsensitiveCompare("public") == .orderedSame { return "1=1" }
        if user?.role.caseInsensitiveCompare("admin") == .orderedSame { return "1=1" }
        if rule.caseInsensitiveCompare("admin") == .orderedSame {
            return (user?.role == "admin") ? "1=1" : "1=0"
        }
        if rule.caseInsensitiveCompare("auth") == .orderedSame {
            return (user != nil && user?.role != "public") ? "1=1" : "1=0"
        }

        if rule.contains("||") {
            let clauses = rule.components(separatedBy: "||").map { compileToSql($0.trimmingCharacters(in: .whitespaces), user: user) }
            return "(" + clauses.joined(separator: " OR ") + ")"
        }
        if rule.contains("&&") {
            let clauses = rule.components(separatedBy: "&&").map { compileToSql($0.trimmingCharacters(in: .whitespaces), user: user) }
            return "(" + clauses.joined(separator: " AND ") + ")"
        }

        if rule.hasPrefix("owner:") {
            let field = String(rule.dropFirst("owner:".count)).replacingOccurrences(of: "'", with: "''")
            if let user, user.uid > 0 {
                return "json_extract(records.data, '$.\(field)') = '\(user.uid)'"
            }
            return "1=0"
        }

        if rule.hasPrefix("{") {
            guard let parsed = JsonValue.parse(rule) else { return "1=0" }
            return jsonConditionToSql(parsed, user: user)
        }

        if let user, rule.caseInsensitiveCompare(user.role) == .orderedSame { return "1=1" }
        return "1=0"
    }

    private static func jsonConditionToSql(_ json: JsonValue, user: Claims?) -> String {
        guard case let .object(pairs) = json else { return "1=1" }
        var clauses: [String] = []

        for (key, value) in pairs {
            switch key.lowercased() {
            case "$or":
                let sub = (value.arrayValue ?? []).map { jsonConditionToSql($0, user: user) }
                clauses.append("(" + sub.joined(separator: " OR ") + ")")
            case "$and":
                let sub = (value.arrayValue ?? []).map { jsonConditionToSql($0, user: user) }
                clauses.append("(" + sub.joined(separator: " AND ") + ")")
            case "@request.auth.role":
                clauses.append(user?.role == value.stringOrNull ? "1=1" : "1=0")
            case "@request.auth.id":
                clauses.append("1=1")
            default:
                let col = "json_extract(records.data, '$.\(key)')"
                let resolved: String
                if value.stringOrNull == "@request.auth.id" {
                    resolved = user.map { String($0.uid) } ?? "NULL"
                } else if let s = value.stringOrNull {
                    resolved = s.replacingOccurrences(of: "'", with: "''")
                } else {
                    resolved = value.serialized
                }
                clauses.append("\(col) = '\(resolved)'")
            }
        }
        return clauses.isEmpty ? "1=1" : clauses.joined(separator: " AND ")
    }
}
