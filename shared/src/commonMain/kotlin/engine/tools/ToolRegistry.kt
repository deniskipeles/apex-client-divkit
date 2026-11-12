package engine.tools

class ToolRegistry(private val handlers: Map<String, ToolHandler>) {

    fun manifest(): CapabilityManifest =
        CapabilityManifest(handlers.keys.associateWith { 1 })

    suspend fun dispatch(
        capability: String,
        args: ToolArgs,
        ctx: ToolContext
    ): ToolResult {
        val handler = handlers[capability]
            ?: return ToolResult.Unsupported(capability, "not compiled into this terminal")

        return try {
            handler.invoke(args, ctx)
        } catch (e: Exception) {
            ToolResult.Failed(capability, e.message ?: "Execution error")
        }
    }

    fun has(capability: String): Boolean = handlers.containsKey(capability)
    fun resolve(capability: String): ToolHandler? = handlers[capability]
    operator fun get(capability: String): ToolHandler? = handlers[capability]
}

enum class Provenance { Agency, Publisher }

/**
 * Provenance access control gate.
 */
class ProvenanceChecker {
    private val publisherAllowlist = setOf(
        "device.haptics@1",
        "device.beeper@1",
        "vision.scan_code@1",
        "vision.capture_photo@1",
        "vision.identify_product@1",
        "vision.embed_image@1",
        "vision.remove_background@1",
        "secure.send@1",
        "secure.status@1",
        "secure.verify_identity@1",
        "auth.biometrics@1",
        "device.clipboard@1",
        "network.push@1",
        "storage.offline_cache@1",
        "network.realtime@1"
    )

    fun allows(provenance: Provenance, capability: String): Boolean = when (provenance) {
        Provenance.Agency -> true
        Provenance.Publisher -> capability in publisherAllowlist
    }
}
