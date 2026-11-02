package engine.crypto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Verifies remote envelopes before any JSON reaches DivKit.
 *
 * Envelope shape (produced by edge `signing.ts`):
 * ```json
 * {
 *   "tenant": "acme",
 *   "schema_version": "1.0",
 *   "iat": 1712345678901,
 *   "jti": "uuid-v4",
 *   "payload": { ... },
 *   "signature": "base64-ed25519"
 * }
 * ```
 *
 * Threat model addressed:
 *  - MITM / forged payloads → Ed25519 signature
 *  - Replay → jti LRU + iat window
 *  - Cross-tenant confusion → tenant binding
 */
class PayloadVerifier(
    private val expectedTenant: String,
    private val publicKeyDer: String,
    private val maxSkewMs: Long = 300_000L,
    private val maxSeenJti: Int = 10_000
) {
    private val json = Json { ignoreUnknownKeys = false }
    private val seenJti = object : LinkedHashSet<String>() {
        override fun add(element: String): Boolean {
            if (size >= maxSeenJti) {
                val itr = iterator()
                if (itr.hasNext()) { itr.next(); itr.remove() }
            }
            return super.add(element)
        }
    }
    private val lock = kotlinx.coroutines.sync.Mutex()
    private val loadedKey by lazy { Ed25519.loadPublicKey(publicKeyDer) }

    @Serializable
    data class Envelope(
        val tenant: String,
        @kotlinx.serialization.SerialName("schema_version") val schemaVersion: String,
        val iat: Long,
        val jti: String,
        val payload: kotlinx.serialization.json.JsonElement,
        val signature: String
    )

    /**
     * @return the verified payload as a raw JSON string ready for DivKit.
     * @throws SecurityException on any verification failure.
     */
    suspend fun verify(rawEnvelope: String): String {
        val env = try {
            json.decodeFromString<Envelope>(rawEnvelope)
        } catch (e: Exception) {
            throw SecurityException("Malformed envelope: ${e.message}")
        }
        if (env.tenant != expectedTenant) {
            throw SecurityException("Tenant mismatch: expected $expectedTenant, got ${env.tenant}")
        }
        val canonicalMap = sortedMapOf(
            "tenant" to env.tenant,
            "schema_version" to env.schemaVersion,
            "iat" to env.iat,
            "jti" to env.jti,
            "payload" to jsonToPlain(env.payload)
        )
        val canonicalBytes = CanonicalJson.canonicalize(canonicalMap).encodeToByteArray()
        if (!Ed25519.verify(publicKeyDer, canonicalBytes, env.signature)) {
            throw SecurityException("Signature verification failed")
        }
        val now = currentTimeMillis()
        if (kotlin.math.abs(now - env.iat) > maxSkewMs) {
            throw SecurityException("Envelope expired (iat=${env.iat}, now=$now)")
        }
        lock.lock()
        try {
            if (!seenJti.add(env.jti)) {
                throw SecurityException("Replay detected: jti=${env.jti}")
            }
        } finally {
            lock.unlock()
        }
        return env.payload.toString()
    }

    private fun jsonToPlain(el: kotlinx.serialization.json.JsonElement): Any? = when (el) {
        is kotlinx.serialization.json.JsonNull -> null
        is kotlinx.serialization.json.JsonPrimitive -> when {
            el.isString -> el.content
            el.content == "true" || el.content == "false" -> el.content.toBoolean()
            el.content.toLongOrNull() != null -> el.content.toLong()
            else -> el.content.toDouble()
        }
        is kotlinx.serialization.json.JsonArray -> el.map { jsonToPlain(it) }
        is kotlinx.serialization.json.JsonObject -> el.mapValues { jsonToPlain(it.value) }
    }
}

expect fun currentTimeMillis(): Long
