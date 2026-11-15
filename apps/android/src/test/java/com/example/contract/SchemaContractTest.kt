package com.example.contract

import engine.sqlite.SqlDriver
import engine.sqlite.SqlPreparedStatement
import engine.sqlite.SqlSchema
import engine.sqlite.QueryResult
import engine.sqlite.SqlCursor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies that the workspace schema builds on a real SQLite-backed SqlDriver and that the
 * statements the engine relies on round-trip. Uses the in-repo DirectSqliteDriver via
 * Robolectric so a genuine SQLite engine executes the DDL (not a mock).
 */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [36])
class SchemaContractTest {

    private fun driver(): SqlDriver =
        engine.sqlite.DirectSqliteDriver(
            schema = engine.db.WorkspaceDatabase.Schema,
            context = org.robolectric.RuntimeEnvironment.getApplication(),
            name = "contract_test.db"
        )

    @Test
    fun schemaCreatesAndRoundTripsPendingSync() {
        driver().use { db ->
            db.executeUpdate(null, "INSERT INTO pending_sync (client_uuid, op, payload, created_at) VALUES (?, ?, ?, ?)", 4) {
                bindString(0, "uuid-1")
                bindString(1, "db.insert")
                bindString(2, """{"a":1}""")
                bindLong(3, 123L)
            }
            var seen = 0
            db.executeQuery(null, "SELECT client_uuid, op, attempts FROM pending_sync", 0).use { c ->
                while (c.next().value) {
                    assertEquals("uuid-1", c.getString(0))
                    assertEquals("db.insert", c.getString(1))
                    assertEquals(0L, c.getLong(2))
                    seen++
                }
            }
            assertEquals(1, seen)
        }
    }

    @Test
    fun schemaDeclaresVersion3() {
        assertEquals(3L, engine.db.WorkspaceDatabase.Schema.version)
    }

    @Test
    fun schemaIsIdempotent() {
        val schema = engine.db.WorkspaceDatabase.Schema
        val db = driver()
        db.use {
            schema.create(it) // second create must not throw (IF NOT EXISTS)
            assertTrue(it.lastInsertedRowId() >= 0L)
        }
    }
}
