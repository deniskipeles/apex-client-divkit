package com.example.enginehost.demo

import com.example.enginehost.TenantWorkspaceSpec
import engine.workspace.WorkspaceBundle
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
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
 * Loads the built-in showcase cards from `src/main/resources/demo/apex/`.
 *
 * Card markup lives in JSON rather than Kotlin so the demo is editable as data. Each tool card
 * is its own file under `demo/apex/tools/`, so one tool's markup can change without touching the
 * rest and the Kotlin side holds no large literal builders. Resources are used rather than
 * `assets/` so the same files load in plain-JVM unit tests, where no `Context` exists.
 *
 * Files use `${name}` placeholders for tenant values; they are filled in at load time.
 */
object DemoCardRepository {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    private const val DEFAULT_ROOT = "demo/apex"

    /** Tool card files in render order — the on-screen showcase order. */
    val toolCardOrder: List<String> = listOf(
        "local_db",
        "scanner",
        "code_generate",
        "codec",
        "camera",
        "mobilenet",
        "u2net",
        "image_manipulation",
        "embedding",
        "libsignal",
        "biometrics",
        "haptics",
        "beeper",
        "clipboard_push",
        "offline_cache",
        "realtime_ws",
        "net",
        "print",
        "screen",
        "files",
        "native_icons",
        "catalog_list",
        "hourly_chart",
        "sdui_chart",
        "sheet_showcase"
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


    /**
     * Workspace-declared startup operations. A card manifest lists the queries it wants run
     * before it renders, so opening a workspace needs no tenant-specific Kotlin.
     */
    fun seedOps(spec: TenantWorkspaceSpec): List<JsonObject> {
        val hub = hubDocument(spec) ?: return emptyList()
        val ops = hub["card"]?.jsonObject?.get("seed_ops")?.jsonArray ?: return emptyList()
        return ops.mapNotNull { it as? JsonObject }
    }

    fun placeholders(spec: TenantWorkspaceSpec): Map<String, String> = mapOf(
        "tenantId" to spec.tenantId,
        "terminalCode" to spec.terminalCode,
        "displayName" to spec.displayName,
        "demoImageUri" to DemoMedia.sampleImageUri(spec.tenantId),
        "demoBarcodeUri" to (DemoMedia.barcodeImageUri(spec.tenantId) ?: ""),
        "demoQrUri" to (DemoMedia.qrImageUri(spec.tenantId) ?: "")
    )

    private fun root(spec: TenantWorkspaceSpec): String =
        spec.demoRoot.ifBlank { DEFAULT_ROOT }

    private fun tools(spec: TenantWorkspaceSpec): String = "${root(spec)}/tools"

    private fun order(spec: TenantWorkspaceSpec, bundle: WorkspaceBundle? = bundleFor(spec)): List<String> =
        bundle?.cardNames?.takeIf { it.isNotEmpty() } ?: spec.toolCardOrder ?: toolCardOrder

    /** All tool cards for [spec], in its card order. Missing documents are skipped. */
    fun toolCards(spec: TenantWorkspaceSpec): List<JsonObject> =
        order(spec).mapNotNull { name -> toolCard(spec, name) }

    /**
     * Imported copy of a workspace, when a dev build has loaded one.
     *
     * A runtime import must win over the APK's own resources, otherwise "load" would look
     * like it worked while the packaged card kept rendering.
     */
    private fun imported(spec: TenantWorkspaceSpec): WorkspaceBundle? {
        val dir = engine.db.currentAndroidContext?.invoke()?.filesDir ?: return null
        return engine.workspace.WorkspaceRepository.importedBundle(spec.tenantId, dir)
    }

    /**
     * The bundle backing [spec]: a dev runtime import wins, then whatever this build ships.
     *
     * A production APK carries no plaintext `demo/` resources at all — the workspace arrives as
     * an encrypted bundle — so reading cards from the classpath alone renders an empty document
     * (a blank screen) on every prod build.
     */
    private fun bundleFor(spec: TenantWorkspaceSpec): WorkspaceBundle? =
        imported(spec) ?: engine.workspace.WorkspaceRepository.packagedBundle(spec.tenantId)

    /** Fills `${placeholder}` tokens; bundle documents are stored verbatim. */
    private fun localized(element: JsonElement, values: Map<String, String>): JsonElement =
        when (element) {
            is JsonObject -> JsonObject(element.mapValues { (_, value) -> localized(value, values) })
            is JsonArray -> JsonArray(element.map { localized(it, values) })
            is kotlinx.serialization.json.JsonPrimitive -> {
                val text = element.contentOrNull
                if (!element.isString || text == null || !text.contains("\${")) {
                    element
                } else {
                    kotlinx.serialization.json.JsonPrimitive(
                        values.entries.fold(text) { acc, (k, v) -> acc.replace("\${$k}", v) }
                    )
                }
            }
        }

    /** One tool card by file name (without extension), or null when absent. */
    fun toolCard(
        spec: TenantWorkspaceSpec,
        name: String,
        bundle: WorkspaceBundle? = bundleFor(spec)
    ): JsonObject? {
        val values = placeholders(spec)
        bundle?.card(name)?.let { return localized(it, values) as JsonObject }
        return readObject("${tools(spec)}/$name.json", values)
    }

    /** `user.*` variable declarations from the hub card, reused by every pushed card. */
    private fun sharedVariables(
        spec: TenantWorkspaceSpec,
        bundle: WorkspaceBundle? = bundleFor(spec)
    ): JsonArray? {
        bundle?.variables?.let { return localized(it, placeholders(spec)) as JsonArray }
        val hub = readObject("${root(spec)}/card.json", placeholders(spec)) ?: return null
        return hub["card"]?.jsonObject?.get("variables") as? JsonArray
    }

    /** The hub document: the shipped bundle's copy, or the plaintext folder in a dev build. */
    private fun hubDocument(
        spec: TenantWorkspaceSpec,
        bundle: WorkspaceBundle? = bundleFor(spec)
    ): JsonObject? {
        val values = placeholders(spec)
        bundle?.let { return localized(it.hub, values) as JsonObject }
        return readObject("${root(spec)}/card.json", values)
    }

    /**
     * The entry hub: `demo/apex/card.json` with a generated catalog spliced into the container
     * flagged by `tool_catalog_after`.
     *
     * The hub links to each tool card instead of inlining all of them, so the landing card
     * stays small and every tool gets its own screen reachable through `nav://push`.
     */
    fun hubCard(
        spec: TenantWorkspaceSpec,
        bundle: WorkspaceBundle? = bundleFor(spec)
    ): JsonObject? {
        val root = hubDocument(spec, bundle) ?: return null
        val catalog = catalogContainer(spec, bundle) ?: return root

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

    /** Catalog of `nav://push` entries, one per tool card, driven by `demo/apex/tool_catalog.json`. */
    fun catalogContainer(
        spec: TenantWorkspaceSpec,
        bundle: WorkspaceBundle? = bundleFor(spec)
    ): JsonObject? {
        val values = placeholders(spec)
        val catalog = bundle?.catalog?.let { localized(it, values) as JsonObject }
            ?: readObject("${root(spec)}/tool_catalog.json", values)
            ?: return null
        val entries = catalog["entries"]?.jsonArray ?: return null

        return buildJsonObject {
            put("type", "container")
            put("orientation", "vertical")
            put("item_spacing", 12)
            put("background_color", "#FFFFFF")
            put("corner_radius", 16)
            putJsonObject("paddings") {
                put("left", 20); put("right", 20); put("top", 20); put("bottom", 20)
            }
            putJsonObject("border") {
                put("corner_radius", 16)
                putJsonObject("stroke") {
                    put("color", "#E5E7EB")
                    put("width", 1)
                }
            }
            putJsonArray("items") {
                addJsonObject {
                    put("type", "text")
                    put("text", catalog["title"]?.jsonPrimitive?.contentOrNull ?: "Workflows")
                    put("font_size", 17)
                    put("font_weight", "bold")
                    put("text_color", "#111827")
                }
                addJsonObject {
                    put("type", "text")
                    put("text", catalog["subtitle"]?.jsonPrimitive?.contentOrNull ?: "")
                    put("font_size", 13)
                    put("text_color", "#6B7280")
                }
                entries.forEach { entryEl ->
                    val entry = entryEl.jsonObject
                    val file = entry["file"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                    addJsonObject {
                        put("type", "button")
                        put("id", "nav_$file")
                        put("text", entry["label"]?.jsonPrimitive?.contentOrNull ?: file)
                        put("subtitle", entry["subtitle"]?.jsonPrimitive?.contentOrNull ?: "")
                        entry["icon"]?.jsonPrimitive?.contentOrNull?.let { put("icon", it) }
                        put("background_color", "#FFFFFF")
                        put("text_color", "#111827")
                        put("font_size", 16)
                        put("corner_radius", 14)
                        put("text_alignment_horizontal", "left")
                        putJsonObject("paddings") {
                            put("left", 16); put("right", 16); put("top", 14); put("bottom", 14)
                        }
                        putJsonObject("border") {
                            put("corner_radius", 14)
                            putJsonObject("stroke") {
                                put("color", "#E5E7EB")
                                put("width", 1)
                            }
                        }
                        putJsonObject("action") {
                            put("log_id", "nav_push_$file")
                            put("url", "nav://push?id=${toolCardId(file)}")
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
    fun toolCardDocument(
        spec: TenantWorkspaceSpec,
        name: String,
        bundle: WorkspaceBundle? = bundleFor(spec)
    ): JsonObject? {
        val rawBody = toolCard(spec, name, bundle) ?: return null
        val timers = rawBody["_timers"]
        val body = if (timers != null) {
            JsonObject(rawBody.filterKeys { it != "_timers" })
        } else {
            rawBody
        }
        val variables = sharedVariables(spec, bundle)

        return buildJsonObject {
            putJsonObject("card") {
                put("log_id", "tool_${name}_${spec.tenantId}")
                if (variables != null) put("variables", variables)
                if (timers != null) put("timers", timers)
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
                                    put("text", "Back to tool cards")
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
    fun cardStack(
        spec: TenantWorkspaceSpec,
        bundle: WorkspaceBundle? = bundleFor(spec)
    ): List<Pair<String, JsonObject>> {
        val cards = mutableListOf<Pair<String, JsonObject>>()
        hubCard(spec, bundle)?.let { cards += hubCardId(spec) to it }
        order(spec, bundle).forEach { name ->
            toolCardDocument(spec, name, bundle)?.let { cards += toolCardId(name) to it }
        }
        return cards
    }

    fun encode(element: JsonObject): String = json.encodeToString(JsonObject.serializer(), element)
}
