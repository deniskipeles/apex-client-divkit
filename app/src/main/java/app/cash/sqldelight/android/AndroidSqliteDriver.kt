package app.cash.sqldelight.android

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.db.SqlSchema
import java.util.concurrent.atomic.AtomicLong

class AndroidSqliteDriver(
    private val schema: SqlSchema,
    context: Context,
    name: String
) : SqlDriver {

    private val lastRowId = AtomicLong(0L)

    private val helper = object : SQLiteOpenHelper(context, name, null, schema.version.toInt().coerceAtLeast(1)) {
        override fun onCreate(db: SQLiteDatabase) {
            schema.create( DirectDriver(db, lastRowId) )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            schema.migrate(DirectDriver(db, lastRowId), oldVersion.toLong(), newVersion.toLong())
        }
    }

    val writableDb: SQLiteDatabase
        get() = helper.writableDatabase

    override fun executeQuery(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?
    ): SqlCursor {
        return DirectDriver(writableDb, lastRowId).executeQuery(identifier, sql, parameters, binders)
    }

    override fun executeUpdate(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?
    ): Long {
        return DirectDriver(writableDb, lastRowId).executeUpdate(identifier, sql, parameters, binders)
    }

    override fun lastInsertedRowId(): Long = lastRowId.get()

    override fun close() {
        helper.close()
    }
}

private class DirectDriver(
    private val db: SQLiteDatabase,
    private val lastRowId: AtomicLong
) : SqlDriver {

    override fun executeQuery(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?
    ): SqlCursor {
        val stmt = RecordingStatement(parameters)
        binders?.invoke(stmt)
        val trimmed = sql.trim()
        val upper = trimmed.uppercase()
        if (upper.startsWith("INSERT") || upper.startsWith("UPDATE") || upper.startsWith("DELETE") || upper.startsWith("CREATE") || upper.startsWith("DROP") || upper.startsWith("ALTER")) {
            executeUpdate(identifier, sql, parameters, binders)
            return EmptySqlCursor
        }
        val stringArgs = stmt.args.map { it?.toString() }.toTypedArray()
        val cursor = db.rawQuery(sql, if (parameters == 0) null else stringArgs)
        return AndroidCursor(cursor)
    }

    override fun executeUpdate(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?
    ): Long {
        val stmt = RecordingStatement(parameters)
        binders?.invoke(stmt)
        val trimmed = sql.trim()
        val upper = trimmed.uppercase()
        val compiled = db.compileStatement(sql)
        return try {
            stmt.args.forEachIndexed { idx, value ->
                val bindIdx = idx + 1 // SQLiteStatement is 1-indexed
                when (value) {
                    null -> compiled.bindNull(bindIdx)
                    is Long -> compiled.bindLong(bindIdx, value)
                    is Int -> compiled.bindLong(bindIdx, value.toLong())
                    is Double -> compiled.bindDouble(bindIdx, value)
                    is Float -> compiled.bindDouble(bindIdx, value.toDouble())
                    is Boolean -> compiled.bindLong(bindIdx, if (value) 1L else 0L)
                    is ByteArray -> compiled.bindBlob(bindIdx, value)
                    else -> compiled.bindString(bindIdx, value.toString())
                }
            }
            when {
                upper.startsWith("INSERT") -> {
                    val id = compiled.executeInsert()
                    if (id >= 0) lastRowId.set(id)
                    if (id >= 0) 1L else 0L
                }
                upper.startsWith("UPDATE") || upper.startsWith("DELETE") -> {
                    compiled.executeUpdateDelete().toLong()
                }
                else -> {
                    compiled.execute()
                    0L
                }
            }
        } finally {
            compiled.close()
        }
    }

    override fun lastInsertedRowId(): Long = lastRowId.get()

    override fun close() {
        db.close()
    }
}

private class RecordingStatement(size: Int) : SqlPreparedStatement {
    val args: Array<Any?> = arrayOfNulls(size)

    override fun bindBytes(index: Int, bytes: ByteArray?) {
        if (index in args.indices) args[index] = bytes
    }

    override fun bindLong(index: Int, long: Long?) {
        if (index in args.indices) args[index] = long
    }

    override fun bindDouble(index: Int, double: Double?) {
        if (index in args.indices) args[index] = double
    }

    override fun bindString(index: Int, string: String?) {
        if (index in args.indices) args[index] = string
    }

    override fun bindBoolean(index: Int, boolean: Boolean?) {
        if (index in args.indices) args[index] = if (boolean == true) 1L else if (boolean == false) 0L else null
    }

    override fun bindNull(index: Int) {
        if (index in args.indices) args[index] = null
    }
}

private object EmptySqlCursor : SqlCursor {
    override fun next(): QueryResult<Boolean> = QueryResult.Value(false)
    override val columnCount: Int = 0
    override fun getColumnName(index: Int): String = ""
    override fun getString(index: Int): String? = null
    override fun getLong(index: Int): Long? = null
    override fun getDouble(index: Int): Double? = null
    override fun close() = Unit
}

private class AndroidCursor(private val cursor: Cursor) : SqlCursor {
    override fun next(): QueryResult<Boolean> = QueryResult.Value(cursor.moveToNext())
    override val columnCount: Int get() = cursor.columnCount
    override fun getColumnName(index: Int): String = cursor.getColumnName(index)

    override fun getString(index: Int): String? {
        if (cursor.isNull(index)) return null
        return cursor.getString(index)
    }

    override fun getLong(index: Int): Long? {
        if (cursor.isNull(index)) return null
        val type = cursor.getType(index)
        if (type == Cursor.FIELD_TYPE_INTEGER) return cursor.getLong(index)
        throw IllegalStateException("Column $index is not INTEGER")
    }

    override fun getDouble(index: Int): Double? {
        if (cursor.isNull(index)) return null
        val type = cursor.getType(index)
        if (type == Cursor.FIELD_TYPE_FLOAT) return cursor.getDouble(index)
        throw IllegalStateException("Column $index is not FLOAT")
    }

    override fun close() {
        cursor.close()
    }
}
