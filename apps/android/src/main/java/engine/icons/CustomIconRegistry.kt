package engine.icons

/**
 * Runtime registry for workspace icon packs.
 *
 * Icons travel as base64 PNG/JPEG so the renderer needs no SVG or network dependency.
 * Dev builds fill this from an imported zip; packaged workspaces fill it from their
 * `icons/icons.json`.
 */
object CustomIconRegistry {

    private val icons = LinkedHashMap<String, ByteArray>()

    fun setAll(source: Map<String, ByteArray>) {
        icons.clear()
        source.forEach { (name, bytes) -> if (bytes.isNotEmpty()) icons[name] = bytes }
    }

    fun add(name: String, bytes: ByteArray) {
        if (name.isNotBlank() && bytes.isNotEmpty()) icons[name] = bytes
    }

    fun fromBase64(map: Map<String, String>): Map<String, ByteArray> = map.mapNotNull { (name, value) ->
        runCatching { name to java.util.Base64.getDecoder().decode(value) }.getOrNull()
    }.toMap()

    fun bytes(name: String): ByteArray? = icons[name]

    fun contains(name: String): Boolean = icons.containsKey(name)

    fun names(): List<String> = icons.keys.toList()

    fun clear() = icons.clear()
}
