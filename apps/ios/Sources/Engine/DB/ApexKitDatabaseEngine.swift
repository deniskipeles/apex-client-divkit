import Foundation

/// Record CRUD with schema enforcement, schema-generated expression indexes and
/// transactional cascades. Mirrors `engine.db.ApexKitDatabaseEngine`.
public final class ApexKitDatabaseEngine {
    private let driver: SqlDriver

    public init(driver: SqlDriver) {
        self.driver = driver
        WorkspaceDatabase.Schema().create(driver)
    }

    // MARK: 1. Schema import & collection registration

    @discardableResult
    public func importTenantSchema(_ package: TenantSchemaPackage) throws -> Int {
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
        try ensureIndexes(listCollections())
        return importedCount
    }

    @discardableResult
    public func importTenantSchemaJson(_ rawJson: String) throws -> Int {
        guard let parsed = JsonValue.parse(rawJson) else { return 0 }
        return try importTenantSchema(TenantSchemaPackage.decode(parsed))
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

    // MARK: 1b. Schema-generated expression indexes

    private struct DesiredIndex {
        let name: String
        let collectionId: Int64
        let kind: String
        let fields: [String]
    }

    private struct AppliedIndex {
        let name: String
        let collectionId: Int64
        let kind: String
        let fields: [String]
    }

    /// Reconciles the indexes required by `collections` with those recorded in
    /// `_applied_indexes`: creates missing, drops removed, recreates changed. Runs in one
    /// transaction so a failed reconcile leaves the previous index set intact.
    public func ensureIndexes(_ collections: [Collection]) throws {
        driver.executeUpdate(
            identifier: nil,
            sql: """
            CREATE TABLE IF NOT EXISTS _applied_indexes (
                index_name    TEXT PRIMARY KEY,
                collection_id INTEGER NOT NULL,
                kind          TEXT NOT NULL,
                fields        TEXT NOT NULL
            )
            """,
            parameters: 0,
            binders: nil
        )

        let desired = computeDesiredIndexes(collections)
        let applied = loadAppliedIndexes()

        driver.beginTransaction()
        do {
            let desiredByName = Dictionary(uniqueKeysWithValues: desired.map { ($0.name, $0) })

            for existing in applied where desiredByName[existing.name] == nil {
                driver.executeUpdate(identifier: nil, sql: "DROP INDEX IF EXISTS \(quoteIdent(existing.name))", parameters: 0, binders: nil)
                driver.executeUpdate(identifier: nil, sql: "DELETE FROM _applied_indexes WHERE index_name = ?", parameters: 1) { st in
                    st.bindString(index: 0, string: existing.name)
                }
            }

            for index in desired {
                let existing = applied.first { $0.name == index.name }
                if let existing, sameSpec(existing, index) { continue }

                if let existing {
                    driver.executeUpdate(identifier: nil, sql: "DROP INDEX IF EXISTS \(quoteIdent(existing.name))", parameters: 0, binders: nil)
                    driver.executeUpdate(identifier: nil, sql: "DELETE FROM _applied_indexes WHERE index_name = ?", parameters: 1) { st in
                        st.bindString(index: 0, string: existing.name)
                    }
                }

                if index.kind == "unique" || index.kind == "composite", let duplicate = findDuplicate(index) {
                    throw EngineError.validation(
                        "Cannot create unique index '\(index.name)' on (\(index.fields.joined(separator: ", "))): duplicate value '\(duplicate)' already exists."
                    )
                }

                driver.executeUpdate(identifier: nil, sql: createIndexSql(index), parameters: 0, binders: nil)
                driver.executeUpdate(
                    identifier: nil,
                    sql: "INSERT OR REPLACE INTO _applied_indexes (index_name, collection_id, kind, fields) VALUES (?, ?, ?, ?)",
                    parameters: 4
                ) { st in
                    st.bindString(index: 0, string: index.name)
                    st.bindLong(index: 1, long: index.collectionId)
                    st.bindString(index: 2, string: index.kind)
                    st.bindString(index: 3, string: JsonValue.array(index.fields.map { .string($0) }).serialized)
                }
            }

            driver.setTransactionSuccessful()
            driver.endTransaction()
        } catch {
            driver.endTransaction()
            throw error
        }
    }

    private func computeDesiredIndexes(_ collections: [Collection]) -> [DesiredIndex] {
        var desired: [DesiredIndex] = []
        for col in collections {
            guard let schema = col.schema else { continue }
            for (fieldName, fieldDef) in schema.fields {
                if fieldDef.unique == true {
                    desired.append(DesiredIndex(name: "idx_u_\(col.id)_\(sanitize(fieldName))", collectionId: col.id, kind: "unique", fields: [fieldName]))
                } else if fieldDef.sqlIndexed {
                    desired.append(DesiredIndex(name: "idx_n_\(col.id)_\(sanitize(fieldName))", collectionId: col.id, kind: "non_unique", fields: [fieldName]))
                }
            }
            for (i, comp) in schema.compositeUnique.enumerated() where !comp.isEmpty {
                desired.append(DesiredIndex(name: "idx_c_\(col.id)_\(i)", collectionId: col.id, kind: "composite", fields: comp))
            }
        }
        return desired
    }

    private func loadAppliedIndexes() -> [AppliedIndex] {
        var list: [AppliedIndex] = []
        let cursor = driver.executeQuery(identifier: nil, sql: "SELECT index_name, collection_id, kind, fields FROM _applied_indexes", parameters: 0, binders: nil)
        while cursor.next() {
            let fields = (cursor.getString(index: 3).flatMap { JsonValue.parse($0) }?.arrayValue ?? []).compactMap { $0.stringOrNull }
            list.append(AppliedIndex(
                name: cursor.getString(index: 0) ?? "",
                collectionId: cursor.getLong(index: 1) ?? 0,
                kind: cursor.getString(index: 2) ?? "",
                fields: fields
            ))
        }
        return list
    }

    private func sameSpec(_ a: AppliedIndex, _ b: DesiredIndex) -> Bool {
        a.collectionId == b.collectionId && a.kind == b.kind && a.fields == b.fields
    }

    private func createIndexSql(_ index: DesiredIndex) -> String {
        let unique = (index.kind == "unique" || index.kind == "composite") ? "UNIQUE " : ""
        let expressions = index.fields.map { field in
            "json_extract(data, '$.\(field.replacingOccurrences(of: "'", with: "''"))')"
        }.joined(separator: ", ")
        return "CREATE \(unique)INDEX \(quoteIdent(index.name)) ON records (collection_id, \(expressions))"
    }

    /// Returns a duplicated value for the index's field set, or nil when all values are distinct.
    private func findDuplicate(_ index: DesiredIndex) -> String? {
        let expressions = index.fields.map { field in
            "json_extract(data, '$.\(field.replacingOccurrences(of: "'", with: "''"))')"
        }.joined(separator: ", ")
        let sql = """
            SELECT \(expressions) AS dup
            FROM records
            WHERE collection_id = \(index.collectionId)
            GROUP BY \(expressions)
            HAVING COUNT(*) > 1
            LIMIT 1
            """
        var duplicate: String?
        let cursor = driver.executeQuery(identifier: nil, sql: sql, parameters: 0, binders: nil)
        if cursor.next() { duplicate = cursor.getString(index: 0) ?? "" }
        return duplicate
    }

    private func sanitize(_ name: String) -> String {
        String(name.map { $0.isLetter || $0.isNumber || $0 == "_" ? $0 : "_" })
    }

    private func quoteIdent(_ name: String) -> String {
        "\"" + name.replacingOccurrences(of: "\"", with: "\"\"") + "\""
    }

    // MARK: 2. Record creation with schema enforcement

    @discardableResult
    public func insertRecord(_ collectionName: String, data: JsonValue, user: Claims? = nil) throws -> String {
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

        let now = String(Int64(Date().timeIntervalSince1970 * 1000))
        let dataStr = data.serialized

        var recordId = ""
        driver.beginTransaction()
        do {
            // 3. Resolve the record identity (supplied id preserved as text, else generated).
            recordId = try resolveRecordId(dict["id"])

            // 4. Insert the record. Uniqueness is enforced by the schema-generated
            //    expression indexes; SQLite raises a constraint error on collision.
            let affected = driver.executeUpdate(
                identifier: nil,
                sql: "INSERT INTO records (id, collection_id, data, created, updated) VALUES (?, ?, ?, ?, ?)",
                parameters: 5
            ) { st in
                st.bindString(index: 0, string: recordId)
                st.bindLong(index: 1, long: col.id)
                st.bindString(index: 2, string: dataStr)
                st.bindString(index: 3, string: now)
                st.bindString(index: 4, string: now)
            }

            if affected <= 0 || driver.lastErrorMessage() != nil {
                driver.endTransaction()
                throw translateConstraintError(driver.lastErrorMessage() ?? "", schema: schema, dict: dict, collectionName: collectionName)
            }

            // 5. Materialise relation rows only where behaviour requires them.
            insertRelationRows(schema: schema, recordId: recordId, dict: dict)

            driver.setTransactionSuccessful()
        } catch {
            driver.endTransaction()
            throw error
        }
        driver.endTransaction()

        return recordId
    }

    /// Resolves a record id: a supplied non-empty value is preserved as text, otherwise a
    /// value is drawn from `_record_id_sequence` and stored as text.
    private func resolveRecordId(_ input: JsonValue?) throws -> String {
        if let input, !input.isNull {
            let text = (input.contentOrNull ?? "").trimmingCharacters(in: .whitespaces)
            if text.isEmpty {
                throw EngineError.validation("Record 'id' must not be empty.")
            }
            return text
        }
        driver.executeUpdate(identifier: nil, sql: "INSERT INTO _record_id_sequence (id) VALUES (NULL)", parameters: 0, binders: nil)
        return String(driver.lastInsertedRowId())
    }

    private func insertRelationRows(schema: CollectionSchema, recordId: String, dict: [String: JsonValue]) {
        for (relName, relDef) in schema.relations {
            guard let targetRecId = recordIdText(dict[relName]) else { continue }

            let requiresRow = relDef.cascadeOnTargetDelete
                || relDef.relationType == .many
                || relDef.hasProperties
                || relDef.sqlIndexed
            if !requiresRow { continue }

            let properties: String? = relDef.hasProperties ? dict["\(relName)_properties"]?.serialized : nil

            driver.executeUpdate(
                identifier: nil,
                sql: "INSERT OR REPLACE INTO _relations (origin_rec_id, target_rec_id, properties) VALUES (?, ?, ?)",
                parameters: 3
            ) { st in
                st.bindString(index: 0, string: recordId)
                st.bindString(index: 1, string: targetRecId)
                if let properties { st.bindString(index: 2, string: properties) } else { st.bindNull(index: 2) }
            }
        }
    }

    private func translateConstraintError(_ message: String, schema: CollectionSchema, dict: [String: JsonValue], collectionName: String) -> EngineError {
        guard message.uppercased().contains("UNIQUE") || message.uppercased().contains("CONSTRAINT") else {
            return .validation("Insert failed on collection '\(collectionName)': \(message)")
        }
        let uniqueFields = schema.fields.filter { $0.value.unique == true && dict[$0.key] != nil }.keys.sorted()
        let detail: String
        if !uniqueFields.isEmpty {
            detail = "Field(s) \(uniqueFields.map { "'\($0)'" }.joined(separator: ", ")) already exist"
        } else if !schema.compositeUnique.isEmpty {
            detail = "Composite unique constraint violated"
        } else {
            detail = "Unique constraint violated"
        }
        return .validation("Unique constraint violation on collection '\(collectionName)': \(detail).")
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
                id: cursor.getString(index: 0) ?? "",
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

    // MARK: 4. Transactional recursive delete

    @discardableResult
    public func deleteRecord(_ collectionName: String, recordId: String, user: Claims? = nil) throws -> Bool {
        var deleted = false
        driver.beginTransaction()
        do {
            var visited = Set<String>()
            deleted = try deleteRecordRec(collectionName, recordId: recordId, user: user, visited: &visited)
            driver.setTransactionSuccessful()
        } catch {
            driver.endTransaction()
            throw error
        }
        driver.endTransaction()
        return deleted
    }

    private func deleteRecordRec(_ collectionName: String, recordId: String, user: Claims?, visited: inout Set<String>) throws -> Bool {
        let id = recordId.trimmingCharacters(in: .whitespaces)
        guard visited.insert(id).inserted else { return false }

        guard let col = getCollection(collectionName) else { return false }
        let schema = col.schema ?? CollectionSchema()
        let rlsSql = PolicyEngine.compileToSql(schema.policies.delete, user: user)
        if rlsSql == "1=0" {
            throw EngineError.accessDenied("Delete rejected by policy on collection '\(collectionName)'.")
        }

        // Resolve cascading origins before deleting: the FK cascade removes the relation
        // rows that identify them.
        let cascadingOrigins = findCascadingOrigins(id)

        let affected = driver.executeUpdate(
            identifier: nil,
            sql: "DELETE FROM records WHERE id = ?",
            parameters: 1
        ) { st in
            st.bindString(index: 0, string: id)
        }

        for originId in cascadingOrigins {
            guard let originCollection = collectionNameForRecord(originId) else { continue }
            _ = try deleteRecordRec(originCollection, recordId: originId, user: user, visited: &visited)
        }

        return affected > 0
    }

    /// Returns the ids of records that reference `targetRecordId` through a relation marked
    /// `cascade_on_target_delete`.
    private func findCascadingOrigins(_ targetRecordId: String) -> [String] {
        var origins: [String] = []
        let cursor = driver.executeQuery(
            identifier: nil,
            sql: "SELECT DISTINCT origin_rec_id FROM _relations WHERE target_rec_id = ?",
            parameters: 1
        ) { st in
            st.bindString(index: 0, string: targetRecordId)
        }
        while cursor.next() {
            if let origin = cursor.getString(index: 0) { origins.append(origin) }
        }

        var cascading: [String] = []
        for originId in origins {
            guard let record = loadRecord(originId) else { continue }
            guard let originSchema = getCollection(String(record.collectionId))?.schema else { continue }
            for (relName, relDef) in originSchema.relations where relDef.cascadeOnTargetDelete {
                if recordIdText(record.data[relName]) == targetRecordId {
                    cascading.append(originId)
                    break
                }
            }
        }
        return cascading
    }

    private struct LoadedRecord {
        let id: String
        let collectionId: Int64
        let data: JsonValue
    }

    private func loadRecord(_ id: String) -> LoadedRecord? {
        var result: LoadedRecord?
        let cursor = driver.executeQuery(identifier: nil, sql: "SELECT id, collection_id, data FROM records WHERE id = ? LIMIT 1", parameters: 1) { st in
            st.bindString(index: 0, string: id)
        }
        if cursor.next() {
            let dataObj = cursor.getString(index: 2).flatMap { JsonValue.parse($0) } ?? .object([])
            result = LoadedRecord(id: cursor.getString(index: 0) ?? id, collectionId: cursor.getLong(index: 1) ?? 0, data: dataObj)
        }
        return result
    }

    private func collectionNameForRecord(_ recordId: String) -> String? {
        var name: String?
        let cursor = driver.executeQuery(
            identifier: nil,
            sql: "SELECT c.name FROM records r JOIN collections c ON c.id = r.collection_id WHERE r.id = ? LIMIT 1",
            parameters: 1
        ) { st in
            st.bindString(index: 0, string: recordId)
        }
        if cursor.next() { name = cursor.getString(index: 0) }
        return name
    }

    /// Normalizes a JSON relation value to the text form used for `records.id`.
    private func recordIdText(_ el: JsonValue?) -> String? {
        guard let el, !el.isNull else { return nil }
        guard let text = el.contentOrNull?.trimmingCharacters(in: .whitespaces), !text.isEmpty else { return nil }
        return text
    }

    // MARK: Schema encoding

    private func encodeSchema(_ schema: CollectionSchema) -> String {
        var fieldPairs: [(String, JsonValue)] = []
        for (name, def) in schema.fields.sorted(by: { $0.key < $1.key }) {
            var pairs: [(String, JsonValue)] = [("type", .string(def.type.rawValue)), ("required", .bool(def.required))]
            if let unique = def.unique { pairs.append(("unique", .bool(unique))) }
            if let relationTo = def.relationTo { pairs.append(("relation_to", .string(relationTo))) }
            if def.sqlIndexed { pairs.append(("sql_indexed", .bool(true))) }
            pairs.append(("uid", .string(def.uid)))
            fieldPairs.append((name, .object(pairs)))
        }
        var relationPairs: [(String, JsonValue)] = []
        for (name, def) in schema.relations.sorted(by: { $0.key < $1.key }) {
            relationPairs.append((name, .object([
                ("target_collection", .string(def.targetCollection)),
                ("relation_type", .string(def.relationType.rawValue)),
                ("required", .bool(def.required)),
                ("uid", .string(def.uid)),
                ("cascade_on_target_delete", .bool(def.cascadeOnTargetDelete)),
                ("has_properties", .bool(def.hasProperties))
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
