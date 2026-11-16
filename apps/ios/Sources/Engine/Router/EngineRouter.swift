import Foundation
import Contract

/// Engine configuration loaded from the verified `config.json` payload. Mirrors
/// `engine.config.EngineConfig`.
public struct EngineConfig: Sendable {
    public enum Mode: String, Sendable { case offline = "OFFLINE", hybrid = "HYBRID", online = "ONLINE" }

    public let tenantId: String
    public let schemaVersion: String
    public let mode: Mode
    public let edgeEndpoint: String
    public let verifyPublicKey: String
    public let capabilities: Set<String>
    public let syncIntervalMs: Int64
    public let dbSchemaVersion: Int

    public init(
        tenantId: String,
        schemaVersion: String = "1.0",
        mode: Mode = .hybrid,
        edgeEndpoint: String,
        verifyPublicKey: String = "",
        capabilities: Set<String> = [],
        syncIntervalMs: Int64 = 900_000,
        dbSchemaVersion: Int = 1
    ) {
        precondition(EngineConfig.isValidTenantId(tenantId), "Invalid tenant_id: must match [a-zA-Z0-9_-]{1,64}")
        precondition(edgeEndpoint.hasPrefix("https://"), "edge_endpoint must be HTTPS")
        self.tenantId = tenantId
        self.schemaVersion = schemaVersion
        self.mode = mode
        self.edgeEndpoint = edgeEndpoint
        self.verifyPublicKey = verifyPublicKey
        self.capabilities = capabilities
        self.syncIntervalMs = syncIntervalMs
        self.dbSchemaVersion = dbSchemaVersion
    }

    static func isValidTenantId(_ id: String) -> Bool {
        guard !id.isEmpty, id.count <= 64 else { return false }
        return id.allSatisfy { $0.isLetter || $0.isNumber || $0 == "_" || $0 == "-" }
    }

    /// Safe factory — call only with a payload already HMAC/Ed25519-verified.
    public static func fromVerifiedPayload(_ payload: String) -> EngineConfig? {
        guard let json = JsonValue.parse(payload) else { return nil }
        return EngineConfig(
            tenantId: json["tenant_id"]?.stringOrNull ?? "",
            schemaVersion: json["schema_version"]?.stringOrNull ?? "1.0",
            mode: Mode(rawValue: json["mode"]?.stringOrNull ?? "HYBRID") ?? .hybrid,
            edgeEndpoint: json["edge_endpoint"]?.stringOrNull ?? "https://edge.invalid",
            verifyPublicKey: json["verify_public_key"]?.stringOrNull ?? "",
            capabilities: Set((json["capabilities"]?.arrayValue ?? []).compactMap { $0.stringOrNull }),
            syncIntervalMs: json["sync_interval_ms"]?.longOrNull ?? 900_000,
            dbSchemaVersion: Int(json["db_schema_version"]?.longOrNull ?? 1)
        )
    }
}

/// Routes DivKit actions to the engine. Mirrors `engine.router.EngineRouter`.
///
/// Schemes: `local-db://op/<name>`, `hardware://<capability>`, `icons://native/<name>`,
/// `workspace://clear_all`, `tools://…`, `div-action://…`; `https://` is left to DivKit.
public final class EngineRouter {
    public enum ActionOutcome: Sendable, Equatable {
        case handled
        case unhandled
        case error(String)
    }

    private static let varNameRegex = try! NSRegularExpression(pattern: "^user\\.[a-zA-Z][a-zA-Z0-9_.]{0,62}$")

    private let config: EngineConfig
    private let workspaceManager: WorkspaceManager
    private let registry: EngineRegistry
    private let activeWorkspace: () -> String
    private let hardwareDispatcher: ((_ capability: String, _ url: String, _ payload: JsonValue?) -> Bool)?
    private let state: EngineState

    public init(
        config: EngineConfig,
        workspaceManager: WorkspaceManager,
        registry: EngineRegistry,
        activeWorkspace: (() -> String)? = nil,
        hardwareDispatcher: ((String, String, JsonValue?) -> Bool)? = nil,
        state: EngineState = .shared
    ) {
        self.config = config
        self.workspaceManager = workspaceManager
        self.registry = registry
        self.activeWorkspace = activeWorkspace ?? { config.tenantId }
        self.hardwareDispatcher = hardwareDispatcher
        self.state = state
    }

    public func handle(url: String, payload: JsonValue?) async -> ActionOutcome {
        if url.hasPrefix("div-action://") { return await handleDivAction(url: url, payload: payload) }
        if url.hasPrefix("local-db://op/") { return handleLocalDb(url: url, payload: payload ?? .object([])) }
        if url.hasPrefix("hardware://") { return handleHardware(url: url, payload: payload) }
        if url.hasPrefix("tools://") { return await handleTools(url: url, payload: payload ?? .object([])) }
        if url.hasPrefix("icons://") { return handleIcons(url: url, payload: payload) }
        if url == "workspace://clear_all" { return handleClearAll() }
        return .unhandled
    }

    private func handleDivAction(url: String, payload: JsonValue?) async -> ActionOutcome {
        let ws = activeWorkspace()
        let ctx = ToolContext(workspaceId: ws)
        guard let result = await TerminalApp.router.handle(raw: url, provenanceType: .publisher, ctx: ctx) else {
            return .unhandled
        }
        switch result {
        case .success:
            return .handled
        case let .unsupported(_, reason):
            emitError("Unsupported tool: \(reason)", payload: payload)
            return .error("Unsupported tool: \(reason)")
        case let .permissionDenied(_, permission):
            emitError("Permission denied: \(permission)", payload: payload)
            return .error("Permission denied: \(permission)")
        case let .failed(_, message):
            emitError("Tool failed: \(message)", payload: payload)
            return .error("Tool failed: \(message)")
        }
    }

    private func handleTools(url: String, payload: JsonValue) async -> ActionOutcome {
        let ws = activeWorkspace()
        let outcome = await NativeToolsSuite.execute(url: url, payload: payload, tenantId: ws)
        if outcome.success {
            return .handled
        }
        let message = outcome.summary
        emitError(message, payload: payload)
        return .error(message)
    }

    private func handleLocalDb(url: String, payload: JsonValue) -> ActionOutcome {
        let opName = String(url.dropFirst("local-db://op/".count))
        let ws = activeWorkspace()
        do {
            let result = try workspaceManager.useDatabase(ws) { db in
                try registry.execute(opName: opName, db: db, workspaceId: ws, payload: payload)
            }
            emitResult(result, payload: payload)
            return .handled
        } catch {
            let message = "\(error)"
            emitError(message, payload: payload)
            return .error(message)
        }
    }

    private func handleHardware(url: String, payload: JsonValue?) -> ActionOutcome {
        let afterScheme = String(url.dropFirst("hardware://".count))
        let host = String(afterScheme.prefix(while: { $0 != "/" }))
        if !config.capabilities.contains(host) {
            emitError("Capability not granted: \(host)", payload: payload)
            return .error("Capability not granted: \(host)")
        }
        if let hardwareDispatcher, hardwareDispatcher(host, url, payload) {
            return .handled
        }
        return .unhandled
    }

    /// Resolves an `icons://native/<name>` reference and publishes the canonical icon name
    /// into a `user.*` variable so DivKit can bind it.
    private func handleIcons(url: String, payload: JsonValue?) -> ActionOutcome {
        guard let key = NativeIconCatalog.normalize(url), let icon = NativeIconCatalog.find(key) else {
            let message = "Unknown native icon: \(url)"
            emitError(message, payload: payload)
            return .error(message)
        }
        let body = payload ?? .object([])
        let target = body["output"]?["icon"]?.contentOrNull
            ?? body["var"]?.contentOrNull
            ?? body["result_var"]?.contentOrNull
            ?? "user.icon_name"
        if EngineRouter.matchesVarName(target) {
            state.pushUpdate(name: target, value: .str(icon.name))
        }
        state.pushUpdate(name: "user.icon_name", value: .str(icon.name))
        state.pushUpdate(name: "user.icon_material", value: .str(icon.material))
        state.pushUpdate(name: "user.icon_category", value: .str(icon.category.label))
        return .handled
    }

    private func handleClearAll() -> ActionOutcome {
        workspaceManager.deleteWorkspace(activeWorkspace())
        state.clear()
        return .handled
    }

    private func emitResult(_ result: OpResult, payload: JsonValue) {
        let output = payload["output"]?.asDictionary
        switch result {
        case let .rows(rows):
            let jsonArrayStr = JsonValue.array(rows.map { row in
                .object(row.keys.sorted().map { ($0, row[$0]!) })
            }).serialized

            if let listVar = payload["json_list_var"]?.contentOrNull {
                requireVar(listVar)
                state.pushUpdate(name: listVar, value: .str(jsonArrayStr))
            }
            if let output {
                for (column, varEl) in output {
                    guard let varName = varEl.contentOrNull else { continue }
                    requireVar(varName)
                    if column == "*" || column == "_json" {
                        state.pushUpdate(name: varName, value: .str(jsonArrayStr))
                    } else {
                        let values = rows.map { row -> DivVariableValue in
                            .str(row[column]?.contentOrNull ?? "")
                        }
                        state.pushUpdate(name: varName, value: .arrayVal(values))
                    }
                }
            }
        case let .row(row):
            if let output {
                for (column, varEl) in output {
                    guard let varName = varEl.contentOrNull else { continue }
                    requireVar(varName)
                    state.pushUpdate(name: varName, value: EngineRouter.toDivValue(row?[column]))
                }
            }
        case let .rowId(id):
            if let v = output?["id"]?.contentOrNull {
                requireVar(v)
                state.pushUpdate(name: v, value: .intVal(id))
            }
        case let .count(n):
            if let v = output?["count"]?.contentOrNull {
                requireVar(v)
                state.pushUpdate(name: v, value: .intVal(n))
            }
        case let .scalar(value):
            if let v = output?["value"]?.contentOrNull {
                requireVar(v)
                state.pushUpdate(name: v, value: EngineRouter.toDivValue(value))
            }
        case let .schemaImported(count):
            if let v = output?["collections"]?.contentOrNull {
                requireVar(v)
                state.pushUpdate(name: v, value: .intVal(Int64(count)))
            }
        case .empty:
            break
        }
    }

    private func emitError(_ message: String, payload: JsonValue?) {
        state.pushUpdate(name: "user.engine_error", value: .str(message))
        if let v = payload?["error_variable"]?.contentOrNull, EngineRouter.matchesVarName(v) {
            state.pushUpdate(name: v, value: .str(message))
        }
    }

    private func requireVar(_ name: String) {
        precondition(EngineRouter.matchesVarName(name), "Output variable must be user.*-namespaced: \(name)")
    }

    static func matchesVarName(_ name: String) -> Bool {
        let range = NSRange(name.startIndex..<name.endIndex, in: name)
        return varNameRegex.firstMatch(in: name, range: range) != nil
    }

    static func toDivValue(_ value: JsonValue?) -> DivVariableValue {
        switch value {
        case .none, .null: return .str("")
        case let .bool(b): return .bool(b)
        case let .int(i): return .intVal(i)
        case let .double(d): return .doubleVal(d)
        case let .string(s): return .str(s)
        case let .array(items): return .arrayVal(items.map { toDivValue($0) })
        case let .object(pairs): return .str(JsonValue.object(pairs).serialized)
        }
    }
}
