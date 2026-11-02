package engine.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Root configuration object loaded from the signed `config.json` payload.
 *
 * Security invariant: this object must never be deserialized from unverified
 * bytes. Always pass the verified payload from [engine.crypto.PayloadVerifier]
 * into [EngineConfig.fromVerifiedPayload].
 */
@Serializable
data class EngineConfig(
    @SerialName("tenant_id") val tenantId: String,
    @SerialName("schema_version") val schemaVersion: String,
    val mode: Mode,
    @SerialName("edge_endpoint") val edgeEndpoint: String,
    @SerialName("verify_public_key") val verifyPublicKey: String,
    val capabilities: Set<String> = emptySet(),
    @SerialName("sync_interval_ms") val syncIntervalMs: Long = 900_000L,
    @SerialName("db_schema_version") val dbSchemaVersion: Int = 1
) {
    enum class Mode { OFFLINE, HYBRID, ONLINE }

    init {
        require(TENANT_ID_REGEX.matches(tenantId)) {
            "Invalid tenant_id: must match [a-zA-Z0-9_-]{1,64}"
        }
        require(edgeEndpoint.startsWith("https://")) {
            "edge_endpoint must be HTTPS"
        }
    }

    companion object {
        private val TENANT_ID_REGEX = Regex("^[a-zA-Z0-9_-]{1,64}$")

        /** Safe factory — call only with a payload already HMAC/Ed25519-verified. */
        fun fromVerifiedPayload(payload: String): EngineConfig =
            kotlinx.serialization.json.Json.decodeFromString(serializer(), payload)
    }
}
