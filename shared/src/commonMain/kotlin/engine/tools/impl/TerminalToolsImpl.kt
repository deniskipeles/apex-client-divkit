package engine.tools.impl

import engine.crypto.currentTimeMillis
import engine.tools.ToolArgs
import engine.tools.ToolContext
import engine.tools.ToolHandler
import engine.tools.ToolResult
import kotlinx.serialization.json.buildJsonObject
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

class CapturePhotoTool : ToolHandler {
    override val capability = "vision.capture_photo@1"
    override val requiredPermissions = setOf("android.permission.CAMERA")

    override suspend fun invoke(args: ToolArgs, ctx: ToolContext): ToolResult {
        val lens = args["lens"] ?: "back"
        val id = "img_capture_01"
        return ToolResult.Success(mapOf("state" to "ok", "image_id" to id, "lens" to lens))
    }
}

class EmbedImageTool : ToolHandler {
    override val capability = "vision.embed_image@1"

    override suspend fun invoke(args: ToolArgs, ctx: ToolContext): ToolResult {
        val imageId = args["image"] ?: args["image_id"]
            ?: return ToolResult.Failed(capability, "missing image")
        return ToolResult.Success(mapOf("state" to "ok", "image_id" to imageId, "dim" to 512))
    }
}

class RemoveBackgroundTool : ToolHandler {
    override val capability = "vision.remove_background@1"

    override suspend fun invoke(args: ToolArgs, ctx: ToolContext): ToolResult {
        val imageId = args["image"] ?: args["image_id"]
            ?: return ToolResult.Failed(capability, "missing image")
        return ToolResult.Success(mapOf("state" to "ok", "image_id" to "cutout_$imageId", "format" to "PNG_TRANSPARENT"))
    }
}

class ProductClassifierTool : ToolHandler {
    override val capability = "vision.identify_product@1"

    override suspend fun invoke(args: ToolArgs, ctx: ToolContext): ToolResult {
        val imageId = args["image"] ?: args["image_id"] ?: "img_snapshot_live"
        val payload = buildJsonObject {
            put("image_id", imageId)
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

class SecureSendTool : ToolHandler {
    override val capability = "secure.send@1"

    override suspend fun invoke(args: ToolArgs, ctx: ToolContext): ToolResult {
        val payload = args["payload_b64"] ?: args["payload"]
            ?: return ToolResult.Failed(capability, "missing payload")
        return ToolResult.Success(
            mapOf("state" to "ok", "ciphertext_b64" to "encrypted_ct_token")
        )
    }
}

class SecureStatusTool : ToolHandler {
    override val capability = "secure.status@1"

    override suspend fun invoke(args: ToolArgs, ctx: ToolContext): ToolResult {
        val sessionId = args["session_id"] ?: "sig_sess_${ctx.workspaceId.take(8)}"
        val peerId = args["peer_id"] ?: "${ctx.workspaceId}_peer"
        return ToolResult.Success(
            mapOf(
                "state" to "ok",
                "status" to "ESTABLISHED",
                "session_id" to sessionId,
                "peer_id" to peerId,
                "ratchet_index" to 3,
                "cipher" to "AES-256-GCM",
                "fingerprint" to "4E-91-A2-5F-C0-83",
                "verified" to true
            )
        )
    }
}

class SecureVerifyIdentityTool : ToolHandler {
    override val capability = "secure.verify_identity@1"

    override suspend fun invoke(args: ToolArgs, ctx: ToolContext): ToolResult {
        val peerId = args["peer_id"] ?: "${ctx.workspaceId}_peer"
        val expected = args["expected_fingerprint"] ?: args["fingerprint"] ?: "4E-91-A2-5F-C0-83"
        val provided = args["provided_fingerprint"] ?: args["safety_number"] ?: expected
        val matches = provided.equals(expected, ignoreCase = true)

        return ToolResult.Success(
            mapOf(
                "state" to if (matches) "verified" else "mismatch",
                "peer_id" to peerId,
                "fingerprint" to expected,
                "verified" to matches,
                "verification_time" to currentTimeMillis()
            )
        )
    }
}

class BeeperTool : ToolHandler {
    override val capability = "device.beeper@1"

    override suspend fun invoke(args: ToolArgs, ctx: ToolContext): ToolResult {
        val tone = args["tone"] ?: "ring"
        val durationMs = args.getInt("duration_ms", 180)
        val frequencyHz = when (tone.lowercase()) {
            "alert" -> 2400.0
            "error" -> 600.0
            else -> args.getDouble("frequency_hz", 1200.0)
        }
        return ToolResult.Success(
            mapOf(
                "state" to "ok",
                "tone" to tone,
                "frequency_hz" to frequencyHz,
                "duration_ms" to durationMs
            )
        )
    }
}

class BiometricsTool : ToolHandler {
    override val capability = "auth.biometrics@1"

    override suspend fun invoke(args: ToolArgs, ctx: ToolContext): ToolResult {
        val role = args["required_role"] ?: "SUPERVISOR"
        return ToolResult.Success(
            mapOf(
                "state" to "ok",
                "authenticated" to true,
                "role" to role,
                "auth_token" to "bio_tok_verified"
            )
        )
    }
}

class ClipboardTool : ToolHandler {
    override val capability = "device.clipboard@1"

    override suspend fun invoke(args: ToolArgs, ctx: ToolContext): ToolResult {
        val op = args["op"] ?: "copy"
        val text = args["text"] ?: ""
        return ToolResult.Success(
            mapOf("state" to "ok", "op" to op, "text" to text, "length" to text.length)
        )
    }
}

class PushAlertTool : ToolHandler {
    override val capability = "network.push@1"

    override suspend fun invoke(args: ToolArgs, ctx: ToolContext): ToolResult {
        val title = args["title"] ?: "Kitchen / POS Alert"
        val channel = args["channel"] ?: "orders"
        return ToolResult.Success(
            mapOf("state" to "ok", "title" to title, "channel" to channel, "status" to "DELIVERED")
        )
    }
}

class OfflineCacheTool : ToolHandler {
    override val capability = "storage.offline_cache@1"

    override suspend fun invoke(args: ToolArgs, ctx: ToolContext): ToolResult {
        return ToolResult.Success(
            mapOf("state" to "ok", "cached_pages" to 4, "cache_size_kb" to 156, "status" to "CACHED_OFFLINE_READY")
        )
    }
}

class RealtimeWsTool : ToolHandler {
    override val capability = "network.realtime@1"

    override suspend fun invoke(args: ToolArgs, ctx: ToolContext): ToolResult {
        val channel = args["channel"] ?: "kitchen_stream"
        return ToolResult.Success(
            mapOf("state" to "ok", "channel" to channel, "connection_state" to "CONNECTED", "latency_ms" to 4L)
        )
    }
}
