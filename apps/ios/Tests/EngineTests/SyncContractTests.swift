import XCTest
@testable import Engine

/// Pins the sync batch contract (packages/contract/docs/sync-batch.md) against the embedded
/// edge gateway, mirroring `SyncContractTest.kt`.
final class SyncContractTests: XCTestCase {

    private let endpoint = "https://edge.sdui-pos.internal"

    private func manager() -> WorkspaceManager {
        WorkspaceManager(rootDir: NSTemporaryDirectory(), useMemory: true)
    }

    private func insertPending(_ manager: WorkspaceManager, ws: String, uuid: String, payload: String) {
        manager.useDatabase(ws) { db in
            db.driver.executeUpdate(
                identifier: nil,
                sql: "INSERT INTO pending_sync (client_uuid, op, payload, created_at) VALUES (?, ?, ?, ?)",
                parameters: 4
            ) { st in
                st.bindString(index: 0, string: uuid)
                st.bindString(index: 1, string: "db.insert")
                st.bindString(index: 2, string: payload)
                st.bindLong(index: 3, long: 1)
            }
        }
    }

    private func pendingCount(_ manager: WorkspaceManager, ws: String) -> Int64 {
        manager.useDatabase(ws) { db -> Int64 in
            let cursor = db.driver.executeQuery(identifier: nil, sql: "SELECT COUNT(*) FROM pending_sync", parameters: 0, binders: nil)
            return cursor.next() ? (cursor.getLong(index: 0) ?? 0) : 0
        }
    }

    func testAcceptedRowIsDeleted() async {
        let manager = manager()
        insertPending(manager, ws: "acme_pos", uuid: "u-accepted", payload: #"{"v":1}"#)
        let outcome = await SyncEngine(workspaceManager: manager, http: createHttpClient()).sync(workspaceId: "acme_pos", endpoint: endpoint)
        XCTAssertEqual(outcome, .success)
        XCTAssertEqual(pendingCount(manager, ws: "acme_pos"), 0)
    }

    func testRetryableRejectionKeepsRowAndSignalsRetry() async {
        let manager = manager()
        insertPending(manager, ws: "acme_pos", uuid: "u-retry", payload: #"{"simulate_reject":"503"}"#)
        let outcome = await SyncEngine(workspaceManager: manager, http: createHttpClient()).sync(workspaceId: "acme_pos", endpoint: endpoint)
        XCTAssertEqual(outcome, .retry)
        XCTAssertEqual(pendingCount(manager, ws: "acme_pos"), 1)
    }

    func testNonRetryableRejectionDeadLettersRow() async {
        let manager = manager()
        insertPending(manager, ws: "acme_pos", uuid: "u-dead", payload: #"{"simulate_reject":"validation_error"}"#)
        let outcome = await SyncEngine(workspaceManager: manager, http: createHttpClient()).sync(workspaceId: "acme_pos", endpoint: endpoint)
        XCTAssertEqual(outcome, .success)
        // Row is retained (dead-lettered via markPendingDead), not deleted.
        XCTAssertEqual(pendingCount(manager, ws: "acme_pos"), 1)
    }

    func testEmptyOutboxIsASuccessfulNoop() async {
        let manager = manager()
        let outcome = await SyncEngine(workspaceManager: manager, http: createHttpClient()).sync(workspaceId: "acme_pos", endpoint: endpoint)
        XCTAssertEqual(outcome, .success)
    }

    func testRetryableReasonClassifier() {
        XCTAssertTrue(SyncEngine.isRetryable(reason: "503"))
        XCTAssertTrue(SyncEngine.isRetryable(reason: "timeout"))
        XCTAssertTrue(SyncEngine.isRetryable(reason: "rate_limited"))
        XCTAssertFalse(SyncEngine.isRetryable(reason: "validation_error"))
    }
}
