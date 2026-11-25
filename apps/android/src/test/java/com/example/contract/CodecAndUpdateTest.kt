package com.example.contract

import com.example.enginehost.demo.DemoCollectionRepository
import engine.db.ApexKitDatabaseEngine
import engine.db.WorkspaceDatabase
import engine.db.auth.Claims
import engine.db.models.Collection
import engine.db.models.CollectionPolicies
import engine.db.models.CollectionSchema
import engine.db.models.FieldDefinition
import engine.db.models.FieldType
import engine.db.models.TenantSchemaPackage
import engine.tools.ToolArgs
import engine.tools.ToolContext
import engine.tools.ToolResult
import engine.tools.codec.Base45
import engine.tools.impl.CodecTool
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CodecAndUpdateTest {

    private fun engine(name: String): ApexKitDatabaseEngine {
        val driver = engine.sqlite.DirectSqliteDriver(
            schema = WorkspaceDatabase.Schema,
            context = RuntimeEnvironment.getApplication(),
            name = "$name.db"
        )
        driver.executeUpdate(null, "PRAGMA foreign_keys = ON;", 0)
        return ApexKitDatabaseEngine(driver)
    }

    private fun demoCollection(): Collection = Collection(
        id = 9L,
        name = "demo_items",
        schema = CollectionSchema(
            fields = mapOf(
                "name" to FieldDefinition(type = FieldType.STRING, uid = "demo_name"),
                "qty" to FieldDefinition(type = FieldType.NUMBER, uid = "demo_qty"),
                "status" to FieldDefinition(type = FieldType.STRING, uid = "demo_status")
            ),
            policies = CollectionPolicies(update = "public", delete = "public")
        )
    )

    @Test
    fun updateRecordMergesAndReturnsId() {
        val db = engine("codec_update_merge")
        db.importTenantSchema(TenantSchemaPackage(collections = listOf(demoCollection())))
        val id = db.insertRecord("demo_items", buildJsonObject {
            put("name", "Coffee")
            put("qty", 2)
            put("status", "OPEN")
        })

        val returned = db.updateRecord(
            collectionName = "demo_items",
            recordId = id,
            patch = buildJsonObject { put("status", "PAID") },
            user = Claims(uid = 0L, role = "public")
        )

        assertEquals(id, returned)
        val record = db.queryRecords("demo_items", user = Claims(uid = 0L, role = "public"))
            .firstOrNull { it.id == id }
        assertNotNull(record)
        assertEquals("Coffee", record!!.data["name"]?.jsonPrimitive?.content)
        assertEquals("2", record.data["qty"]?.jsonPrimitive?.content)
        assertEquals("PAID", record.data["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun updateRecordRejectsUnknownId() {
        val db = engine("codec_update_missing")
        db.importTenantSchema(TenantSchemaPackage(collections = listOf(demoCollection())))
        val error = runCatching {
            db.updateRecord(
                collectionName = "demo_items",
                recordId = "missing",
                patch = buildJsonObject { put("status", "PAID") },
                user = Claims(uid = 0L, role = "public")
            )
        }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error!!.message!!.contains("not found"))
    }

    @Test
    fun base45RoundTripsUtf8() {
        val text = "APEX order 42 — coffee ☕"
        val encoded = Base45.encode(text.toByteArray(Charsets.UTF_8))
        assertEquals(text, String(Base45.decode(encoded), Charsets.UTF_8))
    }

    @Test
    fun codecBase64AndBase45RoundTrip() = runBlocking {
        val tool = CodecTool()
        val encoded64 = tool.invoke(
            ToolArgs(mapOf("codec" to "base64", "action" to "encode", "text" to "hello")),
            ToolContext()
        ) as ToolResult.Success
        assertEquals("aGVsbG8=", encoded64.data["text"])

        val decoded64 = tool.invoke(
            ToolArgs(mapOf("codec" to "base64", "action" to "decode", "text" to "aGVsbG8=")),
            ToolContext()
        ) as ToolResult.Success
        assertEquals("hello", decoded64.data["text"])

        val encoded45 = tool.invoke(
            ToolArgs(mapOf("codec" to "base45", "action" to "encode", "text" to "hello")),
            ToolContext()
        ) as ToolResult.Success
        val decoded45 = tool.invoke(
            ToolArgs(mapOf("codec" to "base45", "action" to "decode", "text" to encoded45.data["text"] as String)),
            ToolContext()
        ) as ToolResult.Success
        assertEquals("hello", decoded45.data["text"])
    }

    @Test
    fun codecJsonExtractSupportsNestedAndArrayPaths() = runBlocking {
        val tool = CodecTool()
        val result = tool.invoke(
            ToolArgs(
                mapOf(
                    "codec" to "json/extract",
                    "json" to """{"order_id":"42","lines":[{"sku":"P1001"}],"total":7.5}""",
                    "paths" to """["order_id","lines.0.sku","total"]""",
                    "subtree_path" to "lines"
                )
            ),
            ToolContext()
        ) as ToolResult.Success
        assertEquals(listOf("42", "P1001", "7.5"), result.data["values"])
        assertTrue((result.data["subtree_json"] as String).contains("P1001"))
    }

    @Test
    fun codecJsonChunkHonoursByteAndChunkLimits() = runBlocking {
        val tool = CodecTool()
        val payload = buildJsonObject {
            put("order_id", "42")
            put("note", "x".repeat(2400))
        }.toString()
        val chunked = tool.invoke(
            ToolArgs(
                mapOf(
                    "codec" to "json/chunk",
                    "json" to payload,
                    "encoding" to "base45",
                    "max_bytes" to "500",
                    "max_chunks" to "32"
                )
            ),
            ToolContext()
        ) as ToolResult.Success
        val chunks = chunked.data["chunks"] as List<*>
        assertTrue(chunks.isNotEmpty())
        assertTrue(chunks.all { (it as String).toByteArray(Charsets.UTF_8).size <= 500 })

        val assembled = tool.invoke(
            ToolArgs(
                mapOf(
                    "codec" to "json/assemble",
                    "encoding" to "base45",
                    "chunks" to chunks.joinToString(",", "[", "]") {
                        kotlinx.serialization.json.JsonPrimitive(it as String).toString()
                    }
                )
            ),
            ToolContext()
        ) as ToolResult.Success
        assertEquals("COMPLETE", assembled.data["status"])
        assertEquals(payload, assembled.data["json"])
    }

    @Test
    fun codecJsonChunkRejectsMoreThan32Chunks() = runBlocking {
        val tool = CodecTool()
        val payload = buildJsonObject { put("note", "x".repeat(20_000)) }.toString()
        val result = tool.invoke(
            ToolArgs(
                mapOf(
                    "codec" to "json/chunk",
                    "json" to payload,
                    "encoding" to "base45",
                    "max_bytes" to "500",
                    "max_chunks" to "32"
                )
            ),
            ToolContext()
        )
        assertTrue(result is ToolResult.Failed)
        assertTrue((result as ToolResult.Failed).message.contains("maximum is 32"))
    }

    @Test
    fun demoCollectionsLoadFromResources() {
        val loaded = DemoCollectionRepository.load()
        assertNotNull(loaded)
        assertTrue(
            loaded!!.names.containsAll(listOf("inventory", "orders", "cust_history"))
        )
        val collections = loaded.schemaPayload["schema"]!!.jsonObject["collections"]!!
            .let { it as kotlinx.serialization.json.JsonArray }
        assertEquals(3, collections.size)
    }
}
