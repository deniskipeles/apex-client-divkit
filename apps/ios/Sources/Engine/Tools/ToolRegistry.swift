import Foundation
import Contract

/// Provenance classification: Agency schemas vs Publisher schemas. Mirrors
/// `engine.tools.Provenance`.
public enum Provenance: Sendable { case agency, publisher }

/// Provenance access control gate. Mirrors `engine.tools.ProvenanceChecker`.
public struct ProvenanceChecker: Sendable {
    public static let publisherAllowlist: Set<String> = [
        "device.haptics@1",
        "device.beeper@1",
        "vision.scan_code@1",
        "vision.capture_photo@1",
        "vision.identify_product@1",
        "vision.embed_image@1",
        "vision.remove_background@1",
        "secure.send@1",
        "secure.status@1",
        "secure.verify_identity@1",
        "auth.biometrics@1",
        "device.clipboard@1",
        "network.push@1",
        "storage.offline_cache@1",
        "network.realtime@1",
        "network.transfer@1",
        "device.printer@1",
        "vision.generate_code@1",
        "device.keep_awake@1"
    ]

    public init() {}

    public func allows(_ provenance: Provenance, _ capability: String) -> Bool {
        switch provenance {
        case .agency: return true
        case .publisher: return ProvenanceChecker.publisherAllowlist.contains(capability)
        }
    }
}

/// Advertised manifest of all capabilities the terminal ships. Mirrors
/// `engine.tools.CapabilityManifest`.
public struct CapabilityManifest: Sendable {
    public let capabilities: [String: Int]

    public init(capabilities: [String: Int]) {
        self.capabilities = capabilities
    }

    public func has(_ cap: String) -> Bool { capabilities[cap] != nil }

    public func satisfies(_ required: [String]) -> [String] {
        required.filter { !has($0) }
    }
}

/// Registry holding compiled `ToolHandler` instances and creating a `CapabilityManifest`.
/// Mirrors `engine.tools.ToolRegistry`.
public final class ToolRegistry: @unchecked Sendable {
    private let handlers: [String: ToolHandler]
    private let lock = NSRecursiveLock()

    public init(handlers: [String: ToolHandler]) {
        self.handlers = handlers
    }

    public func manifest() -> CapabilityManifest {
        CapabilityManifest(capabilities: handlers.keys.reduce(into: [:]) { $0[$1] = 1 })
    }

    public func dispatch(_ capability: String, _ args: ToolArgs, _ ctx: ToolContext) async -> ToolResult {
        guard let handler = handlers[capability] else {
            return .unsupported(tool: capability, reason: "not compiled into this terminal")
        }
        return await handler.invoke(args: args, ctx: ctx)
    }

    public func has(_ capability: String) -> Bool { handlers[capability] != nil }

    public func resolve(_ capability: String) -> ToolHandler? { handlers[capability] }

    public subscript(_ capability: String) -> ToolHandler? { handlers[capability] }
}

/// Parses `div-action://terminal.tool.invoke?tool=<capability>&result_var=<var>&...`.
/// Mirrors `engine.tools.DivActionUrl`.
public struct DivActionUrl: Sendable {
    public let host: String
    public let query: [String: String]

    public var capability: String? { query["tool"] }
    public var resultVar: String? { query["result_var"] }
    public var args: [String: String] { query }

    public static func parse(_ raw: String) -> DivActionUrl? {
        guard raw.hasPrefix("div-action://") else { return nil }
        if let url = URLComponents(string: raw) {
            let host = url.host ?? ""
            var queryMap: [String: String] = [:]
            for item in url.queryItems ?? [] {
                queryMap[item.name] = item.value ?? ""
            }
            return DivActionUrl(host: host, query: queryMap)
        }
        // Fallback manual parsing
        let afterScheme = String(raw.dropFirst("div-action://".count))
        let host = String(afterScheme.prefix(while: { $0 != "?" }))
        let queryStr = afterScheme.contains("?") ? String(afterScheme.drop(while: { $0 != "?" }).dropFirst()) : ""
        var queryMap: [String: String] = [:]
        for param in queryStr.split(separator: "&") {
            let parts = param.split(separator: "=", maxSplits: 1, omittingEmptySubsequences: false)
            let key = String(parts[0]).removingPercentEncoding ?? String(parts[0])
            let value = parts.count > 1 ? (String(parts[1]).removingPercentEncoding ?? String(parts[1])) : ""
            queryMap[key] = value
        }
        return DivActionUrl(host: host, query: queryMap)
    }
}

/// Binds a `ToolResult` back into DivKit `user.*` variables. Mirrors
/// `engine.tools.ResultBinder`.
public enum ResultBinder {
    public static func bind(_ result: ToolResult, _ query: [String: String], _ ctx: ToolContext) {
        let resultVarRaw = query["result_var"]
        let resultVar = resultVarRaw.map { $0.hasPrefix("user.") ? $0 : "user.\($0)" }

        switch result {
        case let .success(data):
            if let resultVar {
                let stateVal = (data["state"] as? String) ?? "ok"
                ctx.variables(resultVar, stateVal)
            }
            for (key, value) in data where key != "candidates" {
                if let resultVar {
                    ctx.variables("\(resultVar)_\(key)", stringify(value))
                }
            }
            if let candidates = data["candidates"] as? [Any], let candidatesVar = query["candidates_var"] {
                let safeVar = candidatesVar.hasPrefix("user.") ? candidatesVar : "user.\(candidatesVar)"
                let jsonArr = JsonValue.array(candidates.map { .string(stringify($0)) }).serialized
                ctx.variables(safeVar, jsonArr)
            }
        case .unsupported:
            if let resultVar { ctx.variables(resultVar, "unsupported") }
        case .permissionDenied:
            if let resultVar { ctx.variables(resultVar, "denied") }
        case .failed:
            if let resultVar { ctx.variables(resultVar, "failed") }
        }
    }

    static func stringify(_ value: Any?) -> String {
        guard let value else { return "" }
        if let s = value as? String { return s }
        return String(describing: value)
    }
}

/// Routes `div-action://terminal.tool.invoke` requests to the `ToolRegistry` with provenance
/// checks. Mirrors `engine.tools.DivActionRouter`.
public final class DivActionRouter {
    private let registry: ToolRegistry
    private let provenance: ProvenanceChecker

    public init(registry: ToolRegistry, provenance: ProvenanceChecker = ProvenanceChecker()) {
        self.registry = registry
        self.provenance = provenance
    }

    public func handle(
        raw: String,
        provenanceType: Provenance = .publisher,
        ctx: ToolContext = ToolContext()
    ) async -> ToolResult? {
        guard let action = DivActionUrl.parse(raw), action.host == "terminal.tool.invoke" else { return nil }
        guard let tool = action.query["tool"] else { return nil }

        if !provenance.allows(provenanceType, tool) {
            let denied = ToolResult.permissionDenied(tool: tool, permission: "Provenance does not allow capability: \(tool)")
            ResultBinder.bind(denied, action.query, ctx)
            return denied
        }

        let result = await registry.dispatch(tool, ToolArgs(action.query), ctx)
        ResultBinder.bind(result, action.query, ctx)
        return result
    }
}
