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
}
