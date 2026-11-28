package engine.workspace

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest

/**
 * Pulls a newer workspace bundle from the authoring gateway and pins it on device.
 *
 * The payload is the same encrypted document the build ships (`APXW` | version | nonce |
 * AES-256-GCM(gzip(json))), so an update is authenticated by AES-GCM before it can replace
 * anything. Both channels read the applied copy: dev keeps an authored folder in
 * `filesDir/workspaces`, updates live in `filesDir/workspace_updates` and outrank it.
 */
object WorkspaceUpdate {

    const val UPDATE_DIR = "workspace_updates"
    private const val META = "update.json"
    private const val ENCRYPTED = "workspace.bin"
    private const val PLAINTEXT = "workspace.json"

    data class Outcome(
        val ok: Boolean,
        val message: String,
        val version: String? = null,
        val cardJson: String? = null,
        val source: String? = null
    )

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    fun directory(filesDir: File): File = File(filesDir, UPDATE_DIR)

    fun appliedVersion(id: String, filesDir: File?): String? = meta(id, filesDir)?.str("version")

    fun appliedAt(id: String, filesDir: File?): Long? =
        meta(id, filesDir)?.str("appliedAt")?.toLongOrNull()

    fun clear(id: String, filesDir: File): Boolean {
        val dir = File(directory(filesDir), id)
        return dir.exists() && dir.deleteRecursively()
    }

    /** The applied update for [id], decrypted in prod, or null when none was applied. */
    fun updatedBundle(
        id: String,
        filesDir: File?,
        key: ByteArray? = WorkspaceRepository.key()
    ): WorkspaceBundle? {
        if (filesDir == null) return null
        val dir = File(directory(filesDir), id)
        if (!dir.isDirectory) return null
        return runCatching {
            val encrypted = File(dir, ENCRYPTED).takeIf { it.isFile }
            if (encrypted != null) {
                val keyBytes = key ?: return null
                val plain = WorkspaceCipher.decrypt(encrypted.readBytes(), keyBytes)
                WorkspaceBundleCodec.decodeAny(plain.toString(Charsets.UTF_8)).copy(origin = "update")
            } else {
                val text = File(dir, PLAINTEXT).takeIf { it.isFile }?.readText() ?: return null
                WorkspaceBundleCodec.decodeAny(text).copy(origin = "update")
            }
        }.getOrNull()?.takeIf { it.id == id }
    }

    /**
     * Gateway base URL + slug for [bundle]: manifest `urls.updates` wins, then the build's
     * `GATEWAY_URL`, then `urls.tunnel` (a demo placeholder in the shipped workspaces, so it is
     * only ever the last resort).
     */
    fun endpoint(bundle: WorkspaceBundle): Pair<String, String> {
        val urls = bundle.manifest["urls"] as? JsonObject
        val updates = urls?.get("updates") as? JsonObject
        val slug = updates?.str("slug") ?: bundle.id.replace('_', '-')
        val base = updates?.str("baseUrl")?.trimEnd('/')
            ?: com.example.BuildConfig.GATEWAY_URL.trimEnd('/').takeIf { it.isNotBlank() }
            ?: urls?.str("tunnel")?.trimEnd('/')?.takeIf { it.isNotBlank() }?.plus("/workspaces")
            ?: ""
        return base to slug
    }

    /**
     * Downloads `workspace.bin` (encrypted, preferred) or `index.json` (dev gateway) for
     * [bundle] and stores it as the applied update. Never throws: failures come back as an
     * [Outcome] so the menu can report them.
     */
    fun apply(bundle: WorkspaceBundle, filesDir: File, baseUrlOverride: String? = null): Outcome {
        val (defaultBase, slug) = endpoint(bundle)
        val base = baseUrlOverride?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: defaultBase
        if (base.isBlank()) {
            return Outcome(false, "no gateway configured for ${bundle.id}")
        }
        val key = WorkspaceRepository.key()
        val candidates = buildList {
            add("$base/$slug/$ENCRYPTED" to true)
            add("$base/$slug/index.json" to false)
        }
        var lastError = "no endpoint answered"
        log("apply ${bundle.id} via $base (slug=$slug)")
        for ((url, expectsBinary) in candidates) {
            val bytes = runCatching { fetch(url) }.getOrElse {
                lastError = "$url: ${it.message ?: it::class.java.simpleName}"
                log("miss $lastError")
                continue
            }
            log("hit $url (${bytes.size} bytes)")
            val encrypted = WorkspaceCipher.isEncrypted(bytes)
            val text = when {
                encrypted -> {
                    val keyBytes = key ?: run {
                        lastError = "$url: this build has no workspace key"
                        continue
                    }
                    runCatching { WorkspaceCipher.decrypt(bytes, keyBytes).toString(Charsets.UTF_8) }
                        .getOrElse {
                            lastError = "$url: cannot decrypt (${it.message})"
                            continue
                        }
                }
                expectsBinary -> bytes.toString(Charsets.UTF_8)
                else -> bytes.toString(Charsets.UTF_8)
            }
            val fetched = runCatching { WorkspaceBundleCodec.decodeAny(text) }.getOrElse {
                lastError = "$url: ${it.message}"
                continue
            }
            if (fetched.id.isNotBlank() && fetched.id != bundle.id) {
                lastError = "$url: bundle is for '${fetched.id}', expected '${bundle.id}'"
                continue
            }
            return store(filesDir, bundle.id, bytes, encrypted, fetched, url)
        }
        return Outcome(false, "update failed — $lastError")
    }

    private fun store(
        filesDir: File,
        id: String,
        raw: ByteArray,
        encrypted: Boolean,
        fetched: WorkspaceBundle,
        sourceUrl: String
    ): Outcome {
        val previous = appliedVersion(id, filesDir)
        val dir = File(directory(filesDir), id)
        if (dir.exists()) dir.deleteRecursively()
        dir.mkdirs()
        if (encrypted) File(dir, ENCRYPTED).writeBytes(raw) else File(dir, PLAINTEXT).writeBytes(raw)
        val version = fetched.manifest.str("schemaVersion")
            ?: fetched.manifest.str("version")
            ?: previous
            ?: "update"
        File(dir, META).writeText(
            buildJsonObject {
                put("version", version)
                put("appliedAt", System.currentTimeMillis().toString())
                put("source", sourceUrl)
                put("encrypted", encrypted)
                put("sha256", sha256(raw))
                put("cards", fetched.cards.size.toString())
            }.toString()
        )
        return Outcome(
            ok = true,
            message = "$id updated to $version · ${fetched.cards.size} card(s)",
            version = version,
            cardJson = fetched.hub.toString(),
            source = sourceUrl
        )
    }

    private fun meta(id: String, filesDir: File?): JsonObject? {
        val file = filesDir?.let { File(File(directory(it), id), META) } ?: return null
        if (!file.isFile) return null
        return runCatching { Json.parseToJsonElement(file.readText()).jsonObject }.getOrNull()
    }

    private fun log(message: String) {
        runCatching { android.util.Log.i("WorkspaceUpdate", message) }
    }

    private fun fetch(url: String): ByteArray {
        val connection = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 20000
            requestMethod = "GET"
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw java.io.IOException("HTTP $code")
            return connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
