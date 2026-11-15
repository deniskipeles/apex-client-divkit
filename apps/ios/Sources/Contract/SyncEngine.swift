import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// Swift mirror of `engine.sync.SyncEngine` (packages/contract/docs/sync-batch.md).
/// The server contract is idempotent by `client_uuid`.
public enum SyncOutcome: Sendable { case success, retry }

public final class SyncEngine: @unchecked Sendable {
    public struct Row: Sendable {
        public let clientUuid: String
        public let op: String
        public let payload: String
    }

    private let endpoint: URL
    private let session: URLSession

    public init(endpoint: URL, session: URLSession = .shared) {
        self.endpoint = endpoint
        self.session = session
    }

    public static func isRetryable(reason: String) -> Bool {
        reason.hasPrefix("5") || reason == "timeout" || reason == "rate_limited"
    }

    public static let batchSize = 50
    public static let maxAttempts = 10

    public func sync(_ rows: [Row]) async -> SyncOutcome {
        guard !rows.isEmpty else { return .success }

        let batch = rows.map { ["client_uuid": $0.clientUuid, "op": $0.op, "payload": $0.payload] }
        let body = try? JSONSerialization.data(withJSONObject: ["batch": batch])

        var request = URLRequest(url: endpoint.appendingPathComponent("sync"))
        request.httpMethod = "POST"
        request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        request.httpBody = body

        do {
            let (data, response) = try await send(request)
            guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) else {
                return .retry
            }
            guard
                let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
                let rejected = json["rejected"] as? [[String: Any]]
            else {
                return .success
            }
            let needsRetry = rejected.contains { item in
                guard let reason = item["reason"] as? String else { return false }
                return SyncEngine.isRetryable(reason: reason)
            }
            return needsRetry ? .retry : .success
        } catch {
            return .retry
        }
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
