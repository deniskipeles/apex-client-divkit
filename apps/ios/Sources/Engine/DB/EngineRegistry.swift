import Foundation

/// Database op outcome. Mirrors `engine.db.OpResult`.
public enum OpResult: Sendable {
    case rows([[String: JsonValue]])
    case row([String: JsonValue]?)
    case scalar(JsonValue)
    case rowId(String)
    case count(Int64)
    case schemaImported(Int)
    case empty
}

/// Routes `local-db://op/<name>` operations to the ApexKit engine. Mirrors
/// `engine.db.EngineRegistry`.
public final class EngineRegistry {
    public static let hardMaxLimit: Int64 = 500
    public static let maxPayloadBytes = 128 * 1024

    private let clock: () -> Int64

    public init(clock: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }) {
        self.clock = clock
    }

    public func execute(
        opName: String,
        db: WorkspaceDatabase,
        workspaceId: String,
        payload: JsonValue
    ) throws -> OpResult {
        let payloadBytes = payload.serialized.utf8.count
        if payloadBytes > EngineRegistry.maxPayloadBytes {
            throw EngineError.accessDenied("Payload exceeds limit of \(EngineRegistry.maxPayloadBytes) bytes")
        }

        let apexEngine = ApexKitDatabaseEngine(driver: db.driver)
        let started = clock()

        do {
            let result = try dispatch(opName: opName, apexEngine: apexEngine, db: db, payload: payload)
            db.queries.insertOpLog(
                ts: clock(),
                op: opName,
                durationMs: clock() - started,
                rowcount: rowcount(result),
                error: nil
            )
            return result
        } catch {
            db.queries.insertOpLog(
                ts: clock(),
                op: opName,
                durationMs: clock() - started,
                rowcount: nil,
                error: "\(error)"
            )
            throw error
        }
    }

    private func rowcount(_ result: OpResult) -> Int64? {
        switch result {
        case let .rows(rows): return Int64(rows.count)
        case let .count(n): return n
        case .rowId: return 1
        default: return nil
        }
    }

    private func dispatch(opName: String, apexEngine: ApexKitDatabaseEngine, db: WorkspaceDatabase, payload: JsonValue) throws -> OpResult {
        switch opName {
        case "db.import_schema", "schema.import":
            let schemaObj = payload["schema"] ?? payload
            let count = try apexEngine.importTenantSchemaJson(schemaObj.serialized)
            return .schemaImported(count)

        case "db.list_collections", "schema.collections":
            let rows = apexEngine.listCollections().map { col -> [String: JsonValue] in
                ["id": .int(col.id), "name": .string(col.name), "index": .string(col.index ?? "")]
            }
            return .rows(rows)

        case "db.insert", "records.create":
            let col = try require(payload["collection"]?.stringOrNull ?? payload["table"]?.stringOrNull, "Missing 'collection' parameter")
            let data = try require(payload["data"] ?? payload["values"], "Missing 'data' or 'values' object")
            let user = Claims(
                uid: payload["auth_uid"]?.longOrNull ?? 0,
                role: payload["auth_role"]?.stringOrNull ?? "public"
            )
            let newId = try apexEngine.insertRecord(col, data: data, user: user)

            let enqueueSync = payload["enqueue_sync"]?.boolOrNull ?? false
            if enqueueSync {
                let clientUuid = payload["client_uuid"]?.stringOrNull ?? UUID().uuidString
                db.driver.executeUpdate(
                    identifier: nil,
                    sql: "INSERT OR IGNORE INTO pending_sync (client_uuid, op, payload, created_at) VALUES (?, ?, ?, ?)",
                    parameters: 4
                ) { st in
                    st.bindString(index: 0, string: clientUuid)
                    st.bindString(index: 1, string: opName)
                    st.bindString(index: 2, string: payload.serialized)
                    st.bindLong(index: 3, long: self.clock())
                }
            }
            return .rowId(newId)

        case "db.query", "records.query":
            let colName = try require(payload["from"]?.stringOrNull ?? payload["collection"]?.stringOrNull ?? payload["table"]?.stringOrNull, "Missing 'from' or 'collection' parameter")
            let colId = apexEngine.getCollection(colName)?.id

            let select: [SelectField] = (payload["select"]?.arrayValue ?? []).compactMap { el in
                switch el {
                case let .string(s): return .simple(s)
                case let .object(pairs):
                    let dict = Dictionary(pairs, uniquingKeysWith: { a, _ in a })
                    return .complex(
                        field: dict["field"]?.stringOrNull,
                        expr: dict["expr"]?.stringOrNull,
                        fn: dict["fn"]?.stringOrNull,
                        alias: dict["as"]?.stringOrNull
                    )
                default: return nil
                }
            }

            let limit = payload["limit"]?.longOrNull ?? 100
            let offset = payload["offset"]?.longOrNull ?? 0
            let query = ApexQuery(
                from: colName,
                select: select,
                where: payload["where"] ?? payload["filter"],
                sort: payload["sort"]?.stringOrNull ?? payload["order_by"]?.stringOrNull,
                limit: limit,
                offset: offset
            )
            let resultArray = QueryEngine.execute(db.driver, query, collectionId: colId)
            let rows: [[String: JsonValue]] = (resultArray.arrayValue ?? []).map { el in
                el.asDictionary.mapValues { v in
                    v.contentOrNull.map { JsonValue.string($0) } ?? .string(v.serialized)
                }
            }
            return .rows(rows)

        case "db.query_one", "records.get":
            let col = try require(payload["collection"]?.stringOrNull ?? payload["table"]?.stringOrNull, "Missing 'collection' parameter")
            let filters = parseFilters(payload["where"]?.arrayValue)
            let user = Claims(
                uid: payload["auth_uid"]?.longOrNull ?? 0,
                role: payload["auth_role"]?.stringOrNull ?? "public"
            )
            let records = apexEngine.queryRecords(col, filters: filters, limit: 1, user: user)
            guard let first = records.first else { return .row(nil) }
            var map = first.data.asDictionary.mapValues { v in
                v.contentOrNull.map { JsonValue.string($0) } ?? .string(v.serialized)
            }
            map["id"] = .string(first.id)
            return .row(map)

        case "db.count", "records.count":
            let col = try require(payload["collection"]?.stringOrNull ?? payload["table"]?.stringOrNull, "Missing 'collection' parameter")
            let filters = parseFilters(payload["where"]?.arrayValue)
            let user = Claims(
                uid: payload["auth_uid"]?.longOrNull ?? 0,
                role: payload["auth_role"]?.stringOrNull ?? "public"
            )
            return .count(apexEngine.countRecords(col, filters: filters, user: user))

        case "db.delete", "records.delete":
            let col = try require(payload["collection"]?.stringOrNull ?? payload["table"]?.stringOrNull, "Missing 'collection' parameter")
            let recordId = try require(payload["id"]?.contentOrNull ?? payload["record_id"]?.contentOrNull, "Missing 'id' parameter for delete operation")
            let user = Claims(
                uid: payload["auth_uid"]?.longOrNull ?? 0,
                role: payload["auth_role"]?.stringOrNull ?? "public"
            )
            let deleted = try apexEngine.deleteRecord(col, recordId: recordId, user: user)
            return .count(deleted ? 1 : 0)

        default:
            throw EngineError.accessDenied("Unknown operation: \(opName)")
        }
    }

    private func require<T>(_ value: T?, _ message: String) throws -> T {
        guard let value else { throw EngineError.validation(message) }
        return value
    }

    private func parseFilters(_ array: [JsonValue]?) -> [QueryFilter] {
        guard let array, !array.isEmpty else { return [] }
        return array.compactMap { el -> QueryFilter? in
            guard case let .object(pairs) = el else { return nil }
            let dict = Dictionary(pairs, uniquingKeysWith: { a, _ in a })
            guard let field = dict["field"]?.stringOrNull ?? dict["column"]?.stringOrNull else { return nil }
            let op = dict["op"]?.stringOrNull ?? "="
            let value = dict["value"]?.contentOrNull ?? dict["value"]?.serialized ?? ""
            return QueryFilter(field: field, op: op, value: value)
        }
    }
}
