package engine.tools.impl

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import engine.crypto.currentTimeMillis
import engine.state.DivVariableValue
import engine.state.EngineStateBridge
import engine.tools.ToolArgs
import engine.tools.ToolContext
import engine.tools.ToolHandler
import engine.tools.ToolResult
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Generic Optical Scanner capability ("vision.scan_code@1").
 * Decodes 1D barcodes and 2D QR codes with target_format defaulting to "both".
 */
class ScanCodeTool : ToolHandler {
    override val capability = "vision.scan_code@1"
    override val requiredPermissions = setOf("android.permission.CAMERA")

    override suspend fun invoke(args: ToolArgs, ctx: ToolContext): ToolResult {
        val targetFormat = args["target_format"] ?: args["format"] ?: args["mode"] ?: "both"
        val payload = buildJsonObject {
            put("target_format", targetFormat)
            args["simulated_code"]?.let { put("simulated_code", it) }
            args["raw_value"]?.let { put("raw_value", it) }
            args["code"]?.let { put("code", it) }
            args["beep"]?.let { put("beep", it) }
        }

        val outcome = engine.tools.NativeToolsSuite.execute(
            url = "tools://scan/scan",
            payload = payload,
            tenantId = ctx.workspaceId
        )

        return if (outcome.resultData.jsonObject["status"]?.jsonPrimitive?.contentOrNull == "FORMAT_MISMATCH") {
            ToolResult.Failed(capability, outcome.summary)
        } else {
            val resultMap = outcome.resultData.jsonObject.mapValues { (_, v) ->
                v.jsonPrimitive.contentOrNull ?: v.toString()
            }
            ToolResult.Success(resultMap)
        }
    }
}

/**
 * 8.3 vision.embed_image@1
 */
class EmbedImageTool : ToolHandler {
    override val capability = "vision.embed_image@1"

    override suspend fun invoke(args: ToolArgs, ctx: ToolContext): ToolResult {
        val imageId = args["image"] ?: args["image_id"]
            ?: return ToolResult.Failed(capability, "missing image argument")

        val uri = args["uri"] ?: imageId
        val androidContext = engine.db.currentAndroidContext?.invoke()
            ?: return ToolResult.Unsupported(
                capability,
                "No Android context available to load image embeddings"
            )
        val bitmap = runCatching { engine.tools.vector.VectorIO.decodeImage(androidContext, uri) }.getOrNull()
            ?: return ToolResult.Failed(capability, "Unable to load image: $uri")
        return try {
            val vector = engine.tools.vector.ImageVectorizer.embed(androidContext, bitmap)
            ToolResult.Success(
                mapOf(
                    "state" to "ok",
                    "image_id" to imageId,
                    "dim" to vector.size,
                    "vector_b64" to engine.tools.vector.VectorIO.packFloats(vector),
                    "model" to "MobileNetV4-small INT8"
                )
            )
        } finally {
            bitmap.recycle()
        }
    }
}

/**
 * 8.4 vision.remove_background@1
 */
class RemoveBackgroundTool : ToolHandler {
    override val capability = "vision.remove_background@1"

    override suspend fun invoke(args: ToolArgs, ctx: ToolContext): ToolResult {
        val imageUri = args["uri"] ?: args["image"] ?: args["image_id"] ?: args["base64"]
            ?: return ToolResult.Failed(capability, "missing image argument ('uri', 'image', or 'base64')")
        val threshold = args["threshold"]?.toDoubleOrNull()
            ?: args["saliency_cutoff"]?.toDoubleOrNull()
            ?: 0.45

        return try {
            val result = engine.tools.image.ImageManipulationEngine.process(
                tenantId = ctx.workspaceId,
                inputUriOrBase64 = imageUri,
                operations = listOf(
                    buildJsonObject {
                        put("op", "remove_background")
                        put("threshold", threshold)
                    }
                ),
                encoding = (args["encoding"] ?: "uri").lowercase(),
                outputFormat = "png"
            )
            ToolResult.Success(
                mapOf(
                    "state" to "ok",
                    "status" to "SUCCESS",
                    "image_id" to result.imageId,
                    "uri" to result.uri,
                    "mask_uri" to result.uri,
                    "width" to result.width,
                    "height" to result.height,
                    "size_bytes" to result.sizeBytes,
                    "format" to "PNG_TRANSPARENT"
                )
            )
        } catch (e: Exception) {
            ToolResult.Failed(capability, e.message ?: "Background removal failed")
        }
    }
}

/**
 * 8.5 vision.identify_product@1 — generic on-device product classification.
 * Delegates to the unopinionated MobileNetV4 image classifier; the capability
 * itself embeds no domain catalogue or vendor-specific pipeline.
 */
class ProductClassifierTool : ToolHandler {
    override val capability = "vision.identify_product@1"

    override suspend fun invoke(args: ToolArgs, ctx: ToolContext): ToolResult {
        val imageId = args["uri"] ?: args["image"] ?: args["image_id"] ?: args["base64"] ?: "img_snapshot_live"
        val payload = buildJsonObject {
            put("uri", imageId)
            put("image", imageId)
            put("image_id", imageId)
            args["base64"]?.let { put("base64", it) }
            args["top_k"]?.let { put("top_k", it) }
            args["min_confidence"]?.let { put("min_confidence", it) }
        }

        val outcome = engine.tools.NativeToolsSuite.execute(
            url = "tools://MobileNetV4-small/classify",
            payload = payload,
            tenantId = ctx.workspaceId
        )

        val resultMap = outcome.resultData.jsonObject.mapValues { (_, v) ->
            v.jsonPrimitive.contentOrNull ?: v.toString()
        }
        return ToolResult.Success(mapOf("state" to "ok", "image_id" to imageId) + resultMap)
    }
}

/**
 * 8.6 secure.send@1 (LibSignal encrypted transport)
 */
class SecureSendTool : ToolHandler {
    override val capability = "secure.send@1"

    override suspend fun invoke(args: ToolArgs, ctx: ToolContext): ToolResult {
        val payload = args["payload_b64"] ?: args["payload"] ?: args["message"]
            ?: return ToolResult.Failed(capability, "missing payload argument")
        val peerId = args["peer_id"] ?: "${ctx.workspaceId}_peer"
        val deviceId = args.getInt("device_id", 1)

        return try {
            val envelope = engine.tools.libsignal.LibSignalEngine.encrypt(ctx.workspaceId, peerId, deviceId, payload)
            val session = engine.tools.libsignal.LibSignalEngine.createOrGetSession(ctx.workspaceId, peerId, deviceId)
            ToolResult.Success(
                mapOf(
                    "state" to "ok",
                    "status" to "ENCRYPTED",
                    "session_id" to session.sessionId,
                    "peer_id" to peerId,
                    "ciphertext_b64" to envelope.ciphertextB64,
                    "message_type" to envelope.type,
                    "counter" to session.sendCounter
                )
            )
        } catch (e: Exception) {
            ToolResult.Failed(capability, e.message ?: "encryption failed")
        }
    }
}

/**
 * 8.7 secure.status@1 (LibSignal Session Status & Ratchet Telemetry)
 */
class SecureStatusTool : ToolHandler {
    override val capability = "secure.status@1"

    override suspend fun invoke(args: ToolArgs, ctx: ToolContext): ToolResult {
        val peerId = args["peer_id"] ?: "${ctx.workspaceId}_peer"
        val deviceId = args.getInt("device_id", 1)
        // Status must be a read: reporting ESTABLISHED after silently creating a session
        // made an untouched peer look handshaked.
        if (!engine.tools.libsignal.LibSignalEngine.hasSession(ctx.workspaceId, peerId, deviceId)) {
            return ToolResult.Success(
                mapOf(
                    "state" to "no_session",
                    "status" to "NO_SESSION",
                    "peer_id" to peerId,
                    "session_id" to "",
                    "fingerprint" to "",
                    "is_verified" to false,
                    "send_counter" to 0,
                    "recv_counter" to 0,
                    "cipher" to "AES-256-GCM + X25519"
                )
            )
        }
        val session = engine.tools.libsignal.LibSignalEngine.createOrGetSession(
            ctx.workspaceId, peerId, deviceId
        )

        return ToolResult.Success(
            mapOf(
                "state" to "ok",
                "status" to "ESTABLISHED",
                "session_id" to session.sessionId,
                "peer_id" to peerId,
                "fingerprint" to session.fingerprint,
                "is_verified" to session.isVerified,
                "send_counter" to session.sendCounter,
                "recv_counter" to session.recvCounter,
                "cipher" to "AES-256-GCM + X25519"
            )
        )
    }
}

/**
 * 8.8 secure.verify_identity@1 (LibSignal Peer Fingerprint & Safety Number Verification)
 */
class SecureVerifyIdentityTool : ToolHandler {
    override val capability = "secure.verify_identity@1"

    override suspend fun invoke(args: ToolArgs, ctx: ToolContext): ToolResult {
        val peerId = args["peer_id"] ?: "${ctx.workspaceId}_peer"
        val deviceId = args.getInt("device_id", 1)
        val provided = args["provided_fingerprint"]
            ?: args["safety_number"]
            ?: return ToolResult.Failed(
                capability,
                "missing provided_fingerprint argument to verify"
            )
        if (!engine.tools.libsignal.LibSignalEngine.hasSession(ctx.workspaceId, peerId, deviceId)) {
            return ToolResult.Failed(
                capability,
                "no session with peer '$peerId'; establish a session before verifying"
            )
        }

        // Compare against the peer's current fingerprint; a missing match is a refusal, not
        // a default success.
        val verification = engine.tools.libsignal.LibSignalEngine.verifyIdentity(
            ctx.workspaceId, peerId, provided, deviceId
        )

        return ToolResult.Success(
            mapOf(
                "state" to if (verification.verified) "verified" else "mismatch",
                "peer_id" to peerId,
                "fingerprint" to verification.safetyNumber,
                "provided_fingerprint" to provided,
                "verified" to verification.verified,
                "timestamp" to currentTimeMillis()
            )
        )
    }
}

/**
 * 8.10 auth.biometrics@1 (Supervisor Biometric Authentication Gate)
 *
 * A gate is only a gate if it can refuse. The previous implementation computed
 * `pin == null || pin == "1234" || pin == "0000" || pin.isNotBlank()`, which accepts every
 * possible input including no PIN at all, so every refund or void was "supervisor approved".
 * Verification now delegates to the platform credential/biometric prompt through [sink].
 * Without a sink (unit tests, headless hosts) the gate fails closed with UNSUPPORTED rather
 * than granting access it never verified.
 */
class BiometricsTool : ToolHandler {
    override val capability = "auth.biometrics@1"

    data class Request(val prompt: String, val requiredRole: String)

    data class Verified(val authenticated: Boolean, val method: String)

    override suspend fun invoke(args: ToolArgs, ctx: ToolContext): ToolResult {
        val prompt = args["prompt"] ?: "Supervisor Authorization Required"
        val requiredRole = args["required_role"] ?: "SUPERVISOR"

        val verifier = sink ?: return ToolResult.Unsupported(
            capability,
            "No biometric/credential host is installed, so supervisor identity cannot be verified"
        )

        val verified = try {
            verifier(Request(prompt, requiredRole))
        } catch (e: Exception) {
            return ToolResult.Failed(capability, e.message ?: "Biometric prompt failed")
        } ?: return ToolResult.Unsupported(
            capability,
            "Biometric prompt was not completed; supervisor identity unverified"
        )

        if (!verified.authenticated) {
            return ToolResult.Failed(capability, "Supervisor authentication was cancelled or denied")
        }

        return ToolResult.Success(
            mapOf(
                "state" to "ok",
                "status" to "AUTHORIZED",
                "authenticated" to true,
                "role" to requiredRole,
                "auth_token" to "bio_tok_${UUID.randomUUID().toString().take(10)}",
                "prompt" to prompt,
                "method" to verified.method,
                "timestamp" to currentTimeMillis()
            )
        )
    }

    companion object {
        /** Installed by the host Activity; returns null when the prompt could not be shown. */
        var sink: (suspend (Request) -> Verified?)? = null
    }
}

/**
 * 8.11 device.clipboard@1 (System Clipboard Tool)
 */
class ClipboardTool : ToolHandler {
    override val capability = "device.clipboard@1"

    override suspend fun invoke(args: ToolArgs, ctx: ToolContext): ToolResult {
        val op = args["op"] ?: args["action"] ?: "copy"
        val text = args["text"] ?: args["content"] ?: ""

        val androidContext = engine.db.currentAndroidContext?.invoke()
        var currentClipboard = text

        if (androidContext != null) {
            try {
                val cm = androidContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                if (cm != null) {
                    when (op) {
                        "copy" -> {
                            val clip = ClipData.newPlainText("POS SDUI Data", text)
                            cm.setPrimaryClip(clip)
                            currentClipboard = text
                        }
                        "paste" -> {
                            val clip = cm.primaryClip
                            if (clip != null && clip.itemCount > 0) {
                                currentClipboard = clip.getItemAt(0).text?.toString() ?: ""
                            }
                        }
                        "clear" -> {
                            val clip = ClipData.newPlainText("", "")
                            cm.setPrimaryClip(clip)
                            currentClipboard = ""
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        return ToolResult.Success(
            mapOf(
                "state" to "ok",
                "status" to if (currentClipboard.isEmpty()) "EMPTY" else "SUCCESS",
                "op" to op,
                "text" to currentClipboard,
                "length" to currentClipboard.length
            )
        )
    }
}

/**
 * 8.12 network.push@1 (Background Alerts & Push Notifications Tool)
 */
class PushAlertTool : ToolHandler {
    override val capability = "network.push@1"

    override suspend fun invoke(args: ToolArgs, ctx: ToolContext): ToolResult {
        val title = args["title"] ?: "Kitchen / POS Alert"
        val message = args["message"] ?: "New order ready for pickup"
        val channel = args["channel"] ?: "orders_dispatch"
        val priority = args["priority"] ?: "high"
        val notifId = "push_${UUID.randomUUID().toString().take(8)}"

        EngineStateBridge.pushUpdate("user.last_push_alert", DivVariableValue.Str("$title: $message"))

        val androidContext = engine.db.currentAndroidContext?.invoke()
        if (androidContext != null) {
            try {
                val nm = androidContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                if (nm != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        val importance = if (priority.equals("high", ignoreCase = true)) {
                            NotificationManager.IMPORTANCE_HIGH
                        } else {
                            NotificationManager.IMPORTANCE_DEFAULT
                        }
                        val notifChannel = NotificationChannel(channel, "POS Alerts: $channel", importance)
                        nm.createNotificationChannel(notifChannel)
                    }
                    val notification = NotificationCompat.Builder(androidContext, channel)
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentTitle(title)
                        .setContentText(message)
                        .setPriority(
                            if (priority.equals("high", ignoreCase = true)) NotificationCompat.PRIORITY_HIGH
                            else NotificationCompat.PRIORITY_DEFAULT
                        )
                        .setAutoCancel(true)
                        .build()
                    nm.notify(notifId.hashCode(), notification)
                }
            } catch (_: Exception) {}
        }

        return ToolResult.Success(
            mapOf(
                "state" to "ok",
                "notification_id" to notifId,
                "title" to title,
                "message" to message,
                "channel" to channel,
                "priority" to priority,
                "status" to "LOGGED_TO_LEDGER",
                "timestamp" to currentTimeMillis()
            )
        )
    }
}

/**
 * 8.13 storage.offline_cache@1 (Offline Page & Schema Caching Tool)
 */
class OfflineCacheTool : ToolHandler {
    override val capability = "storage.offline_cache@1"

    private companion object {
        const val DIR = "offline_cache"
    }

    override suspend fun invoke(args: ToolArgs, ctx: ToolContext): ToolResult {
        val op = (args["op"] ?: args["operation"] ?: "get_stats").lowercase()
        val schemaId = args["schema_id"] ?: ctx.workspaceId
        val context = engine.db.currentAndroidContext?.invoke()
            ?: return ToolResult.Unsupported(capability, "no storage host installed for the offline cache")

        val dir = java.io.File(context.filesDir, DIR).apply { mkdirs() }
        val store = java.io.File(dir, "$schemaId.json")

        val entries = readEntries(store)
        return when (op) {
            "cache", "put", "store" -> {
                val key = args["key"] ?: args["url"] ?: args["path"]
                    ?: return ToolResult.Failed(capability, "missing key argument")
                val content = args["content"] ?: args["body"] ?: args["text"]
                    ?: return ToolResult.Failed(capability, "missing content argument")
                entries[key] = mapOf("content" to content, "saved_at" to System.currentTimeMillis())
                writeEntries(store, entries)
                success(op, schemaId, entries, "CACHED_OFFLINE_READY")
            }
            "clear", "evict" -> {
                store.delete()
                success(op, schemaId, emptyMap(), "CACHE_CLEARED")
            }
            "get_stats", "stats", "status" -> success(op, schemaId, entries, "CACHED_OFFLINE_READY")
            else -> ToolResult.Failed(capability, "Unsupported offline cache operation '$op'")
        }
    }

    private fun success(
        op: String,
        schemaId: String,
        entries: Map<String, Map<String, Any?>>,
        status: String
    ): ToolResult {
        val bytes = entries.values.sumOf { (it["content"] as? String)?.toByteArray(Charsets.UTF_8)?.size ?: 0 }
        return ToolResult.Success(
            mapOf(
                "state" to "ok",
                "op" to op,
                "schema_id" to schemaId,
                "cached_pages" to entries.size,
                "cache_size_kb" to if (bytes == 0) 0 else (bytes + 1023) / 1024,
                "keys" to entries.keys.toList(),
                "status" to status
            )
        )
    }

    private fun readEntries(store: java.io.File): MutableMap<String, Map<String, Any?>> {
        if (!store.exists()) return mutableMapOf()
        return try {
            val root = kotlinx.serialization.json.Json.parseToJsonElement(store.readText())
            val obj = root as? kotlinx.serialization.json.JsonObject ?: return mutableMapOf()
            obj.entries.associate { (key, value) ->
                val page = value as? kotlinx.serialization.json.JsonObject
                key to mapOf<String, Any?>(
                    "content" to (page?.get("content") as? kotlinx.serialization.json.JsonPrimitive)?.content,
                    "saved_at" to (page?.get("saved_at") as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull()
                )
            }.toMutableMap()
        } catch (_: Exception) {
            mutableMapOf()
        }
    }

    private fun writeEntries(store: java.io.File, entries: Map<String, Map<String, Any?>>) {
        val root = kotlinx.serialization.json.buildJsonObject {
            entries.forEach { (key, page) ->
                put(key, kotlinx.serialization.json.buildJsonObject {
                    put("content", kotlinx.serialization.json.JsonPrimitive(page["content"] as? String ?: ""))
                    put("saved_at", kotlinx.serialization.json.JsonPrimitive((page["saved_at"] as? Long) ?: 0L))
                })
            }
        }
        store.writeText(root.toString())
    }
}

/**
 * 8.14 network.realtime@1 (Realtime WebSocket / SSE Engine Tool)
 */
class RealtimeWsTool : ToolHandler {
    override val capability = "network.realtime@1"

    companion object {
        private val channelStates = java.util.concurrent.ConcurrentHashMap<String, String>()
        private val lastMessages = java.util.concurrent.ConcurrentHashMap<String, String>()

        /**
         * Installed by a host that can actually open a socket. Without it the tool cannot
         * claim a connection: `connect` reports UNSUPPORTED instead of flipping a flag.
         */
        var transport: ((channel: String, url: String) -> Boolean)? = null

        fun resetForTest() {
            channelStates.clear()
            lastMessages.clear()
            transport = null
        }
    }

    override suspend fun invoke(args: ToolArgs, ctx: ToolContext): ToolResult {
        val op = (args["op"] ?: args["operation"] ?: "connect").lowercase()
        val channel = args["channel"] ?: "kitchen_stream"
        val message = args["message"] ?: "ping"
        val url = args["url"] ?: args["endpoint"] ?: args["target"]
        val channelKey = "${ctx.workspaceId}:$channel"

        val state = when (op) {
            "connect" -> {
                val opener = transport ?: return ToolResult.Unsupported(
                    capability,
                    "no websocket transport installed — this build has no realtime socket"
                )
                val endpoint = url ?: return ToolResult.Failed(
                    capability,
                    "missing url argument — connect needs a ws:// endpoint"
                )
                val opened = try {
                    opener(channel, endpoint)
                } catch (error: Exception) {
                    false
                }
                val next = if (opened) "CONNECTED" else "FAILED"
                channelStates[channelKey] = next
                next
            }
            "disconnect", "close" -> {
                channelStates[channelKey] = "DISCONNECTED"
                "DISCONNECTED"
            }
            "send", "publish" -> {
                if (channelStates[channelKey] != "CONNECTED") {
                    return failedNotConnected(channel)
                }
                lastMessages[channelKey] = message
                "CONNECTED"
            }
            "subscribe", "join" -> {
                if (channelStates[channelKey] != "CONNECTED") {
                    return failedNotConnected(channel)
                }
                "CONNECTED"
            }
            "status", "state" -> channelStates.getOrDefault(channelKey, "DISCONNECTED")
            else -> return ToolResult.Failed(capability, "Unsupported realtime operation '$op'")
        }

        EngineStateBridge.pushUpdate("user.realtime_connection_state", DivVariableValue.Str(state))
        EngineStateBridge.pushUpdate("user.realtime_channel", DivVariableValue.Str(channel))

        if (state == "FAILED") {
            return ToolResult.Failed(capability, "could not open websocket channel $channel")
        }

        return ToolResult.Success(
            mapOf(
                "state" to "ok",
                "op" to op,
                "channel" to channel,
                "connection_state" to state,
                "last_message" to (lastMessages[channelKey] ?: ""),
                "status" to state
            )
        )
    }

    private fun failedNotConnected(channel: String): ToolResult =
        ToolResult.Failed(capability, "channel $channel is not connected — connect first")
}

