package engine.workspace

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Dev-build workspace authoring: feed a workspace a DivKit card, a collection schema or a
 * zip of custom icons, then unload it to return to the packaged version.
 *
 * Everything is stored under `filesDir/workspaces/<id>`; the packaged workspace is copied
 * there on first import, so an import never mutates the APK's own resources and `unload`
 * is a directory delete.
 */
object DevWorkspaceImport {

    data class Outcome(val ok: Boolean, val message: String)

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    fun materialize(workspaceId: String, filesDir: File): File? {
        val bundle = WorkspaceRepository.importedBundle(workspaceId, filesDir)
            ?: runCatching { WorkspaceRepository.load(workspaceId) }.getOrNull()
            ?: return null
        return WorkspaceRepository.materialize(bundle, filesDir)
    }

    /** Adds (or replaces) a DivKit card document, e.g. `{"type":"container","items":[...]}`. */
    fun importCard(workspaceId: String, fileName: String, text: String, filesDir: File): Outcome {
        if (!WorkspaceRepository.isDev()) return Outcome(false, "imports are disabled in production builds")
        val name = normalise(fileName, "card")
        val body = runCatching { Json.parseToJsonElement(text).jsonObject }
            .getOrElse { return Outcome(false, "card is not a JSON object") }
        val isDivKit = body["type"] != null || body["card"] != null
        if (!isDivKit) return Outcome(false, "card must be a DivKit document ('type' or 'card' missing)")

        val dir = materialize(workspaceId, filesDir) ?: return Outcome(false, "unknown workspace '$workspaceId'")
        File(dir, "tools").mkdirs()
        File(dir, "tools/$name.json").writeText(text)

        val manifestFile = File(dir, "workspace.json")
        val manifest = json.parseToJsonElement(manifestFile.readText()).jsonObject
        val cards = manifest["cards"]?.jsonObject ?: JsonObject(emptyMap())
        val order = cards["order"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
        val updated = buildJsonObject {
            manifest.forEach { (k, v) -> if (k != "cards") put(k, v) }
            put("cards", buildJsonObject {
                cards.forEach { (k, v) -> if (k != "order") put(k, v) }
                put("dir", cards["dir"]?.jsonPrimitive?.contentOrNull ?: "tools")
                put("order", buildJsonArray {
                    (order + name).distinct().forEach { add(JsonPrimitive(it)) }
                })
            })
        }
        manifestFile.writeText(WorkspaceBundleCodec.prettyManifest(updated))
        return Outcome(true, "card '$name' loaded into $workspaceId")
    }

    /**
     * Adds a collection schema. Accepts either one collection document or a schema package
     * (`{"schema":{"collections":[...]}}`).
     */
    fun importCollection(workspaceId: String, text: String, filesDir: File): Outcome {
        if (!WorkspaceRepository.isDev()) return Outcome(false, "imports are disabled in production builds")
        val root = runCatching { Json.parseToJsonElement(text).jsonObject }
            .getOrElse { return Outcome(false, "collection is not a JSON object") }
        val documents: List<JsonObject> = when {
            root["schema"]?.jsonObject?.get("collections") is kotlinx.serialization.json.JsonArray ->
                root["schema"]!!.jsonObject["collections"]!!.jsonArray.mapNotNull { it as? JsonObject }
            root["collections"] is kotlinx.serialization.json.JsonArray ->
                root["collections"]!!.jsonArray.mapNotNull { it as? JsonObject }
            root["name"] != null -> listOf(root)
            else -> return Outcome(false, "collection needs 'name' or a 'schema.collections' array")
        }
        if (documents.isEmpty()) return Outcome(false, "collection document has no collections")

        val dir = materialize(workspaceId, filesDir) ?: return Outcome(false, "unknown workspace '$workspaceId'")
        val collections = File(dir, "collections").apply { mkdirs() }
        val added = mutableListOf<String>()
        documents.forEachIndexed { index, document ->
            val name = document["name"]?.jsonPrimitive?.contentOrNull ?: "collection_$index"
            File(collections, "$name.json").writeText(document.toString())
            added.add("$name.json")
        }
        val indexFile = File(collections, "index.json")
        val existing = if (indexFile.isFile) {
            runCatching {
                json.parseToJsonElement(indexFile.readText()).jsonObject["collections"]?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            }.getOrNull().orEmpty()
        } else {
            emptyList()
        }
        indexFile.writeText(buildJsonObject {
            put("version", 1)
            put("collections", buildJsonArray {
                (existing + added).distinct().forEach { add(JsonPrimitive(it)) }
            })
        }.toString())
        return Outcome(true, "collection ${added.joinToString()} loaded into $workspaceId")
    }

    /**
     * Unpacks a zip of custom icons: `icons.json` (`{"name":"file.png"}`) plus image files.
     * Icons are stored as base64 PNG/JPEG so the renderer needs no extra dependency.
     */
    fun importIconPack(workspaceId: String, zip: ByteArray, filesDir: File): Outcome {
        if (!WorkspaceRepository.isDev()) return Outcome(false, "imports are disabled in production builds")
        val entries = mutableMapOf<String, ByteArray>()
        runCatching {
            ZipInputStream(ByteArrayInputStream(zip)).use { stream ->
                while (true) {
                    val entry = stream.nextEntry ?: break
                    if (!entry.isDirectory) entries[entry.name] = stream.readBytes()
                }
            }
        }.onFailure { return Outcome(false, "icon pack is not a readable zip: ${it.message}") }
        if (entries.isEmpty()) return Outcome(false, "icon pack is empty")

        val manifestBytes = entries.entries.firstOrNull { it.key.endsWith("icons.json") }?.value
            ?: return Outcome(false, "icon pack needs icons.json")
        val manifest = runCatching { json.parseToJsonElement(String(manifestBytes, Charsets.UTF_8)).jsonObject }
            .getOrElse { return Outcome(false, "icons.json is not a JSON object") }

        val dir = materialize(workspaceId, filesDir) ?: return Outcome(false, "unknown workspace '$workspaceId'")
        val iconsDir = File(dir, "icons").apply { mkdirs() }
        val existingFile = File(iconsDir, "icons.json")
        val merged = if (existingFile.isFile) {
            runCatching { json.parseToJsonElement(existingFile.readText()).jsonObject }.getOrNull()
                ?: JsonObject(emptyMap())
        } else {
            JsonObject(emptyMap())
        }.toMutableMap()

        var loaded = 0
        manifest.forEach { (name, value) ->
            val fileName = (value as? JsonPrimitive)?.contentOrNull ?: return@forEach
            val bytes = entries[fileName]
                ?: entries.entries.firstOrNull { it.key.endsWith("/$fileName") }?.value
                ?: return@forEach
            val base64 = java.util.Base64.getEncoder().encodeToString(bytes)
            merged[name] = JsonPrimitive(base64)
            File(iconsDir, fileName).writeBytes(bytes)
            loaded++
        }
        if (loaded == 0) return Outcome(false, "icons.json names no files present in the zip")
        existingFile.writeText(JsonObject(merged).toString())

        val manifestFile = File(dir, "workspace.json")
        val workspace = json.parseToJsonElement(manifestFile.readText()).jsonObject
        if (workspace["icons"] == null) {
            manifestFile.writeText(
                WorkspaceBundleCodec.prettyManifest(
                    JsonObject(workspace + ("icons" to JsonPrimitive("icons/icons.json")))
                )
            )
        }
        return Outcome(true, "$loaded icon(s) loaded into $workspaceId")
    }

    /**
     * Downloads a workspace from the authoring gateway and materialises it under
     * `filesDir/workspaces/<id>`, so card work never needs a rebuild.
     *
     * The payload is either the production bundle document (`apex.workspace.bundle/1`) or the
     * dev-server `index.json` (`manifest` + `hub` + `cards` + `collections` + `order`), which is
     * what `apps/dev-server` publishes under `client/public/workspaces/<slug>/`.
     */
    fun importFromUrl(workspaceId: String, url: String, filesDir: File): Outcome {
        if (!WorkspaceRepository.isDev()) return Outcome(false, "imports are disabled in production builds")
        val text = runCatching { fetchText(url) }
            .getOrElse { return Outcome(false, "fetch failed: ${it.message ?: "network error"}") }
        return importBundleText(workspaceId, text, filesDir)
    }

    fun importBundleText(workspaceId: String, text: String, filesDir: File): Outcome {
        if (!WorkspaceRepository.isDev()) return Outcome(false, "imports are disabled in production builds")
        val bundle = runCatching { WorkspaceBundleCodec.decodeAny(text) }
            .getOrElse { return Outcome(false, "not a workspace bundle: ${it.message}") }
        val manifest = if (bundle.id == workspaceId || workspaceId.isBlank()) bundle.manifest
        else JsonObject(bundle.manifest + ("id" to JsonPrimitive(workspaceId)))
        val target = bundle.copy(manifest = manifest)
        return runCatching {
            // Replace, don't merge: a card the gateway dropped must not survive from an older import.
            WorkspaceRepository.unload(workspaceId, filesDir)
            WorkspaceRepository.materialize(target, filesDir)
            WorkspaceRepository.importedBundle(workspaceId, filesDir)
                ?: throw IllegalStateException("import did not materialise")
        }.fold(
            onSuccess = { stored -> Outcome(true, "$workspaceId loaded from gateway (${stored.cards.size} card(s))") },
            onFailure = { Outcome(false, "import failed: ${it.message}") }
        )
    }

    /** `index.json` as published by `apps/dev-server`: returns the ids it advertises. */
    fun parseCatalogue(text: String): List<GatewayWorkspace> =
        runCatching {
            Json.parseToJsonElement(text).jsonObject["workspaces"]?.jsonArray?.mapNotNull { el ->
                val obj = el as? JsonObject ?: return@mapNotNull null
                GatewayWorkspace(
                    slug = obj["slug"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                    title = obj["title"]?.jsonPrimitive?.contentOrNull ?: "",
                    subtitle = obj["subtitle"]?.jsonPrimitive?.contentOrNull ?: ""
                )
            }.orEmpty()
        }.getOrElse { emptyList() }

    data class GatewayWorkspace(val slug: String, val title: String, val subtitle: String)

    private fun fetchText(url: String): String {
        val connection = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 15000
            requestMethod = "GET"
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw java.io.IOException("HTTP $code")
            return connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            connection.disconnect()
        }
    }

    fun unload(workspaceId: String, filesDir: File): Outcome =
        if (WorkspaceRepository.unload(workspaceId, filesDir)) {
            Outcome(true, "$workspaceId unloaded; packaged version restored")
        } else {
            Outcome(false, "$workspaceId has no imported copy")
        }

    private fun normalise(raw: String, fallback: String): String {
        val base = raw.substringAfterLast('/').substringAfterLast('\\')
            .removeSuffix(".json")
            .lowercase()
            .replace(Regex("[^a-z0-9_]+"), "_")
            .trim('_')
        return base.ifBlank { fallback }
    }
}
