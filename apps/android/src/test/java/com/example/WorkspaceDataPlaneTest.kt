package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.enginehost.WorkspaceProvisioner
import engine.db.ApexKitDatabaseEngine
import engine.db.EngineRegistry
import engine.db.OpResult
import engine.db.SchemaOps
import engine.db.WorkspaceDatabase
import engine.db.WorkspaceDriverFactory
import engine.db.WorkspaceManager
import engine.state.DivVariableValue
import engine.tools.NativeToolsSuite
import engine.tools.ToolArgs
import engine.tools.ToolContext
import engine.tools.ToolResult
import engine.tools.impl.LibSignalTool
import engine.tools.impl.NetTool
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The workspace data plane is schema-driven: these tests exercise the generic operations
 * (child lists, roll-ups, transfer packing, lookups, delete guards) that replaced the
 * former per-domain kiosk code, plus the libsignal and LAN contracts the cards depend on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WorkspaceDataPlaneTest {

    private val spec = WorkspaceProvisioner.defaultWorkspaces.first { it.tenantId == "apex_kiosk" }
    private val registry = EngineRegistry()

    private fun withWorkspace(block: (WorkspaceDatabase, ApexKitDatabaseEngine) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = WorkspaceManager(WorkspaceDriverFactory(context))
        runBlocking {
            manager.useDatabase(spec.tenantId) { db ->
                WorkspaceProvisioner.ensureTenantSchemaAndSeed(db, spec, EngineRegistry())
                block(db, ApexKitDatabaseEngine(db.driver))
            }
        }
    }

    // ---- generic helpers ---------------------------------------------------

    private fun WorkspaceDatabase.op(name: String, payload: JsonObject): OpResult =
        registry.execute(name, this, spec.tenantId, payload)

    private fun WorkspaceDatabase.row(name: String, payload: JsonObject): Map<String, Any?> =
        (op(name, payload) as OpResult.Row).row ?: error("$name returned no row")

    private fun WorkspaceDatabase.newDraft(terminal: String = "KSK-001"): String {
        val row = row(
            "db.upsert",
            buildJsonObject {
                put("collection", "kiosk_orders")
                put("data", buildJsonObject {
                    put("direction", "CUSTOMER_REQUEST")
                    put("status", "draft")
                    put("terminal_id", terminal)
                    put("created_at", "2026-01-01T00:00:00Z")
                })
            }
        )
        return row["id"] as String
    }

    private fun WorkspaceDatabase.append(orderId: String, sku: String, name: String, price: Double, qty: Int = 1) =
        row(
            "db.append_child",
            buildJsonObject {
                put("collection", "kiosk_order_lines")
                put("parent_id", orderId)
                put("data", buildJsonObject {
                    put("sku", sku)
                    put("item_name", name)
                    put("unit_price", price)
                    put("quantity", qty)
                })
            }
        )

    private fun WorkspaceDatabase.orderRow(orderId: String): Map<String, Any?> =
        (op(
            "db.query",
            buildJsonObject {
                put("collection", "kiosk_orders")
                put("where", buildJsonObject { put("id", orderId) })
            }
        ) as OpResult.Rows).rows.first()

    private fun WorkspaceDatabase.lines(orderId: String): List<JsonObject> =
        (op(
            "db.query",
            buildJsonObject {
                put("collection", "kiosk_order_lines")
                put("where", buildJsonObject { put("order_id", orderId) })
            }
        ) as OpResult.Rows).rows.map { row -> JsonObject(row.mapValues { (_, v) -> JsonPrimitive(v.toString()) }) }

    // ---- child lists and roll-ups -----------------------------------------

    /**
     * The kiosk order screen renders its cart from `lines_json`, so every write that returns the
     * order's roll-ups must also carry the projected child rows - otherwise the header shows
     * "2 in cart" while the list underneath says "No items yet".
     */
    @Test
    fun `a child write returns the projected lines the card renders`() = withWorkspace { db, _ ->
        val order = db.newDraft()
        val empty = db.orderRow(order)
        assertEquals("[]", empty["lines_json"])
        assertEquals(0, (empty["line_count"] as? Number)?.toInt())

        db.append(order, "P1001", "Mineral Water", 1.50)
        db.append(order, "P1001", "Mineral Water", 1.50)
        val merged = db.append(order, "P1008", "Sandwich", 4.00)

        val lines = Json.parseToJsonElement(merged["lines_json"] as String).jsonArray
        assertEquals(2, lines.size)
        assertEquals(2, (merged["line_count"] as? Number)?.toInt())
        // item_count counts units, not rows, so the header reads "3 items".
        assertEquals(3, (merged["item_count"] as? Number)?.toInt())

        val water = lines.map { it.jsonObject }
            .first { it["sku"]?.jsonPrimitive?.contentOrNull == "P1001" }
        assertEquals(2.0, water["quantity"]?.jsonPrimitive?.contentOrNull?.toDouble()!!, 0.001)
        assertEquals("3.00", water["line_total"]?.jsonPrimitive?.contentOrNull)
        assertTrue("line rows need an id for the +/- and remove actions",
            water["id"]?.jsonPrimitive?.contentOrNull?.isNotBlank() == true)
    }

    /** A brand-new order has no lines yet, so its roll-ups must read 0 / $0.00, not blank. */
    @Test
    fun aFreshDraftReportsZeroRollUps() = withWorkspace { db, _ ->
        val row = db.row(
            "db.upsert",
            buildJsonObject {
                put("collection", "kiosk_orders")
                put("data", buildJsonObject {
                    put("direction", "CUSTOMER_REQUEST")
                    put("status", "draft")
                    put("terminal_id", "KSK-001")
                })
            }
        )
        assertEquals(0, (row["item_count"] as? Number)?.toInt())
        assertEquals("0.00", row["total_display"])
        assertEquals("0.00", row["subtotal_display"])
    }

    @Test
    fun `adding the same key twice merges the row and doubles the total`() = withWorkspace { db, _ ->
        val order = db.newDraft()
        db.append(order, "P1001", "Mineral Water", 1.50)
        val merged = db.append(order, "P1001", "Mineral Water", 1.50)

        assertEquals(1, db.lines(order).size)
        assertEquals("3.00", db.orderRow(order)["total_display"])
        assertEquals(3.0, merged["total"] as Double, 0.001)
    }

    @Test
    fun `removing a line recomputes the totals and returns an undo payload`() = withWorkspace { db, _ ->
        val order = db.newDraft()
        db.append(order, "P1001", "Mineral Water", 1.50)
        db.append(order, "P1008", "Sandwich", 4.00, 2)
        val sandwich = db.lines(order).first { it["sku"]?.jsonPrimitive?.contentOrNull == "P1008" }

        val removed = db.row(
            "db.remove_child",
            buildJsonObject {
                put("collection", "kiosk_order_lines")
                put("id", sandwich["id"]!!.jsonPrimitive.content)
            }
        )
        assertEquals(1.5, removed["total"] as Double, 0.001)

        val undo = removed["undo_json"] as String
        assertTrue(undo.contains("P1008"))
        val restored = db.row(
            "db.append_child",
            buildJsonObject {
                put("collection", "kiosk_order_lines")
                put("parent_id", order)
                put("data", Json.parseToJsonElement(undo).jsonObject)
            }
        )
        assertEquals(9.5, restored["total"] as Double, 0.001)
    }

    @Test
    fun `setting quantity to zero removes the line`() = withWorkspace { db, _ ->
        val order = db.newDraft()
        db.append(order, "P1001", "Mineral Water", 1.50)
        val id = db.lines(order).single()["id"]!!.jsonPrimitive.content
        val result = db.row(
            "db.set_child_qty",
            buildJsonObject {
                put("collection", "kiosk_order_lines")
                put("id", id)
                put("quantity", 0)
            }
        )
        assertEquals(0.0, result["total"] as Double, 0.001)
        assertTrue(db.lines(order).isEmpty())
    }

    @Test
    fun `stock guard rejects overselling and accepts the available quantity`() = withWorkspace { db, _ ->
        val order = db.newDraft()
        val oversell = runCatching { db.append(order, "P1024", "Croissant", 2.75, 26) }
        assertTrue(oversell.isFailure)
        assertTrue(oversell.exceptionOrNull()!!.message!!.contains("Only 25 left"))

        val ok = db.append(order, "P1024", "Croissant", 2.75, 25)
        assertEquals(68.75, ok["total"] as Double, 0.001)
    }

    @Test
    fun `commit refuses an empty order and locks a committed one`() = withWorkspace { db, _ ->
        val order = db.newDraft()
        val empty = runCatching {
            db.op("db.commit", buildJsonObject {
                put("collection", "kiosk_orders")
                put("id", order)
            })
        }
        assertTrue(empty.isFailure)
        assertTrue(empty.exceptionOrNull()!!.message!!.contains("Nothing to commit"))

        db.append(order, "P1012", "Coffee", 2.50)
        val committed = db.row("db.commit", buildJsonObject {
            put("collection", "kiosk_orders")
            put("id", order)
        })
        assertEquals("submitted", committed["status"])
        assertEquals("2.50", committed["total_display"])

        val again = runCatching {
            db.op("db.commit", buildJsonObject {
                put("collection", "kiosk_orders")
                put("id", order)
            })
        }
        assertTrue(again.isFailure)
        assertTrue(again.exceptionOrNull()!!.message!!.contains("no longer change"))
    }

    /**
     * The card gates Add/Submit/remove on `user.kiosk_status == 'draft'`, so a child write must
     * return the parent's status. A roll-up that only carried aggregates blanked the variable
     * and every state-gated control vanished after the first item.
     */
    @Test
    fun `a child write returns the parent status the card gates on`() = withWorkspace { db, _ ->
        val order = db.newDraft()
        val added = db.append(order, "P1001", "Mineral Water", 1.50)
        assertEquals("draft", added["status"])
        assertEquals("CUSTOMER_REQUEST", added["direction"])
        assertEquals(order, added["id"])

        val removed = db.row(
            "db.remove_child",
            buildJsonObject {
                put("collection", "kiosk_order_lines")
                put("id", added["child_id"] as String)
            }
        )
        assertEquals("draft", removed["status"])
    }

    /** A committed order is read-only: the card hides its controls, the engine must agree. */
    @Test
    fun `a committed order refuses further child writes`() = withWorkspace { db, _ ->
        val order = db.newDraft()
        val line = db.append(order, "P1001", "Mineral Water", 1.50)
        db.row("db.commit", buildJsonObject {
            put("collection", "kiosk_orders")
            put("id", order)
        })

        val appended = runCatching { db.append(order, "P1008", "Sandwich", 4.00) }
        assertTrue(appended.isFailure)
        assertTrue(appended.exceptionOrNull()!!.message!!.contains("no longer change"))

        val qty = runCatching {
            db.row(
                "db.set_child_qty",
                buildJsonObject {
                    put("collection", "kiosk_order_lines")
                    put("id", line["child_id"] as String)
                    put("quantity", 5)
                }
            )
        }
        assertTrue(qty.isFailure)
        assertTrue(qty.exceptionOrNull()!!.message!!.contains("no longer change"))

        val removed = runCatching {
            db.row(
                "db.remove_child",
                buildJsonObject {
                    put("collection", "kiosk_order_lines")
                    put("id", line["child_id"] as String)
                }
            )
        }
        assertTrue(removed.isFailure)

        // The committed roll-up is untouched.
        assertEquals("1.50", db.orderRow(order)["total_display"])
        assertEquals(1, db.lines(order).size)
    }

    /**
     * `output` maps a write result onto DivKit variables. A column the result does not carry is
     * left alone instead of being published as an empty string.
     */
    @Test
    fun `output mapping leaves variables alone for columns the row lacks`() {
        val row = mapOf<String, Any?>("id" to "9", "total_display" to "3.00")
        val output = buildJsonObject {
            put("id", "user.kiosk_order_id")
            put("total_display", "user.kiosk_total")
            put("status", "user.kiosk_status")
            put("item_count", "user.kiosk_item_count")
        }
        val values = engine.router.rowOutputValues(row, output)
        assertEquals(setOf("user.kiosk_order_id", "user.kiosk_total"), values.keys)
        assertEquals(
            DivVariableValue.Str("3.00"),
            values["user.kiosk_total"]
        )
        assertTrue(engine.router.rowOutputValues(null, output).isEmpty())
        assertTrue(engine.router.rowOutputValues(row, null).isEmpty())
    }

    // ---- transfer packing --------------------------------------------------

    private fun WorkspaceDatabase.packedOrder(): Map<String, Any?> {
        val order = newDraft()
        append(order, "P1001", "Mineral Water", 1.50, 2)
        append(order, "P1012", "Coffee", 2.50)
        row("db.commit", buildJsonObject {
            put("collection", "kiosk_orders")
            put("id", order)
        })
        return row("doc.pack", buildJsonObject {
            put("collection", "kiosk_orders")
            put("id", order)
            put("chunk_bytes", 60)
        })
    }

    @Test
    fun `packing is deterministic for the same order and chunk size`() = withWorkspace { db, _ ->
        val first = db.packedOrder()
        val second = db.row(
            "doc.pack",
            buildJsonObject {
                put("collection", "kiosk_orders")
                put("id", first["id"] as String)
                put("chunk_bytes", 60)
            }
        )
        assertEquals(first["payload_json"], second["payload_json"])
        assertEquals(first["checksum"], second["checksum"])
        assertEquals(first["chunks_json"], second["chunks_json"])
        assertTrue((first["chunk_count"] as Int) >= 1)
    }

    @Test
    fun `chunks assemble in any order and reject missing duplicate or corrupt sets`() = withWorkspace { db, _ ->
        val packed = db.packedOrder()
        val chunks = Json.parseToJsonElement(packed["chunks_json"] as String).jsonArray.map { it.jsonPrimitive.content }
        assertTrue(chunks.size >= 2)

        val reversed = db.row("doc.unpack", buildJsonObject {
            put("chunks_json", JsonArray(chunks.reversed().map { JsonPrimitive(it) }).toString())
            put("checksum", packed["checksum"] as String)
        })
        assertEquals(true, reversed["ok"])
        assertEquals(packed["payload_json"], reversed["payload_json"])

        val missing = db.row("doc.unpack", buildJsonObject {
            put("chunks_json", JsonArray(chunks.drop(1).map { JsonPrimitive(it) }).toString())
            put("checksum", packed["checksum"] as String)
        })
        assertEquals(false, missing["ok"])
        assertTrue((missing["reason"] as String).contains("Missing"))

        val duplicate = SchemaOps.append(chunks, chunks.first())
        assertFalse(duplicate.accepted)
        assertTrue(duplicate.reason!!.contains("already scanned"))

        val corrupt = SchemaOps.append(emptyList(), "1|2|!!!")
        assertFalse(corrupt.accepted)
        assertTrue(corrupt.corrupt)

        val wrongChecksum = db.row("doc.unpack", buildJsonObject {
            put("chunks_json", JsonArray(chunks.map { JsonPrimitive(it) }).toString())
            put("checksum", "deadbeef")
        })
        assertEquals(false, wrongChecksum["ok"])
        assertTrue((wrongChecksum["reason"] as String).contains("Checksum mismatch"))
    }

    // ---- validation, lookup, delete guard ---------------------------------

    @Test
    fun `duplicate sku and non numeric price are rejected by the schema`() = withWorkspace { db, _ ->
        val duplicate = db.row("db.upsert", buildJsonObject {
            put("collection", "inventory")
            put("data", buildJsonObject {
                put("item_name", "Copy of water")
                put("sku", "P1001")
                put("price", 1.0)
            })
        })
        assertEquals(false, duplicate["ok"])
        assertTrue((duplicate["errors_json"] as String).contains("sku"))

        val badPrice = db.row("db.upsert", buildJsonObject {
            put("collection", "inventory")
            put("data", buildJsonObject {
                put("item_name", "Bad price")
                put("sku", "P9999")
                put("price", "abc")
            })
        })
        assertEquals(false, badPrice["ok"])
        assertTrue((badPrice["errors_json"] as String).contains("price"))
    }

    @Test
    fun `lookup resolves a scanned value to the product`() = withWorkspace { db, _ ->
        val saved = db.row("db.upsert", buildJsonObject {
            put("collection", "inventory")
            put("data", buildJsonObject {
                put("item_name", "Barcode item")
                put("sku", "P7777")
                put("price", 3.0)
                put("barcode", "0123456789012")
            })
        })
        assertEquals(true, saved["ok"])

        val hit = db.row("lookup.resolve", buildJsonObject {
            put("collection", "inventory")
            put("value", "0123456789012")
        })
        assertEquals(true, hit["ok"])
        assertEquals("Barcode item", hit["item_name"])
        assertEquals("P7777", hit["sku"])

        val miss = db.row("lookup.resolve", buildJsonObject {
            put("collection", "inventory")
            put("value", "0000000000000")
        })
        assertEquals(false, miss["ok"])
    }

    @Test
    fun `delete is refused while order lines reference the product`() = withWorkspace { db, _ ->
        val order = db.newDraft()
        db.append(order, "P1008", "Sandwich", 4.00)
        val sandwich = ((db.op("db.query", buildJsonObject {
            put("collection", "inventory")
            put("where", buildJsonObject { put("sku", "P1008") })
        }) as OpResult.Rows).rows.first())["id"] as String

        val refused = runCatching {
            db.op("db.delete", buildJsonObject {
                put("collection", "inventory")
                put("id", sandwich)
            })
        }
        assertTrue(refused.isFailure)
        assertTrue(refused.exceptionOrNull()!!.message!!.contains("can't be deleted"))

        val unused = ((db.op("db.query", buildJsonObject {
            put("collection", "inventory")
            put("where", buildJsonObject { put("sku", "P1020") })
        }) as OpResult.Rows).rows.first())["id"] as String
        val deleted = db.op("db.delete", buildJsonObject {
            put("collection", "inventory")
            put("id", unused)
        })
        assertEquals(1L, (deleted as OpResult.Count).n)
    }

    @Test
    fun `every operation a workspace card uses is registered`() = withWorkspace { db, _ ->
        val generic = listOf(
            "db.upsert" to buildJsonObject {
                put("collection", "kiosk_orders")
                put("data", buildJsonObject {
                    put("direction", "CUSTOMER_REQUEST")
                    put("status", "draft")
                })
            },
            "lookup.resolve" to buildJsonObject {
                put("collection", "inventory")
                put("value", "P1001")
            }
        )
        for ((op, payload) in generic) {
            assertTrue("$op must return a row", db.op(op, payload) is OpResult.Row)
        }
        assertTrue(runCatching { db.op("kiosk.nope", buildJsonObject { }) }.exceptionOrNull() is SecurityException)
        assertTrue(runCatching { db.op("db.nope", buildJsonObject { }) }.exceptionOrNull() is SecurityException)
    }
}
