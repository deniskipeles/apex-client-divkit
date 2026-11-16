import Foundation

/// Opens and caches per-workspace databases. Mirrors `engine.db.WorkspaceManager`.
public final class WorkspaceManager {
    private let idRegex = try! NSRegularExpression(pattern: "^[a-zA-Z0-9_-]{1,64}$")
    private var openDrivers: [String: SqlDriver] = [:]
    private let lock = NSRecursiveLock()
    private let rootDir: String
    private let useMemory: Bool

    /// Creates a manager that stores each workspace in its own SQLite file under `rootDir`.
    /// Pass `useMemory: true` for tests so no files are written.
    public init(rootDir: String, useMemory: Bool = false) {
        self.rootDir = rootDir
        self.useMemory = useMemory
        try? FileManager.default.createDirectory(atPath: rootDir, withIntermediateDirectories: true)
    }

    public func validateWorkspaceId(_ id: String) {
        let range = NSRange(id.startIndex..<id.endIndex, in: id)
        precondition(idRegex.firstMatch(in: id, range: range) != nil, "Invalid workspaceId: \(id)")
    }

    private func databasePath(_ workspaceId: String) -> String {
        useMemory ? ":memory:" : "\(rootDir)/workspace_\(workspaceId).db"
    }

    public func getDriver(_ workspaceId: String) -> SqlDriver {
        validateWorkspaceId(workspaceId)
        lock.lock(); defer { lock.unlock() }
        if let existing = openDrivers[workspaceId] { return existing }
        let driver = DirectSqliteDriver(schema: WorkspaceDatabase.Schema(), path: databasePath(workspaceId))
        openDrivers[workspaceId] = driver
        return driver
    }

    public func useDatabase<T>(_ workspaceId: String, _ block: (WorkspaceDatabase) throws -> T) rethrows -> T {
        try block(WorkspaceDatabase(driver: getDriver(workspaceId)))
    }

    public func useEngine<T>(_ workspaceId: String, _ block: (ApexKitDatabaseEngine) throws -> T) rethrows -> T {
        try block(ApexKitDatabaseEngine(driver: getDriver(workspaceId)))
    }

    public func deleteWorkspace(_ workspaceId: String) {
        validateWorkspaceId(workspaceId)
        lock.lock(); defer { lock.unlock() }
        openDrivers.removeValue(forKey: workspaceId)?.close()
        if !useMemory {
            try? FileManager.default.removeItem(atPath: databasePath(workspaceId))
        }
    }

    public func activeDatabaseFileNames() -> [String] {
        lock.lock(); defer { lock.unlock() }
        return openDrivers.keys.map { "workspace_\($0).db" }
    }
}
