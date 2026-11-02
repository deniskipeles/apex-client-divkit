package app.cash.sqldelight.db

import java.io.Closeable

sealed interface QueryResult<T> {
    val value: T

    @JvmInline
    value class Value<T>(override val value: T) : QueryResult<T>
}

interface SqlCursor : Closeable {
    fun next(): QueryResult<Boolean>
    val columnCount: Int
    fun getColumnName(index: Int): String
    fun getString(index: Int): String?
    fun getLong(index: Int): Long?
    fun getDouble(index: Int): Double?
}

interface SqlPreparedStatement {
    fun bindBytes(index: Int, bytes: ByteArray?)
    fun bindLong(index: Int, long: Long?)
    fun bindDouble(index: Int, double: Double?)
    fun bindString(index: Int, string: String?)
    fun bindBoolean(index: Int, boolean: Boolean?)
    fun bindNull(index: Int)
}

interface SqlDriver : Closeable {
    fun executeQuery(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)? = null
    ): SqlCursor

    fun executeUpdate(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)? = null
    ): Long

    fun lastInsertedRowId(): Long
}

interface SqlSchema {
    val version: Long
    fun create(driver: SqlDriver)
    fun migrate(driver: SqlDriver, oldVersion: Long, newVersion: Long)
}
