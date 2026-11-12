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
                    val hasSession = LibSignalEngine.hasSession(tenantId, peerId, deviceId)
                    if (!hasSession) {
                        val bundle = LibSignalEngine.exportBundle(tenantId)
                        LibSignalEngine.processPreKeyBundle(tenantId, peerId, deviceId, bundle)
                    }
                    val safetyNumber = LibSignalEngine.getSafetyNumber(tenantId, peerId, deviceId)
                    ToolResult.Success(
                        mapOf(
                            "status" to "ESTABLISHED",
                            "peer_id" to peerId,
                            "device_id" to deviceId,
                            "safety_number" to safetyNumber,
                            "cipher" to "DoubleRatchet + X3DH + Curve25519"
                        )
                    )
                }

                "encrypt", "send" -> {
                    val dataToEncrypt = args["data"] ?: args["payload"] ?: args["message"]
                        ?: return ToolResult.Failed(capability, "Missing 'data' or 'payload' to encrypt")
                    val envelope = LibSignalEngine.encrypt(tenantId, peerId, deviceId, dataToEncrypt)
                    val safetyNumber = LibSignalEngine.getSafetyNumber(tenantId, peerId, deviceId)

                    ToolResult.Success(
                        mapOf(
                            "status" to "ENCRYPTED",
                            "type" to envelope.type,
                            "ciphertext" to envelope.ciphertextB64,
                            "peer_id" to peerId,
                            "safety_number" to safetyNumber
                        )
                    )
                }

                "decrypt", "receive" -> {
                    val ciphertext = args["ciphertext"] ?: args["payload"]
                        ?: return ToolResult.Failed(capability, "Missing 'ciphertext' to decrypt")
                    val type = args.getInt("type", 3)
                    val plaintext = LibSignalEngine.decrypt(tenantId, peerId, deviceId, ciphertext, type)
                    val safetyNumber = LibSignalEngine.getSafetyNumber(tenantId, peerId, deviceId)

                    ToolResult.Success(
                        mapOf(
                            "status" to "DECRYPTED",
                            "plaintext" to plaintext,
                            "peer_id" to peerId,
                            "safety_number" to safetyNumber
                        )
                    )
                }

                "status", "safety_numbers" -> {
                    val safetyNumber = LibSignalEngine.getSafetyNumber(tenantId, peerId, deviceId)
                    val hasSession = LibSignalEngine.hasSession(tenantId, peerId, deviceId)
                    ToolResult.Success(
                        mapOf(
                            "status" to if (hasSession) "ESTABLISHED" else "NO_SESSION",
                            "peer_id" to peerId,
                            "safety_number" to safetyNumber
                        )
                    )
                }

                "reset_session" -> {
                    LibSignalEngine.resetSession(tenantId, peerId, deviceId)
                    ToolResult.Success(
                        mapOf("status" to "SESSION_RESET", "peer_id" to peerId)
                    )
                }

                else -> ToolResult.Failed(capability, "Unknown operation '$op'")
            }
        } catch (e: Exception) {
            ToolResult.Failed(capability, e.message ?: "Signal Protocol error")
        }
    }
}