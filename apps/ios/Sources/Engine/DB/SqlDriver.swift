import Foundation
import CSQLite

/// Platform SQLite driver contract, mirroring `engine.sqlite.SqlDriver`.
///
/// The Android engine talks to SQLite through this narrow interface so the schema, query
/// compiler and repositories stay platform-free. iOS binds it to the system `libsqlite3`.
public protocol SqlPreparedStatement: AnyObject {
    func bindBytes(index: Int, bytes: [UInt8]?)
    func bindLong(index: Int, long: Int64?)
    func bindDouble(index: Int, double: Double?)
    func bindString(index: Int, string: String?)
    func bindBoolean(index: Int, boolean: Bool?)
    func bindNull(index: Int)
}

public protocol SqlCursor: AnyObject {
    /// Advances the cursor; returns true while a row is available.
    func next() -> Bool
    var columnCount: Int { get }
    func getColumnName(index: Int) -> String
    func getString(index: Int) -> String?
    func getLong(index: Int) -> Int64?
    func getDouble(index: Int) -> Double?
}

public protocol SqlDriver: AnyObject {
    @discardableResult
    func executeUpdate(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: ((SqlPreparedStatement) -> Void)?
    ) -> Int64

    func executeQuery(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: ((SqlPreparedStatement) -> Void)?
    ) -> SqlCursor

    func lastInsertedRowId() -> Int64

    func close()
}

public protocol SqlSchema {
    var version: Int64 { get }
    func create(_ driver: SqlDriver)
    func migrate(_ driver: SqlDriver, oldVersion: Int64, newVersion: Int64)
}

public extension SqlDriver {
    @discardableResult
    func executeUpdate(identifier: Int?, _ sql: String, _ parameters: Int, _ binders: ((SqlPreparedStatement) -> Void)? = nil) -> Int64 {
        executeUpdate(identifier: identifier, sql: sql, parameters: parameters, binders: binders)
    }

    func executeQuery(identifier: Int?, _ sql: String, _ parameters: Int, _ binders: ((SqlPreparedStatement) -> Void)? = nil) -> SqlCursor {
        executeQuery(identifier: identifier, sql: sql, parameters: parameters, binders: binders)
    }
}

/// `SqlDriver` backed by the system SQLite library. Mirrors the Android
/// `DirectSqliteDriver`: an in-process connection that runs the workspace DDL and the
/// engine's parameterised statements.
public final class DirectSqliteDriver: SqlDriver {
    private var db: OpaquePointer?
    private let lock = NSRecursiveLock()

    public init(schema: SqlSchema, path: String = ":memory:") {
        sqlite3_open(path, &db)
        sqlite3_busy_timeout(db, 5_000)
        schema.create(self)
    }

    deinit { close() }

    public func close() {
        lock.lock(); defer { lock.unlock() }
        if let db { sqlite3_close_v2(db) }
        db = nil
    }

    private func prepare(_ sql: String) -> OpaquePointer? {
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK else {
            return nil
        }
        return stmt
    }

    @discardableResult
    public func executeUpdate(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: ((SqlPreparedStatement) -> Void)?
    ) -> Int64 {
        lock.lock(); defer { lock.unlock() }
        guard let stmt = prepare(sql) else { return 0 }
        defer { sqlite3_finalize(stmt) }
        let binder = SqlitePreparedStatement(stmt: stmt)
        binders?(binder)
        sqlite3_step(stmt)
        return Int64(sqlite3_changes(db))
    }

    public func executeQuery(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: ((SqlPreparedStatement) -> Void)?
    ) -> SqlCursor {
        lock.lock()
        guard let stmt = prepare(sql) else {
            lock.unlock()
            return SqliteCursor(stmt: nil, lock: lock, db: nil)
        }
        let binder = SqlitePreparedStatement(stmt: stmt)
        binders?(binder)
        return SqliteCursor(stmt: stmt, lock: lock, db: db)
    }

    public func lastInsertedRowId() -> Int64 {
        lock.lock(); defer { lock.unlock() }
        return Int64(sqlite3_last_insert_rowid(db))
    }
}

private final class SqlitePreparedStatement: SqlPreparedStatement {
    private let stmt: OpaquePointer
    init(stmt: OpaquePointer) { self.stmt = stmt }

    func bindBytes(index: Int, bytes: [UInt8]?) {
        guard let bytes else { sqlite3_bind_null(stmt, Int32(index + 1)); return }
        bytes.withUnsafeBufferPointer { buf in
            _ = sqlite3_bind_blob(stmt, Int32(index + 1), buf.baseAddress, Int32(bytes.count), Self.transient)
        }
    }

    func bindLong(index: Int, long: Int64?) {
        if let long { sqlite3_bind_int64(stmt, Int32(index + 1), long) }
        else { sqlite3_bind_null(stmt, Int32(index + 1)) }
    }

    func bindDouble(index: Int, double: Double?) {
        if let double { sqlite3_bind_double(stmt, Int32(index + 1), double) }
        else { sqlite3_bind_null(stmt, Int32(index + 1)) }
    }

    func bindString(index: Int, string: String?) {
        if let string { sqlite3_bind_text(stmt, Int32(index + 1), string, -1, Self.transient) }
        else { sqlite3_bind_null(stmt, Int32(index + 1)) }
    }

    func bindBoolean(index: Int, boolean: Bool?) {
        if let boolean { sqlite3_bind_int64(stmt, Int32(index + 1), boolean ? 1 : 0) }
        else { sqlite3_bind_null(stmt, Int32(index + 1)) }
    }

    func bindNull(index: Int) { sqlite3_bind_null(stmt, Int32(index + 1)) }

    /// SQLITE_TRANSIENT — tells SQLite to copy the bound bytes/text.
    static let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)
}

private final class SqliteCursor: SqlCursor {
    private var stmt: OpaquePointer?
    private let lock: NSRecursiveLock
    private let db: OpaquePointer?
    private var started = false

    init(stmt: OpaquePointer?, lock: NSRecursiveLock, db: OpaquePointer?) {
        self.stmt = stmt
        self.lock = lock
        self.db = db
    }

    deinit { finish() }

    private func finish() {
        guard let stmt else { return }
        sqlite3_finalize(stmt)
        self.stmt = nil
        lock.unlock()
    }

    func next() -> Bool {
        guard let stmt else { return false }
        if !started { started = true }
        return sqlite3_step(stmt) == SQLITE_ROW
    }

    var columnCount: Int {
        guard let stmt else { return 0 }
        return Int(sqlite3_column_count(stmt))
    }

    func getColumnName(index: Int) -> String {
        guard let stmt, let cName = sqlite3_column_name(stmt, Int32(index)) else { return "" }
        return String(cString: cName)
    }

    func getString(index: Int) -> String? {
        guard let stmt, let cText = sqlite3_column_text(stmt, Int32(index)) else { return nil }
        return String(cString: cText)
    }

    func getLong(index: Int) -> Int64? {
        guard let stmt else { return nil }
        if sqlite3_column_type(stmt, Int32(index)) == SQLITE_NULL { return nil }
        return sqlite3_column_int64(stmt, Int32(index))
    }

    func getDouble(index: Int) -> Double? {
        guard let stmt else { return nil }
        if sqlite3_column_type(stmt, Int32(index)) == SQLITE_NULL { return nil }
        return sqlite3_column_double(stmt, Int32(index))
    }
}
