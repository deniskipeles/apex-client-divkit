package engine.tools.impl

import engine.tools.ToolArgs
import engine.tools.ToolContext
import engine.tools.ToolHandler
import engine.tools.ToolResult
import engine.tools.libsignal.LibSignalEngine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class LibSignalTool : ToolHandler {
    override val capability: String = "crypto.signal@1"

    override suspend fun invoke(args: ToolArgs, ctx: ToolContext): ToolResult {
        val op = args["op"] ?: args["action"] ?: args["operation"] ?: "encrypt"
        val peerId = args["peer_id"] ?: args["recipient"] ?: "default_peer"
        val deviceId = args.getInt("device_id", 1)
        val tenantId = ctx.workspaceId

        return try {
            when (op.lowercase()) {
                "setup_identity", "init_keys" -> {
                    val (regId, identityKeyB64) = LibSignalEngine.setupIdentity(tenantId)
                    val bundle = LibSignalEngine.exportBundle(tenantId)
                    ToolResult.Success(
                        mapOf(
                            "status" to "IDENTITY_READY",
                            "registration_id" to regId,
                            "identity_key" to identityKeyB64,
                            "signed_prekey" to bundle.signedPreKeyB64,
                            "library" to "org.whispersystems:signal-protocol-android:2.8.1"
                        )
                    )
                }

                "export_bundle", "get_prekey_bundle" -> {
                    val bundle = LibSignalEngine.exportBundle(tenantId)
                    ToolResult.Success(
                        mapOf(
                            "registration_id" to bundle.registrationId,
                            "identity_key" to bundle.identityKeyB64,
                            "signed_prekey" to bundle.signedPreKeyB64,
                            "signed_prekey_sig" to bundle.signedPreKeySignatureB64,
                            "prekey_id" to bundle.preKeyId,
                            "prekey" to bundle.preKeyB64
                        )
                    )
                }

                "create_session", "establish_session" -> {
                    // Establish is a fresh handshake: discard any ratchet persisted from a
                    // previous run so encrypt/decrypt always operate on the session shown.
                    val session = LibSignalEngine.createOrGetSession(tenantId, peerId, deviceId, fresh = true)
                    val verification = LibSignalEngine.verificationStatus(tenantId, peerId, deviceId)
                    ToolResult.Success(
                        mapOf(
                            "status" to "ESTABLISHED",
                            "session_id" to session.sessionId,
                            "peer_id" to peerId,
                            "device_id" to deviceId,
                            "safety_number" to session.fingerprint,
                            "safety_number_display" to groupSafetyNumber(session.fingerprint),
                            "fingerprint" to session.fingerprint,
                            // A new session must not inherit the previous session's ciphertext or
                            // plaintext on screen: the card maps these empty values back, so the
                            // decrypt button can never act on a stale envelope.
                            "ciphertext" to "",
                            "plaintext" to "",
                            "verification_status" to verification.status,
                            "cipher" to "DoubleRatchet + X3DH + Curve25519"
                        )
                    )
                }

                "encrypt", "send" -> {
                    val dataToEncrypt = (args["data"] ?: args["payload"] ?: args["message"])
                        ?.takeIf { it.isNotBlank() }
                        ?: return ToolResult.Failed(
                            capability,
                            "Nothing to encrypt — the card sent an empty payload"
                        )
                    val envelope = LibSignalEngine.encrypt(tenantId, peerId, deviceId, dataToEncrypt)
                    val safetyNumber = LibSignalEngine.getSafetyNumber(tenantId, peerId, deviceId)

                    ToolResult.Success(
                        mapOf(
                            "status" to "ENCRYPTED",
                            "type" to envelope.type,
                            "ciphertext" to envelope.ciphertextB64,
                            "peer_id" to peerId,
                            "safety_number" to safetyNumber,
                            "safety_number_display" to groupSafetyNumber(safetyNumber)
                        )
                    )
                }

                "decrypt", "receive" -> {
                    // The card binds user.signal_ciphertext, which is "" until Encrypt runs.
                    // An empty string is not a ciphertext: fail with a reason the user can act
                    // on instead of letting the ratchet throw ParseException("Input too small").
                    val ciphertext = (args["ciphertext"] ?: args["payload"])
                        ?.takeIf { it.isNotBlank() }
                        ?: return ToolResult.Failed(
                            capability,
                            "No ciphertext to decrypt — tap Encrypt first"
                        )
                    // 0 = infer from the envelope: a reply on an established session is a
                    // SignalMessage (type 2), not the PreKeySignalMessage (type 3) that
                    // starts it. Defaulting to 3 made the second half of a conversation fail.
                    val type = args.getInt("type", 0)
                    // Diagnostic path for the tamper test: corrupt one byte of the envelope so the
                    // AEAD tag check fails. Without this the UI cannot exercise the failure branch.
                    val payloadToDecrypt =
                        if (args["tamper"].isTruthy()) tamper(ciphertext) else ciphertext
                    val plaintext = LibSignalEngine.decrypt(tenantId, peerId, deviceId, payloadToDecrypt, type)
                    val safetyNumber = LibSignalEngine.getSafetyNumber(tenantId, peerId, deviceId)

                    ToolResult.Success(
                        mapOf(
                            "status" to "DECRYPTED",
                            "plaintext" to plaintext,
                            "peer_id" to peerId,
                            "safety_number" to safetyNumber,
                            "safety_number_display" to groupSafetyNumber(safetyNumber)
                        )
                    )
                }

                "status", "safety_numbers" -> {
                    val verification = LibSignalEngine.verificationStatus(tenantId, peerId, deviceId)
                    val hasSession = LibSignalEngine.hasSession(tenantId, peerId, deviceId)
                    val sessionId = if (hasSession) {
                        LibSignalEngine.createOrGetSession(tenantId, peerId, deviceId, fresh = false).sessionId
                    } else {
                        ""
                    }
                    ToolResult.Success(
                        mapOf(
                            "status" to if (hasSession) "ESTABLISHED" else "NO_SESSION",
                            "peer_id" to peerId,
                            "safety_number" to verification.safetyNumber,
                            "safety_number_display" to groupSafetyNumber(verification.safetyNumber),
                            "fingerprint" to verification.safetyNumber,
                            "verified" to verification.verified,
                            "verification_status" to verification.status,
                            "session_id" to sessionId
                        )
                    )
                }

                "verify_identity", "verify", "compare_safety_number" -> {
                    // The caller echoes the safety number it read off the other terminal.
                    // There is no safe default: treating a missing number as a match would
                    // authorise a peer nobody compared.
                    val provided = args["safety_number"]
                        ?: args["provided_fingerprint"]
                        ?: args["expected_fingerprint"]
                        ?: args["fingerprint"]
                        ?: return ToolResult.Failed(
                            capability,
                            "missing safety_number argument to verify"
                        )
                    if (!LibSignalEngine.hasSession(tenantId, peerId, deviceId)) {
                        return ToolResult.Failed(
                            capability,
                            "no session with peer '$peerId'; establish a session before verifying"
                        )
                    }

                    val verification = LibSignalEngine.verifyIdentity(tenantId, peerId, provided, deviceId)
                    ToolResult.Success(
                        mapOf(
                            "status" to verification.status,
                            "peer_id" to peerId,
                            "device_id" to deviceId,
                            "safety_number" to verification.safetyNumber,
                            "safety_number_display" to groupSafetyNumber(verification.safetyNumber),
                            "fingerprint" to verification.safetyNumber,
                            "provided_safety_number" to provided,
                            "verified" to verification.verified,
                            "identity_changed" to (verification.status == "KEY_CHANGED")
                        )
                    )
                }

                "reset_session" -> {
                    LibSignalEngine.resetSession(tenantId, peerId, deviceId)
                    ToolResult.Success(
                        mapOf(
                            "status" to "SESSION_RESET",
                            "peer_id" to peerId,
                            "session_id" to "",
                            "safety_number" to "",
                            "safety_number_display" to "",
                            "fingerprint" to "",
                            "verification_status" to "UNVERIFIED",
                            "ciphertext" to "",
                            "plaintext" to ""
                        )
                    )
                }

                else -> ToolResult.Failed(capability, "Unknown operation '$op'")
            }
        } catch (e: Exception) {
            android.util.Log.e("LibSignalTool", "op=$op peer=$peerId failed", e)
            ToolResult.Failed(capability, userFacingFailure(e))
        }
    }

    private fun String?.isTruthy(): Boolean =
        this?.lowercase() in setOf("1", "true", "yes", "on")

    /** Flips one character in the base64 body, leaving the envelope length unchanged. */
    private fun tamper(ciphertext: String): String {
        if (ciphertext.length < 8) return ciphertext + "A"
        val index = ciphertext.length / 2
        val original = ciphertext[index]
        val replacement = if (original == 'A') 'B' else 'A'
        return ciphertext.substring(0, index) + replacement + ciphertext.substring(index + 1)
    }

    /**
     * The card renders this string, so it must never be a raw exception class name
     * (`java.text.ParseException: Input too small:`).
     */
    private fun userFacingFailure(e: Exception): String = when (e) {
        is java.text.ParseException, is java.io.IOException, is IllegalArgumentException ->
            "Ciphertext is malformed or truncated — encrypt a fresh payload and decrypt that"
        is IllegalStateException -> e.message?.takeIf { it.isNotBlank() }
            ?: "No Signal session with this peer — establish a session first"
        // org.whispersystems.libsignal.InvalidMessageException ("Bad Mac!") is an
        // Exception, not a ParseException, so it used to fall through to the generic
        // message and never told the operator the envelope had been altered.
        is org.whispersystems.libsignal.InvalidMessageException ->
            "Ciphertext failed its authentication check — it was altered or belongs to another session"
        else -> "Signal Protocol operation failed — see the terminal log for details"
    }

    /**
     * Signal displays a 60-digit safety number as twelve groups of five. Grouping is
     * presentation only: the raw digits stay in `safety_number` so a comparison uses the
     * exact value the peer shows.
     */
    private fun groupSafetyNumber(value: String): String {
        val digits = value.filter { it.isDigit() }
        if (digits.isEmpty()) return ""
        return digits.chunked(5).joinToString(" ")
    }
}