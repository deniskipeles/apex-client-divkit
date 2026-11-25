import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Packs every `demo/<id>` folder into one JSON bundle and encrypts it with AES-256-GCM.
 *
 * Output: `<outputDir>/demo/<id>/workspace.bin` plus `<outputDir>/demo/workspaces.json`.
 * The plaintext folders stay in the dev channel only, so a production APK cannot be edited
 * on device.
 */
abstract class EncryptWorkspacesTask : DefaultTask() {

    @get:InputDirectory
    @get:Optional
    abstract val input: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    /**
     * An input, not internal: the bundle is encrypted with this key, so a key change must
     * re-run the task. As an `@Internal` value a rebuilt APK kept a bundle encrypted with the
     * previous key and could not decrypt its own workspace, which crashed at launch.
     */
    @get:Input
    abstract val workspaceKey: Property<String>

    @TaskAction
    fun encryptAll() {
        val key = workspaceKey.get().trim()
        if (key.isEmpty()) {
            throw GradleException(
                "production workspace build needs a key: -PapexWorkspaceKey=<64 hex chars> or APEX_WORKSPACE_KEY"
            )
        }
        val keyBytes = when {
            key.matches(Regex("[0-9a-fA-F]{64}")) ->
                ByteArray(32) { i -> key.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
            else -> MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
        }
        val root = input.get().asFile
        val dest = outputDir.get().asFile
        dest.deleteRecursively()
        dest.mkdirs()
        val ids = mutableListOf<String>()
        root.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }?.forEach { dir ->
            if (!File(dir, "workspace.json").isFile) return@forEach
            val plain = JsonOutput.toJson(buildBundle(dir)).toByteArray(Charsets.UTF_8)
            val gz = ByteArrayOutputStream()
            GZIPOutputStream(gz).use { it.write(plain) }
            val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, nonce))
            val sealed = cipher.doFinal(gz.toByteArray())
            val out = File(dest, "demo/${dir.name}").apply { mkdirs() }
            File(out, "workspace.bin").writeBytes(
                "APXW".toByteArray(Charsets.US_ASCII) + byteArrayOf(1) + nonce + sealed
            )
            ids.add(dir.name)
        }
        File(dest, "demo/workspaces.json").writeText(
            "{\"workspaces\":[" + ids.sorted().joinToString(",") { "\"$it\"" } + "]}"
        )
        logger.lifecycle("encrypted ${ids.size} workspace(s) -> $dest")
    }

    private fun buildBundle(dir: File): Map<String, Any?> {
        val slurper = JsonSlurper()
        fun read(file: File): Map<*, *> = slurper.parse(file) as Map<*, *>
        val manifest = read(File(dir, "workspace.json"))
        fun opt(key: String, fallback: String): String = (manifest[key] as? String) ?: fallback

        val hub = read(File(dir, opt("hub", "card.json")))
        val catalogFile = File(dir, opt("catalog", "tool_catalog.json"))
        val catalog = if (catalogFile.isFile) read(catalogFile) else null

        @Suppress("UNCHECKED_CAST")
        val cardsSpec = manifest["cards"] as? Map<String, Any?> ?: emptyMap()
        val cardDir = (cardsSpec["dir"] as? String) ?: "tools"
        val order = (cardsSpec["order"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
        val cards = order.mapNotNull { name ->
            val file = File(dir, "$cardDir/$name.json")
            if (file.isFile) mapOf("name" to name, "body" to read(file)) else null
        }

        @Suppress("UNCHECKED_CAST")
        val collectionsSpec = manifest["collections"] as? Map<String, Any?> ?: emptyMap()
        val collectionDir = (collectionsSpec["dir"] as? String) ?: "collections"
        val declared = (collectionsSpec["files"] as? List<*>)?.mapNotNull { it as? String }.orEmpty()
        val indexed = File(dir, "$collectionDir/index.json").takeIf { it.isFile }?.let {
            (read(it)["collections"] as? List<*>)?.mapNotNull { name -> name as? String }
        }.orEmpty()
        val collections = (declared + indexed).distinct().mapNotNull { file ->
            File(dir, "$collectionDir/$file").takeIf { it.isFile }?.let { read(it) }
        }

        val iconsFile = opt("icons", "").takeIf { it.isNotBlank() }?.let { File(dir, it) }
        val icons = LinkedHashMap<String, String>()
        if (iconsFile != null && iconsFile.isFile) {
            read(iconsFile).forEach { (name, value) ->
                val file = File(iconsFile.parentFile, value as? String ?: return@forEach)
                if (file.isFile) {
                    icons[name as String] = Base64.getEncoder().encodeToString(file.readBytes())
                }
            }
        }

        return linkedMapOf(
            "format" to "apex.workspace.bundle/1",
            "origin" to "builtin",
            "manifest" to manifest,
            "hub" to hub,
            "catalog" to catalog,
            "cards" to cards,
            "collections" to collections,
            "icons" to icons
        )
    }
}
