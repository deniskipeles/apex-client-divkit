package engine.tools.impl

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import engine.crypto.currentTimeMillis
import engine.db.currentAndroidContext
import engine.tools.ToolArgs
import engine.tools.ToolContext
import engine.tools.ToolHandler
import engine.tools.ToolResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Base64
import java.util.UUID

/**
 * Universal Hardware Camera Capability ("hardware.camera@1" / "vision.capture_photo@1").
 * Unopinionated image capture sensor supporting lens selection, format transcoding,
 * base64 data URLs, tenant-isolated media storage, and flashlight control.
 *
 * Frame acquisition is delegated to a platform sink because only the host owns a
 * CameraX lifecycle and a `PreviewView` surface. When no sink is installed (unit tests,
 * headless environments) the tool reports `UNSUPPORTED` rather than inventing a frame:
 * a fabricated bitmap looks like a successful capture and silently ships a black photo.
 */
class CameraTool : ToolHandler {
    override val capability: String = "hardware.camera@1"
    override val requiredPermissions: Set<String> = setOf("android.permission.CAMERA")

    /**
     * A real camera request. Returns the JPEG bytes written by the platform, or null when the
     * capture could not be completed (permission denied, no camera, user cancelled).
     */
    data class CaptureRequest(
        val facing: String,
        val maxDimension: Int,
        val showPreview: Boolean
    )

    companion object {
        /** Installed by the Android host; null in unit tests. */
        var captureSink: (suspend (CaptureRequest) -> ByteArray?)? = null
    }

    override suspend fun invoke(args: ToolArgs, ctx: ToolContext): ToolResult {
        val op = args["op"] ?: args["action"] ?: args["operation"] ?: "capture"
        val tenantId = ctx.workspaceId

        return try {
            when (op.lowercase()) {
                "capture", "take_photo" -> executeCapture(args, tenantId)
                "torch", "torch_toggle" -> executeTorch(args)
                "status", "capabilities", "get_status" -> executeStatus()
                else -> ToolResult.Failed(capability, "Unsupported camera operation '$op'")
            }
        } catch (e: Exception) {
            ToolResult.Failed(capability, e.message ?: "Camera operation failed")
        }
    }

    private suspend fun executeCapture(args: ToolArgs, tenantId: String): ToolResult {
        val facing = args["facing"] ?: args["lens"] ?: "back"
        val quality = args.getInt("quality", 85).coerceIn(10, 100)
        val format = (args["format"] ?: args["mime_type"] ?: "image/jpeg").lowercase()
        val maxDim = args.getInt("max_dimension", 1920)
        val encoding = (args["encoding"] ?: "uri").lowercase() // "uri", "base64", "data_url", "both"
        val simulatedBase64 = args["simulated_base64"] ?: args["simulated_image"]

        val context = currentAndroidContext?.invoke()
        val timestamp = currentTimeMillis()
        val imageId = "img_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val extension = when {
            format.contains("png") -> "png"
            format.contains("webp") -> "webp"
            else -> "jpg"
        }

        // Prepare tenant-isolated media directory: files/workspaces/<tenantId>/media/
        val mediaDir = context?.let {
            File(it.filesDir, "workspaces/$tenantId/media").apply { mkdirs() }
        } ?: File(System.getProperty("java.io.tmpdir") ?: ".", "media").apply { mkdirs() }

        val outputFile = File(mediaDir, "$imageId.$extension")

        val captured: ByteArray = if (simulatedBase64 != null) {
            Base64.getDecoder().decode(simulatedBase64)
        } else {
            val sink = captureSink
                ?: return ToolResult.Unsupported(
                    capability,
                    "No camera host installed: capture requires an Activity with a camera preview"
                )
            sink(CaptureRequest(facing = facing, maxDimension = maxDim, showPreview = true))
                ?: return ToolResult.Failed(
                    capability,
                    "Camera capture was cancelled or returned no frame"
                )
        }

        val bytes = transcode(captured, extension, quality, maxDim)
        outputFile.writeBytes(bytes)
        val (width, height) = decodeDimensions(bytes, maxDim)

        val base64Str = if (encoding in setOf("base64", "data_url", "both")) {
            Base64.getEncoder().encodeToString(bytes)
        } else null

        val dataUrl = if (encoding in setOf("data_url", "both") && base64Str != null) {
            "data:$format;base64,$base64Str"
        } else null

        val fileUri = "file://${outputFile.absolutePath}"

        val resultData = mutableMapOf<String, Any?>(
            "uri" to fileUri,
            "image_id" to imageId,
            "mime_type" to format,
            "width" to width,
            "height" to height,
            "size_bytes" to bytes.size,
            "facing" to facing,
            "timestamp" to timestamp,
            "status" to "SUCCESS"
        )

        base64Str?.let { resultData["base64"] = it }
        dataUrl?.let { resultData["data_url"] = it }

        return ToolResult.Success(resultData)
    }

    /** Re-encodes the captured frame to the requested container and bounded dimension. */
    private fun transcode(raw: ByteArray, extension: String, quality: Int, maxDim: Int): ByteArray {
        if (extension == "jpg" && maxDim <= 0) return raw
        val decoded = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return raw
        val scaled = scaleDown(decoded, maxDim)
        val target = if (extension == "png") Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        val stream = ByteArrayOutputStream()
        scaled.compress(target, quality, stream)
        if (scaled !== decoded) decoded.recycle()
        scaled.recycle()
        return stream.toByteArray()
    }

    private fun scaleDown(bitmap: Bitmap, maxDim: Int): Bitmap {
        if (maxDim <= 0) return bitmap
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxDim) return bitmap
        val scale = maxDim.toFloat() / longest
        val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)
        return if (scaled === bitmap) bitmap else scaled
    }

    private fun executeTorch(args: ToolArgs): ToolResult {
        val enabled = args.getFlag("enabled") || args["state"] == "on" || args["torch"] == "true"
        val context = currentAndroidContext?.invoke()
            ?: return ToolResult.Unsupported(capability, "No Android context to drive the torch")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return ToolResult.Unsupported(capability, "Torch control requires Android 6.0+")
        }

        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return ToolResult.Unsupported(capability, "Camera service unavailable")
        val cameraId = try {
            cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        } catch (e: Exception) {
            return ToolResult.Failed(capability, e.message ?: "Could not query camera flash")
        } ?: return ToolResult.Unsupported(capability, "This device has no flash unit")

        return try {
            cameraManager.setTorchMode(cameraId, enabled)
            ToolResult.Success(
                mapOf("torch_state" to enabled, "status" to "SUCCESS", "camera_id" to cameraId)
            )
        } catch (e: Exception) {
            // setTorchMode throws when another app holds the camera; reporting SUCCESS here
            // would tell the user the light is on when it is not.
            ToolResult.Failed(capability, e.message ?: "Torch could not be toggled")
        }
    }

    private fun executeStatus(): ToolResult {
        val context = currentAndroidContext?.invoke()
        var hasBack = true
        var hasFront = true
        var hasFlash = true

        if (context != null) {
            try {
                val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                hasBack = cm.cameraIdList.any {
                    cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
                }
                hasFront = cm.cameraIdList.any {
                    cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
                }
                hasFlash = cm.cameraIdList.any {
                    cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                }
            } catch (_: Exception) {}
        }

        return ToolResult.Success(
            mapOf(
                "available" to true,
                "has_back_camera" to hasBack,
                "has_front_camera" to hasFront,
                "has_flash" to hasFlash,
                "status" to "READY"
            )
        )
    }

    private fun decodeDimensions(bytes: ByteArray, maxDim: Int): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        val w = options.outWidth.coerceAtLeast(1)
        val h = options.outHeight.coerceAtLeast(1)
        val scale = if (maxDim > 0 && w > maxDim) maxDim.toDouble() / w else 1.0
        return (w * scale).toInt().coerceAtLeast(1) to (h * scale).toInt().coerceAtLeast(1)
    }
}