import Foundation

public struct ToolArgs: Sendable {
    private let raw: [String: String]
    public init(_ raw: [String: String]) { self.raw = raw }
    public subscript(_ k: String) -> String? { raw[k] }
    public func getInt(_ k: String, default d: Int) -> Int { raw[k].flatMap(Int.init) ?? d }
    public func getDouble(_ k: String, default d: Double) -> Double { raw[k].flatMap(Double.init) ?? d }
    public func getFlag(_ k: String) -> Bool { raw[k] == "true" || raw[k] == "1" }
    public func toMap() -> [String: String] { raw }
}

public struct ToolContext: Sendable {
    public let workspaceId: String
    public let variables: @Sendable (String, String) -> Void
    public init(workspaceId: String = "acme_pos",
                variables: @escaping @Sendable (String, String) -> Void = { _, _ in }) {
        self.workspaceId = workspaceId
        self.variables = variables
    }
}

public enum ToolResult: Sendable {
    case success([String: AnyHashable])
    case unsupported(tool: String, reason: String)
    case permissionDenied(tool: String, permission: String)
    case failed(tool: String, message: String)
}

public protocol ToolHandler: AnyObject, Sendable {
    var capability: String { get }
    var requiredPermissions: Set<String> { get }
    func invoke(args: ToolArgs, ctx: ToolContext) async -> ToolResult
}
