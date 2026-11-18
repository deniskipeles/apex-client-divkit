import XCTest
@testable import Engine

/// Exercises the workspace database + ApexKit engine end to end (schema import, insert,
/// count, query, policy enforcement) against a real SQLite database, mirroring the Android
/// `SchemaContractTest` / engine DB tests.
final class WorkspaceDatabaseTests: XCTestCase {

    private func manager() -> WorkspaceManager {
        WorkspaceManager(rootDir: NSTemporaryDirectory(), useMemory: true)
    }

    private func schemaPayload() -> JsonValue {
        .object([
            ("schema", .object([
                ("collections", .array([
                    .object([
                        ("id", .int(1)),
                        ("name", .string("orders")),
                        ("schema", .object([
                            ("fields", .object([
                                ("item_name", .object([("type", .string("string")), ("required", .bool(true)), ("uid", .string("orders_item_name"))])),
                                ("sku", .object([("type", .string("string")), ("uid", .string("orders_sku"))])),
                                ("price", .object([("type", .string("number")), ("uid", .string("orders_price"))])),
                                ("qty", .object([("type", .string("string")), ("uid", .string("orders_qty"))])),
                                ("terminal_id", .object([("type", .string("string")), ("uid", .string("orders_terminal_id"))])),
                                ("status", .object([("type", .string("string")), ("uid", .string("orders_status"))]))
                            ])),
                            ("policies", .object([
                                ("read", .string("public")), ("create", .string("public")),
                                ("update", .string("admin")), ("delete", .string("admin"))
                            ]))
                        ]))
                    ])
                ]))
            ]))
        ])
    }

    func testImportSchemaThenInsertCountAndQuery() throws {
        let mgr = manager()
        let registry = EngineRegistry(clock: { 1_700_000_000_000 })
        let ws = "acme_pos"

        let imported = try mgr.useDatabase(ws) { db in
            try registry.execute(opName: "db.import_schema", db: db, workspaceId: ws, payload: schemaPayload())
        }
        guard case let .schemaImported(count) = imported else {
            return XCTFail("expected schemaImported, got \(imported)")
        }
        XCTAssertEqual(count, 1)

        _ = try mgr.useDatabase(ws) { db in
            try registry.execute(opName: "db.insert", db: db, workspaceId: ws, payload: .object([
                ("table", .string("orders")),
                ("values", .object([
                    ("item_name", .string("Wagyu Smash Burger")),
                    ("sku", .string("ACM-101")),
                    ("price", .double(16.50)),
                    ("qty", .int(1)),
                    ("terminal_id", .string("POS-TERM-01")),
                    ("status", .string("PAID"))
                ]))
            ]))
        }

        let counted = try mgr.useDatabase(ws) { db in
            try registry.execute(opName: "db.count", db: db, workspaceId: ws, payload: .object([("table", .string("orders"))]))
        }
        guard case let .count(n) = counted else { return XCTFail("expected count") }
        XCTAssertEqual(n, 1)

        let rows = try mgr.useDatabase(ws) { db in
            try registry.execute(opName: "db.query", db: db, workspaceId: ws, payload: .object([
                ("table", .string("orders")),
                ("select", .array([.string("sku"), .string("status")]))
            ]))
        }
        guard case let .rows(resultRows) = rows else { return XCTFail("expected rows") }
        XCTAssertEqual(resultRows.count, 1)
        XCTAssertEqual(resultRows[0]["sku"]?.stringOrNull, "ACM-101")
    }

    func testUnknownOperationIsRejected() {
        let mgr = manager()
        let registry = EngineRegistry()
        XCTAssertThrowsError(try mgr.useDatabase("acme_pos") { db in
            try registry.execute(opName: "db.nonsense", db: db, workspaceId: "acme_pos", payload: .object([]))
        })
    }

    func testPayloadSizeLimitIsEnforced() {
        let mgr = manager()
        let registry = EngineRegistry()
        let huge = String(repeating: "x", count: EngineRegistry.maxPayloadBytes + 1)
        XCTAssertThrowsError(try mgr.useDatabase("acme_pos") { db in
            try registry.execute(opName: "db.count", db: db, workspaceId: "acme_pos", payload: .object([("junk", .string(huge))]))
        })
    }

    func testWorkspaceIdValidation() {
        let mgr = manager()
        XCTAssertNoThrow(mgr.validateWorkspaceId("acme_pos"))
        XCTAssertNoThrow(mgr.validateWorkspaceId("ws-01"))
        // Invalid ids trip a precondition (trap), so we only assert the valid path here.
    }

    // MARK: v4 storage refactor

    private func engine(_ path: String = ":memory:") -> (ApexKitDatabaseEngine, SqlDriver) {
        let driver = DirectSqliteDriver(schema: WorkspaceDatabase.Schema(), path: path)
        return (ApexKitDatabaseEngine(driver: driver), driver)
    }

    private func indexNames(_ driver: SqlDriver) -> [String] {
        var names: [String] = []
        let cursor = driver.executeQuery(identifier: nil, sql: "SELECT name FROM sqlite_master WHERE type='index'", parameters: 0, binders: nil)
        while cursor.next() { if let name = cursor.getString(index: 0) { names.append(name) } }
        return names
    }

    private func relationRowCount(_ driver: SqlDriver) -> Int64 {
        let cursor = driver.executeQuery(identifier: nil, sql: "SELECT COUNT(*) FROM _relations", parameters: 0, binders: nil)
        return cursor.next() ? (cursor.getLong(index: 0) ?? 0) : 0
    }

    private func uniqueEmailSchema() -> CollectionSchema {
        CollectionSchema(fields: [
            "email": FieldDefinition(type: .string, unique: true, uid: "c_email"),
            "status": FieldDefinition(type: .string, uid: "c_status", sqlIndexed: true)
        ])
    }

    func testUniqueFieldCreatesExpressionIndexAndRejectsDuplicate() throws {
        let (apex, driver) = engine()
        try apex.importTenantSchema(TenantSchemaPackage(collections: [
            Collection(id: 5, name: "customers", schema: uniqueEmailSchema())
        ]))

        XCTAssertTrue(indexNames(driver).contains("idx_u_5_email"))
        XCTAssertTrue(indexNames(driver).contains("idx_n_5_status"))

        try apex.insertRecord("customers", data: .object([("email", .string("a@b.com"))]))
        XCTAssertThrowsError(try apex.insertRecord("customers", data: .object([("email", .string("a@b.com"))])))
    }

    func testRecordIdsAreAlwaysText() throws {
        let (apex, _) = engine()
        try apex.importTenantSchema(TenantSchemaPackage(collections: [
            Collection(id: 1, name: "customers", schema: CollectionSchema())
        ]))

        XCTAssertEqual(try apex.insertRecord("customers", data: .object([("id", .string("customer_abc"))])), "customer_abc")
        XCTAssertEqual(try apex.insertRecord("customers", data: .object([("id", .int(42))])), "42")
        let generated = try apex.insertRecord("customers", data: .object([("name", .string("auto"))]))
        XCTAssertNotNil(Int64(generated))

        let stored = Set(apex.queryRecords("customers").map { $0.id })
        XCTAssertTrue(stored.contains("customer_abc"))
        XCTAssertTrue(stored.contains("42"))
        XCTAssertTrue(stored.contains(generated))
    }

    private func relationSchema(cascade: Bool, many: Bool) -> CollectionSchema {
        CollectionSchema(relations: [
            "target_id": RelationDefinition(
                targetCollection: "targets",
                relationType: many ? .many : .one,
                uid: "c_target",
                cascadeOnTargetDelete: cascade
            )
        ])
    }

    func testSimpleReferenceDoesNotMaterialiseRelationRow() throws {
        let (apex, driver) = engine()
        try apex.importTenantSchema(TenantSchemaPackage(collections: [
            Collection(id: 1, name: "targets", schema: CollectionSchema()),
            Collection(id: 2, name: "orders", schema: relationSchema(cascade: false, many: false))
        ]))

        let target = try apex.insertRecord("targets", data: .object([("name", .string("T"))]))
        _ = try apex.insertRecord("orders", data: .object([("target_id", .string(target))]))
        XCTAssertEqual(relationRowCount(driver), 0)
    }

    func testForeignKeyCascadeRemovesRelationRows() throws {
        let (apex, driver) = engine()
        try apex.importTenantSchema(TenantSchemaPackage(collections: [
            Collection(id: 1, name: "targets", schema: CollectionSchema()),
            Collection(id: 2, name: "orders", schema: relationSchema(cascade: false, many: true))
        ]))

        let target = try apex.insertRecord("targets", data: .object([("name", .string("T"))]))
        let order = try apex.insertRecord("orders", data: .object([("target_id", .string(target))]))
        XCTAssertEqual(relationRowCount(driver), 1)

        _ = try apex.deleteRecord("orders", recordId: order, user: Claims(role: "admin"))
        XCTAssertEqual(relationRowCount(driver), 0)
    }

    func testApplicationCascadeDeletesOriginWhenTargetDeleted() throws {
        let (apex, driver) = engine()
        try apex.importTenantSchema(TenantSchemaPackage(collections: [
            Collection(id: 1, name: "targets", schema: CollectionSchema()),
            Collection(id: 2, name: "orders", schema: relationSchema(cascade: true, many: false))
        ]))

        let target = try apex.insertRecord("targets", data: .object([("name", .string("T"))]))
        _ = try apex.insertRecord("orders", data: .object([("target_id", .string(target))]))

        _ = try apex.deleteRecord("targets", recordId: target, user: Claims(role: "admin"))
        XCTAssertEqual(apex.countRecords("targets"), 0)
        XCTAssertEqual(apex.countRecords("orders"), 0)
        XCTAssertEqual(relationRowCount(driver), 0)
    }
}
