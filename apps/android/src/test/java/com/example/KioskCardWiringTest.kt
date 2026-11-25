package com.example

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Demo cards are data, so nothing type-checks the variable a button reads against the variable some
 * action writes. The Kiosk "Add" button sent `parent_id=@{user.kiosk_order_id}` while "New order"
 * mapped the created id to `user.kiosk_order_label`, so every add/remove/submit hit
 * `Record " not found` and the whole Kiosk flow was dead.
 *
 * This pins the linkage: every variable a card uses as a parent key must be produced by an action
 * in the same workspace, and every `@{user.*}` a card reads must be declared on its card.
 */
class KioskCardWiringTest {

    private val workspaceRoot = File("src/dev/resources/demo")
    private val ref = Regex("@\\{user\\.([A-Za-z0-9_]+)")

    private fun workspaceDirs(): List<File> {
        assumeTrue("demo sources not present", workspaceRoot.isDirectory)
        return workspaceRoot.listFiles { f -> f.isDirectory }?.toList().orEmpty()
    }

    private fun jsonFiles(dir: File): List<File> =
        dir.walkTopDown().filter { it.isFile && it.extension == "json" }.toList()

    private fun parse(file: File): JsonElement? =
        runCatching { Json.parseToJsonElement(file.readText()) }.getOrNull()

    private fun walk(node: JsonElement?, visit: (JsonObject) -> Unit) {
        when (node) {
            is JsonObject -> {
                visit(node)
                node.values.forEach { walk(it, visit) }
            }
            is kotlinx.serialization.json.JsonArray -> node.forEach { walk(it, visit) }
            else -> Unit
        }
    }

    @Test
    fun `every parent key a card writes is produced by an action`() {
        val problems = mutableListOf<String>()
        for (workspace in workspaceDirs()) {
            val written = mutableSetOf<String>()
            val parentKeys = mutableSetOf<String>()
            for (file in jsonFiles(workspace)) {
                val root = parse(file) ?: continue
                walk(root) { node ->
                    node["variable_name"]?.jsonPrimitive?.contentOrNull?.let { written += it }
                    (node["payload"] as? JsonObject)?.get("output")?.let { out ->
                        walk(out) { output ->
                            written += output.values.mapNotNull { it.jsonPrimitive.contentOrNull }
                        }
                    }
                    (node["payload"] as? JsonObject)?.get("parent_id")?.jsonPrimitive?.contentOrNull
                        ?.let { ref.find(it)?.let { m -> parentKeys += "user.${m.groupValues[1]}" } }
                }
            }
            for (key in parentKeys) {
                if (key !in written) {
                    problems += "${workspace.name}: $key is used as a parent id but no action writes it"
                }
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun `every user variable a tool card reads is declared on the card`() {
        val problems = mutableListOf<String>()
        for (workspace in workspaceDirs()) {
            val declared = mutableSetOf<String>()
            val cardFile = File(workspace, "card.json")
            parse(cardFile)?.let { root ->
                walk(root) { node ->
                    node["name"]?.jsonPrimitive?.contentOrNull
                        ?.takeIf { it.startsWith("user.") }
                        ?.let { declared += it }
                }
            }
            assumeTrue("${workspace.name} has no card variables", declared.isNotEmpty())
            val reads = mutableSetOf<String>()
            for (file in File(workspace, "tools").listFiles()?.toList().orEmpty()) {
                val root = parse(file) ?: continue
                ref.findAll(file.readText()).forEach { reads += "user.${it.groupValues[1]}" }
                walk(root) { _ -> }
            }
            for (read in reads) {
                if (read !in declared) problems += "${workspace.name}/${read} is read but not declared"
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    /**
     * `kiosk_orders.commit_status` is `submitted`, so a card that compares against `SUBMITTED`
     * never matches: the "order is locked" banner stayed hidden and the receipt list filtered
     * every row out. Status literals are pinned to the schema here.
     */
    @Test
    fun `kiosk status literals match the schema commit status`() {
        val kiosk = File(workspaceRoot, "apex_kiosk")
        assumeTrue("kiosk demo sources not present", kiosk.isDirectory)
        val schema = parse(File(kiosk, "collections/kiosk_orders.json")) as? JsonObject
            ?: error("kiosk_orders collection schema is missing")
        val aggregates = schema["schema"]?.jsonObject?.get("aggregates")?.jsonArray
            ?: error("kiosk_orders declares no aggregates")
        val commitStatus = aggregates.mapNotNull {
            it.jsonObject["commit_status"]?.jsonPrimitive?.contentOrNull
        }.first()
        val editable = aggregates.mapNotNull {
            it.jsonObject["editable_status"]?.jsonArray
        }.first().map { it.jsonPrimitive.content }
        val legal = (editable + commitStatus).toSet()

        val problems = mutableListOf<String>()
        val literal = Regex("""kiosk_status\s*==\s*'([^']*)'""")
        for (file in jsonFiles(kiosk)) {
            literal.findAll(file.readText()).forEach { m ->
                val value = m.groupValues[1]
                if (value !in legal) problems += "${file.name} compares kiosk_status to '$value'"
            }
            val root = parse(file) ?: continue
            walk(root) { node ->
                val where = (node["payload"] as? JsonObject)?.get("where") as? JsonObject
                    ?: return@walk
                val value = where["status"]?.jsonPrimitive?.contentOrNull ?: return@walk
                if (value !in legal) problems += "${file.name} filters status = \"$value\""
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    /**
     * Editing controls only exist on a draft. A submitted order is a receipt: adding a line,
     * changing a quantity or removing one would silently rewrite a committed total.
     */
    @Test
    fun `kiosk customer card only offers editing controls on a draft`() {
        val card = parse(File(workspaceRoot, "apex_kiosk/tools/kiosk_customer.json")) as? JsonObject
            ?: error("kiosk_customer card is missing")
        val draftGate = "user.kiosk_status == 'draft'"
        val controls = setOf("Add", "Submit order", "Remove", "\u2212", "+")
        val gated = mutableSetOf<String>()
        val ungated = mutableSetOf<String>()

        fun walk(node: JsonElement?, insideDraft: Boolean) {
            when (node) {
                is JsonObject -> {
                    val visibility = node["visibility"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val draftHere = insideDraft || visibility.contains(draftGate)
                    val text = node["text"]?.jsonPrimitive?.contentOrNull
                    if (text != null && text in controls) {
                        if (draftHere) {
                            gated += text
                        } else {
                            ungated += text
                        }
                    }
                    node.values.forEach { walk(it, draftHere) }
                }
                is kotlinx.serialization.json.JsonArray -> node.forEach { walk(it, insideDraft) }
                else -> Unit
            }
        }
        walk(card, false)

        assertTrue("ungated editing controls: ${ungated}", ungated.isEmpty())
        assertTrue("missing editing controls: ${controls - gated}", gated.containsAll(controls))
    }
}
