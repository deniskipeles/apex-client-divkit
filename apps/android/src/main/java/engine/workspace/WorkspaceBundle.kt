package engine.workspace

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** One DivKit document that belongs to a workspace. */
data class WorkspaceCard(
    val name: String,
    val body: JsonObject,
    val title: String? = null
)

/**
 * A complete workspace as data: manifest, DivKit documents, collection schemas and icons.
 *
 * Nothing here is workspace-specific, so a workspace can ship as a folder of JSON, be
 * imported at runtime by a dev build, or arrive as an encrypted binary in production.
 */
data class WorkspaceBundle(
    val manifest: JsonObject,
    val hub: JsonObject,
    val catalog: JsonObject?,
    val cards: List<WorkspaceCard>,
    val collections: List<JsonObject>,
    val icons: Map<String, String>,
    val origin: String = "builtin"
) {
    val id: String get() = manifest.str("id").orEmpty()

    val title: String get() = manifest.str("title") ?: id

    val subtitle: String get() = manifest.str("subtitle").orEmpty()

    val terminal: String get() = manifest.str("terminal") ?: id.uppercase()

    val accent: String get() = manifest.str("accent") ?: "#0F766E"

    val mode: String get() = manifest.str("mode") ?: "HYBRID"

    val capabilities: List<String>
        get() = manifest["capabilities"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()

    val tools: JsonObject get() = manifest["tools"]?.jsonObject ?: JsonObject(emptyMap())

    val urls: JsonObject get() = manifest["urls"]?.jsonObject ?: JsonObject(emptyMap())

    val unread: Int get() = manifest.str("unread")?.toIntOrNull() ?: 0

    val seedOps: List<JsonObject>
        get() = manifest["seed_ops"]?.jsonArray?.mapNotNull { it as? JsonObject } ?: emptyList()

    val cardNames: List<String> get() = cards.map { it.name }

    fun card(name: String): JsonObject? = cards.firstOrNull { it.name == name }?.body

    /** `user.*` declarations from the hub, reused by every pushed card. */
    val variables: JsonArray?
        get() = hub["card"]?.jsonObject?.get("variables") as? JsonArray

    fun toJson(): JsonObject = buildJsonObject {
        put("format", WorkspaceBundleCodec.FORMAT)
        put("origin", origin)
        put("manifest", manifest)
        put("hub", hub)
        catalog?.let { put("catalog", it) }
        put("cards", buildJsonArray {
            cards.forEach { card ->
                add(buildJsonObject {
                    put("name", card.name)
                    card.title?.let { put("title", it) }
                    put("body", card.body)
                })
            }
        })
        put("collections", buildJsonArray { collections.forEach { add(it) } })
        put("icons", buildJsonObject { icons.forEach { (name, base64) -> put(name, base64) } })
    }
}

/** Reads text files from a classpath folder or an on-disk folder. */
interface WorkspaceSource {
    fun read(path: String): String?
    fun exists(path: String): Boolean
}

class ClasspathSource(private val loader: ClassLoader? = WorkspaceSource::class.java.classLoader) : WorkspaceSource {
    override fun read(path: String): String? = runCatching {
        loader?.getResourceAsStream(path)?.bufferedReader()?.use { it.readText() }
    }.getOrNull()

    override fun exists(path: String): Boolean = read(path) != null
}

class MapSource(private val files: Map<String, String>) : WorkspaceSource {
    override fun read(path: String): String? = files[path]
    override fun exists(path: String): Boolean = files.containsKey(path)
}

class DirectorySource(private val root: java.io.File) : WorkspaceSource {
    override fun read(path: String): String? {
        val file = java.io.File(root, path)
        return if (file.isFile) runCatching { file.readText() }.getOrNull() else null
    }

    override fun exists(path: String): Boolean = java.io.File(root, path).isFile
}

object WorkspaceBundleCodec {

    const val FORMAT = "apex.workspace.bundle/1"
    const val MANIFEST_FORMAT = "apex.workspace/1"

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    /** Serialises a bundle into the single document that production encrypts. */
    fun encode(bundle: WorkspaceBundle): String = bundle.toJson().toString()

    /** Parses and validates an encoded bundle. Throws [WorkspaceFormatException] when invalid. */
    fun decode(text: String): WorkspaceBundle {
        val root = runCatching { Json.parseToJsonElement(text).jsonObject }
            .getOrElse { throw WorkspaceFormatException("not a JSON object") }
        val format = root.str("format")
        if (format != null && format != FORMAT) throw WorkspaceFormatException("unsupported bundle format '$format'")
        val manifest = root["manifest"]?.jsonObject ?: throw WorkspaceFormatException("missing manifest")
        validateManifest(manifest)
        val hub = root["hub"]?.jsonObject ?: throw WorkspaceFormatException("missing hub card")
        val cards = root["cards"]?.jsonArray?.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val name = obj.str("name") ?: return@mapNotNull null
            val body = obj["body"] as? JsonObject ?: return@mapNotNull null
            WorkspaceCard(name, body, obj.str("title"))
        } ?: emptyList()
        return WorkspaceBundle(
            manifest = manifest,
            hub = hub,
            catalog = root["catalog"] as? JsonObject,
            cards = cards,
            collections = root["collections"]?.jsonArray?.mapNotNull { it as? JsonObject } ?: emptyList(),
            icons = root["icons"]?.jsonObject?.mapValues { it.value.jsonPrimitive.contentOrNull.orEmpty() } ?: emptyMap(),
            origin = root.str("origin") ?: "bundle"
        )
    }

    /** Loads a workspace folder: `workspace.json` plus the files it names. */
    fun fromDirectory(source: WorkspaceSource, dir: String): WorkspaceBundle {
        val manifestText = source.read("$dir/workspace.json")
            ?: throw WorkspaceFormatException("missing $dir/workspace.json")
        val manifest = runCatching { Json.parseToJsonElement(manifestText).jsonObject }
            .getOrElse { throw WorkspaceFormatException("$dir/workspace.json is not a JSON object") }
        validateManifest(manifest)

        fun readDoc(path: String, what: String): JsonObject =
            source.read("$dir/$path")?.let {
                runCatching { Json.parseToJsonElement(it).jsonObject }
                    .getOrElse { throw WorkspaceFormatException("$dir/$path is not a JSON object") }
            } ?: throw WorkspaceFormatException("missing $what ($dir/$path)")

        val hub = readDoc(manifest.str("hub") ?: "card.json", "hub card")
        val catalog = (manifest.str("catalog") ?: "tool_catalog.json")
            .let { path -> source.read("$dir/$path") }
            ?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }

        val cardDir = manifest["cards"]?.jsonObject?.str("dir") ?: "tools"
        val order = manifest["cards"]?.jsonObject?.get("order")?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?: emptyList()
        val cards = order.mapNotNull { name ->
            val body = source.read("$dir/$cardDir/$name.json")?.let {
                runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull()
            } ?: return@mapNotNull null
            WorkspaceCard(name, body, body.str("title"))
        }

        val collectionDir = manifest["collections"]?.jsonObject?.str("dir") ?: "collections"
        val declared = manifest["collections"]?.jsonObject?.get("files")?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            .orEmpty()
        // index.json is what runtime imports append to, so the union keeps both authoring styles.
        val indexed = source.read("$dir/$collectionDir/index.json")
            ?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
            ?.get("collections")?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
            .orEmpty()
        val collectionIndex = (declared + indexed).distinct()
        val collections = collectionIndex.mapNotNull { file ->
            source.read("$dir/$collectionDir/$file")?.let {
                runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull()
            }
        }

        val icons = manifest.str("icons")?.let { path -> source.read("$dir/$path") }
            ?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
            ?.mapValues { it.value.jsonPrimitive.contentOrNull.orEmpty() }
            ?: emptyMap()

        return WorkspaceBundle(manifest, hub, catalog, cards, collections, icons, "builtin")
    }

    /** Manifest text as written back to disk for an imported workspace. */
    fun prettyManifest(manifest: JsonObject): String = Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), manifest)

    fun validateManifest(manifest: JsonObject) {
        val id = manifest.str("id")
        if (id.isNullOrBlank()) throw WorkspaceFormatException("manifest is missing 'id'")
        if (!id.matches(Regex("[a-z0-9_]{2,40}"))) {
            throw WorkspaceFormatException("workspace id '$id' must be lowercase letters, digits or '_'")
        }
        val format = manifest.str("format")
        if (format != null && format != MANIFEST_FORMAT) {
            throw WorkspaceFormatException("unsupported manifest format '$format'")
        }
        if (manifest["capabilities"] != null && manifest["capabilities"] !is JsonArray) {
            throw WorkspaceFormatException("'capabilities' must be an array")
        }
    }
}

class WorkspaceFormatException(message: String) : IllegalArgumentException(message)

internal fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull
