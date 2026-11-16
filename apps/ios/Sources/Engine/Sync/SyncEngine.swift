import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

public enum SyncOutcome: Sendable, Equatable { case success, retry }

/// A queued outbox row. Mirrors `engine.sync.SyncRow`.
public struct SyncRow: Sendable {
    public let id: Int64
    public let clientUuid: String
    public let op: String
    public let payload: String
}

/// HTTP response wrapper. Mirrors `engine.sync.HttpResponse`.
public struct HttpResponse {
    public let statusCode: Int
    public let body: String
    public var isSuccess: Bool { (200...299).contains(statusCode) }
}

/// Lightweight HTTP client. Mirrors `engine.sync.HttpClient`.
///
/// When pointed at the embedded Edge SDUI Gateway (`https://edge.sdui-pos.internal/...`) the
/// idempotent sync contract is processed locally with realistic latency and validation, so the
/// offline-first flow is exercisable without a network.
public final class HttpClient: @unchecked Sendable {
    private let session: URLSession
    private let simulateLatency: TimeInterval

    public init(session: URLSession = .shared, simulateLatency: TimeInterval = 0.18) {
        self.session = session
        self.simulateLatency = simulateLatency
    }

    public func post(_ url: String, body: String, contentType: String = "application/json; charset=utf-8") async -> HttpResponse {
        if url.hasPrefix("https://edge.sdui-pos.internal") {
            try? await Task.sleep(nanoseconds: UInt64(simulateLatency * 1_000_000_000))
            return Self.embeddedGateway(body: body)
        }

        guard let requestUrl = URL(string: url) else {
            return HttpResponse(statusCode: 400, body: "{}")
        }
        var request = URLRequest(url: requestUrl)
        request.httpMethod = "POST"
        request.setValue(contentType, forHTTPHeaderField: "Content-Type")
        request.httpBody = Data(body.utf8)

        do {
            let (data, response) = try await send(request)
            let code = (response as? HTTPURLResponse)?.statusCode ?? 500
            return HttpResponse(statusCode: code, body: String(data: data, encoding: .utf8) ?? "{}")
        } catch {
            return HttpResponse(statusCode: 0, body: "{}")
        }
    }

    /// Processes a batch through the embedded gateway: rows carrying `simulate_reject` are
    /// rejected with that reason, everything else is accepted.
    static func embeddedGateway(body: String) -> HttpResponse {
        guard let parsed = JsonValue.parse(body), let batch = parsed["batch"]?.arrayValue else {
            return HttpResponse(statusCode: 200, body: #"{"accepted":[],"rejected":[]}"#)
        }
        var accepted: [JsonValue] = []
        var rejected: [JsonValue] = []
        for item in batch {
            guard let uuid = item["client_uuid"]?.stringOrNull else { continue }
            if let reason = item["payload"]?["simulate_reject"]?.stringOrNull {
                rejected.append(.object([("uuid", .string(uuid)), ("reason", .string(reason))]))
            } else {
                accepted.append(.string(uuid))
            }
        }
        let response: JsonValue = .object([
            ("accepted", .array(accepted)),
            ("rejected", .array(rejected))
        ])
        return HttpResponse(statusCode: 200, body: response.serialized)
    }

    /// `URLSession.data(for:)` is unavailable in swift-corelibs-foundation, so the request is
    /// bridged from the completion-handler API to work on every platform.
    private func send(_ request: URLRequest) async throws -> (Data, URLResponse) {
        try await withCheckedThrowingContinuation { continuation in
            let task = session.dataTask(with: request) { data, response, error in
                if let error {
                    continuation.resume(throwing: error)
                } else if let data, let response {
                    continuation.resume(returning: (data, response))
                } else {
                    continuation.resume(throwing: URLError(.badServerResponse))
                }
            }
            task.resume()
        }
    }
}

public func createHttpClient() -> HttpClient { HttpClient() }

/// Pushes `pending_sync` rows to the edge. Mirrors `engine.sync.SyncEngine`.
///
/// Server contract (idempotent by `client_uuid`):
/// ```
/// POST {edgeEndpoint}/sync
/// Body: { "batch": [{ "client_uuid": "...", "op": "db.insert", "payload": {...} }] }
/// Response: { "accepted": ["uuid"], "rejected": [{"uuid": "...", "reason": "..."}] }
/// ```
///
/// Retry policy: network / 5xx / rate-limited → retry; 4xx → dead-letter at `maxAttempts`.
public final class SyncEngine {
    public static let batchSize = 50
    public static let maxAttempts = 10

    private let workspaceManager: WorkspaceManager
    private let http: HttpClient
    private let state: EngineState

    public init(workspaceManager: WorkspaceManager, http: HttpClient, state: EngineState = .shared) {
        self.workspaceManager = workspaceManager
        self.http = http
        self.state = state
    }

    public func sync(workspaceId: String, endpoint: String) async -> SyncOutcome {
        state.pushUpdate(name: "user.sync_status", value: .str("syncing"))
        let batch = readBatch(workspaceId)
        if batch.isEmpty {
            state.pushUpdate(name: "user.sync_status", value: .str("synced (0 pending)"))
            return .success
        }

        do {
            let response = try await pushBatch(endpoint: endpoint, batch: batch)
            let accepted = (response["accepted"]?.arrayValue ?? []).compactMap { $0.stringOrNull }
            let rejected = response["rejected"]?.arrayValue ?? []
            var shouldRetry = false

            workspaceManager.useDatabase(workspaceId) { db in
                for uuid in accepted { db.workspaceQueries.deletePendingSync(clientUuid: uuid) }
                for el in rejected {
                    guard let uuid = el["uuid"]?.stringOrNull else { continue }
                    let reason = el["reason"]?.stringOrNull ?? "rejected"
                    if SyncEngine.isRetryable(reason: reason) {
                        db.workspaceQueries.incrementPendingAttempts(clientUuid: uuid)
                        shouldRetry = true
                    } else {
                        db.workspaceQueries.markPendingDead(reason: reason, clientUuid: uuid)
                    }
                }
            }

            let statusLabel = shouldRetry
                ? "retry_scheduled (\(accepted.count) ok, \(rejected.count) retry)"
                : "synced (\(accepted.count) pushed)"
            state.pushUpdate(name: "user.sync_status", value: .str(statusLabel))
            return shouldRetry ? .retry : .success
        } catch {
            state.pushUpdate(name: "user.sync_status", value: .str("retry_needed: \(error)"))
            return .retry
        }
    }

    public static func isRetryable(reason: String) -> Bool {
        reason.hasPrefix("5") || reason == "timeout" || reason == "rate_limited"
    }

    private func readBatch(_ workspaceId: String) -> [SyncRow] {
        workspaceManager.useDatabase(workspaceId) { db -> [SyncRow] in
            var rows: [SyncRow] = []
            let cursor = db.driver.executeQuery(
                identifier: nil,
                sql: "SELECT id, client_uuid, op, payload FROM pending_sync " +
                    "WHERE attempts < \(SyncEngine.maxAttempts) ORDER BY id LIMIT \(SyncEngine.batchSize)",
                parameters: 0,
                binders: nil
            )
            while cursor.next() {
                rows.append(SyncRow(
                    id: cursor.getLong(index: 0) ?? 0,
                    clientUuid: cursor.getString(index: 1) ?? "",
                    op: cursor.getString(index: 2) ?? "",
                    payload: cursor.getString(index: 3) ?? "{}"
                ))
            }
            return rows
        }
    }

    private func pushBatch(endpoint: String, batch: [SyncRow]) async throws -> JsonValue {
        let body: JsonValue = .object([
            ("batch", .array(batch.map { row in
                .object([
                    ("client_uuid", .string(row.clientUuid)),
                    ("op", .string(row.op)),
                    ("payload", JsonValue.parse(row.payload) ?? .object([]))
                ])
            }))
        ])
        let response = await http.post("\(endpoint)/sync", body: body.serialized)
        guard response.isSuccess else {
            throw EngineError.validation("Sync HTTP \(response.statusCode)")
        }
        guard let parsed = JsonValue.parse(response.body) else {
            throw EngineError.validation("Malformed sync response")
        }
        return parsed
    }
}
