import Foundation

/// Record CRUD with schema enforcement and unique-index locking. Mirrors
/// `engine.db.ApexKitDatabaseEngine`.
public final class ApexKitDatabaseEngine {
    private let driver: SqlDriver

    public init(driver: SqlDriver) {
        self.driver = driver
        WorkspaceDatabase.Schema().create(driver)
    }

    // MARK: 1. Schema import & collection registration

    @discardableResult
    public func importTenantSchema(_ package: TenantSchemaPackage) -> Int {
        var importedCount = 0
        for col in package.collections {
            let schemaStr = col.schema.map { encodeSchema($0) }
            driver.executeUpdate(
                identifier: nil,
                sql: "INSERT OR REPLACE INTO collections (id, name, schema, index_key) VALUES (?, ?, ?, ?)",
                parameters: 4
            ) { st in
                st.bindLong(index: 0, long: col.id)
                st.bindString(index: 1, string: col.name)
                if let schemaStr { st.bindString(index: 2, string: schemaStr) } else { st.bindNull(index: 2) }
                if let index = col.index { st.bindString(index: 3, string: index) } else { st.bindNull(index: 3) }
            }
            importedCount += 1
        }
        return importedCount
    }

    @discardableResult
    public func importTenantSchemaJson(_ rawJson: String) -> Int {
        guard let parsed = JsonValue.parse(rawJson) else { return 0 }
        return importTenantSchema(TenantSchemaPackage.decode(parsed))
    }

    public func getCollection(_ nameOrId: String) -> Collection? {
        let isId = Int64(nameOrId) != nil
        let sql = isId
            ? "SELECT id, name, schema, index_key FROM collections WHERE id = ? LIMIT 1"
            : "SELECT id, name, schema, index_key FROM collections WHERE name = ? LIMIT 1"

        var result: Collection?
        let cursor = driver.executeQuery(identifier: nil, sql: sql, parameters: 1) { st in
            if let id = Int64(nameOrId) { st.bindLong(index: 0, long: id) } else { st.bindString(index: 0, string: nameOrId) }
        }
        if cursor.next() { result = readCollection(cursor) }
        return result
    }

    public func listCollections() -> [Collection] {
        var list: [Collection] = []
        let cursor = driver.executeQuery(
            identifier: nil,
            sql: "SELECT id, name, schema, index_key FROM collections ORDER BY id ASC",
            parameters: 0,
            binders: nil
        )
        while cursor.next() { list.append(readCollection(cursor)) }
        return list
    }

    private func readCollection(_ c: SqlCursor) -> Collection {
        let schemaStr = c.getString(index: 2)
        let schema = schemaStr.flatMap { JsonValue.parse($0) }.map { CollectionSchema.decode($0) }
        return Collection(
            id: c.getLong(index: 0) ?? 0,
            name: c.getString(index: 1) ?? "",
            schema: schema,
            index: c.getString(index: 3)
        )
    }

    // MARK: 2. Record creation with schema enforcement

    @discardableResult
    public func insertRecord(_ collectionName: String, data: JsonValue, user: Claims? = nil) throws -> Int64 {
        guard let col = getCollection(collectionName) else {
            throw EngineError.validation("Collection '\(collectionName)' not found in schema.")
        }
        let schema = col.schema ?? CollectionSchema()
        let dict = data.asDictionary

        // 1. RLS create policy
        let policySql = PolicyEngine.compileToSql(schema.policies.create, user: user)
        if policySql == "1=0" {
            throw EngineError.accessDenied("Record creation rejected by policy on collection '\(collectionName)'.")
        }

        // 2. Required fields
        for (fieldName, fieldDef) in schema.fields where fieldDef.required && dict[fieldName] == nil {
            throw EngineError.validation("Field '\(fieldName)' is required in collection '\(collectionName)'.")
        }

        // 3. Single-field uniqueness
        for (fieldName, fieldDef) in schema.fields where fieldDef.unique == true {
            guard let raw = dict[fieldName] else { continue }
            let rawVal = raw.contentOrNull ?? raw.serialized
            let idxKey = "\(col.id)-\(fieldDef.uid)"
            if let conflict = uniqueConflict(indexKey: idxKey, value: rawVal) {
                throw EngineError.validation("Unique constraint violation: Field '\(fieldName)' with value '\(rawVal)' already exists (Record #\(conflict)).")
            }
        }

        // 4. Composite uniqueness
        for comp in schema.compositeUnique where comp.allSatisfy({ dict[$0] != nil }) {
            let compKey = "\(col.id)-comp-" + comp.joined(separator: "_")
            let compVal = comp.map { dict[$0]!.contentOrNull ?? dict[$0]!.serialized }.joined(separator: "|")
            if let conflict = uniqueConflict(indexKey: compKey, value: compVal) {
                throw EngineError.validation("Composite unique violation on (\(comp.joined(separator: ", "))): Value '\(compVal)' already exists (Record #\(conflict)).")
            }
        }

        let now = String(Int64(Date().timeIntervalSince1970 * 1000))
        let dataStr = data.serialized

        // 5. Insert record
        driver.executeUpdate(
            identifier: nil,
            sql: "INSERT INTO records (collection_id, data, created, updated) VALUES (?, ?, ?, ?)",
            parameters: 4
        ) { st in
            st.bindLong(index: 0, long: col.id)
            st.bindString(index: 1, string: dataStr)
            st.bindString(index: 2, string: now)
            st.bindString(index: 3, string: now)
        }
        let recordId = driver.lastInsertedRowId()

        // 6. Register single-field unique tokens
        for (fieldName, fieldDef) in schema.fields where fieldDef.unique == true {
            guard let raw = dict[fieldName] else { continue }
            let rawVal = raw.contentOrNull ?? raw.serialized
            insertUniqueToken(indexKey: "\(col.id)-\(fieldDef.uid)", value: rawVal, recordId: recordId)
        }

        // 7. Register composite unique tokens
        for comp in schema.compositeUnique where comp.allSatisfy({ dict[$0] != nil }) {
            let compKey = "\(col.id)-comp-" + comp.joined(separator: "_")
            let compVal = comp.map { dict[$0]!.contentOrNull ?? dict[$0]!.serialized }.joined(separator: "|")
            insertUniqueToken(indexKey: compKey, value: compVal, recordId: recordId)
        }

        // 8. Register relations
        for (relName, relDef) in schema.relations {
            guard let targetRecId = dict[relName]?.longOrNull else { continue }
            guard let targetCol = getCollection(relDef.targetCollection) else { continue }
            driver.executeUpdate(
                identifier: nil,
                sql: """
                INSERT OR REPLACE INTO _relations
                (origin_col_id, origin_rec_id, target_col_id, target_rec_id, rel_name, properties)
                VALUES (?, ?, ?, ?, ?, NULL)
                """,
                parameters: 5
            ) { st in
                st.bindLong(index: 0, long: col.id)
                st.bindLong(index: 1, long: recordId)
                st.bindLong(index: 2, long: targetCol.id)
                st.bindLong(index: 3, long: targetRecId)
                st.bindString(index: 4, string: relName)
            }
        }

        return recordId
    }

    // MARK: 3. Query engine

    public func queryRecords(
        _ collectionName: String,
        filters: [QueryFilter] = [],
        orderBy: String? = nil,
        ascending: Bool = false,
        limit: Int = 100,
        offset: Int = 0,
        user: Claims? = nil
    ) -> [Record] {
        guard let col = getCollection(collectionName) else { return [] }
        let schema = col.schema ?? CollectionSchema()

        let rlsSql = PolicyEngine.compileToSql(schema.policies.read, user: user)
        if rlsSql == "1=0" { return [] }

        var whereClauses = ["records.collection_id = \(col.id)", rlsSql]
        var args: [String] = []

        for f in filters {
            let fieldPath = f.field.replacingOccurrences(of: "'", with: "''")
            switch f.op.uppercased() {
            case "=", "!=", ">", ">=", "<", "<=":
                whereClauses.append("json_extract(records.data, '$.\(fieldPath)') \(f.op) ?")
                args.append(f.value)
            case "LIKE":
                whereClauses.append("json_extract(records.data, '$.\(fieldPath)') LIKE ?")
                args.append(f.value)
            case "IN":
                let values = f.value.components(separatedBy: ",").map { $0.trimmingCharacters(in: .whitespaces) }
                let placeholders = values.map { _ in "?" }.joined(separator: ",")
                whereClauses.append("json_extract(records.data, '$.\(fieldPath)') IN (\(placeholders))")
                args.append(contentsOf: values)
            default:
                break
            }
        }

        let orderClause: String
        if let orderBy, !orderBy.trimmingCharacters(in: .whitespaces).isEmpty {
            let orderField = orderBy.replacingOccurrences(of: "'", with: "''")
            let dir = ascending ? "ASC" : "DESC"
            orderClause = orderField == "id" ? "ORDER BY records.id \(dir)" : "ORDER BY json_extract(records.data, '$.\(orderField)') \(dir)"
        } else {
            orderClause = "ORDER BY records.id DESC"
        }

        let sql = """
            SELECT records.id, records.collection_id, records.data, records.created, records.updated
            FROM records
            WHERE \(whereClauses.joined(separator: " AND "))
            \(orderClause)
            LIMIT \(limit) OFFSET \(offset)
            """

        var records: [Record] = []
        let cursor = driver.executeQuery(identifier: nil, sql: sql, parameters: args.count) { st in
            for (i, v) in args.enumerated() { st.bindString(index: i, string: v) }
        }
        while cursor.next() {
            let rawData = cursor.getString(index: 2) ?? "{}"
            let dataObj = JsonValue.parse(rawData) ?? .object([])
            records.append(Record(
                id: cursor.getLong(index: 0) ?? 0,
                collectionId: cursor.getLong(index: 1) ?? col.id,
                data: dataObj,
                created: cursor.getString(index: 3) ?? "",
                updated: cursor.getString(index: 4) ?? ""
            ))
        }
        return records
    }

    public func countRecords(_ collectionName: String, filters: [QueryFilter] = [], user: Claims? = nil) -> Int64 {
        guard let col = getCollection(collectionName) else { return 0 }
        let schema = col.schema ?? CollectionSchema()
        let rlsSql = PolicyEngine.compileToSql(schema.policies.read, user: user)
        if rlsSql == "1=0" { return 0 }

        var whereClauses = ["records.collection_id = \(col.id)", rlsSql]
        var args: [String] = []
        for f in filters {
            let fieldPath = f.field.replacingOccurrences(of: "'", with: "''")
            whereClauses.append("json_extract(records.data, '$.\(fieldPath)') \(f.op) ?")
            args.append(f.value)
        }

        let sql = "SELECT COUNT(*) FROM records WHERE \(whereClauses.joined(separator: " AND "))"
        var count: Int64 = 0
        let cursor = driver.executeQuery(identifier: nil, sql: sql, parameters: args.count) { st in
            for (i, v) in args.enumerated() { st.bindString(index: i, string: v) }
        }
        if cursor.next() { count = cursor.getLong(index: 0) ?? 0 }
        return count
    }

    @discardableResult
    public func deleteRecord(_ collectionName: String, recordId: Int64, user: Claims? = nil) throws -> Bool {
        guard let col = getCollection(collectionName) else { return false }
        let schema = col.schema ?? CollectionSchema()
        let rlsSql = PolicyEngine.compileToSql(schema.policies.delete, user: user)
        if rlsSql == "1=0" {
            throw EngineError.accessDenied("Delete rejected by policy on collection '\(collectionName)'.")
        }

        driver.executeUpdate(identifier: nil, sql: "DELETE FROM _unique_values WHERE record_id = ?", parameters: 1) { st in
            st.bindLong(index: 0, long: recordId)
        }
        driver.executeUpdate(identifier: nil, sql: "DELETE FROM _relations WHERE origin_rec_id = ? OR target_rec_id = ?", parameters: 2) { st in
            st.bindLong(index: 0, long: recordId)
            st.bindLong(index: 1, long: recordId)
        }
        let affected = driver.executeUpdate(
            identifier: nil,
            sql: "DELETE FROM records WHERE collection_id = ? AND id = ?",
            parameters: 2
        ) { st in
            st.bindLong(index: 0, long: col.id)
            st.bindLong(index: 1, long: recordId)
        }
        return affected > 0
    }

    // MARK: Helpers

    private func uniqueConflict(indexKey: String, value: String) -> Int64? {
        var conflict: Int64?
        let cursor = driver.executeQuery(
            identifier: nil,
            sql: "SELECT record_id FROM _unique_values WHERE index_key = ? AND value = ? LIMIT 1",
            parameters: 2
        ) { st in
            st.bindString(index: 0, string: indexKey)
            st.bindString(index: 1, string: value)
        }
        if cursor.next() { conflict = cursor.getLong(index: 0) }
        return conflict
    }

    private func insertUniqueToken(indexKey: String, value: String, recordId: Int64) {
        driver.executeUpdate(
            identifier: nil,
            sql: "INSERT OR REPLACE INTO _unique_values (index_key, value, record_id) VALUES (?, ?, ?)",
            parameters: 3
        ) { st in
            st.bindString(index: 0, string: indexKey)
            st.bindString(index: 1, string: value)
            st.bindLong(index: 2, long: recordId)
        }
    }

    private func encodeSchema(_ schema: CollectionSchema) -> String {
        var fieldPairs: [(String, JsonValue)] = []
        for (name, def) in schema.fields.sorted(by: { $0.key < $1.key }) {
            var pairs: [(String, JsonValue)] = [("type", .string(def.type.rawValue)), ("required", .bool(def.required))]
            if let unique = def.unique { pairs.append(("unique", .bool(unique))) }
            if let relationTo = def.relationTo { pairs.append(("relation_to", .string(relationTo))) }
            pairs.append(("uid", .string(def.uid)))
            fieldPairs.append((name, .object(pairs)))
        }
        var relationPairs: [(String, JsonValue)] = []
        for (name, def) in schema.relations.sorted(by: { $0.key < $1.key }) {
            relationPairs.append((name, .object([
                ("target_collection", .string(def.targetCollection)),
                ("relation_type", .string(def.relationType.rawValue)),
                ("required", .bool(def.required)),
                ("uid", .string(def.uid))
            ])))
        }
        let policies: JsonValue = .object([
            ("read", .string(schema.policies.read)),
            ("create", .string(schema.policies.create)),
            ("update", .string(schema.policies.update)),
            ("delete", .string(schema.policies.delete))
        ])
        return JsonValue.object([
            ("fields", .object(fieldPairs)),
            ("relations", .object(relationPairs)),
            ("policies", policies),
            ("composite_unique", .array(schema.compositeUnique.map { .array($0.map { JsonValue.string($0) }) }))
        ]).serialized
    }
}

/// A single-field filter. Mirrors `engine.db.QueryFilter`.
public struct QueryFilter: Sendable {
    public var field: String
    public var op: String
    public var value: String

    public init(field: String, op: String = "=", value: String) {
        self.field = field
        self.op = op
        self.value = value
    }
}

/// Engine error mirroring the Kotlin `error(...)`/`SecurityException` paths.
public enum EngineError: Error, CustomStringConvertible {
    case validation(String)
    case accessDenied(String)

    public var description: String {
        switch self {
        case let .validation(m): return "Validation Error: \(m)"
        case let .accessDenied(m): return "Access Denied: \(m)"
        }
    }
}
