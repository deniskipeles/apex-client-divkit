package engine.workspace

import com.example.BuildConfig
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

/**
 * Discovers and loads workspaces.
 *
 * * `dev` reads plaintext `demo/<id>/workspace.json` folders and also accepts workspaces
 *   imported at runtime (under `filesDir/workspaces/<id>`).
 * * `prod` reads only `demo/<id>/workspace.bin` and refuses to start without a key, so a
 *   production build cannot fall back to the plaintext demo.
 */
object WorkspaceRepository {

    const val MODE_DEV = "dev"
    const val MODE_PROD = "prod"

    const val INDEX_PATH = "demo/workspaces.json"
    private const val IMPORT_ROOT = "workspaces"

    fun mode(): String = BuildConfig.WORKSPACE_MODE

    fun isDev(): Boolean = mode() == MODE_DEV

    fun key(): ByteArray? =
        BuildConfig.WORKSPACE_KEY.takeIf { it.isNotBlank() }?.let { WorkspaceCipher.keyFrom(it) }

    data class Failure(val id: String, val reason: String)

    class LoadReport(
        val bundles: List<WorkspaceBundle>,
        val failures: List<Failure>
    )

    /**
     * The app's `filesDir` when a Context has been installed, so `loadAll()` without an explicit
     * directory still sees applied gateway updates and dev imports.
     */
    fun defaultFilesDir(): File? = engine.db.currentAndroidContext?.invoke()?.filesDir

    /** Every workspace visible to this build: packaged ones plus imported/updated copies. */
    fun loadAll(
        source: WorkspaceSource = ClasspathSource(),
        filesDir: File? = defaultFilesDir()
    ): LoadReport {
        val ids = runCatching { index(source) }.getOrDefault(emptyList())
        val bundles = mutableListOf<WorkspaceBundle>()
        val failures = mutableListOf<Failure>()
        ids.forEach { id ->
            // An applied gateway update outranks both the packaged copy and a dev import.
            val updated = WorkspaceUpdate.updatedBundle(id, filesDir)
            if (updated != null) {
                bundles.add(updated)
                return@forEach
            }
            val imported = if (isDev()) importedBundle(id, filesDir) else null
            if (imported != null) {
                bundles.add(imported)
                return@forEach
            }
            runCatching { load(id, source) }
                .onSuccess { bundles.add(it) }
                .onFailure { failures.add(Failure(id, it.message ?: it::class.java.simpleName)) }
        }
        if (isDev() && filesDir != null) {
            importedIds(filesDir).filter { it !in ids }.forEach { id ->
                importedBundle(id, filesDir)?.let { bundles.add(it) }
            }
        }
        return LoadReport(bundles, failures)
    }

    fun load(
        id: String,
        source: WorkspaceSource = ClasspathSource(),
        mode: String = mode(),
        workspaceKey: ByteArray? = key(),
        binarySource: WorkspaceBinarySource = ClasspathBinarySource()
    ): WorkspaceBundle =
        if (mode == MODE_DEV) loadPlain(id, source) else loadEncrypted(id, workspaceKey, binarySource)

    private val packagedCache = HashMap<String, WorkspaceBundle?>()

    /**
     * The bundle this build ships for [id] — decrypted in prod — or null when it is missing or
     * unreadable. Cached per process: the card layer asks for it once per card otherwise, which
     * would re-decrypt the whole bundle every time.
     */
    fun packagedBundle(
        id: String,
        source: WorkspaceSource = ClasspathSource(),
        mode: String = mode(),
        workspaceKey: ByteArray? = key(),
        binarySource: WorkspaceBinarySource = ClasspathBinarySource()
    ): WorkspaceBundle? {
        val cacheKey = "$mode:$id"
        if (packagedCache.containsKey(cacheKey)) return packagedCache[cacheKey]
        val bundle = runCatching { load(id, source, mode, workspaceKey, binarySource) }.getOrNull()
        packagedCache[cacheKey] = bundle
        return bundle
    }

    /** Drops memoised bundles; tests use it to re-read an edited folder. */
    fun clearPackagedCache() = packagedCache.clear()

    /** Ids listed by `demo/workspaces.json`; missing index means "no workspaces". */
    fun index(source: WorkspaceSource = ClasspathSource()): List<String> {
        val text = source.read(INDEX_PATH)
            ?: assetText(INDEX_PATH)
            ?: return emptyList()
        return runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(text).jsonObject
                .get("workspaces")?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
        }.getOrNull() ?: emptyList()
    }

    private fun loadPlain(id: String, source: WorkspaceSource): WorkspaceBundle =
        WorkspaceBundleCodec.fromDirectory(source, "demo/$id")

    private fun loadEncrypted(
        id: String,
        workspaceKey: ByteArray?,
        binarySource: WorkspaceBinarySource
    ): WorkspaceBundle {
        val key = workspaceKey ?: throw WorkspaceFormatException(
            "production build has no workspace key; set -PapexWorkspaceKey when building"
        )
        val blob = binarySource.readBytes("demo/$id/workspace.bin")
            ?: assetBytes("demo/$id/workspace.bin")
            ?: throw WorkspaceFormatException("missing encrypted workspace demo/$id/workspace.bin")
        val plain = runCatching { WorkspaceCipher.decrypt(blob, key) }.getOrElse {
            throw WorkspaceFormatException("cannot decrypt demo/$id/workspace.bin: ${it.message}")
        }
        return WorkspaceBundleCodec.decode(String(plain, Charsets.UTF_8))
            .copy(origin = "builtin")
    }

    /** Production bundles live in `assets/` so the APK ships no plaintext workspace. */
    private fun assetBytes(path: String): ByteArray? = runCatching {
        engine.db.currentAndroidContext?.invoke()?.assets?.open(path)?.use { it.readBytes() }
    }.getOrNull()

    private fun assetText(path: String): String? = assetBytes(path)?.toString(Charsets.UTF_8)

    // ---- dev-only runtime imports -------------------------------------------------------

    fun importRoot(filesDir: File): File = File(filesDir, IMPORT_ROOT)

    fun importedIds(filesDir: File?): List<String> {
        val root = filesDir?.let { importRoot(it) } ?: return emptyList()
        return root.listFiles()?.filter { it.isDirectory && File(it, "workspace.json").isFile }
            ?.map { it.name }?.sorted() ?: emptyList()
    }

    fun importedBundle(id: String, filesDir: File?): WorkspaceBundle? {
        if (!isDev() || filesDir == null) return null
        val dir = File(importRoot(filesDir), id)
        if (!dir.isDirectory) return null
        return runCatching {
            WorkspaceBundleCodec.fromDirectory(DirectorySource(importRoot(filesDir)), id)
                .copy(origin = "imported")
        }.getOrNull()
    }

    /** Copies a packaged workspace into the import root so it can be edited and unloaded. */
    fun materialize(bundle: WorkspaceBundle, filesDir: File): File {
        require(isDev()) { "runtime workspace imports are disabled in production builds" }
        val dir = File(importRoot(filesDir), bundle.id)
        dir.mkdirs()
        File(dir, "workspace.json").writeText(WorkspaceBundleCodec.prettyManifest(bundle.manifest))
        File(dir, "card.json").writeText(bundle.hub.toString())
        bundle.catalog?.let { File(dir, "tool_catalog.json").writeText(it.toString()) }
        val tools = File(dir, "tools").apply { mkdirs() }
        bundle.cards.forEach { card -> File(tools, "${card.name}.json").writeText(card.body.toString()) }
        val collections = File(dir, "collections").apply { mkdirs() }
        bundle.collections.forEachIndexed { index, collection ->
            val name = (collection["name"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "collection_$index"
            File(collections, "$name.json").writeText(collection.toString())
        }
        File(collections, "index.json").writeText(
            kotlinx.serialization.json.buildJsonObject {
                put("version", JsonPrimitive(1))
                put(
                    "collections",
                    kotlinx.serialization.json.buildJsonArray {
                        bundle.collections.forEachIndexed { index, collection ->
                            val name = (collection["name"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                                ?: "collection_$index"
                            add(kotlinx.serialization.json.JsonPrimitive("$name.json"))
                        }
                    }
                )
            }.toString()
        )
        if (bundle.icons.isNotEmpty()) {
            val icons = File(dir, "icons").apply { mkdirs() }
            File(icons, "icons.json").writeText(
                kotlinx.serialization.json.buildJsonObject {
                    bundle.icons.forEach { (name, base64) ->
                        put(name, kotlinx.serialization.json.JsonPrimitive(base64))
                    }
                }.toString()
            )
        }
        return dir
    }

    fun unload(id: String, filesDir: File): Boolean {
        val dir = File(importRoot(filesDir), id)
        return if (dir.exists()) dir.deleteRecursively() else false
    }

    fun resetImports(filesDir: File) {
        importRoot(filesDir).deleteRecursively()
    }
}

/** Binary reads for encrypted bundles (classpath or folder). */
interface WorkspaceBinarySource {
    fun readBytes(path: String): ByteArray?
}

class ClasspathBinarySource(
    private val loader: ClassLoader? = WorkspaceBinarySource::class.java.classLoader
) : WorkspaceBinarySource {
    override fun readBytes(path: String): ByteArray? =
        runCatching { loader?.getResourceAsStream(path)?.use { it.readBytes() } }.getOrNull()
}

class DirectoryBinarySource(private val root: File) : WorkspaceBinarySource {
    override fun readBytes(path: String): ByteArray? {
        val file = File(root, path)
        return if (file.isFile) runCatching { file.readBytes() }.getOrNull() else null
    }
}
