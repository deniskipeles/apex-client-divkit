import Foundation

/// Query AST + SQL compiler mirroring `engine.db.query.*`.
public enum FilterOp: String, Sendable {
    case eq, neq, gt, gte, lt, lte, `in`, nin, like, contains
}

public enum LogicOp: Sendable { case and, or }

/// A boolean filter tree. Mirrors `engine.db.query.FilterNode`.
public indirect enum FilterNode: Sendable {
    case empty
    case condition(field: String, op: FilterOp, value: JsonValue)
    case group(op: LogicOp, children: [FilterNode])

    /// Parses a Mongo-style filter document into a tree.
    public static func parse(_ json: JsonValue?) -> FilterNode {
        guard let json, case let .object(pairs) = json else { return .empty }
        var conditions: [FilterNode] = []

        for (key, value) in pairs {
            if key.caseInsensitiveCompare("$and") == .orderedSame || key.caseInsensitiveCompare("$or") == .orderedSame {
                let logic: LogicOp = key.caseInsensitiveCompare("$and") == .orderedSame ? .and : .or
                if case let .array(items) = value {
                    conditions.append(.group(op: logic, children: items.map { parse($0) }))
                }
            } else {
                conditions.append(parseFieldCondition(field: key, value: value))
            }
        }

        switch conditions.count {
        case 0: return .empty
        case 1: return conditions[0]
        default: return .group(op: .and, children: conditions)
        }
    }

    private static func parseFieldCondition(field: String, value: JsonValue) -> FilterNode {
        if case let .object(entries) = value, let first = entries.first {
            let op: FilterOp? = {
                switch first.0.lowercased() {
                case "$eq", "eq", "_eq": return .eq
                case "$neq", "neq", "_neq": return .neq
                case "$gt", "gt", "_gt": return .gt
                case "$gte", "gte", "_gte": return .gte
                case "$lt", "lt", "_lt": return .lt
                case "$lte", "lte", "_lte": return .lte
                case "$in", "in", "_in": return .in
                case "$nin", "nin", "_nin": return .nin
                case "$like", "like", "_like": return .like
                case "$contains", "contains", "_contains": return .contains
                default: return nil
                }
            }()
            if let op { return .condition(field: field, op: op, value: first.1) }
            return .condition(field: field, op: .eq, value: value)
        }
        return .condition(field: field, op: .eq, value: value)
    }

    /// Translates the tree into parameterised SQL. Uses `json_extract()` for cross-version
    /// compatibility (API 24 through 36 on Android; the same SQLite on iOS).
    public func toSql(params: inout [JsonValue]) -> String? {
        switch self {
        case .empty:
            return nil
        case let .group(op, children):
            let parts = children.compactMap { $0.toSql(params: &params) }
            if parts.isEmpty { return nil }
            let joiner = op == .and ? " AND " : " OR "
            return "(" + parts.joined(separator: joiner) + ")"
        case let .condition(field, op, value):
            let column = Self.formatJsonColumn(field)
            switch op {
            case .in, .nin:
                guard case let .array(items) = value, !items.isEmpty else { return "1=0" }
                let placeholders = Array(repeating: "?", count: items.count).joined(separator: ", ")
                for item in items { params.append(item) }
                let sqlOp = op == .nin ? "NOT IN" : "IN"
                return "\(column) \(sqlOp) (\(placeholders))"
            default:
                let sqlOp: String
                switch op {
                case .eq: sqlOp = "="
                case .neq: sqlOp = "!="
                case .gt: sqlOp = ">"
                case .gte: sqlOp = ">="
                case .lt: sqlOp = "<"
                case .lte: sqlOp = "<="
                case .like, .contains: sqlOp = "LIKE"
                case .in, .nin: sqlOp = "IN"
                }
                var finalVal = value
                if op == .contains, case let .string(s) = value {
                    finalVal = .string("%\(s)%")
                }
                params.append(finalVal)
                return "\(column) \(sqlOp) ?"
            }
        }
    }

    private static func formatJsonColumn(_ key: String) -> String {
        var cleanKey = key
        if key.hasPrefix("@record.data.") { cleanKey = String(key.dropFirst("@record.data.".count)) }
        else if key.hasPrefix("@record.") { cleanKey = String(key.dropFirst("@record.".count)) }

        if cleanKey == "id" { return "records.id" }
        if cleanKey == "created" { return "records.created" }
        if cleanKey == "updated" { return "records.updated" }

        let sanitized = cleanKey.replacingOccurrences(of: "'", with: "''")
        return "json_extract(records.data, '$.\(sanitized)')"
    }
}

/// Select projection. Mirrors `engine.db.query.SelectField`.
public enum SelectField: Sendable {
    case simple(String)
    case complex(field: String?, expr: String?, fn: String?, alias: String?)
}

/// In-memory pipeline step. Mirrors `engine.db.query.PipelineStep`.
public enum PipelineStep: Sendable {
    case cumulativeSum(field: String, outputField: String)
    case pivot(key: String, value: String, agg: String)
}

/// Query description. Mirrors `engine.db.query.ApexQuery`.
public struct ApexQuery: Sendable {
    public var from: String
    public var system: Bool
    public var select: [SelectField]
    public var `where`: JsonValue?
    public var groupBy: [String]
    public var sort: String?
    public var limit: Int64?
    public var offset: Int64?
    public var pipeline: [PipelineStep]

    public init(
        from: String,
        system: Bool = false,
        select: [SelectField] = [],
        where: JsonValue? = nil,
        groupBy: [String] = [],
        sort: String? = nil,
        limit: Int64? = 100,
        offset: Int64? = 0,
        pipeline: [PipelineStep] = []
    ) {
        self.from = from
        self.system = system
        self.select = select
        self.where = `where`
        self.groupBy = groupBy
        self.sort = sort
        self.limit = limit
        self.offset = offset
        self.pipeline = pipeline
    }
}

/// SQL compiler + executor for `ApexQuery`. Mirrors `engine.db.query.QueryEngine`.
public enum QueryEngine {

    public static func buildSql(_ query: ApexQuery, collectionId: Int64?) -> (String, [JsonValue]) {
        var params: [JsonValue] = []
        var selectClauses: [String] = []
        var groupClauses: [String] = []

        let isSystem = query.system || query.from.hasPrefix("_")
        let tableName = isSystem ? query.from : "records"
        var whereSql: String
        if isSystem {
            whereSql = "WHERE 1=1"
        } else {
            params.append(.int(collectionId ?? 0))
            whereSql = "WHERE records.collection_id = ?"
        }

        if query.select.isEmpty {
            if isSystem {
                selectClauses.append("*")
            } else {
                selectClauses.append(contentsOf: [
                    "records.id AS id",
                    "records.data AS data",
                    "records.created AS created",
                    "records.updated AS updated"
                ])
            }
        } else {
            for field in query.select {
                switch field {
                case let .simple(value):
                    let col = isSystem ? value : resolveColumn(value)
                    selectClauses.append("\(col) AS \"\(value)\"")
                case let .complex(field, expr, fn, alias):
                    let baseCol: String
                    if let expr { baseCol = expr }
                    else if let field { baseCol = isSystem ? field : resolveColumn(field) }
                    else { baseCol = "*" }
                    let sqlExpr: String
                    switch fn?.lowercased() {
                    case "count": sqlExpr = "COUNT(\(baseCol))"
                    case "sum": sqlExpr = "SUM(CAST(\(baseCol) AS REAL))"
                    case "avg": sqlExpr = "AVG(CAST(\(baseCol) AS REAL))"
                    case "min": sqlExpr = "MIN(CAST(\(baseCol) AS REAL))"
                    case "max": sqlExpr = "MAX(CAST(\(baseCol) AS REAL))"
                    case "year": sqlExpr = "strftime('%Y', \(baseCol))"
                    case "month": sqlExpr = "strftime('%m', \(baseCol))"
                    case "day": sqlExpr = "strftime('%Y-%m-%d', \(baseCol))"
                    default: sqlExpr = baseCol
                    }
                    if let alias {
                        selectClauses.append("\(sqlExpr) AS \"\(alias)\"")
                    } else {
                        selectClauses.append(sqlExpr)
                    }
                }
            }
        }

        if let whereClause = query.where {
            let node = FilterNode.parse(whereClause)
            if let filterSql = node.toSql(params: &params) {
                whereSql += " AND (\(filterSql))"
            }
        }

        for g in query.groupBy {
            groupClauses.append(isSystem ? g : resolveColumn(g))
        }
        let groupSql = groupClauses.isEmpty ? "" : "GROUP BY " + groupClauses.joined(separator: ", ")

        let orderSql: String
        if let sort = query.sort {
            let parts = sort.components(separatedBy: ",").map { s -> String in
                let trimmed = s.trimmingCharacters(in: .whitespaces)
                let desc = trimmed.hasPrefix("-")
                let clean = desc ? String(trimmed.dropFirst()) : trimmed
                let col = isSystem ? clean : resolveColumn(clean)
                return "\(col) \(desc ? "DESC" : "ASC")"
            }
            orderSql = "ORDER BY " + parts.joined(separator: ", ")
        } else if isSystem {
            orderSql = "ORDER BY 1 DESC"
        } else {
            orderSql = "ORDER BY records.id DESC"
        }

        let limit = query.limit ?? 100
        let offset = query.offset ?? 0

        let finalSql = "SELECT \(selectClauses.joined(separator: ", ")) FROM \(tableName) \(whereSql) \(groupSql) \(orderSql) LIMIT \(limit) OFFSET \(offset)"
        return (finalSql, params)
    }

    private static func resolveColumn(_ field: String) -> String {
        switch field {
        case "id": return "records.id"
        case "created": return "records.created"
        case "updated": return "records.updated"
        default:
            let sanitized = field.replacingOccurrences(of: "'", with: "''")
            return "json_extract(records.data, '$.\(sanitized)')"
        }
    }

    /// Executes the compiled SQL and applies in-memory pipeline steps, returning a JSON array.
    public static func execute(_ driver: SqlDriver, _ query: ApexQuery, collectionId: Int64?) -> JsonValue {
        let (sql, params) = buildSql(query, collectionId: collectionId)
        var rows: [JsonValue] = []

        let cursor = driver.executeQuery(identifier: nil, sql: sql, parameters: params.count) { st in
            for (index, param) in params.enumerated() {
                switch param {
                case .null: st.bindNull(index: index)
                case let .int(i): st.bindLong(index: index, long: i)
                case let .double(d): st.bindDouble(index: index, double: d)
                case let .bool(b): st.bindLong(index: index, long: b ? 1 : 0)
                case let .string(s): st.bindString(index: index, string: s)
                default: st.bindString(index: index, string: param.serialized)
                }
            }
        }

        while cursor.next() {
            var pairs: [(String, JsonValue)] = []
            for i in 0..<cursor.columnCount {
                let colName = cursor.getColumnName(index: i)
                let name = colName.isEmpty ? "col_\(i)" : colName
                pairs.append((name, decodeColumn(cursor.getString(index: i))))
            }
            rows.append(.object(pairs))
        }

        var processed = rows
        if !query.pipeline.isEmpty {
            processed = processPipeline(processed, query.pipeline)
        }
        return .array(processed)
    }

    private static func decodeColumn(_ raw: String?) -> JsonValue {
        guard let raw else { return .null }
        if (raw.hasPrefix("{") && raw.hasSuffix("}")) || (raw.hasPrefix("[") && raw.hasSuffix("]")) {
            return JsonValue.parse(raw) ?? .string(raw)
        }
        if let i = Int64(raw) { return .int(i) }
        if let d = Double(raw) { return .double(d) }
        if raw.lowercased() == "true" || raw.lowercased() == "false" { return .bool(raw.lowercased() == "true") }
        return .string(raw)
    }

    private static func processPipeline(_ data: [JsonValue], _ pipeline: [PipelineStep]) -> [JsonValue] {
        var current = data
        for step in pipeline {
            switch step {
            case let .cumulativeSum(field, outputField):
                var running = 0.0
                current = current.map { row in
                    var dict = row.asDictionary
                    let valNum = dict[field]?.doubleOrNull ?? 0.0
                    running += valNum
                    dict[outputField] = .double(running)
                    return dict.asJsonValue
                }
            case let .pivot(key, value, agg):
                var pivotMap: [String: Double] = [:]
                for row in current {
                    let dict = row.asDictionary
                    let k = dict[key]?.contentOrNull ?? "unknown"
                    let v = dict[value]?.doubleOrNull ?? 0.0
                    let existing = pivotMap[k] ?? 0.0
                    switch agg.lowercased() {
                    case "sum": pivotMap[k] = existing + v
                    case "count": pivotMap[k] = existing + 1.0
                    case "avg": pivotMap[k] = (existing + v) / 2.0
                    default: break
                    }
                }
                return [Dictionary(uniqueKeysWithValues: pivotMap.map { ($0.key, JsonValue.double($0.value)) }).asJsonValue]
            }
        }
        return current
    }
}
