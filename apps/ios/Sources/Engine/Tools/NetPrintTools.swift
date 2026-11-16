import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
import Contract

/// Universal network transfer capability ("network.transfer@1"). Mirrors Android `NetTool`.
///
/// Android builds on Ktor (CIO client + embedded LAN hub server). The portable target keeps
/// the same observable contract — outbound HTTP request/download/upload plus the in-memory
/// share registry (`serve`/`share`/`list`/`unshare`) — using `URLSession` for the client and
/// an in-process hub registry. The embedded server socket itself is a platform concern and is
/// wired by the iOS app target.
public final class NetTool: ToolHandler {
    public let capability = "network.transfer@1"
    public let requiredPermissions: Set<String> = ["INTERNET"]

    /// Platform hook used to actually start the LAN hub server; returns the resolved port.
    public nonisolated(unsafe) static var hubStarter: ((_ port: Int, _ label: String) -> Int?)?
    /// Platform hook used to stop the LAN hub server.
    public nonisolated(unsafe) static var hubStopper: (() -> Void)?

    nonisolated(unsafe) private var serverRunning = false
    nonisolated(unsafe) private var serverPort = 0
    nonisolated(unsafe) private var hubId: String?
    nonisolated(unsafe) private var shared: [String: SharedItem] = [:]
    private let lock = NSRecursiveLock()

    private struct SharedItem {
        let id: String
        let name: String
        let mime: String
        let bytes: [UInt8]
    }

    public init() {}

    public func invoke(args: ToolArgs, ctx: ToolContext) async -> ToolResult {
        let op = (args["op"] ?? args["action"] ?? args["operation"] ?? "request").lowercased()
        switch op {
        case "request", "http", "get", "post", "put", "patch", "delete", "head", "fetch":
            return await executeRequest(args, op: op)
        case "download":
            return await executeDownload(args)
        case "upload", "send":
            return await executeUpload(args, op: op)
        case "serve", "hub", "host", "start_server":
            return executeServe(args)
        case "stop", "stop_server", "shutdown":
            return executeStop()
        case "status", "capabilities":
            return executeStatus()
        case "share", "put_share", "add":
            return executeShare(args)
        case "list", "list_share", "ls":
            return executeList()
        case "unshare", "remove":
            return executeUnshare(args)
        default:
            return .failed(tool: capability, message: "Unsupported net operation '\(op)'")
        }
    }

    // MARK: Outbound client operations

    private func executeRequest(_ args: ToolArgs, op: String) async -> ToolResult {
        guard let urlString = args["url"] ?? args["endpoint"] ?? args["target"] else {
            return .failed(tool: capability, message: "missing url argument")
        }
        let method = NetTool.resolveMethod(args, op: op)
        let body = NetTool.decodePayload(args)
        let timeoutMs = args.getInt("timeout_ms", default: 30_000)

        guard let url = URL(string: urlString) else {
            return .failed(tool: capability, message: "invalid url")
        }
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.timeoutInterval = Double(timeoutMs) / 1000.0
        for (k, v) in args.toMap() where k.hasPrefix("header_") {
            request.setValue(v, forHTTPHeaderField: String(k.dropFirst("header_".count)))
        }
        if let ct = args["content_type"] { request.setValue(ct, forHTTPHeaderField: "Content-Type") }
        if let body, method != "GET", method != "HEAD" { request.httpBody = Data(body) }

        do {
            let (data, response) = try await send(request)
            let http = response as? HTTPURLResponse
            let contentType = http?.value(forHTTPHeaderField: "Content-Type") ?? "application/octet-stream"
            var headers: [String: String] = [:]
            http?.allHeaderFields.forEach { headers["\($0.key)"] = "\($0.value)" }

            var out: [String: Any?] = [
                "status": "SUCCESS",
                "http_status": http?.statusCode ?? 0,
                "method": method,
                "url": urlString,
                "content_type": contentType,
                "bytes": data.count,
                "headers": headers,
                "timestamp": ToolSupport.nowMs()
            ]
            if NetTool.isTextLike(contentType) {
                out["body"] = String(data: data, encoding: .utf8) ?? ""
            } else {
                out["body_b64"] = data.base64EncodedString()
            }
            return ToolSupport.success(out)
        } catch {
            return .failed(tool: capability, message: "\(error)")
        }
    }

    private func executeDownload(_ args: ToolArgs) async -> ToolResult {
        guard let urlString = args["url"] ?? args["endpoint"] ?? args["target"] else {
            return .failed(tool: capability, message: "missing url argument")
        }
        let timeoutMs = args.getInt("timeout_ms", default: 60_000)
        guard let url = URL(string: urlString) else {
            return .failed(tool: capability, message: "invalid url")
        }
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.timeoutInterval = Double(timeoutMs) / 1000.0
        for (k, v) in args.toMap() where k.hasPrefix("header_") {
            request.setValue(v, forHTTPHeaderField: String(k.dropFirst("header_".count)))
        }
        do {
            let (data, response) = try await send(request)
            let http = response as? HTTPURLResponse
            let contentType = http?.value(forHTTPHeaderField: "Content-Type") ?? "application/octet-stream"
            return ToolSupport.success([
                "status": "SUCCESS",
                "http_status": http?.statusCode ?? 0,
                "url": urlString,
                "file_name": args["file_name"] ?? NetTool.deriveFileName(urlString),
                "content_type": contentType,
                "bytes": data.count,
                "content_b64": data.base64EncodedString(),
                "timestamp": ToolSupport.nowMs()
            ])
        } catch {
            return .failed(tool: capability, message: "\(error)")
        }
    }

    private func executeUpload(_ args: ToolArgs, op: String) async -> ToolResult {
        guard let urlString = args["url"] ?? args["endpoint"] ?? args["target"] else {
            return .failed(tool: capability, message: "missing url argument")
        }
        guard let body = NetTool.decodePayload(args) else {
            return .failed(tool: capability, message: "missing content_base64/payload/file_path argument")
        }
        let method = NetTool.resolveMethod(args, op: op == "send" ? "post" : op)
        let timeoutMs = args.getInt("timeout_ms", default: 60_000)
        guard let url = URL(string: urlString) else {
            return .failed(tool: capability, message: "invalid url")
        }
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.timeoutInterval = Double(timeoutMs) / 1000.0
        for (k, v) in args.toMap() where k.hasPrefix("header_") {
            request.setValue(v, forHTTPHeaderField: String(k.dropFirst("header_".count)))
        }
        request.setValue(args["content_type"] ?? "application/octet-stream", forHTTPHeaderField: "Content-Type")
        request.httpBody = Data(body)
        do {
            let (data, response) = try await send(request)
            let http = response as? HTTPURLResponse
            return ToolSupport.success([
                "status": "SUCCESS",
                "http_status": http?.statusCode ?? 0,
                "method": method,
                "url": urlString,
                "uploaded_bytes": body.count,
                "response_body": String(data: data, encoding: .utf8) ?? "",
                "timestamp": ToolSupport.nowMs()
            ])
        } catch {
            return .failed(tool: capability, message: "\(error)")
        }
    }

    // MARK: Embedded LAN transfer hub

    private func executeServe(_ args: ToolArgs) -> ToolResult {
        lock.lock(); defer { lock.unlock() }
        if serverRunning {
            var data = statusData()
            data["note"] = "hub already running"
            return ToolSupport.success(data)
        }
        let port = min(max(args.getInt("port", default: 8089), 0), 65535)
        let host = args["host"] ?? "0.0.0.0"
        let label = args["label"] ?? args["name"] ?? "ApexClient Transfer Hub"
        serverPort = NetTool.hubStarter?(port, label) ?? port
        serverRunning = true
        if hubId == nil { hubId = NetTool.newId() }
        return ToolSupport.success([
            "status": "SERVING",
            "hub_id": hubId ?? "",
            "label": label,
            "host": host,
            "port": serverPort,
            "share_count": shared.count,
            "urls": [
                "http://<device-ip>:\(serverPort)/",
                "http://<device-ip>:\(serverPort)/health",
                "http://<device-ip>:\(serverPort)/list"
            ],
            "timestamp": ToolSupport.nowMs()
        ])
    }

    private func executeStop() -> ToolResult {
        lock.lock(); defer { lock.unlock() }
        let wasRunning = serverRunning
        let stoppedPort = serverPort
        NetTool.hubStopper?()
        serverRunning = false
        serverPort = 0
        return ToolSupport.success([
            "status": wasRunning ? "STOPPED" : "NOT_RUNNING",
            "port": stoppedPort,
            "timestamp": ToolSupport.nowMs()
        ])
    }

    private func statusData() -> [String: Any?] {
        [
            "status": serverRunning ? "SERVING" : "IDLE",
            "running": serverRunning,
            "hub_id": hubId ?? "",
            "port": serverPort,
            "share_count": shared.count,
            "supported_operations": [
                "request", "get", "post", "download", "upload",
                "serve", "stop", "status", "share", "list", "unshare"
            ],
            "timestamp": ToolSupport.nowMs()
        ]
    }

    private func executeStatus() -> ToolResult {
        lock.lock(); defer { lock.unlock() }
        return ToolSupport.success(statusData())
    }

    private func executeShare(_ args: ToolArgs) -> ToolResult {
        guard let bytes = NetTool.decodePayload(args) else {
            return .failed(tool: capability, message: "missing content_base64/payload/file_path argument")
        }
        lock.lock(); defer { lock.unlock() }
        let id = NetTool.newId()
        let name = args["name"] ?? args["file_name"] ?? "item_\(id)"
        let mime = args["content_type"] ?? "application/octet-stream"
        shared[id] = SharedItem(id: id, name: name, mime: mime, bytes: bytes)
        return ToolSupport.success([
            "status": "SHARED",
            "id": id,
            "name": name,
            "mime": mime,
            "bytes": bytes.count,
            "path": "/file/\(id)",
            "timestamp": ToolSupport.nowMs()
        ])
    }

    private func executeList() -> ToolResult {
        lock.lock(); defer { lock.unlock() }
        let items: [[String: Any?]] = shared.values.map {
            ["id": $0.id, "name": $0.name, "mime": $0.mime, "bytes": $0.bytes.count]
        }
        return ToolSupport.success([
            "status": "OK",
            "count": items.count,
            "items": items,
            "timestamp": ToolSupport.nowMs()
        ])
    }

    private func executeUnshare(_ args: ToolArgs) -> ToolResult {
        guard let id = args["id"] ?? args["share_id"] else {
            return .failed(tool: capability, message: "missing id argument")
        }
        lock.lock(); defer { lock.unlock() }
        let removed = shared.removeValue(forKey: id) != nil
        return ToolSupport.success([
            "status": removed ? "REMOVED" : "NOT_FOUND",
            "id": id,
            "remaining": shared.count,
            "timestamp": ToolSupport.nowMs()
        ])
    }

    // MARK: Helpers

    static func resolveMethod(_ args: ToolArgs, op: String) -> String {
        let raw = args["method"] ?? (["get", "post", "put", "patch", "delete", "head"].contains(op) ? op : "GET")
        return raw.uppercased()
    }

    static func decodePayload(_ args: ToolArgs) -> [UInt8]? {
        if let s = args["content_base64"], let d = Data(base64Encoded: s) { return [UInt8](d) }
        if let s = args["payload_b64"], let d = Data(base64Encoded: s) { return [UInt8](d) }
        if let s = args["body"] { return [UInt8](s.utf8) }
        if let s = args["payload"] { return [UInt8](s.utf8) }
        if let s = args["content"] { return [UInt8](s.utf8) }
        if let path = args["file_path"], let d = FileManager.default.contents(atPath: path) { return [UInt8](d) }
        return nil
    }

    static func deriveFileName(_ url: String) -> String {
        let path = url.split(separator: "?").first.map(String.init)?.split(separator: "/").last.map(String.init) ?? ""
        return path.isEmpty ? "download_\(ToolSupport.nowMs())" : path
    }

    static func isTextLike(_ contentType: String) -> Bool {
        let ct = contentType.lowercased()
        return ct.hasPrefix("text/") || ct.contains("json") || ct.contains("xml") ||
            ct.contains("javascript") || ct.contains("x-www-form-urlencoded")
    }

    static func newId() -> String {
        String(UUID().uuidString.replacingOccurrences(of: "-", with: "").prefix(12))
    }

    private func send(_ request: URLRequest) async throws -> (Data, URLResponse) {
        try await withCheckedThrowingContinuation { continuation in
            let task = URLSession.shared.dataTask(with: request) { data, response, error in
                if let error { continuation.resume(throwing: error) }
                else if let data, let response { continuation.resume(returning: (data, response)) }
                else { continuation.resume(throwing: URLError(.badServerResponse)) }
            }
            task.resume()
        }
    }
}

/// Bluetooth printer / POS bridge ("device.printer@1"). Mirrors Android `PrintTool`.
///
/// The ESC/POS byte construction (init, alignment, bold, wrap, feed, cut) is identical to
/// Android so the emitted payload is byte-compatible. Bluetooth transport is a platform
/// concern wired by the iOS app target; without it the tool degrades to the same deterministic
/// emulation Android uses in unit tests.
public final class PrintTool: ToolHandler {
    public let capability = "device.printer@1"
    public let requiredPermissions: Set<String> = ["BLUETOOTH_CONNECT", "BLUETOOTH_SCAN"]

    /// Platform transport hook: (op, address, payload) -> handled?
    public nonisolated(unsafe) static var transport: ((_ op: String, _ address: String?, _ payload: [UInt8]?) -> Bool)?

    nonisolated(unsafe) private var connections: Set<String> = []
    private let lock = NSRecursiveLock()

    public init() {}

    public func invoke(args: ToolArgs, ctx: ToolContext) async -> ToolResult {
        let op = (args["op"] ?? args["action"] ?? args["operation"] ?? "scan").lowercased()
        switch op {
        case "scan", "discover", "list", "devices", "printers": return executeScan(args)
        case "pair", "bond": return executePair(args)
        case "connect": return executeConnect(args)
        case "disconnect", "close": return executeDisconnect(args)
        case "print", "print_text", "receipt", "write": return executePrint(args)
        case "status", "capabilities", "get_status": return executeStatus()
        default:
            return .failed(tool: capability, message: "Unsupported printer operation '\(op)'")
        }
    }

    private func executeScan(_ args: ToolArgs) -> ToolResult {
        _ = PrintTool.transport?("scan", nil, nil)
        return ToolSupport.success([
            "status": "SUCCESS",
            "mode": "emulated",
            "devices": [[String: Any?]](),
            "count": 0,
            "timestamp": ToolSupport.nowMs()
        ])
    }

    private func executePair(_ args: ToolArgs) -> ToolResult {
        guard let address = args["address"] ?? args["mac"] ?? args["device"] else {
            return .failed(tool: capability, message: "missing address argument")
        }
        _ = PrintTool.transport?("pair", address, nil)
        return ToolSupport.success(["status": "PAIRED", "address": address, "mode": "emulated", "timestamp": ToolSupport.nowMs()])
    }

    private func executeConnect(_ args: ToolArgs) -> ToolResult {
        guard let address = args["address"] ?? args["mac"] ?? args["device"] else {
            return .failed(tool: capability, message: "missing address argument")
        }
        _ = PrintTool.transport?("connect", address, nil)
        lock.lock(); connections.insert(address); lock.unlock()
        return ToolSupport.success(["status": "CONNECTED", "address": address, "mode": "emulated", "timestamp": ToolSupport.nowMs()])
    }

    private func executeDisconnect(_ args: ToolArgs) -> ToolResult {
        guard let address = args["address"] ?? args["mac"] ?? args["device"] else {
            return .failed(tool: capability, message: "missing address argument")
        }
        lock.lock(); let removed = connections.remove(address) != nil; lock.unlock()
        _ = PrintTool.transport?("disconnect", address, nil)
        return ToolSupport.success([
            "status": removed ? "DISCONNECTED" : "NOT_CONNECTED",
            "address": address,
            "timestamp": ToolSupport.nowMs()
        ])
    }

    private func executePrint(_ args: ToolArgs) -> ToolResult {
        let address = args["address"] ?? args["mac"] ?? args["device"]
        let payload = PrintTool.buildEscPos(args)
        let handled = PrintTool.transport?("print", address, payload) ?? false
        return ToolSupport.success([
            "status": "SUCCESS",
            "mode": handled ? "bluetooth_rfcomm" : "emulated",
            "address": address ?? "",
            "bytes": payload.count,
            "preview_hex": payload.prefix(32).map { String(format: "%02X", $0) }.joined(separator: " "),
            "timestamp": ToolSupport.nowMs()
        ])
    }

    private func executeStatus() -> ToolResult {
        lock.lock(); let conns = Array(connections); lock.unlock()
        return ToolSupport.success([
            "status": "READY",
            "available": true,
            "enabled": true,
            "bonded_printers": 0,
            "connections": conns,
            "supported_operations": ["scan", "pair", "connect", "disconnect", "print", "status"],
            "timestamp": ToolSupport.nowMs()
        ])
    }

    static func buildEscPos(_ args: ToolArgs) -> [UInt8] {
        var out: [UInt8] = [0x1B, 0x40]
        out += alignCommand((args["align"] ?? "left").lowercased())
        out += args.getFlag("bold") ? [0x1B, 0x45, 0x01] : [0x1B, 0x45, 0x00]

        let width = min(max(args.getInt("width", default: 32), 16), 80)
        for line in resolveLines(args) {
            out += [UInt8](wrap(line, width: width).utf8)
            out.append(0x0A)
        }
        let feedLines = min(max(args.getInt("feed_lines", default: 3), 0), 10)
        for _ in 0..<feedLines { out.append(0x0A) }
        if args.getFlag("cut") || args["cut"] == "true" { out += [0x1D, 0x56, 0x00] }
        return out
    }

    static func resolveLines(_ args: ToolArgs) -> [String] {
        if let text = args["text"] { return splitLines(text) }
        if let lines = args["lines"] {
            return lines.split(whereSeparator: { "\n,|".contains($0) })
                .map { $0.trimmingCharacters(in: .whitespaces) }
                .filter { !$0.isEmpty }
        }
        if let receipt = args["receipt"] { return splitLines(receipt) }
        return [""]
    }

    private static func splitLines(_ raw: String) -> [String] {
        raw.replacingOccurrences(of: "\\n", with: "\n").components(separatedBy: "\n")
    }

    static func wrap(_ line: String, width: Int) -> String {
        if line.count <= width { return line }
        var out = ""
        var count = 0
        for ch in line {
            if count == width { out.append("\n"); count = 0 }
            out.append(ch)
            count += 1
        }
        return out
    }

    static func alignCommand(_ align: String) -> [UInt8] {
        switch align {
        case "center", "centre": return [0x1B, 0x61, 0x01]
        case "right": return [0x1B, 0x61, 0x02]
        default: return [0x1B, 0x61, 0x00]
        }
    }
}
