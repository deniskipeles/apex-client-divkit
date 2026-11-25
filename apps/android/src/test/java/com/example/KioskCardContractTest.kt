package com.example

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the Apex Kiosk workspace as a coherent, complete POS surface.
 *
 * The cards are data, so nothing type-checks them: these tests are the compiler. They caught the
 * `user.now_iso` reference (never declared or produced, so every new order was saved with an
 * empty timestamp) and the copy that leaked internal storage vocabulary to the cashier.
 */
class KioskCardContractTest {

    private val workspace = "demo/apex_kiosk"
    private val cardNames = listOf("kiosk_customer", "kiosk_owner", "kiosk_transfer", "kiosk_lan")

    private fun resource(path: String): String =
        javaClass.classLoader!!.getResourceAsStream(path)!!.bufferedReader().use { it.readText() }

    private fun card(name: String): JsonObject =
        Json.parseToJsonElement(resource("$workspace/tools/$name.json")).jsonObject

    private fun hub(): JsonObject = Json.parseToJsonElement(resource("$workspace/card.json")).jsonObject

    private fun walk(node: Any?, visit: (JsonObject) -> Unit) {
        when (node) {
            is JsonObject -> {
                visit(node)
                node.forEach { (key, value) ->
                    if (key in setOf("items", "states", "div", "row_card")) walk(value, visit)
                }
            }
            is JsonArray -> node.forEach { walk(it, visit) }
        }
    }

    private fun textsOf(root: JsonObject): List<String> {
        val out = mutableListOf<String>()
        walk(root) { node ->
            (node["text"] as? JsonPrimitive)?.contentOrNull?.let { out.add(it) }
        }
        return out
    }

    private fun idsOf(root: JsonObject): Set<String> {
        val out = mutableSetOf<String>()
        walk(root) { node ->
            (node["id"] as? JsonPrimitive)?.contentOrNull?.let { out.add(it) }
        }
        return out
    }

    private fun actionsOf(root: JsonObject): List<JsonObject> {
        val out = mutableListOf<JsonObject>()
        walk(root) { node ->
            (node["action"] as? JsonObject)?.let { out.add(it) }
            (node["actions"] as? JsonArray)?.forEach { (it as? JsonObject)?.let(out::add) }
        }
        return out
    }

    /** Every variable the workspace can legally read: hub declarations plus anything a card writes. */
    private fun declaredVariables(): Set<String> {
        val names = mutableSetOf<String>()
        val hubVars = hub()["card"]?.jsonObject?.get("variables") as? JsonArray
        hubVars?.forEach { entry ->
            (entry as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull?.let(names::add)
        }
        cardNames.forEach { name ->
            val root = card(name)
            actionsOf(root).forEach { action ->
                (action["variable_name"] as? JsonPrimitive)?.contentOrNull?.let(names::add)
                (action["payload"] as? JsonObject)?.let { payload ->
                    (payload["output"] as? JsonObject)?.values?.forEach { target ->
                        (target as? JsonPrimitive)?.contentOrNull
                            ?.takeIf { it.startsWith("user.") }
                            ?.let(names::add)
                    }
                    (payload["json_list_var"] as? JsonPrimitive)?.contentOrNull?.let(names::add)
                }
            }
        }
        return names
    }

    @Test
    fun everyKioskVariableReferenceIsDeclaredOrProduced() {
        val declared = declaredVariables()
        val refs = Regex("user\\.[a-z0-9_]+")
        val missing = mutableMapOf<String, MutableSet<String>>()
        cardNames.forEach { name ->
            val raw = resource("$workspace/tools/$name.json")
            refs.findAll(raw).map { it.value }.forEach { ref ->
                if (ref !in declared) missing.getOrPut(ref) { mutableSetOf() }.add(name)
            }
        }
        assertTrue("undeclared user.* references: $missing", missing.isEmpty())
    }

    @Test
    fun kioskCopyDoesNotLeakStorageVocabulary() {
        val banned = listOf("SQLite", "sqlite", "inventory collection", "json_list", "null", "None", "TODO")
        cardNames.forEach { name ->
            val texts = textsOf(card(name))
            banned.forEach { token ->
                val hit = texts.firstOrNull { it.contains(token) }
                assertTrue("$name exposes \"$token\": ${hit ?: ""}", hit == null)
            }
        }
    }

    @Test
    fun kioskJsonListsAllCarryAnEmptyState() {
        var total = 0
        cardNames.forEach { name ->
            var lists = 0
            walk(card(name)) { node ->
                if (node["type"]?.jsonPrimitive?.contentOrNull == "json_list") {
                    lists++
                    val empty = node["empty_text"]?.jsonPrimitive?.contentOrNull
                    assertTrue("$name json_list without empty_text", !empty.isNullOrBlank())
                    assertTrue("$name json_list without row_card", node["row_card"] is JsonObject)
                }
            }
            total += lists
        }
        assertTrue("the kiosk workspace renders no json_list at all", total > 0)
    }

    @Test
    fun customerCardExposesTheWholeOrderFlow() {
        val root = card("kiosk_customer")
        val ids = idsOf(root)
        assertTrue(ids.containsAll(listOf("kiosk_new_order", "kiosk_undo_remove", "kiosk_submit")))
        val texts = textsOf(root)
        listOf("Add", "Remove", "−", "+", "Submit order", "Total").forEach {
            assertTrue("customer card is missing \"$it\"", texts.contains(it))
        }
        assertTrue(
            "customer card lost its error banner",
            ids.contains("kiosk_customer_error")
        )
        val catalogRow = catalogRowCard(root)
        assertTrue(
            "catalog row must show SKU, price and stock",
            textsOf(catalogRow).any { it.contains("item.sku") } &&
                textsOf(catalogRow).any { it.contains("price") } &&
                textsOf(catalogRow).any { it.contains("in stock") }
        )
    }

    @Test
    fun lineRowsShowQuantityUnitPriceAndLineTotal() {
        listOf("kiosk_customer", "kiosk_owner").forEach { name ->
            val root = card(name)
            val lines = jsonList(root, "user.kiosk_lines_json") ?: error("$name has no lines list")
            val row = lines["row_card"] as JsonObject
            val texts = textsOf(row)
            assertTrue("$name line row lost the unit price", texts.any { it.contains("item.unit_price") })
            assertTrue("$name line row lost the line total", texts.any { it.contains("item.line_total") })
            assertTrue("$name line row lost the quantity", texts.any { it.contains("item.quantity") })
            assertTrue("$name line row lost Remove", texts.contains("Remove"))
            assertTrue("$name line row lost the steppers", texts.contains("+") && texts.contains("−"))
        }
    }

    @Test
    fun transferCardExposesTheWholeTransferFlow() {
        val ids = idsOf(card("kiosk_transfer"))
        assertTrue(
            ids.containsAll(
                listOf(
                    "kiosk_build_qr_set", "kiosk_qr_prev", "kiosk_qr_next",
                    "kiosk_scan_current", "kiosk_clear_scanned",
                    "kiosk_assemble_scanned", "kiosk_corrupt_chunk"
                )
            )
        )
    }

    @Test
    fun ownerAndLanCardsExposeTheirFlows() {
        val owner = idsOf(card("kiosk_owner"))
        assertTrue(
            owner.containsAll(
                listOf(
                    "kiosk_new_receipt", "kiosk_reload_receipt", "kiosk_close_receipt",
                    "kiosk_generate_receipt_qr", "kiosk_receipt_next_chunk"
                )
            )
        )
        val lan = idsOf(card("kiosk_lan"))
        assertTrue(
            lan.containsAll(
                listOf(
                    "kiosk_lan_start", "kiosk_lan_stop", "kiosk_lan_share",
                    "kiosk_lan_unshare", "kiosk_lan_list", "kiosk_lan_fetch"
                )
            )
        )
    }

    @Test
    fun orderTimestampsUseARealExpression() {
        listOf("kiosk_customer", "kiosk_owner").forEach { name ->
            val raw = resource("$workspace/tools/$name.json")
            assertFalse("$name still references the undeclared user.now_iso", raw.contains("user.now_iso"))
            assertTrue("$name lost its created_at timestamp", raw.contains("format_date(now()"))
        }
    }

    @Test
    fun everyKioskButtonDispatchesSomething() {
        cardNames.forEach { name ->
            var buttons = 0
            walk(card(name)) { node ->
                if (node["type"]?.jsonPrimitive?.contentOrNull != "button") return@walk
                buttons++
                val actions = (node["actions"] as? JsonArray) ?: JsonArray(emptyList())
                assertTrue("$name has a button without actions", actions.isNotEmpty())
                val dispatches = actions.any { action ->
                    val obj = action as? JsonObject ?: return@any false
                    obj["url"] != null || obj["type"] != null
                }
                assertTrue("$name button dispatches nothing", dispatches)
            }
            assertTrue("$name has no buttons", buttons > 0)
        }
    }

    private fun jsonList(root: JsonObject, dataVar: String): JsonObject? {
        var found: JsonObject? = null
        walk(root) { node ->
            if (node["type"]?.jsonPrimitive?.contentOrNull == "json_list" &&
                node["data_var"]?.jsonPrimitive?.contentOrNull == dataVar
            ) {
                found = node
            }
        }
        return found
    }

    private fun catalogRowCard(root: JsonObject): JsonObject {
        val list = jsonList(root, "user.catalog_json") ?: error("no catalog list")
        return list["row_card"] as JsonObject
    }

    @Test
    fun catalogEntriesCarryTheAddAction() {
        listOf("kiosk_customer", "kiosk_owner").forEach { name ->
            val row = catalogRowCard(card(name))
            val actions = actionsOf(row)
            assertTrue("$name catalog row has no action", actions.isNotEmpty())
            assertEquals(
                "$name catalog row must add the tapped product once",
                "db.append_child",
                actions.firstNotNullOfOrNull { it["url"]?.jsonPrimitive?.contentOrNull }
                    ?.substringAfterLast('/')
            )
        }
    }
}
