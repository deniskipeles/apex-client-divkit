import Foundation
import Contract

/// Tenant-scoped file storage ("storage.files@1" / `tools://files`). Mirrors Android
/// `FilesTool` and the desktop `tools/files.rs` engine.
///
/// Unopinionated by design: it moves bytes and reports metadata. It never parses, sniffs or
/// rewrites content, so the same capability backs image output, cached JSON, exported CSV and
/// anything else a card needs to persist.
///
/// Every path resolves inside `workspaces/<tenantId>/` and is rejected if it escapes, so a card
/// cannot read another workspace or reach the app's private data. Callers pass
/// workspace-relative paths (`media/capture.png`), never absolute ones.
public final class FilesTool: ToolHandler {
    public let capability = "storage.files@1"
    public let requiredPermissions: Set<String> = []

    private static let maxInlineRead = 8 * 1024 * 1024
    private static let unsafeNameChars = CharacterSet(charactersIn: "/\\\u{0000}")

    private let rootOverride: String?

    /// `rootOverride` lets tests point the sandbox at a temporary directory; the app uses the
    /// default location under Application Support.
    public init(rootOverride: String? = nil) {
        self.rootOverride = rootOverride
    }

    public func invoke(args: ToolArgs, ctx: ToolContext) async -> ToolResult {
        let op = (args["op"] ?? args["action"] ?? args["operation"] ?? "list").lowercased()
        let root = workspaceRoot(ctx.workspaceId)

        do {
            switch op {
            case "list", "ls": return try list(root, args)
            case "info", "stat": return try info(root, args)
            case "read": return try read(root, args)
            case "write", "put": return try write(root, args, append: false)
            case "append": return try write(root, args, append: true)
            case "copy", "cp": return try copy(root, args)
            case "move", "mv", "rename": return try move(root, args)
            case "delete", "rm": return try delete(root, args)
            case "mkdir": return try mkdir(root, args)
            case "exists", "has": return try exists(root, args)
            case "uri": return try uri(root, args)
            default: return .failed(tool: capability, message: "Unsupported files operation '\(op)'")
            }
        } catch let error as FileStoreError {
            return .failed(tool: capability, message: error.message)
        } catch {
            return .failed(tool: capability, message: error.localizedDescription)
        }
    }

    // MARK: - Sandbox

    private func workspaceRoot(_ tenantId: String) -> String {
        let base = rootOverride ?? defaultRoot()
        let safeTenant = tenantId
            .components(separatedBy: FilesTool.unsafeNameChars)
            .joined(separator: "_")
        let tenant = safeTenant.isEmpty ? "default" : safeTenant
        let root = (base as NSString).appendingPathComponent("workspaces/\(tenant)")
        try? FileManager.default.createDirectory(atPath: root, withIntermediateDirectories: true)
        return root
    }

    private func defaultRoot() -> String {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSTemporaryDirectory())
        return base.appendingPathComponent("apex-workspaces").path
    }

    /// Resolves a workspace-relative `path` inside `root`, rejecting absolute paths and any
    /// segment that escapes. Normalisation happens before the containment check because `..`
    /// can only be detected once the path is lexically resolved.
    private func resolve(_ root: String, _ raw: String?) throws -> String {
        guard let raw = raw?.trimmingCharacters(in: .whitespaces), !raw.isEmpty else {
            throw FileStoreError("Missing 'path' argument")
        }
        guard !raw.hasPrefix("file://") else {
            throw FileStoreError("Pass a workspace-relative path, not a file:// URI: '\(raw)'")
        }
        guard !raw.hasPrefix("/") else {
            throw FileStoreError("Path must be workspace-relative: '\(raw)'")
        }

        let rootPath = (root as NSString).standardizingPath
        let target = ((root as NSString).appendingPathComponent(raw) as NSString).standardizingPath
        let prefix = rootPath.hasSuffix("/") ? rootPath : rootPath + "/"
        guard target == rootPath || target.hasPrefix(prefix) else {
            throw FileStoreError("Path escapes the workspace sandbox: '\(raw)'")
        }
        return target
    }

    private func relative(_ root: String, _ path: String) -> String {
        let rootPath = (root as NSString).standardizingPath
        guard path.hasPrefix(rootPath) else { return path }
        let rel = String(path.dropFirst(rootPath.count))
        return rel.hasPrefix("/") ? String(rel.dropFirst()) : rel
    }

    private func fileURI(_ path: String) -> String {
        URL(fileURLWithPath: path).absoluteString
    }

    // MARK: - Operations

    private func list(_ root: String, _ args: ToolArgs) throws -> ToolResult {
        let dir = try resolve(root, args["path"] ?? args["dir"] ?? ".")
        let fm = FileManager.default
        var isDir: ObjCBool = false
        guard fm.fileExists(atPath: dir, isDirectory: &isDir) else {
            throw FileStoreError("No such directory: \(relative(root, dir))")
        }
        guard isDir.boolValue else {
            throw FileStoreError("Not a directory: \(relative(root, dir))")
        }

        let recursive = args.getFlag("recursive")
        let limit = min(max(args.getInt("limit", default: 500), 1), 5000)
        let glob = args["pattern"].flatMap { $0.isEmpty ? nil : $0 }
        let matcher: ((String) -> Bool)? = glob.map(globMatcher)

        var collected: [String] = []
        collect(dir, recursive: recursive, into: &collected, limit: limit)
        let truncated = collected.count >= limit

        let matching = collected.filter { path in
            guard let matcher else { return true }
            return matcher((path as NSString).lastPathComponent)
        }
        var sorted = WorkspaceFileSystem.sort(matching.map { WorkspaceFileSystem.toEntry($0) })
        if sorted.count > limit { sorted = Array(sorted.prefix(limit)) }

        let entries: [[String: Any]] = sorted.map { entry in
            [
                "name": entry.name,
                "path": relative(root, entry.path),
                "type": entry.isDirectory ? "directory" : "file",
                "size_bytes": entry.isDirectory ? 0 : entry.sizeBytes,
                "modified_ms": entry.lastModified,
            ]
        }
        let totalBytes = sorted.reduce(into: Int64(0)) { sum, entry in
            if !entry.isDirectory { sum += entry.sizeBytes }
        }
        let listing = sorted
            .map { "\($0.isDirectory ? "d" : "-") \($0.name)" }
            .joined(separator: "\n")
        // `ToolResult` only carries hashable scalars, so the structured listing travels as JSON.
        let entriesJSON = (try? JSONSerialization.data(withJSONObject: entries, options: [.sortedKeys]))
            .flatMap { String(data: $0, encoding: .utf8) } ?? "[]"

        let relDir = relative(root, dir)
        return ToolSupport.success([
            "status": "SUCCESS",
            "path": relDir.isEmpty ? "." : relDir,
            "count": entries.count,
            "total_bytes": totalBytes,
            "truncated": truncated,
            "entries_json": entriesJSON,
            "listing": listing,
        ])
    }

    private func collect(_ dir: String, recursive: Bool, into out: inout [String], limit: Int) {
        let children = (try? FileManager.default.contentsOfDirectory(atPath: dir))?
            .sorted()
            .map { (dir as NSString).appendingPathComponent($0) } ?? []
        for child in children {
            if out.count >= limit { return }
            var isDir: ObjCBool = false
            _ = FileManager.default.fileExists(atPath: child, isDirectory: &isDir)
            out.append(child)
            if recursive && isDir.boolValue { collect(child, recursive: true, into: &out, limit: limit) }
        }
    }

    /// Linear wildcard matcher: `*` spans any run, `?` matches one character.
    private func globMatcher(_ glob: String) -> (String) -> Bool {
        let pattern = Array(glob)
        return { name in
            let target = Array(name)
            var p = 0, t = 0
            var star: Int? = nil, mark = 0
            while t < target.count {
                if p < pattern.count && (pattern[p] == "?" || pattern[p] == target[t]) {
                    p += 1; t += 1
                } else if p < pattern.count && pattern[p] == "*" {
                    star = p; mark = t; p += 1
                } else if let s = star {
                    p = s + 1; mark += 1; t = mark
                } else {
                    return false
                }
            }
            while p < pattern.count && pattern[p] == "*" { p += 1 }
            return p == pattern.count
        }
    }

    private func info(_ root: String, _ args: ToolArgs) throws -> ToolResult {
        let path = try resolve(root, args["path"])
        guard FileManager.default.fileExists(atPath: path) else {
            throw FileStoreError("No such file: \(args["path"] ?? "")")
        }
        let entry = WorkspaceFileSystem.toEntry(path)
        return ToolSupport.success([
            "status": "SUCCESS",
            "name": entry.name,
            "path": relative(root, path),
            "type": entry.isDirectory ? "directory" : "file",
            "size_bytes": entry.isDirectory ? 0 : entry.sizeBytes,
            "modified_ms": entry.lastModified,
            "readable": FileManager.default.isReadableFile(atPath: path),
            "writable": FileManager.default.isWritableFile(atPath: path),
        ])
    }

    /// Reads as text, or as base64 when asked. Binary content is never silently mangled into
    /// text: without `encoding=base64` a binary file is refused, because a lossy string would
    /// corrupt it on the way back out.
    private func read(_ root: String, _ args: ToolArgs) throws -> ToolResult {
        let path = try resolve(root, args["path"])
        var isDir: ObjCBool = false
        guard FileManager.default.fileExists(atPath: path, isDirectory: &isDir), !isDir.boolValue else {
            throw FileStoreError("No such file: \(args["path"] ?? "")")
        }
        let attrs = try FileManager.default.attributesOfItem(atPath: path)
        let size = (attrs[.size] as? NSNumber)?.int64Value ?? 0
        guard size <= Int64(FilesTool.maxInlineRead) else {
            throw FileStoreError("File is \(size) bytes; inline reads are capped at \(FilesTool.maxInlineRead)")
        }

        let data = try Data(contentsOf: URL(fileURLWithPath: path))
        let encoding = (args["encoding"] ?? "utf8").lowercased()
        let wantsBase64 = encoding == "base64" || encoding == "binary"

        let content: String
        if wantsBase64 {
            content = data.base64EncodedString()
        } else {
            guard !data.contains(0), let text = String(data: data, encoding: .utf8) else {
                throw FileStoreError("File looks binary; pass encoding=base64 to read it verbatim")
            }
            content = text
        }

        return ToolSupport.success([
            "status": "SUCCESS",
            "path": relative(root, path),
            "size_bytes": data.count,
            "encoding": wantsBase64 ? "base64" : "utf8",
            "content": content,
            "uri": fileURI(path),
        ])
    }

    /// Writes `content` verbatim: base64 when `encoding=base64`, otherwise UTF-8 text.
    private func write(_ root: String, _ args: ToolArgs, append: Bool) throws -> ToolResult {
        let path = try resolve(root, args["path"])
        var isDir: ObjCBool = false
        if FileManager.default.fileExists(atPath: path, isDirectory: &isDir), isDir.boolValue {
            throw FileStoreError("Path is a directory: \(args["path"] ?? "")")
        }

        let encoding = (args["encoding"] ?? "utf8").lowercased()
        let payload = args["content"] ?? args["data"] ?? args["text"] ?? ""
        let bytes: Data
        if encoding == "base64" || encoding == "binary" {
            guard let decoded = Data(base64Encoded: payload) else {
                throw FileStoreError("Content is not valid base64")
            }
            bytes = decoded
        } else {
            bytes = Data(payload.utf8)
        }

        let parent = (path as NSString).deletingLastPathComponent
        try FileManager.default.createDirectory(atPath: parent, withIntermediateDirectories: true)

        if append, FileManager.default.fileExists(atPath: path) {
            let handle = try FileHandle(forWritingTo: URL(fileURLWithPath: path))
            defer { try? handle.close() }
            _ = try handle.seekToEnd()
            try handle.write(contentsOf: bytes)
        } else {
            try bytes.write(to: URL(fileURLWithPath: path))
        }

        let written = (try? FileManager.default.attributesOfItem(atPath: path))?[.size] as? NSNumber
        return ToolSupport.success([
            "status": "SUCCESS",
            "path": relative(root, path),
            "size_bytes": written?.int64Value ?? Int64(bytes.count),
            "bytes_written": bytes.count,
            "encoding": encoding == "base64" || encoding == "binary" ? "base64" : "utf8",
            "uri": fileURI(path),
        ])
    }

    private func copy(_ root: String, _ args: ToolArgs) throws -> ToolResult {
        let from = try resolve(root, args["from"] ?? args["source"] ?? args["path"])
        let to = try resolve(root, args["to"] ?? args["destination"] ?? args["target"])
        var isDir: ObjCBool = false
        guard FileManager.default.fileExists(atPath: from, isDirectory: &isDir) else {
            throw FileStoreError("No such source: \(args["from"] ?? "")")
        }
        guard !isDir.boolValue else {
            throw FileStoreError("copy only handles files; use mkdir for directories")
        }
        if FileManager.default.fileExists(atPath: to), !args.getFlag("overwrite") {
            throw FileStoreError("Destination exists: \(args["to"] ?? "") (pass overwrite=true)")
        }
        try FileManager.default.createDirectory(
            atPath: (to as NSString).deletingLastPathComponent,
            withIntermediateDirectories: true
        )
        try FileManager.default.copyItem(atPath: from, toPath: to)

        let size = (try? FileManager.default.attributesOfItem(atPath: to))?[.size] as? NSNumber
        return ToolSupport.success([
            "status": "SUCCESS",
            "from": relative(root, from),
            "path": relative(root, to),
            "size_bytes": size?.int64Value ?? 0,
        ])
    }

    private func move(_ root: String, _ args: ToolArgs) throws -> ToolResult {
        let from = try resolve(root, args["from"] ?? args["source"] ?? args["path"])
        let to = try resolve(root, args["to"] ?? args["destination"] ?? args["target"])
        guard FileManager.default.fileExists(atPath: from) else {
            throw FileStoreError("No such source: \(args["from"] ?? "")")
        }
        if FileManager.default.fileExists(atPath: to), !args.getFlag("overwrite") {
            throw FileStoreError("Destination exists: \(args["to"] ?? "") (pass overwrite=true)")
        }
        try FileManager.default.createDirectory(
            atPath: (to as NSString).deletingLastPathComponent,
            withIntermediateDirectories: true
        )
        try FileManager.default.moveItem(atPath: from, toPath: to)

        return ToolSupport.success([
            "status": "SUCCESS",
            "from": relative(root, from),
            "path": relative(root, to),
        ])
    }

    private func delete(_ root: String, _ args: ToolArgs) throws -> ToolResult {
        let path = try resolve(root, args["path"])
        var isDir: ObjCBool = false
        guard FileManager.default.fileExists(atPath: path, isDirectory: &isDir) else {
            throw FileStoreError("No such file: \(args["path"] ?? "")")
        }
        if isDir.boolValue && !args.getFlag("recursive") {
            let contents = (try? FileManager.default.contentsOfDirectory(atPath: path)) ?? []
            if !contents.isEmpty {
                throw FileStoreError("Directory is not empty; pass recursive=true to delete it")
            }
        }
        let removed = WorkspaceFileSystem.delete(path)
        return ToolSupport.success([
            "status": removed ? "SUCCESS" : "FAILED",
            "path": relative(root, path),
            "deleted": removed,
        ])
    }

    private func mkdir(_ root: String, _ args: ToolArgs) throws -> ToolResult {
        let dir = try resolve(root, args["path"])
        let created = !FileManager.default.fileExists(atPath: dir)
        try FileManager.default.createDirectory(atPath: dir, withIntermediateDirectories: true)
        return ToolSupport.success([
            "status": "SUCCESS",
            "path": relative(root, dir),
            "created": created,
        ])
    }

    private func exists(_ root: String, _ args: ToolArgs) throws -> ToolResult {
        let path = try resolve(root, args["path"])
        var isDir: ObjCBool = false
        let present = FileManager.default.fileExists(atPath: path, isDirectory: &isDir)
        let kind = !present ? "none" : (isDir.boolValue ? "directory" : "file")
        return ToolSupport.success([
            "status": "SUCCESS",
            "path": relative(root, path),
            "exists": present,
            "type": kind,
        ])
    }

    private func uri(_ root: String, _ args: ToolArgs) throws -> ToolResult {
        let path = try resolve(root, args["path"])
        let present = FileManager.default.fileExists(atPath: path)
        return ToolSupport.success([
            "status": present ? "SUCCESS" : "MISSING",
            "path": relative(root, path),
            "exists": present,
            "uri": fileURI(path),
        ])
    }
}

/// Failure raised by [FilesTool] so the message reaches the caller unchanged.
private struct FileStoreError: Error {
    let message: String
    init(_ message: String) { self.message = message }
}
