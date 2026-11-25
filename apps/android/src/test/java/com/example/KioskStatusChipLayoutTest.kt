package com.example

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The kiosk status chip is a text node that paints its own pill. Sitting next to a `weight = 1`
 * sibling it re-measured to zero width on a state push: the pill stayed (paddings only) and the
 * status disappeared, so a fresh order showed an empty navy capsule instead of `draft`.
 *
 * The chip now lives on the summary line, which has no weighted children. This pins that shape
 * for every demo card.
 */
class KioskStatusChipLayoutTest {

    private val demoRoot = File("src/dev/resources/demo")

    private fun cards(): List<Pair<String, JsonObject>> {
        assumeTrue("demo sources not present", demoRoot.isDirectory)
        return demoRoot.walkTopDown()
            .filter { it.isFile && it.extension == "json" && it.parentFile.name == "tools" }
            .mapNotNull { file ->
                runCatching { Json.parseToJsonElement(file.readText()) as JsonObject }
                    .getOrNull()
                    ?.let { "${file.parentFile.parentFile.name}/${file.name}" to it }
            }
            .toList()
    }

    @Test
    fun `no self-painted label shares a row with a weighted sibling`() {
        val problems = mutableListOf<String>()
        for ((name, card) in cards()) {
            fun walk(node: Any?) {
                when (node) {
                    is JsonObject -> {
                        val items = node["items"] as? JsonArray
                        if (items != null && node["orientation"]?.jsonPrimitive?.contentOrNull == "horizontal") {
                            val hasWeighted = items.any {
                                (it as? JsonObject)?.get("weight") != null
                            }
                            val painted = items.filter {
                                val child = it as? JsonObject
                                child?.get("type")?.jsonPrimitive?.contentOrNull == "text" &&
                                    child["background_color"] != null &&
                                    child["weight"] == null
                            }
                            if (hasWeighted) {
                                painted.forEach {
                                    problems += "$name: ${(it as JsonObject)["text"]?.jsonPrimitive?.contentOrNull} " +
                                        "shares a row with a weighted sibling"
                                }
                            }
                        }
                        node.values.forEach { walk(it) }
                    }
                    is JsonArray -> node.forEach { walk(it) }
                }
            }
            walk(card)
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    /**
     * A cart line used to separate its qty cluster from Remove with an empty `weight = 1`
     * container. Compose measured that spacer across the whole row, Remove collapsed to zero
     * width, and the line could not be removed from the device UI. Keep the weighted element
     * first (as the catalog rows do) and the action last.
     */
    @Test
    fun `no horizontal row uses an empty weighted spacer`() {
        val problems = mutableListOf<String>()
        for ((name, card) in cards()) {
            fun walk(node: Any?) {
                when (node) {
                    is JsonObject -> {
                        val items = node["items"] as? JsonArray
                        if (items != null && node["orientation"]?.jsonPrimitive?.contentOrNull == "horizontal") {
                            val last = items.lastOrNull() as? JsonObject
                            items.forEachIndexed { index, el ->
                                val child = el as? JsonObject ?: return@forEachIndexed
                                val empty = child["type"]?.jsonPrimitive?.contentOrNull == "container" &&
                                    (child["items"] as? JsonArray)?.isEmpty() != false
                                // A leading spacer is fine (it right-aligns the rest); one in the
                                // middle takes the whole row and starves what follows it.
                                if (child["weight"] != null && empty && index != 0 && child !== last) {
                                    problems += "$name: weighted spacer at row index $index of ${items.size}"
                                }
                            }
                        }
                        node.values.forEach { walk(it) }
                    }
                    is JsonArray -> node.forEach { walk(it) }
                }
            }
            walk(card)
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun `every kiosk card renders its status chip`() {
        // Only the two order screens carry the order-status chip; the transfer/LAN cards use
        // their own status labels.
        val kiosk = cards().filter {
            it.first.endsWith("kiosk_customer.json") || it.first.endsWith("kiosk_owner.json")
        }
        assumeTrue("kiosk cards not present", kiosk.isNotEmpty())
        for ((name, card) in kiosk) {
            var found = false
            fun walk(node: Any?) {
                when (node) {
                    is JsonObject -> {
                        // The chip may bind the raw status or a friendly label derived from it.
                        val text = node["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        if (text.contains("user.kiosk_status")) found = true
                        node.values.forEach { walk(it) }
                    }
                    is JsonArray -> node.forEach { walk(it) }
                }
            }
            walk(card)
            assertTrue("$name has no status chip", found)
        }
    }
}
