package com.example.enginehost.demo

import com.example.enginehost.TenantWorkspaceSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Loads the built-in showcase cards from `src/main/resources/demo/`.
 *
 * Card markup lives in JSON rather than Kotlin so the demo is editable as data. Each tool card
 * is its own file under `demo/tools/`, so one tool's markup can change without touching the
 * rest and the Kotlin side holds no large literal builders. Resources are used rather than
 * `assets/` so the same files load in plain-JVM unit tests, where no `Context` exists.
 *
 * Files use `${name}` placeholders for tenant values; they are filled in at load time.
 */
object DemoCardRepository {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    private const val ROOT = "demo"
    private const val TOOLS = "$ROOT/tools"

    /** Tool card files in render order — the on-screen showcase order. */
    val toolCardOrder: List<String> = listOf(
        "scanner",
        "code_generate",
        "camera",
        "mobilenet",
        "u2net",
        "image_manipulation",
        "vectorize",
        "libsignal",
        "biometrics",
        "haptics",
        "beeper",
        "clipboard_push",
        "offline_cache",
        "realtime_ws",
        "net",
        "print",
        "keep_awake",
        "native_icons",
        "catalog_list",
        "hourly_chart",
        "sdui_chart"
    )

    /** CardRegistry id of the entry hub card — route index 0. */
    fun hubCardId(spec: TenantWorkspaceSpec): String = "hub_${spec.tenantId}"

    /** CardRegistry id of the card that hosts one tool, e.g. `tool_scanner`. */
    fun toolCardId(name: String): String = "tool_$name"

    private val cache = HashMap<String, String>()

    /** Raw resource text, or null when missing. */
    fun rawAsset(path: String): String? {
        cache[path]?.let { return it }
        val text = runCatching {
            DemoCardRepository::class.java.classLoader
                ?.getResourceAsStream(path)
                ?.bufferedReader()
                ?.use { it.readText() }
        }.getOrNull() ?: return null
        cache[path] = text
        return text
    }

    /** Reads a demo asset and substitutes `${key}` placeholders from [values]. */
    fun read(path: String, values: Map<String, String>): String? {
        val raw = rawAsset(path) ?: return null
        var out = raw
        values.forEach { (k, v) -> out = out.replace("\${$k}", v) }
        return out
    }

    /** Parses [path] into a [JsonObject], or null when unreadable or unparseable. */
    fun readObject(path: String, values: Map<String, String>): JsonObject? {
        val text = read(path, values) ?: return null
        return runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
    }

    fun placeholders(spec: TenantWorkspaceSpec): Map<String, String> = mapOf(
        "tenantId" to spec.tenantId,
        "terminalCode" to spec.terminalCode,
        "displayName" to spec.displayName,
        "demoImageUri" to DemoMedia.sampleImageUri(spec.tenantId),
        "demoBarcodeUri" to (DemoMedia.barcodeImageUri(spec.tenantId) ?: ""),
        "demoQrUri" to (DemoMedia.qrImageUri(spec.tenantId) ?: "")
    )

    /** All tool cards for [spec], in [toolCardOrder]. Missing resources are skipped. */
    fun toolCards(spec: TenantWorkspaceSpec): List<JsonObject> {
        val values = placeholders(spec)
        return toolCardOrder.mapNotNull { name -> readObject("$TOOLS/$name.json", values) }
    }

    /** One tool card by file name (without extension), or null when absent. */
    fun toolCard(spec: TenantWorkspaceSpec, name: String): JsonObject? =
        readObject("$TOOLS/$name.json", placeholders(spec))

    /** `user.*` variable declarations from the hub card, reused by every pushed card. */
    private fun sharedVariables(spec: TenantWorkspaceSpec): JsonArray? {
        val hub = readObject("$ROOT/card.json", placeholders(spec)) ?: return null
        return hub["card"]?.jsonObject?.get("variables") as? JsonArray
    }

    /**
     * The entry hub: `demo/card.json` with a generated catalog spliced into the container
     * flagged by `tool_catalog_after`.
     *
     * The hub links to each tool card instead of inlining all of them, so the landing card
     * stays small and every tool gets its own screen reachable through `nav://push`.
     */
    fun hubCard(spec: TenantWorkspaceSpec): JsonObject? {
        val values = placeholders(spec)
        val root = readObject("$ROOT/card.json", values) ?: return null
        val catalog = catalogContainer(spec) ?: return root

        val card = root["card"]?.jsonObject ?: return root
        val states = card["states"] as? JsonArray ?: return root

        val patchedStates = buildJsonArray {
            states.forEach { stateEl ->
                val state = stateEl.jsonObject
                val div = state["div"]?.jsonObject
                val items = div?.get("items") as? JsonArray
                val slot = div?.get("tool_catalog_after")?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                if (div == null || items == null || slot == null) {
                    add(stateEl)
                } else {
                    val merged = buildJsonArray {
                        items.forEachIndexed { index, item ->
                            add(item)
                            if (index == slot) add(catalog)
                        }
                    }
                    add(buildJsonObject {
                        state.forEach { (k, v) -> put(k, v) }
                        put("div", buildJsonObject {
                            div.forEach { (k, v) -> put(k, v) }
                            put("items", merged)
                        })
                    })
                }
            }
        }

        return buildJsonObject {
            root.forEach { (k, v) -> put(k, v) }
            put("card", buildJsonObject {
                card.forEach { (k, v) -> put(k, v) }
                put("states", patchedStates)
            })
        }
    }

    /** Catalog of `nav://push` entries, one per tool card, driven by `demo/tool_catalog.json`. */
    fun catalogContainer(spec: TenantWorkspaceSpec): JsonObject? {
        val catalog = readObject("$ROOT/tool_catalog.json", placeholders(spec)) ?: return null
        val entries = catalog["entries"]?.jsonArray ?: return null

        return buildJsonObject {
            put("type", "container")
            put("orientation", "vertical")
            put("item_spacing", 8)
            put("background_color", "#0F172A")
            put("corner_radius", 12)
            putJsonObject("paddings") {
                put("left", 12); put("right", 12); put("top", 10); put("bottom", 10)
            }
            putJsonObject("border") {
                put("corner_radius", 12)
                putJsonObject("stroke") {
                    put("color", "#1E293B")
                    put("width", 1)
                }
            }
            putJsonArray("items") {
                addJsonObject {
                    put("type", "text")
                    put("text", catalog["title"]?.jsonPrimitive?.contentOrNull ?: "TOOL CARDS")
                    put("font_size", 11)
                    put("font_weight", "bold")
                    put("text_color", "#94A3B8")
                }
                addJsonObject {
                    put("type", "text")
                    put("text", catalog["subtitle"]?.jsonPrimitive?.contentOrNull ?: "")
                    put("font_size", 11)
                    put("text_color", "#64748B")
                }
                addJsonObject {
                    put("type", "grid")
                    put("column_count", 2)
                    put("item_spacing", 8)
                    putJsonArray("items") {
                        entries.forEach { entryEl ->
                            val entry = entryEl.jsonObject
                            val file = entry["file"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                            addJsonObject {
                                put("type", "button")
                                put("id", "nav_$file")
                                put("text", entry["label"]?.jsonPrimitive?.contentOrNull ?: file)
                                put("subtitle", "Open card")
                                entry["icon"]?.jsonPrimitive?.contentOrNull?.let { put("icon", it) }
                                put(
                                    "background_color",
                                    entry["accent_color"]?.jsonPrimitive?.contentOrNull ?: "#1E293B"
                                )
                                put("text_color", "#F8FAFC")
                                put("corner_radius", 10)
                                putJsonObject("action") {
                                    put("log_id", "nav_push_$file")
                                    put("url", "nav://push?id=${toolCardId(file)}")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * One tool card as a standalone DivKit document, ready for [engine.navigation.CardRegistry].
     *
     * The card reuses the hub's `user.*` declarations so it renders correctly even when it is
     * pushed before the hub has been displayed, and it carries a back button that pops the
     * navigation stack.
     */
    fun toolCardDocument(spec: TenantWorkspaceSpec, name: String): JsonObject? {
        val body = toolCard(spec, name) ?: return null
        val variables = sharedVariables(spec)

        return buildJsonObject {
            putJsonObject("card") {
                put("log_id", "tool_${name}_${spec.tenantId}")
                if (variables != null) put("variables", variables)
                putJsonArray("states") {
                    addJsonObject {
                        put("state_id", 0)
                        putJsonObject("div") {
                            put("type", "container")
                            put("orientation", "vertical")
                            put("item_spacing", 10)
                            putJsonObject("paddings") {
                                put("left", 12); put("right", 12); put("top", 10); put("bottom", 10)
                            }
                            putJsonArray("items") {
                                addJsonObject {
                                    put("type", "button")
                                    put("id", "nav_back_$name")
                                    put("text", "← Back to tool cards")
                                    put("icon", "back")
                                    put("background_color", "#1E293B")
                                    put("text_color", "#CBD5E1")
                                    put("corner_radius", 10)
                                    putJsonObject("action") {
                                        put("log_id", "nav_pop_$name")
                                        put("url", "nav://pop")
                                    }
                                }
                                add(body)
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Every card the host registers, entry hub first.
     *
     * Route index 0 is the hub; the rest are the individual tool cards it links to.
     */
    fun cardStack(spec: TenantWorkspaceSpec): List<Pair<String, JsonObject>> {
        val cards = mutableListOf<Pair<String, JsonObject>>()
        hubCard(spec)?.let { cards += hubCardId(spec) to it }
        toolCardOrder.forEach { name ->
            toolCardDocument(spec, name)?.let { cards += toolCardId(name) to it }
        }
        return cards
    }

    fun encode(element: JsonObject): String = json.encodeToString(JsonObject.serializer(), element)
}
