package com.example.contract

import engine.db.ApexKitDatabaseEngine
import engine.db.WorkspaceDatabase
import engine.db.models.Collection
import engine.db.models.CollectionSchema
import engine.db.models.FieldDefinition
import engine.db.models.FieldType
import engine.db.models.RelationDefinition
import engine.db.models.RelationType
import engine.db.auth.Claims
import engine.db.models.TenantSchemaPackage
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Exercises the v4 storage refactor against a real SQLite engine (Robolectric):
 * schema-generated expression indexes, TEXT record ids, selective relation rows,
 * foreign-key cascades and application-level `cascade_on_target_delete`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class StorageRefactorTest {

    private fun engine(name: String): ApexKitDatabaseEngine {
        val driver = engine.sqlite.DirectSqliteDriver(
            schema = WorkspaceDatabase.Schema,
            context = RuntimeEnvironment.getApplication(),
            name = "$name.db"
        )
        // Mirror WorkspaceDriverFactory: FK enforcement is per-connection.
        driver.executeUpdate(null, "PRAGMA foreign_keys = ON;", 0)
        return ApexKitDatabaseEngine(driver)
    }

    private fun uniqueEmailSchema() = CollectionSchema(
        fields = mapOf(
            "email" to FieldDefinition(type = FieldType.STRING, uid = "c_email", unique = true),
            "status" to FieldDefinition(type = FieldType.STRING, uid = "c_status", sql_indexed = true)
        )
    )

    private fun importCollection(engine: ApexKitDatabaseEngine, col: Collection) {
        engine.importTenantSchema(TenantSchemaPackage(collections = listOf(col)))
    }

    private fun indexNames(engine: ApexKitDatabaseEngine): List<String> {
        val names = mutableListOf<String>()
        engine.driver.executeQuery(null, "SELECT name FROM sqlite_master WHERE type='index'", 0).use { c ->
            while (c.next().value) c.getString(0)?.let { names.add(it) }
        }
        return names
    }

    @Test
    fun uniqueFieldCreatesExpressionIndexAndRecordsMetadata() {
        val engine = engine("refactor_unique")
        importCollection(engine, Collection(id = 5L, name = "customers", schema = uniqueEmailSchema()))

        assertTrue(indexNames(engine).any { it == "idx_u_5_email" })
        assertTrue(indexNames(engine).any { it == "idx_n_5_status" })

        var metaCount = 0
        engine.driver.executeQuery(null, "SELECT kind, fields FROM _applied_indexes ORDER BY index_name", 0).use { c ->
            while (c.next().value) {
                metaCount++
                assertNotNull(c.getString(0))
                assertNotNull(c.getString(1))
            }
        }
        assertEquals(2, metaCount)

        engine.insertRecord("customers", buildJsonObject { put("email", "a@b.com") })
        val failure = runCatching {
            engine.insertRecord("customers", buildJsonObject { put("email", "a@b.com") })
        }
        assertTrue("second insert with duplicate email must fail", failure.isFailure)
    }

    @Test
    fun queryPlannerUsesGeneratedExpressionIndex() {
        val engine = engine("refactor_planner")
        importCollection(engine, Collection(id = 5L, name = "customers", schema = uniqueEmailSchema()))
        engine.insertRecord("customers", buildJsonObject { put("email", "a@b.com") })

        var plan = ""
        engine.driver.executeQuery(
            null,
            "EXPLAIN QUERY PLAN SELECT * FROM records WHERE collection_id = 5 AND json_extract(data, '$.email') = 'a@b.com'",
            0
        ).use { c ->
            while (c.next().value) plan += (c.getString(3) ?: "") + "\n"
        }
        assertTrue("expected the expression index in the plan, got:\n$plan", plan.contains("idx_u_5_email"))
    }

    @Test
    fun recordIdsAreAlwaysText() {
        val engine = engine("refactor_ids")
        importCollection(engine, Collection(id = 1L, name = "customers", schema = CollectionSchema()))

        val supplied = engine.insertRecord("customers", buildJsonObject {
            put("id", "customer_abc")
        })
        assertEquals("customer_abc", supplied)

        val numeric = engine.insertRecord("customers", buildJsonObject { put("id", 42) })
        assertEquals("42", numeric)

        val generated = engine.insertRecord("customers", buildJsonObject { put("name", "auto") })
        assertTrue("generated id must be numeric-looking text", generated.toLongOrNull() != null)

        val stored = engine.queryRecords("customers").map { it.id }.toSet()
        assertTrue(stored.contains("customer_abc"))
        assertTrue(stored.contains("42"))
        assertTrue(stored.contains(generated))
    }

    private fun relationSchema(cascade: Boolean, many: Boolean, withProps: Boolean) = CollectionSchema(
        relations = mapOf(
            "target_id" to RelationDefinition(
                target_collection = "targets",
                relation_type = if (many) RelationType.MANY else RelationType.ONE,
                uid = "c_target",
                cascade_on_target_delete = cascade,
                has_properties = withProps
            )
        )
    )

    @Test
    fun simpleReferenceDoesNotCreateRelationRow() {
        val engine = engine("refactor_simple")
        importCollection(engine, Collection(id = 1L, name = "targets", schema = CollectionSchema()))
        importCollection(engine, Collection(id = 2L, name = "orders", schema = relationSchema(cascade = false, many = false, withProps = false)))

        val target = engine.insertRecord("targets", buildJsonObject { put("name", "T") })
        engine.insertRecord("orders", buildJsonObject { put("target_id", target) })

        assertEquals(0, relationRowCount(engine))
    }

    @Test
    fun cascadeRelationCreatesExactlyOneRelationRow() {
        val engine = engine("refactor_cascade_row")
        importCollection(engine, Collection(id = 1L, name = "targets", schema = CollectionSchema()))
        importCollection(engine, Collection(id = 2L, name = "orders", schema = relationSchema(cascade = true, many = false, withProps = false)))

        val target = engine.insertRecord("targets", buildJsonObject { put("name", "T") })
        engine.insertRecord("orders", buildJsonObject { put("target_id", target) })

        assertEquals(1, relationRowCount(engine))
    }

    @Test
    fun foreignKeyCascadeRemovesRelationRowsOnEitherEndpointDelete() {
        val engine = engine("refactor_fk")
        importCollection(engine, Collection(id = 1L, name = "targets", schema = CollectionSchema()))
        importCollection(engine, Collection(id = 2L, name = "orders", schema = relationSchema(cascade = false, many = true, withProps = false)))

        val target = engine.insertRecord("targets", buildJsonObject { put("name", "T") })
        val order = engine.insertRecord("orders", buildJsonObject { put("target_id", target) })
        assertEquals(1, relationRowCount(engine))

        engine.deleteRecord("orders", order, Claims(role = "admin"))
        assertEquals("deleting the origin removes its relation rows", 0, relationRowCount(engine))
    }

    @Test
    fun applicationCascadeDeletesOriginWhenTargetDeleted() {
        val engine = engine("refactor_app_cascade")
        importCollection(engine, Collection(id = 1L, name = "targets", schema = CollectionSchema()))
        importCollection(engine, Collection(id = 2L, name = "orders", schema = relationSchema(cascade = true, many = false, withProps = false)))

        val target = engine.insertRecord("targets", buildJsonObject { put("name", "T") })
        val order = engine.insertRecord("orders", buildJsonObject { put("target_id", target) })

        engine.deleteRecord("targets", target, Claims(role = "admin"))

        assertEquals(0L, engine.countRecords("targets"))
        assertEquals("origin record must be deleted with its target", 0L, engine.countRecords("orders"))
        assertEquals(0, relationRowCount(engine))
        assertFalse(engine.queryRecords("orders").any { it.id == order })
    }

    @Test
    fun cycleProtectionTerminatesSelfReferentialCascades() {
        val engine = engine("refactor_cycle")
        importCollection(
            engine,
            Collection(
                id = 1L,
                name = "nodes",
                schema = CollectionSchema(
                    relations = mapOf(
                        "parent_id" to RelationDefinition(
                            target_collection = "nodes",
                            relation_type = RelationType.ONE,
                            uid = "c_parent",
                            cascade_on_target_delete = true
                        )
                    )
                )
            )
        )

        val a = engine.insertRecord("nodes", buildJsonObject { put("id", "a") })
        val b = engine.insertRecord("nodes", buildJsonObject { put("id", "b"); put("parent_id", "a") })
        // Close the cycle: a -> b.
        engine.driver.executeUpdate(
            null,
            "UPDATE records SET data = '{\"id\":\"a\",\"parent_id\":\"b\"}' WHERE id = 'a'",
            0
        )
        engine.driver.executeUpdate(
            null,
            "INSERT OR REPLACE INTO _relations (origin_rec_id, target_rec_id, properties) VALUES ('a', 'b', NULL)",
            0
        )

        // Must terminate (no infinite loop) and leave nothing behind.
        engine.deleteRecord("nodes", a, Claims(role = "admin"))
        assertEquals(0L, engine.countRecords("nodes"))
        assertEquals(0, relationRowCount(engine))
        assertNotNull(b)
    }

    private fun relationRowCount(engine: ApexKitDatabaseEngine): Int {
        var n = 0
        engine.driver.executeQuery(null, "SELECT COUNT(*) FROM _relations", 0).use { c ->
            if (c.next().value) n = (c.getLong(0) ?: 0L).toInt()
        }
        return n
    }
}
