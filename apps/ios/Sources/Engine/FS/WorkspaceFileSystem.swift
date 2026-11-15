import Foundation

/// A single entry (file or directory) surfaced by the file manager. Mirrors `engine.fs.FsEntry`.
public struct FsEntry: Sendable, Equatable {
    public let name: String
    public let path: String
    public let isDirectory: Bool
    public let sizeBytes: Int64
    public let lastModified: Int64
    public let isReadOnly: Bool

    public init(name: String, path: String, isDirectory: Bool, sizeBytes: Int64, lastModified: Int64, isReadOnly: Bool = false) {
        self.name = name
        self.path = path
        self.isDirectory = isDirectory
        self.sizeBytes = sizeBytes
        self.lastModified = lastModified
        self.isReadOnly = isReadOnly
    }

    public var `extension`: String {
        guard let dot = name.lastIndex(of: "."), dot != name.startIndex else { return "" }
        return String(name[name.index(after: dot)...]).lowercased()
    }

    public var isTextPreviewable: Bool {
        !isDirectory && FsEntry.textExtensions.contains(self.extension)
    }

    public static let textExtensions: Set<String> = [
        "txt", "json", "md", "log", "csv", "xml", "yml", "yaml", "kt", "js", "html", "properties"
    ]
}

/// A workspace sandbox folder plus the tenant database that belongs to it. Mirrors
/// `engine.fs.WorkspaceFolder`.
public struct WorkspaceFolder: Sendable, Equatable {
    public let workspaceId: String
    public let displayName: String
    public let dirPath: String
    public let databasePath: String?
    public let sizeBytes: Int64
    public let fileCount: Int
    public let lastModified: Int64
}

/// Pure filesystem operations backing the workspace file manager. Mirrors
/// `engine.fs.WorkspaceFileSystem`.
///
/// Every mutating helper validates the target name through `childOf`, which canonicalises both
/// sides and rejects any name that would escape the parent directory.
public enum WorkspaceFileSystem {

    /// Read cap for text previews; larger files are truncated in the UI.
    public static let maxPreviewBytes = 64 * 1024

    private static let invalidNameChars = CharacterSet(charactersIn: "/\\\u{0000}")

    public static func isSafeName(_ name: String) -> Bool {
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        if trimmed.isEmpty || trimmed == "." || trimmed == ".." { return false }
        return trimmed.rangeOfCharacter(from: invalidNameChars) == nil
    }

    /// Resolves `name` under `parent`, or nil when the name is unsafe or escapes `parent`.
    public static func childOf(_ parent: String, _ name: String) -> String? {
        guard isSafeName(name) else { return nil }
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        let parentPath = (parent as NSString).resolvingSymlinksInPath
        let childPath = ((parent as NSString).appendingPathComponent(trimmed) as NSString).resolvingSymlinksInPath
        let expected = (parentPath as NSString).appendingPathComponent(trimmed)
        return childPath == expected ? childPath : nil
    }

    public static func toEntry(_ path: String, readOnly: Bool = false) -> FsEntry {
        let fm = FileManager.default
        var isDir: ObjCBool = false
        _ = fm.fileExists(atPath: path, isDirectory: &isDir)
        let attrs = try? fm.attributesOfItem(atPath: path)
        let modified = (attrs?[.modificationDate] as? Date).map { Int64($0.timeIntervalSince1970 * 1000) } ?? 0
        return FsEntry(
            name: (path as NSString).lastPathComponent,
            path: path,
            isDirectory: isDir.boolValue,
            sizeBytes: isDir.boolValue ? directorySize(path) : (attrs?[.size] as? NSNumber)?.int64Value ?? 0,
            lastModified: modified,
            isReadOnly: readOnly
        )
    }

    /// Lists `dir`, directories first, then files, each group ordered case-insensitively.
    public static func listEntries(_ dir: String) -> [FsEntry] {
        let children = (try? FileManager.default.contentsOfDirectory(atPath: dir)) ?? []
        return sort(children.map { toEntry((dir as NSString).appendingPathComponent($0)) })
    }

    public static func sort(_ entries: [FsEntry]) -> [FsEntry] {
        entries.sorted { a, b in
            if a.isDirectory != b.isDirectory { return a.isDirectory }
            return a.name.lowercased() < b.name.lowercased()
        }
    }

    public static func directorySize(_ dir: String) -> Int64 {
        let fm = FileManager.default
        guard let enumerator = fm.enumerator(atPath: dir) else { return 0 }
        var total: Int64 = 0
        for case let rel as String in enumerator {
            let full = (dir as NSString).appendingPathComponent(rel)
            var isDir: ObjCBool = false
            _ = fm.fileExists(atPath: full, isDirectory: &isDir)
            if !isDir.boolValue {
                total += ((try? fm.attributesOfItem(atPath: full))?[.size] as? NSNumber)?.int64Value ?? 0
            }
        }
        return total
    }

    public static func humanSize(_ bytes: Int64) -> String {
        if bytes < 1024 { return "\(bytes) B" }
        if bytes < 1024 * 1024 { return String(format: "%.1f KB", Double(bytes) / 1024.0) }
        if bytes < 1024 * 1024 * 1024 { return String(format: "%.1f MB", Double(bytes) / (1024.0 * 1024)) }
        return String(format: "%.2f GB", Double(bytes) / (1024.0 * 1024 * 1024))
    }

    @discardableResult
    public static func createDirectory(_ parent: String, _ name: String) -> String? {
        guard let target = childOf(parent, name) else { return nil }
        if FileManager.default.fileExists(atPath: target) { return nil }
        do { try FileManager.default.createDirectory(atPath: target, withIntermediateDirectories: true); return target }
        catch { return nil }
    }

    /// Creates a text file, appending `.txt` when `name` carries no extension.
    @discardableResult
    public static func createTextFile(_ parent: String, _ name: String, _ content: String) -> String? {
        let withExtension = name.contains(".") ? name : "\(name).txt"
        guard let target = childOf(parent, withExtension) else { return nil }
        if FileManager.default.fileExists(atPath: target) { return nil }
        do {
            try FileManager.default.createDirectory(atPath: (target as NSString).deletingLastPathComponent, withIntermediateDirectories: true)
            try content.write(toFile: target, atomically: true, encoding: .utf8)
            return target
        } catch { return nil }
    }

    @discardableResult
    public static func rename(_ path: String, _ newName: String) -> String? {
        let parent = (path as NSString).deletingLastPathComponent
        guard let target = childOf(parent, newName) else { return nil }
        if target == path { return nil }
        if FileManager.default.fileExists(atPath: target) { return nil }
        do { try FileManager.default.moveItem(atPath: path, toPath: target); return target }
        catch { return nil }
    }

    @discardableResult
    public static func delete(_ path: String) -> Bool {
        do { try FileManager.default.removeItem(atPath: path); return true }
        catch { return false }
    }

    /// Reads up to `maxBytes` of `path` as UTF-8, or nil when the file is missing or looks
    /// binary (a NUL byte in the sampled prefix).
    public static func readTextPreview(_ path: String, maxBytes: Int = 64 * 1024) -> String? {
        let fm = FileManager.default
        var isDir: ObjCBool = false
        guard fm.fileExists(atPath: path, isDirectory: &isDir), !isDir.boolValue else { return nil }
        guard let handle = FileHandle(forReadingAtPath: path) else { return nil }
        defer { try? handle.close() }
        let data = handle.readData(ofLength: maxBytes)
        if data.contains(0) { return nil }
        return String(data: data, encoding: .utf8)
    }
}

/// iOS-facing view over the per-workspace sandbox on disk. Mirrors
/// `engine.fs.WorkspaceFileManager`.
public final class WorkspaceFileManager {
    private let rootDir: String

    public init(rootDir: String) {
        self.rootDir = rootDir
        try? FileManager.default.createDirectory(atPath: rootDir, withIntermediateDirectories: true)
    }

    public var workspacesRoot: String {
        let root = (rootDir as NSString).appendingPathComponent("workspaces")
        try? FileManager.default.createDirectory(atPath: root, withIntermediateDirectories: true)
        return root
    }

    private var databasesDir: String {
        (rootDir as NSString).appendingPathComponent("databases")
    }

    public func databaseFile(_ workspaceId: String) -> String {
        (databasesDir as NSString).appendingPathComponent("workspace_\(workspaceId).db")
    }

    /// Lists workspace folders. Every provisioned `knownWorkspaceIds` entry is reported even
    /// before its sandbox exists, and any extra folder on disk is included so it can still be
    /// inspected and cleaned up.
    public func listWorkspaceFolders(
        knownWorkspaceIds: [String],
        displayNames: [String: String] = [:]
    ) -> [WorkspaceFolder] {
        let root = workspacesRoot
        let onDisk = ((try? FileManager.default.contentsOfDirectory(atPath: root)) ?? [])
            .filter { path in
                var isDir: ObjCBool = false
                _ = FileManager.default.fileExists(atPath: (root as NSString).appendingPathComponent(path), isDirectory: &isDir)
                return isDir.boolValue
            }
        var seen = Set<String>()
        let ids = (knownWorkspaceIds + onDisk).filter { seen.insert($0).inserted }

        return ids.compactMap { id -> WorkspaceFolder? in
            let dir = (root as NSString).appendingPathComponent(id)
            guard FileManager.default.fileExists(atPath: dir) else { return nil }
            let db = databaseFile(id)
            let dbExists = FileManager.default.fileExists(atPath: db)
            let dbSize = dbExists ? (((try? FileManager.default.attributesOfItem(atPath: db))?[.size] as? NSNumber)?.int64Value ?? 0) : 0
            let dbModified = dbExists ? Int64(((try? FileManager.default.attributesOfItem(atPath: db))?[.modificationDate] as? Date ?? Date.distantPast).timeIntervalSince1970 * 1000) : 0
            let dirModified = Int64(((try? FileManager.default.attributesOfItem(atPath: dir))?[.modificationDate] as? Date ?? Date.distantPast).timeIntervalSince1970 * 1000)
            return WorkspaceFolder(
                workspaceId: id,
                displayName: displayNames[id] ?? id,
                dirPath: dir,
                databasePath: dbExists ? db : nil,
                sizeBytes: WorkspaceFileSystem.directorySize(dir) + dbSize,
                fileCount: countFiles(dir),
                lastModified: max(dirModified, dbModified)
            )
        }
        .sorted { $0.workspaceId.lowercased() < $1.workspaceId.lowercased() }
    }

    /// Directory entries for `folder`, with the tenant database appended as a read-only entry.
    public func entriesForFolder(_ folder: WorkspaceFolder) -> [FsEntry] {
        var entries = WorkspaceFileSystem.listEntries(folder.dirPath)
        if let dbPath = folder.databasePath, FileManager.default.fileExists(atPath: dbPath) {
            entries.append(WorkspaceFileSystem.toEntry(dbPath, readOnly: true))
        }
        return WorkspaceFileSystem.sort(entries)
    }

    /// Lists `dir` for the browser. At the workspace root the read-only tenant database is
    /// appended; deeper directories are plain listings.
    public func listForBrowse(_ folder: WorkspaceFolder, dir: String) -> [FsEntry] {
        (dir as NSString).standardizingPath == (folder.dirPath as NSString).standardizingPath
            ? entriesForFolder(folder)
            : WorkspaceFileSystem.listEntries(dir)
    }

    private func countFiles(_ dir: String) -> Int {
        let fm = FileManager.default
        guard let enumerator = fm.enumerator(atPath: dir) else { return 0 }
        var count = 0
        for case let rel as String in enumerator {
            var isDir: ObjCBool = false
            _ = fm.fileExists(atPath: (dir as NSString).appendingPathComponent(rel), isDirectory: &isDir)
            if !isDir.boolValue { count += 1 }
        }
        return count
    }
}
