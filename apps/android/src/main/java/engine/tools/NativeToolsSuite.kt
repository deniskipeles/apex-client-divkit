package engine.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import engine.crypto.currentTimeMillis
import engine.state.DivVariableValue
import engine.state.EngineStateBridge
import engine.tools.audio.AudioToneSynthesizer
import engine.tools.scan.BarcodeDecoder
import engine.tools.vector.ImageVectorizer
import engine.tools.vector.OcrExtractor
import engine.tools.vector.TextVectorizer
import engine.tools.vector.VectorMath
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor

object NativeToolsSuite {
    /** Statuses that mean the operation did not do what was asked. */
    private val FAILURE_STATUSES = setOf(
        "FAILED", "UNSUPPORTED", "NO_INPUT", "NO_CODE_FOUND", "FORMAT_MISMATCH",
        "DENIED", "BLUETOOTH_OFF", "NO_DEVICE", "ERROR",
        // A safety-number comparison that did not match must not read as a success.
        "MISMATCH", "KEY_CHANGED"
    )

    /**
     * Installed by the Android host to provide a live camera viewfinder. Returns the first
     * decoded symbology, or null when the user cancelled or the camera was unavailable.
     * Null in unit tests, where scanning is driven by explicit image references instead.
     */
    var liveScanSink: (suspend () -> engine.tools.scan.ScanResult?)? = null

    /**
     * Asks the user for an image (camera or an existing file) and returns a path the tools can
     * decode, or null when the user cancelled. Installed by the Android host.
     *
     * Vision tools run without an Activity, so they cannot open a picker or a camera. Without
     * this sink a card can only work if it hardcodes an image reference, which is why the OCR
     * and classifier cards could not operate on the user's own photo.
     */
    var imageSourceSink: (suspend (allowCamera: Boolean) -> String?)? = null

    @Serializable
    data class ToolSchema(
        val toolId: String,
        val displayName: String,
        val description: String,
        val supportedOperations: List<String>,
        val defaultOptions: Map<String, String>,
        val defaultOutputMapping: Map<String, String>
    )

    data class ToolExecutionOutcome(
        val success: Boolean,
        val toolId: String,
        val operation: String,
        val summary: String,
        val latencyMs: Long,
        val resultData: JsonElement,
        val outputVariables: Map<String, DivVariableValue>
    )

    val registeredSchemas: List<ToolSchema> = listOf(
        // -------------------------------------------------------------
        // UNIFIED GENERIC OPTICAL SCANNER
        // Replaces deprecated split tools: scan-barcode & scan-qr
        // -------------------------------------------------------------
        ToolSchema(
            toolId = "scan",
            displayName = "Unified Optical Scanner (1D Barcode & 2D QR)",
            description = "Generic optical decoder for 1D linear barcodes and 2D matrix symbologies. Defaults to recognizing both formats simultaneously, or can be restricted using target_format.",
            supportedOperations = listOf("scan", "continuous_scan", "simulate", "parse", "ocr"),
            defaultOptions = mapOf(
                "target_format" to "both", // "both", "barcode", "qr", or specific format ("EAN_13", "UPC_A", "CODE_128", "QR_CODE")
                "beep" to "true",
                "haptics" to "true",
                "camera_facing" to "back"
            ),
            defaultOutputMapping = mapOf(
                "raw_value" to "user.scanned_code",
                "format" to "user.scanned_format",
                "type" to "user.scanned_type",
                "status" to "user.scan_status",
                "timestamp" to "user.scan_timestamp",
                "text" to "user.ocr_text",
                "confidence" to "user.ocr_confidence",
                "block_count" to "user.ocr_block_count"
            )
        ),
        // Other generic system tools (haptics, beeper, libsignal, camera, biometrics, clipboard, etc.)
        ToolSchema(
            toolId = "haptics",
            displayName = "Universal Haptics & Vibration Engine",
            description = "Unopinionated tactile feedback actuator supporting semantic impacts (click, heavy, tick, success, error), custom one-shot vibrations, and W3C waveform timing arrays.",
            supportedOperations = listOf("perform", "vibrate", "click", "heavy", "tick", "success", "warning", "error", "stop", "status"),
            defaultOptions = mapOf(
                "pattern" to "click",
                "duration_ms" to "20",
                "amplitude" to "180"
            ),
            defaultOutputMapping = mapOf(
                "status" to "user.haptics_status",
                "pattern" to "user.haptics_pattern"
            )
        ),
        ToolSchema(
            toolId = "beeper",
            displayName = "Universal Audio Tone Synthesizer",
            description = "Unopinionated PCM frequency synthesizer supporting pure sine/square/triangle waves, acoustic feedback profiles, and melodic sequences.",
            supportedOperations = listOf("tone", "play", "beep", "chime", "stop", "status"),
            defaultOptions = mapOf(
                "frequency_hz" to "1600",
                "duration_ms" to "80",
                "waveform" to "sine",
                "volume" to "0.85"
            ),
            defaultOutputMapping = mapOf(
                "status" to "user.beeper_status",
                "frequency_hz" to "user.beeper_frequency"
            )
        ),
        ToolSchema(
            toolId = "camera",
            displayName = "Hardware Camera Bridge",
            description = "Camera frame capture and torch controller.",
            supportedOperations = listOf("capture", "torch_toggle", "get_status"),
            defaultOptions = mapOf("facing" to "back", "resolution" to "1080p"),
            defaultOutputMapping = mapOf("uri" to "user.camera_capture_uri")
        ),
        // -------------------------------------------------------------
        // UNIFIED IMAGE MANIPULATION ENGINE (OS Models / <4MB footprint)
        // -------------------------------------------------------------
        ToolSchema(
            toolId = "files",
            displayName = "Workspace File Store",
            description = "Tenant-scoped file storage for bytes of any kind: list, stat, read, write, copy, move, delete, mkdir. Paths are workspace-relative and sandboxed; content is never parsed or rewritten.",
            supportedOperations = listOf(
                "list", "info", "read", "write", "append", "copy", "move", "delete", "mkdir", "exists", "uri"
            ),
            defaultOptions = mapOf("op" to "list", "path" to "."),
            defaultOutputMapping = mapOf(
                "uri" to "user.files_last_uri",
                "path" to "user.files_last_path",
                "status" to "user.files_status"
            )
        ),
        ToolSchema(
            toolId = "image-manipulation",
            displayName = "Universal Image Manipulation & Background Removal",
            description = "High-performance on-device image processing using OS models (<4MB footprint). Supports neural/chroma background removal, edge-preserving smoothing, color adjustment, and cropping.",
            supportedOperations = listOf("process", "remove_background", "smooth", "adjust_color", "crop", "resize", "threshold"),
            defaultOptions = mapOf(
                "op" to "remove_background",
                "encoding" to "uri"
            ),
            defaultOutputMapping = mapOf(
                "uri" to "user.processed_image_uri",
                "status" to "user.image_processing_status"
            )
        ),
        ToolSchema(
            toolId = "embedding",
            displayName = "Text & Image Embeddings",
            description = "Embeds text, images, or image-derived OCR text into L2-normalized vectors. Text uses MediaPipe USE-Lite when its JNI is present, otherwise a 512-d lexical fallback; images use the MobileNetV4 INT8 descriptor; image_text chains ML Kit OCR into the text embedder. Standalone text extraction lives in tools://scan/ocr. `vectorize` remains an alias.",
            supportedOperations = listOf("text", "image", "image_text", "text_similarity", "similarity"),
            defaultOptions = mapOf("normalize" to "true", "include_ocr_text" to "true"),
            defaultOutputMapping = mapOf(
                "vector_b64" to "user.vector_b64",
                "dim" to "user.vector_dim",
                "source_type" to "user.vector_source",
                "ocr_text" to "user.ocr_text",
                "score" to "user.vector_similarity"
            )
        ),
        ToolSchema(
            toolId = "clipboard",
            displayName = "System Clipboard Controller",
            description = "Read and write strings to device clipboard.",
            supportedOperations = listOf("copy", "paste", "clear"),
            defaultOptions = mapOf("op" to "copy"),
            defaultOutputMapping = mapOf("text" to "user.clipboard_content")
        ),
        ToolSchema(
            toolId = "biometrics",
            displayName = "OS Biometric Authentication",
            description = "Biometric prompt gate for sensitive actions.",
            supportedOperations = listOf("authenticate"),
            defaultOptions = mapOf("prompt" to "Authentication Required"),
            defaultOutputMapping = mapOf("authenticated" to "user.biometric_authenticated")
        ),
        ToolSchema(
            toolId = "libsignal",
            displayName = "Signal E2EE Cryptographic Transport",
            description = "Signal Protocol Double Ratchet sessions, prekeys, and end-to-end encrypted messaging between terminals.",
            supportedOperations = listOf("setup_identity", "init_keys", "create_session", "encrypt", "decrypt", "status", "verify_identity", "reset_session"),
            defaultOptions = emptyMap(),
            defaultOutputMapping = mapOf(
                "status" to "user.signal_status",
                "session_id" to "user.signal_session_id",
                "ciphertext" to "user.signal_ciphertext",
                "plaintext" to "user.signal_plaintext",
                "safety_number" to "user.signal_fingerprint",
                "fingerprint" to "user.signal_fingerprint"
            )
        ),
        ToolSchema(
            toolId = "MobileNetV4-small",
            displayName = "MobileNetV4 Image Classifier",
            description = "On-device INT8 image classification and SKU recognition.",
            supportedOperations = listOf("classify", "detect", "predict"),
            defaultOptions = mapOf("top_k" to "3", "min_confidence" to "0.40"),
            defaultOutputMapping = mapOf(
                "prediction_label" to "user.ai_prediction_label",
                "prediction_sku" to "user.ai_prediction_sku",
                "confidence" to "user.ai_confidence"
            )
        ),
        ToolSchema(
            toolId = "u-2-Net.tflite",
            displayName = "U-2-Net Salient Object Segmentation",
            description = "On-device salient object segmentation producing an alpha matte mask.",
            supportedOperations = listOf("segment", "infer", "mask"),
            defaultOptions = mapOf("tensor_input" to "320x320x3", "saliency_cutoff" to "0.45"),
            defaultOutputMapping = mapOf(
                "mask_uri" to "user.u2net_mask_uri",
                "confidence" to "user.u2net_confidence"
            )
        ),
        ToolSchema(
            toolId = "push",
            displayName = "Background Alerts & Push Notifications",
            description = "Dispatches background push alerts to a named notification channel.",
            supportedOperations = listOf("send_alert", "notify", "send"),
            defaultOptions = mapOf("channel" to "orders_dispatch", "priority" to "high"),
            defaultOutputMapping = mapOf(
                "notification_id" to "user.push_notification_id",
                "status" to "user.push_status"
            )
        ),
        ToolSchema(
            toolId = "offline-cache",
            displayName = "Offline Page & Schema Cache",
            description = "Caches SDUI pages and schemas for offline resilience.",
            supportedOperations = listOf("cache", "get_stats", "clear"),
            defaultOptions = mapOf("op" to "get_stats"),
            defaultOutputMapping = mapOf(
                "status" to "user.offline_cache_status",
                "cached_pages" to "user.offline_cached_pages"
            )
        ),
        ToolSchema(
            toolId = "realtime-ws",
            displayName = "Realtime WebSocket / SSE Engine",
            description = "Opens and manages realtime WSS/SSE channels for live workspace updates.",
            supportedOperations = listOf("connect", "disconnect", "send", "subscribe"),
            defaultOptions = mapOf("channel" to "kitchen_stream"),
            defaultOutputMapping = mapOf(
                "connection_state" to "user.realtime_connection_state",
                "channel" to "user.realtime_channel"
            )
        ),
        ToolSchema(
            toolId = "net",
            displayName = "Network Transfer (Ktor) & LAN Hub",
            description = "Generic Ktor-powered networking: HTTP requests, downloads/uploads, and an embedded LAN transfer hub for peer-to-peer / Xender-style sharing.",
            supportedOperations = listOf(
                "request", "get", "post", "download", "upload",
                "serve", "stop", "status", "share", "list", "unshare"
            ),
            defaultOptions = mapOf("timeout_ms" to "30000"),
            defaultOutputMapping = mapOf(
                "status" to "user.net_status",
                "http_status" to "user.net_http_status",
                "bytes" to "user.net_bytes",
                "port" to "user.net_hub_port"
            )
        ),
        ToolSchema(
            toolId = "print",
            displayName = "Bluetooth Printer / POS Bridge",
            description = "Scans for bonded and discoverable Bluetooth thermal printers and POS devices, connects over RFCOMM SPP, and prints ESC/POS receipts.",
            supportedOperations = listOf(
                "scan", "pair", "connect", "disconnect", "print", "status"
            ),
            defaultOptions = mapOf("align" to "left", "feed_lines" to "3", "width" to "32"),
            defaultOutputMapping = mapOf(
                "status" to "user.print_status",
                "count" to "user.print_device_count",
                "bytes" to "user.print_bytes"
            )
        ),
        ToolSchema(
            toolId = "code-generate",
            displayName = "Barcode & QR Code Generator",
            description = "Encodes arbitrary content into a 1D barcode or 2D matrix symbology (QR, DataMatrix, Aztec, PDF417) and renders it to an image plus an inspectable module matrix.",
            supportedOperations = listOf("generate", "qr", "barcode", "render"),
            defaultOptions = mapOf(
                "format" to "qr",
                "width" to "512",
                "height" to "512",
                "margin" to "4",
                "ec_level" to "M",
                "encoding" to "uri"
            ),
            defaultOutputMapping = mapOf(
                "status" to "user.code_status",
                "uri" to "user.code_image_uri",
                "content" to "user.code_content",
                "format" to "user.code_format",
                "kind" to "user.code_kind",
                "code_id" to "user.code_id",
                "matrix_rows" to "user.code_matrix_rows"
            )
        ),
        ToolSchema(
            toolId = "screen",
            displayName = "Screen: Keep-Awake, Screenshot & Recording",
            description = "Screen-facing terminal capability: hold the display awake, capture a screenshot, or start/stop an MP4 screen recording. All three share one MediaProjection permission prompt.",
            supportedOperations = listOf("keep-awake", "screenshot", "recording", "status"),
            defaultOptions = mapOf(
                "level" to "screen",
                "timeout_ms" to "0",
                "format" to "png",
                "quality" to "95"
            ),
            defaultOutputMapping = mapOf(
                "status" to "user.screen_status",
                "enabled" to "user.screen_keep_awake",
                "recording" to "user.screen_recording",
                "recording_id" to "user.screen_recording_id",
                "uri" to "user.screen_media_uri",
                "path" to "user.screen_media_path",
                "width" to "user.screen_width",
                "height" to "user.screen_height",
                "bytes" to "user.screen_bytes",
                "duration_ms" to "user.screen_duration_ms",
                "image_id" to "user.screen_image_id",
                "error" to "user.screen_error",
                "reason" to "user.screen_error"
            )
        )
    )

    suspend fun execute(
        url: String,
        payload: JsonObject,
        tenantId: String = "default"
    ): ToolExecutionOutcome {
        val startTime = currentTimeMillis()
        val path = url.removePrefix("tools://").removePrefix("tool://")
        val toolRaw = path.substringBefore('/')
        val operation = path.substringAfter('/', "default").substringBefore('?')

        val toolId = normalizeToolId(toolRaw)
        val schema = registeredSchemas.firstOrNull { it.toolId.equals(toolId, ignoreCase = true) }
            ?: registeredSchemas.first { it.toolId == "scan" }

        // `tools://ocr/...` is an alias for the scanner's OCR mode regardless of the
        // trailing segment, so normalize the reported/executed operation here.
        val ocrAlias = toolRaw.trim().lowercase() in setOf("ocr", "read-text", "text-recognition")
        val effectiveOperation = if (ocrAlias) "ocr" else operation

        val options = buildMap {
            schema.defaultOptions.forEach { (k, v) -> put(k, v) }
            payload["options"]?.jsonObject?.forEach { (k, v) ->
                put(k, v.jsonPrimitive.contentOrNull ?: v.toString())
            }
        }

        val outputMapping = buildMap {
            schema.defaultOutputMapping.forEach { (k, v) -> put(k, v) }
            payload["output"]?.jsonObject?.forEach { (k, v) ->
                v.jsonPrimitiveOrNull()?.let { put(k, it) }
            }
        }

        val outcome = when (toolId.lowercase()) {
            "scan", "scanner", "vision.scan_code@1", "vision.scan_code", "scan-barcode", "scan-qr" ->
                executeScan(effectiveOperation, options, payload)
            "haptics", "device.haptics", "device.haptics@1" -> executeHaptics(operation, options, payload)
            "beeper", "device.beeper", "device.beeper@1" -> executeBeeper(operation, options, payload)
            "camera" -> executeCamera(operation, options, payload, tenantId)
            "embedding" -> executeEmbedding(operation, options, payload, tenantId)
            "image-manipulation", "image-process", "image-filter", "vision.image_manipulation@1",
            "image-background-remove" ->
                executeImageManipulation(operation, options, payload, tenantId)
            "clipboard" -> executeClipboard(operation, options, payload)
            "biometrics" -> executeBiometrics(operation, options, payload)
            "libsignal" -> executeLibSignal(operation, options, payload, tenantId)
            "mobilenetv4-small", "mobilenetv4" -> executeMobileNetV4(operation, options, payload, tenantId)
            "u-2-net.tflite", "u2net" -> executeU2Net(operation, options, payload, tenantId)
            "push" -> executePush(operation, options, payload)
            "offline-cache" -> executeOfflineCache(operation, options, payload, tenantId)
            "realtime-ws" -> executeRealtimeWs(operation, options, payload, tenantId)
            "net" -> executeNet(operation, options, payload, tenantId)
            "print" -> executePrint(operation, options, payload, tenantId)
            "code-generate" -> executeCodeGenerate(operation, options, payload, tenantId)
            "screen" -> executeScreen(operation, options, payload, tenantId)
            "files" -> executeFiles(operation, options, payload, tenantId)
            else -> executeGenericFallback(toolId, operation, payload)
        }

        val totalLatency = (currentTimeMillis() - startTime).coerceAtLeast(1L)
        val finalVariables = mutableMapOf<String, DivVariableValue>()

        // Dynamic State Bus Output Mapping
        outputMapping.forEach { (toolField, stateVarName) ->
            val value = outcome.dataMap[toolField]
            if (value != null) {
                val divVal = when (value) {
                    is Boolean -> DivVariableValue.Bool(value)
                    is Int -> DivVariableValue.IntVal(value.toLong())
                    is Long -> DivVariableValue.IntVal(value)
                    is Double -> if (value == floor(value) && !value.isInfinite()) {
                        DivVariableValue.IntVal(value.toLong())
                    } else {
                        DivVariableValue.DoubleVal(value)
                    }
                    is Float -> DivVariableValue.DoubleVal(value.toDouble())
                    else -> DivVariableValue.Str(value.toString())
                }
                finalVariables[stateVarName] = divVal
                EngineStateBridge.pushUpdate(stateVarName, divVal)
            }
        }

        return ToolExecutionOutcome(
            // Statuses are domain-specific ("ENCRYPTED", "ALREADY_PAIRED", ...), so success is
            // modelled as "not one of the explicit failure states" rather than an allowlist.
            success = outcome.dataMap["status"] !in FAILURE_STATUSES,
            toolId = schema.toolId,
            operation = effectiveOperation,
            summary = outcome.summary,
            latencyMs = totalLatency,
            resultData = outcome.resultJson,
            outputVariables = finalVariables
        )
    }

    private fun normalizeToolId(raw: String): String = when (raw.lowercase()) {
        "scan", "scanner", "scan-code", "vision.scan_code@1", "vision.scan_code", "scan-barcode", "scan-qr", "barcode", "qr", "ocr", "read-text", "text-recognition" -> "scan"
        "haptics", "device.haptics", "device.haptics@1", "vibrate" -> "haptics"
        "beeper", "device.beeper", "device.beeper@1", "tone", "audio" -> "beeper"
        "camera", "photo" -> "camera"
        "embedding", "embeddings", "vectorize", "vector", "embed", "embed-text", "embed-image" -> "embedding"
        "clipboard", "device.clipboard" -> "clipboard"
        "biometrics", "auth.biometrics" -> "biometrics"
        "libsignal", "signal", "secure.send", "secure.status", "secure.verify_identity" -> "libsignal"
        "mobilenetv4-small", "mobilenetv4", "mobilenet", "vision.identify_product@1", "identify-product" -> "MobileNetV4-small"
        "u-2-net.tflite", "u-2-net", "u2net", "u2net.tflite", "vision.remove_background@1" -> "u-2-Net.tflite"
        "push", "network.push", "network.push@1", "alerts" -> "push"
        "offline-cache", "offline_cache", "storage.offline_cache", "cache" -> "offline-cache"
        "realtime-ws", "realtime_ws", "network.realtime", "realtime", "ws" -> "realtime-ws"
        "net", "network.transfer", "network.transfer@1", "network", "http", "transfer" -> "net"
        "print", "printer", "device.printer", "device.printer@1", "bluetooth.print", "pos.print" -> "print"
        "code-generate", "code_gen", "codegen", "generate-code", "generate_code", "barcode-generate",
        "qr-generate", "vision.generate_code@1", "vision.generate_code" -> "code-generate"
        "screen", "device.screen", "device.screen@1", "screen-capture", "screen-recording",
        "keep-awake", "keep_awake", "keepawake", "device.keep_awake", "device.keep_awake@1", "wakelock" -> "screen"
        "files", "file", "storage.files", "storage.files@1", "file-store", "fs" -> "files"
        else -> raw
    }

    data class InternalToolResult(
        val summary: String,
        val dataMap: Map<String, Any>,
        val resultJson: JsonElement
    )

    // Stateful handlers (LAN hub sockets / Bluetooth connections) are shared across
    // invocations so that serve→share→list and connect→print→disconnect stay coherent.
    // `TerminalApp` registers these same instances: a hub started through `tools://net/serve`
    // must be the one `div-action://...tool=network.transfer@1&op=status` reports on.
    internal val netTool = engine.tools.impl.NetTool()
    internal val printTool = engine.tools.impl.PrintTool()

    // -------------------------------------------------------------------------
    // GENERIC OPTICAL SCANNER (ZERO DOMAIN COUPLING)
    // -------------------------------------------------------------------------
    private suspend fun executeScan(
        operation: String,
        options: Map<String, String>,
        payload: JsonObject
    ): InternalToolResult {
        // Optical character recognition (OCR) is an image-reading mode of the same
        // optical scanner: it extracts human-readable text instead of symbology.
        if (operation.trim().lowercase() in setOf("ocr", "text", "read_text")) {
            return executeScanOcr(options, payload)
        }

        // 1. target_format defaults to "both" (or "all")
        val targetFormatRaw = payload["target_format"]?.jsonPrimitive?.contentOrNull
            ?: options["target_format"]
            ?: payload["format"]?.jsonPrimitive?.contentOrNull
            ?: options["format"]
            ?: "both"

        val targetFormat = targetFormatRaw.trim().lowercase()

        // 2. Decode real pixels when an image is supplied. `simulated_code` stays supported so
        // callers (and tests) can inject a payload without a camera, but it is never inferred
        // from string shape: the format comes from ZXing or from the explicit payload.
        val imageRef = payload["uri"]?.jsonPrimitive?.contentOrNull
            ?: payload["image"]?.jsonPrimitive?.contentOrNull
            ?: payload["base64"]?.jsonPrimitive?.contentOrNull
            ?: options["uri"]
            ?: options["image"]

        val decoded = imageRef?.let { ref ->
            BarcodeDecoder.loadBitmap(ref)?.let { bitmap ->
                try {
                    BarcodeDecoder.decode(bitmap)
                } finally {
                    bitmap.recycle()
                }
            }
        }

        if (imageRef != null && decoded == null) {
            val failure = mapOf(
                "status" to "NO_CODE_FOUND",
                "error" to "No barcode or QR code could be decoded from $imageRef",
                "source" to imageRef
            )
            return InternalToolResult(
                summary = "Scan failed: no symbology found in image",
                dataMap = failure,
                resultJson = buildJsonObject { failure.forEach { (k, v) -> put(k, v) } }
            )
        }

        val declaredRaw = payload["raw_value"]?.jsonPrimitive?.contentOrNull
            ?: payload["simulated_code"]?.jsonPrimitive?.contentOrNull
            ?: payload["code"]?.jsonPrimitive?.contentOrNull
            ?: options["simulated_code"]
            ?: options["code"]

        // Live camera path: with no still image and no injected payload, open a real viewfinder
        // and decode the first symbology the user points at. `camera` may be set to "false" to
        // keep the call strictly off-camera (headless callers and tests).
        val wantsCamera = (payload["camera"]?.jsonPrimitive?.contentOrNull ?: options["camera"]) != "false"
        val liveScan = if (imageRef == null && decoded == null && declaredRaw == null && wantsCamera) {
            liveScanSink?.invoke()
        } else {
            null
        }

        val rawValue = decoded?.rawValue
            ?: liveScan?.rawValue
            ?: declaredRaw
            ?: return InternalToolResult(
                summary = "Scan failed: no camera frame or image supplied",
                dataMap = mapOf(
                    "status" to "NO_INPUT",
                    "error" to "Provide a camera frame, an image uri, or a code payload"
                ),
                resultJson = buildJsonObject {
                    put("status", "NO_INPUT")
                    put("error", "Provide a camera frame, an image uri, or a code payload")
                }
            )

        // 3. Symbology comes from the decoder when pixels were decoded, otherwise from an
        // explicit payload declaration. Never guessed from string length.
        val declaredFormat = payload["format"]?.jsonPrimitive?.contentOrNull
            ?: payload["symbology"]?.jsonPrimitive?.contentOrNull
            ?: options["symbology"]
        val detectedFormat = decoded?.format
            ?: liveScan?.format
            ?: declaredFormat?.trim()?.uppercase()?.replace("-", "_")
            ?: "UNKNOWN"
        val detectedType = decoded?.type
            ?: liveScan?.type
            ?: when {
                BarcodeDecoder.matchesTarget(detectedFormat, "qr") -> "2D"
                detectedFormat != "UNKNOWN" -> "1D"
                else -> "UNKNOWN"
            }

        // 4. Enforce target_format filter
        val matches = when {
            targetFormat in setOf("both", "all", "*") -> true
            targetFormat in setOf("barcode", "1d", "linear") -> detectedType == "1D"
            targetFormat in setOf("qr", "2d", "matrix") -> detectedType == "2D"
            else -> detectedFormat.equals(targetFormat, ignoreCase = true)
        }

        if (!matches) {
            val mismatchData = mapOf(
                "status" to "FORMAT_MISMATCH",
                "error" to "Scanned format [$detectedFormat] does not match target_format [$targetFormatRaw]",
                "raw_value" to rawValue,
                "detected_format" to detectedFormat,
                "detected_type" to detectedType,
                "target_format" to targetFormatRaw
            )
            return InternalToolResult(
                summary = "Scan Rejected: expected $targetFormatRaw, got $detectedFormat",
                dataMap = mismatchData,
                resultJson = buildJsonObject { mismatchData.forEach { (k, v) -> put(k, v.toString()) } }
            )
        }

        // 5. Hardware sensory confirmation
        val beep = (payload["beep"]?.jsonPrimitive?.contentOrNull ?: options["beep"]) != "false"
        if (beep) {
            val freq = if (detectedType == "2D") 2200.0 else 1600.0
            AudioToneSynthesizer.playTone(frequencyHz = freq, durationMs = 80)
        }

        val data = mapOf(
            "raw_value" to rawValue,
            "format" to detectedFormat,
            "type" to detectedType,
            "bytes_length" to rawValue.toByteArray(Charsets.UTF_8).size,
            "status" to "SUCCESS",
            "timestamp" to currentTimeMillis()
        )

        return InternalToolResult(
            summary = "Decoded $detectedType [$detectedFormat]: $rawValue",
            dataMap = data,
            resultJson = buildJsonObject {
                data.forEach { (k, v) ->
                    when (v) {
                        is Number -> put(k, v.toLong())
                        else -> put(k, v.toString())
                    }
                }
            }
        )
    }

    // -------------------------------------------------------------------------
    // OPTICAL CHARACTER RECOGNITION (TEXT READING MODE)
    // -------------------------------------------------------------------------
    private suspend fun executeScanOcr(
        options: Map<String, String>,
        payload: JsonObject
    ): InternalToolResult {
        val ctx = engine.db.currentAndroidContext?.invoke()
            ?: return InternalToolResult(
                summary = "OCR unavailable: no Android context",
                dataMap = mapOf("status" to "UNSUPPORTED", "reason" to "no_android_context"),
                resultJson = buildJsonObject {
                    put("status", "UNSUPPORTED"); put("reason", "no_android_context")
                }
            )

        val declaredUri = payload["uri"]?.jsonPrimitive?.contentOrNull
            ?: payload["image"]?.jsonPrimitive?.contentOrNull
            ?: payload["base64"]?.jsonPrimitive?.contentOrNull
            ?: options["uri"]

        // No image in the call means the user wants to supply one, so ask. `camera=false` only
        // forbids the camera; the file picker stays available.
        val allowCamera = (payload["camera"]?.jsonPrimitive?.contentOrNull ?: options["camera"])
            ?.equals("false", ignoreCase = true) != true
        val uri = declaredUri ?: run {
            val sink = imageSourceSink
                ?: return noInput("OCR", "No image source host is installed")
            sink(allowCamera) ?: return cancelled("OCR", "No image was captured or selected")
        }

        val bmp = try {
            decodeBitmap(ctx, uri)
        } catch (e: Exception) {
            return InternalToolResult(
                summary = "OCR failed: ${e.message}",
                dataMap = mapOf("status" to "FAILED", "error" to (e.message ?: "decode error")),
                resultJson = buildJsonObject {
                    put("status", "FAILED"); put("error", e.message ?: "decode error")
                }
            )
        }

        val ocr = try {
            OcrExtractor.extract(bmp)
        } catch (e: Exception) {
            return InternalToolResult(
                summary = "OCR failed: ${e.message}",
                dataMap = mapOf("status" to "FAILED", "error" to (e.message ?: "recognizer unavailable")),
                resultJson = buildJsonObject {
                    put("status", "FAILED"); put("error", e.message ?: "recognizer unavailable")
                }
            )
        }
        val blocks = buildJsonArray {
            ocr.blocks.forEach { b ->
                add(buildJsonObject {
                    put("text", b.text)
                    put("bbox", b.bbox.joinToString(","))
                })
            }
        }
        val data = mapOf(
            "text" to ocr.text,
            "confidence" to ocr.meanConfidence,
            "block_count" to ocr.blocks.size,
            "blocks" to blocks,
            // Echo the image back so the card can show what was read instead of only the text.
            "source_image" to uri,
            "status" to "SUCCESS",
            "model" to "ML Kit Text Recognition v2 (Latin)",
            "timestamp" to currentTimeMillis()
        )
        return InternalToolResult(
            summary = "OCR extracted ${ocr.text.length} chars across ${ocr.blocks.size} block(s)",
            dataMap = data,
            resultJson = buildJsonObject {
                data.forEach { (k, v) ->
                    when (v) {
                        is Float -> put(k, v.toDouble())
                        is Double -> put(k, v)
                        is Int -> put(k, v.toLong())
                        is JsonElement -> put(k, v)
                        else -> put(k, v.toString())
                    }
                }
            }
        )
    }

    // -------------------------------------------------------------------------
    // GENERIC HAPTIC ACTUATOR (ZERO DOMAIN COUPLING)
    // -------------------------------------------------------------------------
    private suspend fun executeHaptics(
        operation: String,
        options: Map<String, String>,
        payload: JsonObject
    ): InternalToolResult {
        val mergedArgs = buildMap {
            putAll(options)
            // Non-generic operations are interpreted as a semantic pattern name
            // or a lifecycle command by the tool itself.
            if (operation !in setOf("default", "perform", "invoke")) {
                if (operation in setOf("stop", "cancel", "status", "capabilities")) {
                    put("op", operation)
                } else {
                    put("pattern", operation)
                }
            }
            payload.forEach { (k, v) -> put(k, jsonContent(v)) }
        }

        val toolResult = engine.tools.impl.HapticsTool().invoke(ToolArgs(mergedArgs), ToolContext())
        return internalFromToolResult(
            toolResult = toolResult,
            label = "Haptics",
            successSummary = "Haptic ${mergedArgs["pattern"] ?: operation} executed"
        )
    }

    // -------------------------------------------------------------------------
    // GENERIC AUDIO TONE SYNTHESIZER (ZERO DOMAIN COUPLING)
    // -------------------------------------------------------------------------
    private suspend fun executeBeeper(
        operation: String,
        options: Map<String, String>,
        payload: JsonObject
    ): InternalToolResult {
        val mergedArgs = buildMap {
            putAll(options)
            if (operation !in setOf("default", "invoke")) {
                put("op", operation)
            }
            payload.forEach { (k, v) ->
                put(k, jsonContent(v))
            }
        }

        val toolResult = engine.tools.impl.BeeperTool().invoke(ToolArgs(mergedArgs), ToolContext())
        return internalFromToolResult(
            toolResult = toolResult,
            label = "Audio synthesis",
            successSummary = "Tone played (${options["frequency_hz"] ?: operation})"
        )
    }

    // -------------------------------------------------------------------------
    // GENERIC HARDWARE CAMERA SENSOR
    // -------------------------------------------------------------------------
    private suspend fun executeCamera(
        operation: String,
        options: Map<String, String>,
        payload: JsonObject,
        tenantId: String
    ): InternalToolResult {
        val mergedArgs = buildMap {
            putAll(options)
            put("op", operation)
            payload.forEach { (k, v) ->
                put(k, jsonContent(v))
            }
        }

        val toolResult = engine.tools.impl.CameraTool().invoke(
            args = ToolArgs(mergedArgs),
            ctx = ToolContext(workspaceId = tenantId)
        )
        return internalFromToolResult(
            toolResult = toolResult,
            label = "Camera",
            successSummary = "Camera $operation completed successfully"
        )
    }

    private suspend fun executeEmbedding(
        operation: String, options: Map<String, String>, payload: JsonObject, tenantId: String
    ): InternalToolResult {
        val op = operation.lowercase().ifBlank { "text" }
        val t0 = currentTimeMillis()
        val data: Map<String, Any>
        when (op) {
            "text" -> {
                val text = (payload["text"]?.jsonPrimitive?.contentOrNull ?: options["text"]).orEmpty().trim()
                if (text.isEmpty()) return embeddingFailure("text embedding requires non-blank 'text'")
                val ctx = embeddingContext()
                    ?: return embeddingFailure("No Android context available for embeddings")
                val embedding = runCatching { TextVectorizer.embed(ctx, text) }.getOrElse {
                    return embeddingFailure(it.message ?: "Text embedding failed")
                }
                val v = embedding.vector
                data = mapOf("vector_b64" to packFloats(v), "dim" to v.size,
                             "source_type" to "text", "status" to "SUCCESS",
                             "model" to embedding.model)
            }
            "image" -> {
                val ctx = embeddingContext()
                    ?: return embeddingFailure("No Android context available for embeddings")
                val bmp = decodeEmbeddingBitmap(ctx, payload, options)
                    ?: return embeddingFailure("image embedding needs a decodable 'uri'")
                try {
                    val v = runCatching { ImageVectorizer.embed(ctx, bmp) }.getOrElse {
                        return embeddingFailure(it.message ?: "Image embedding failed")
                    }
                    data = mapOf("vector_b64" to packFloats(v), "dim" to v.size,
                                 "source_type" to "image", "status" to "SUCCESS",
                                 "model" to "MobileNetV4-small INT8 (1000-d class-score descriptor)")
                } finally {
                    bmp.recycle()
                }
            }
            "image_text" -> {
                val ctx = embeddingContext()
                    ?: return embeddingFailure("No Android context available for embeddings")
                val bmp = decodeEmbeddingBitmap(ctx, payload, options)
                    ?: return embeddingFailure("image_text embedding needs a decodable 'uri'")
                try {
                    val ocr = runCatching { OcrExtractor.extract(bmp) }.getOrElse {
                        return embeddingFailure(it.message ?: "OCR failed")
                    }
                    if (ocr.text.isBlank()) return embeddingFailure("no text found in image")
                    val embedding = runCatching { TextVectorizer.embed(ctx, ocr.text) }.getOrElse {
                        return embeddingFailure(it.message ?: "Text embedding failed")
                    }
                    val v = embedding.vector
                    data = mapOf("vector_b64" to packFloats(v), "dim" to v.size,
                                 "source_type" to "image_text", "status" to "SUCCESS",
                                 "ocr_text" to ocr.text, "ocr_confidence" to ocr.meanConfidence,
                                 "model" to "ML Kit + ${embedding.model}")
                } finally {
                    bmp.recycle()
                }
            }
            "text_similarity" -> {
                val aText = (payload["a"]?.jsonPrimitive?.contentOrNull
                    ?: payload["text_a"]?.jsonPrimitive?.contentOrNull
                    ?: options["a"] ?: options["text_a"]).orEmpty().trim()
                val bText = (payload["b"]?.jsonPrimitive?.contentOrNull
                    ?: payload["text_b"]?.jsonPrimitive?.contentOrNull
                    ?: options["b"] ?: options["text_b"]).orEmpty().trim()
                if (aText.isEmpty() || bText.isEmpty()) {
                    return embeddingFailure("text_similarity requires non-blank 'a' and 'b' text")
                }
                val ctx = embeddingContext()
                    ?: return embeddingFailure("No Android context available for embeddings")
                val va = runCatching { TextVectorizer.embed(ctx, aText) }.getOrElse {
                    return embeddingFailure(it.message ?: "Text embedding failed")
                }
                val vb = runCatching { TextVectorizer.embed(ctx, bText) }.getOrElse {
                    return embeddingFailure(it.message ?: "Text embedding failed")
                }
                data = mapOf("score" to VectorMath.cosine(va.vector, vb.vector), "dim" to va.vector.size,
                             "method" to "cosine", "source_type" to "text_similarity",
                             "status" to "SUCCESS", "model" to va.model)
            }
            "similarity" -> {
                val aRaw = (payload["a"]?.jsonPrimitive?.contentOrNull ?: options["a"]).orEmpty()
                val bRaw = (payload["b"]?.jsonPrimitive?.contentOrNull ?: options["b"]).orEmpty()
                if (aRaw.isBlank() || bRaw.isBlank()) {
                    return embeddingFailure("similarity requires packed vectors 'a' and 'b'")
                }
                val a = runCatching { unpackFloats(aRaw) }.getOrElse {
                    return embeddingFailure("invalid vector 'a': ${it.message}")
                }
                val b = runCatching { unpackFloats(bRaw) }.getOrElse {
                    return embeddingFailure("invalid vector 'b': ${it.message}")
                }
                if (a.isEmpty() || b.isEmpty()) return embeddingFailure("similarity vectors must not be empty")
                if (a.size != b.size) return embeddingFailure("dimension mismatch: ${a.size} vs ${b.size}")
                if (a.any { !it.isFinite() } || b.any { !it.isFinite() }) {
                    return embeddingFailure("similarity vectors must contain finite values")
                }
                data = mapOf("score" to VectorMath.cosine(a, b), "dim" to a.size,
                             "method" to "cosine", "source_type" to "similarity", "status" to "SUCCESS")
            }
            else -> return embeddingFailure("Unknown embedding op: $op")
        }
        val elapsed = (currentTimeMillis() - t0).coerceAtLeast(1L)
        return InternalToolResult(
            summary = "embedding/$op completed in ${elapsed}ms",
            dataMap = data,
            resultJson = buildJsonObject { data.forEach { (k, v) -> putJsonValue(k, v) } }
        )
    }

    private fun embeddingContext(): Context? = engine.db.currentAndroidContext?.invoke()

    private fun embeddingFailure(reason: String): InternalToolResult = InternalToolResult(
        summary = "Embedding failed: $reason",
        dataMap = mapOf("status" to "FAILED", "error" to reason),
        resultJson = buildJsonObject {
            put("status", "FAILED")
            put("error", reason)
        }
    )

    private fun decodeEmbeddingBitmap(
        ctx: Context, payload: JsonObject, options: Map<String, String>
    ): Bitmap? {
        val ref = payload["uri"]?.jsonPrimitive?.contentOrNull
            ?: payload["image"]?.jsonPrimitive?.contentOrNull
            ?: options["uri"] ?: options["image"]
        return runCatching { decodeBitmap(ctx, ref) }.getOrNull()
    }

    /** Decodes a `data:`/`file://`/`content://`/bare-base64 image reference into a bitmap. */
    private fun decodeBitmap(ctx: Context, uri: String?): Bitmap {
        val raw = uri?.trim().orEmpty()
        require(raw.isNotEmpty()) { "embedding needs a bitmap uri" }
        val bytes: ByteArray = when {
            raw.startsWith("data:image") -> Base64.decode(raw.substringAfter("base64,"), Base64.DEFAULT)
            raw.startsWith("content://") ->
                ctx.contentResolver.openInputStream(Uri.parse(raw))?.use { it.readBytes() }
                    ?: error("Unable to open $raw")
            raw.startsWith("file://") -> File(raw.removePrefix("file://")).readBytes()
            raw.startsWith("/") -> File(raw).readBytes()
            else -> Base64.decode(raw, Base64.DEFAULT)
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: error("Unable to decode bitmap from uri")
    }

    private fun packFloats(v: FloatArray): String {
        val buf = ByteBuffer.allocate(v.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        v.forEach { buf.putFloat(it) }
        return Base64.encodeToString(buf.array(), Base64.NO_WRAP)
    }

    private fun unpackFloats(s: String): FloatArray {
        val bytes = Base64.decode(s, Base64.DEFAULT)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / Float.SIZE_BYTES) { buf.getFloat() }
    }

    // -------------------------------------------------------------------------
    // WORKSPACE FILE STORE (BYTES IN, BYTES OUT)
    // -------------------------------------------------------------------------
    private suspend fun executeFiles(
        operation: String,
        options: Map<String, String>,
        payload: JsonObject,
        tenantId: String
    ): InternalToolResult {
        val mergedArgs = buildMap {
            putAll(options)
            if (operation !in setOf("default", "invoke")) {
                put("op", operation)
            }
            payload.forEach { (k, v) ->
                // Nested objects (e.g. an `output` map) are not tool arguments.
                if (k != "output" && k != "options") put(k, jsonContent(v))
            }
        }

        val toolResult = engine.tools.impl.FilesTool().invoke(
            args = ToolArgs(mergedArgs),
            ctx = ToolContext(workspaceId = tenantId)
        )
        return internalFromToolResult(
            toolResult = toolResult,
            label = "Files",
            successSummary = "Files [$operation] completed"
        )
    }

    private suspend fun executeImageManipulation(
        operation: String,
        options: Map<String, String>,
        payload: JsonObject,
        tenantId: String
    ): InternalToolResult {
        val mergedArgs = buildMap {
            putAll(options)
            if (operation !in setOf("default", "invoke", "process")) {
                put("op", operation)
            }
            payload.forEach { (k, v) ->
                // Nested objects (`output`, `pipeline`) keep their own shape below.
                if (k != "output") put(k, jsonContent(v))
            }
        }

        // No source reference means the caller wants to supply one, so ask for a photo.
        // `camera=false` only forbids the camera; the file picker stays available.
        var sourceUri = mergedArgs["uri"] ?: mergedArgs["image"]
        if (sourceUri.isNullOrBlank()) {
            val allowCamera = mergedArgs["camera"]?.equals("false", ignoreCase = true) != true
            val sink = imageSourceSink
                ?: return noInput("Image manipulation", "No image source host is installed")
            sourceUri = sink(allowCamera)
            if (sourceUri.isNullOrBlank()) {
                return cancelled("Image manipulation", "No image was captured or selected")
            }
        }

        val args = buildMap {
            putAll(mergedArgs)
            put("uri", sourceUri)
            // The pipeline arrives as JSON text from the card; ToolArgs is a flat string map.
            payload["pipeline"]?.let { put("pipeline", it.toString()) }
        }

        val toolResult = engine.tools.impl.ImageManipulationTool().invoke(
            args = ToolArgs(args),
            ctx = ToolContext(workspaceId = tenantId)
        )
        return internalFromToolResult(
            toolResult = toolResult,
            label = "Image manipulation",
            successSummary = "Image processing [$operation] completed"
        )
    }

    // -------------------------------------------------------------------------
    // SIGNAL PROTOCOL E2EE (OFFICIAL LIBRARY, ZERO CUSTOM CRYPTO)
    // -------------------------------------------------------------------------
    private suspend fun executeLibSignal(
        operation: String,
        options: Map<String, String>,
        payload: JsonObject,
        tenantId: String
    ): InternalToolResult {
        val mergedArgs = buildMap {
            putAll(options)
            put("op", operation)
            payload.forEach { (k, v) -> put(k, jsonContent(v)) }
        }
        val toolResult = engine.tools.impl.LibSignalTool().invoke(
            args = ToolArgs(mergedArgs),
            ctx = ToolContext(workspaceId = tenantId)
        )
        return internalFromToolResult(
            toolResult = toolResult,
            label = "Signal E2EE",
            successSummary = "Signal $operation completed"
        )
    }

    // -------------------------------------------------------------------------
    // ON-DEVICE IMAGE CLASSIFIER (MobileNetV4 INT8)
    // -------------------------------------------------------------------------
    private suspend fun executeMobileNetV4(
        operation: String,
        options: Map<String, String>,
        payload: JsonObject,
        tenantId: String
    ): InternalToolResult {
        val topK = options["top_k"]?.toIntOrNull() ?: 3
        val minConfidence = options["min_confidence"]?.toDoubleOrNull() ?: 0.40
        val ctx = engine.db.currentAndroidContext?.invoke()
            ?: return unsupported("MobileNetV4 classifier", "No Android context for model assets")

        val imageRef = payload["uri"]?.jsonPrimitive?.contentOrNull
            ?: payload["image"]?.jsonPrimitive?.contentOrNull
            ?: payload["base64"]?.jsonPrimitive?.contentOrNull
            ?: options["uri"]
            ?: options["image"]

        // With no image argument, acquire a real frame from the camera rather than inventing a
        // prediction: classification is only meaningful against actual pixels.
        var sourceImage = imageRef
        val bitmap = if (imageRef != null) {
            try {
                decodeBitmap(ctx, imageRef)
            } catch (e: Exception) {
                return InternalToolResult(
                    summary = "MobileNetV4 could not read the image",
                    dataMap = mapOf("status" to "FAILED", "error" to (e.message ?: "unreadable image")),
                    resultJson = buildJsonObject {
                        put("status", "FAILED")
                        put("error", e.message ?: "unreadable image")
                    }
                )
            }
        } else {
            // No image argument: let the user choose a photo from the gallery or take one now,
            // rather than only supporting the camera.
            val wantsCamera = (payload["camera"]?.jsonPrimitive?.contentOrNull ?: options["camera"])
                ?.equals("false", ignoreCase = true) != true
            val sink = imageSourceSink
                ?: return noInput("MobileNetV4", "No image source host is installed")
            val acquired = sink(wantsCamera)
                ?: return cancelled("MobileNetV4", "No image was captured or selected")
            sourceImage = acquired
            try {
                decodeBitmap(ctx, acquired)
            } catch (e: Exception) {
                return InternalToolResult(
                    summary = "MobileNetV4 could not read the image",
                    dataMap = mapOf("status" to "FAILED", "error" to (e.message ?: "unreadable image")),
                    resultJson = buildJsonObject {
                        put("status", "FAILED")
                        put("error", e.message ?: "unreadable image")
                    }
                )
            }
        }

        val t0 = currentTimeMillis()
        val predictions = try {
            engine.tools.vector.ImageClassifier.classify(ctx, bitmap, topK, minConfidence)
        } catch (e: Throwable) {
            // UnsatisfiedLinkError (native TFLite missing) is an Error, not an Exception; letting
            // it escape would crash the terminal instead of reporting an unusable model.
            bitmap.recycle()
            return InternalToolResult(
                summary = "MobileNetV4 inference failed",
                dataMap = mapOf("status" to "FAILED", "error" to (e.message ?: "inference error")),
                resultJson = buildJsonObject {
                    put("status", "FAILED")
                    put("error", e.message ?: "inference error")
                }
            )
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
        val elapsed = (currentTimeMillis() - t0).coerceAtLeast(1L)

        if (predictions.isEmpty()) {
            val data = mapOf(
                "status" to "NO_CODE_FOUND",
                "error" to "No class cleared the confidence threshold ($minConfidence)",
                "threshold" to minConfidence,
                "inference_ms" to elapsed
            )
            return InternalToolResult(
                summary = "MobileNetV4 found no confident match",
                dataMap = data,
                resultJson = buildJsonObject { data.forEach { (k, v) -> put(k, v.toString()) } }
            )
        }

        val top = predictions.first()
        val data = mapOf(
            "status" to "SUCCESS",
            "prediction_label" to top.label,
            "prediction_index" to top.index,
            "confidence" to top.confidence,
            "candidates" to predictions.joinToString("|") { "${it.index}:${"%.3f".format(it.confidence)}" },
            "labels" to predictions.joinToString("|") { it.label },
            "model" to "MobileNetV4-small INT8 (ImageNet-1k)",
            "tenant_id" to tenantId,
            "inference_ms" to elapsed,
            // Echo the classified image so the card can show it next to the prediction.
            "source_image" to (sourceImage ?: ""),
            "timestamp" to currentTimeMillis()
        )
        return InternalToolResult(
            summary = "MobileNetV4 classified [${top.label}] (${(top.confidence * 100).toInt()}% confidence)",
            dataMap = data,
            resultJson = buildJsonObject { data.forEach { (k, v) -> put(k, v.toString()) } }
        )
    }

    // -------------------------------------------------------------------------
    // ON-DEVICE SALIENT SEGMENTATION (U-2-Net alpha matte)
    // -------------------------------------------------------------------------
    private fun noInput(label: String, message: String): InternalToolResult {
        val data = mapOf("status" to "NO_INPUT", "error" to message)
        return InternalToolResult(
            summary = "$label: $message",
            dataMap = data,
            resultJson = buildJsonObject { data.forEach { (k, v) -> put(k, v) } }
        )
    }

    /** A user-initiated cancel is not a tool failure; keep the card state untouched. */
    private fun cancelled(label: String, message: String): InternalToolResult {
        val data = mapOf("status" to "CANCELLED", "cancelled" to "true", "error" to message)
        return InternalToolResult(
            summary = "$label cancelled: $message",
            dataMap = data,
            resultJson = buildJsonObject { data.forEach { (k, v) -> put(k, v) } }
        )
    }

    private suspend fun executeU2Net(
        operation: String,
        options: Map<String, String>,
        payload: JsonObject,
        tenantId: String
    ): InternalToolResult {
        // U-2-Netp is bundled as a 4.4 MB TFLite graph and runs locally. The colour matte is
        // only a fallback for hosts where TFLite cannot load (e.g. plain JVM tests).
        val ctx = engine.db.currentAndroidContext?.invoke()
            ?: return unsupported("Background removal", "No Android context")

        val declared = payload["uri"]?.jsonPrimitive?.contentOrNull
            ?: payload["image"]?.jsonPrimitive?.contentOrNull
            ?: options["uri"]
            ?: options["image"]
        val wantsCamera = (payload["camera"]?.jsonPrimitive?.contentOrNull ?: options["camera"])
            ?.equals("false", ignoreCase = true) != true
        val uri = declared ?: run {
            val sink = imageSourceSink
                ?: return noInput("Background removal", "No image source host is installed")
            sink(wantsCamera) ?: return cancelled("Background removal", "No image was captured or selected")
        }

        // Prefer the real U-2-Netp graph when the bundled model and TFLite runtime load.
        android.util.Log.d("U2NetTool", "segment input=$uri")
        val u2net = try {
            val bitmap = decodeBitmap(ctx, uri)
            try {
                engine.tools.image.U2NetEngine.segment(ctx, tenantId, bitmap).also {
                    android.util.Log.d("U2NetTool", "segment result=${it != null}")
                }
            } finally {
                bitmap.recycle()
            }
        } catch (t: Throwable) {
            android.util.Log.e("U2NetTool", "segment threw", t)
            null
        }
        if (u2net != null) {
            val data = mapOf(
                "status" to "SUCCESS",
                "uri" to u2net.cutoutUri,
                "mask_uri" to u2net.maskUri,
                "image_id" to u2net.imageId,
                "width" to u2net.width,
                "height" to u2net.height,
                "confidence" to u2net.confidence,
                "inference_ms" to u2net.inferenceMs,
                "algorithm" to "U-2-Netp (TFLite, 320x320 salient-object matte)",
                "model" to "u2netp.tflite",
                "tensor_input" to "320x320x3",
                "saliency_cutoff" to (options["threshold"] ?: options["saliency_cutoff"] ?: "0.45"),
                "source_image" to uri
            )
            return InternalToolResult(
                summary = "U-2-Netp removed the background (${(u2net.confidence * 100).toInt()}% mask coverage)",
                dataMap = data,
                resultJson = buildJsonObject { data.forEach { (k, v) -> put(k, v.toString()) } }
            )
        }

        val result = try {
            engine.tools.image.ImageManipulationEngine.process(
                tenantId = tenantId,
                inputUriOrBase64 = uri,
                operations = listOf(
                    buildJsonObject {
                        put("op", "remove_background")
                        // Older cards sent `saliency_cutoff`; accept both so the control works.
                        val cutoff = (options["threshold"] ?: options["saliency_cutoff"])
                            ?.toDoubleOrNull() ?: 0.45
                        put("threshold", cutoff)
                    }
                ),
                encoding = "uri"
            )
        } catch (e: Throwable) {
            return InternalToolResult(
                summary = "Background removal failed",
                dataMap = mapOf("status" to "FAILED", "error" to (e.message ?: "processing error")),
                resultJson = buildJsonObject {
                    put("status", "FAILED")
                    put("error", e.message ?: "processing error")
                }
            )
        }

        val data = mapOf(
            "status" to "SUCCESS",
            "uri" to result.uri,
            // `mask_uri` matches this tool's declared output mapping.
            "mask_uri" to result.uri,
            "image_id" to result.imageId,
            "width" to result.width,
            "height" to result.height,
            "algorithm" to "on-device colour-distance matte (U-2-Net fallback)",
            "note" to "U-2-Netp inference was unavailable on this host; used the native matte fallback",
            // Echo the segmented source so the card can show what was cut out.
            "source_image" to uri
        )
        return InternalToolResult(
            summary = "Background removed with the on-device matte fallback",
            dataMap = data,
            resultJson = buildJsonObject { data.forEach { (k, v) -> put(k, v.toString()) } }
        )
    }

    // -------------------------------------------------------------------------
    // BACKGROUND ALERTS & PUSH NOTIFICATIONS
    // -------------------------------------------------------------------------
    private suspend fun executePush(
        operation: String,
        options: Map<String, String>,
        payload: JsonObject
    ): InternalToolResult {
        val mergedArgs = buildMap {
            putAll(options)
            put("op", operation)
            payload.forEach { (k, v) -> put(k, jsonContent(v)) }
        }
        val toolResult = engine.tools.impl.PushAlertTool().invoke(ToolArgs(mergedArgs), ToolContext())
        return internalFromToolResult(
            toolResult = toolResult,
            label = "Push alert",
            successSummary = "Push alert dispatched"
        )
    }

    // -------------------------------------------------------------------------
    // OFFLINE PAGE & SCHEMA CACHE
    // -------------------------------------------------------------------------
    private suspend fun executeOfflineCache(
        operation: String,
        options: Map<String, String>,
        payload: JsonObject,
        tenantId: String
    ): InternalToolResult {
        val mergedArgs = buildMap {
            putAll(options)
            put("op", operation)
            payload.forEach { (k, v) -> put(k, jsonContent(v)) }
        }
        val toolResult = engine.tools.impl.OfflineCacheTool().invoke(
            args = ToolArgs(mergedArgs),
            ctx = ToolContext(workspaceId = tenantId)
        )
        return internalFromToolResult(
            toolResult = toolResult,
            label = "Offline cache",
            successSummary = "Offline cache $operation completed"
        )
    }

    // -------------------------------------------------------------------------
    // REALTIME WEBSOCKET / SSE CHANNEL
    // -------------------------------------------------------------------------
    private suspend fun executeRealtimeWs(
        operation: String,
        options: Map<String, String>,
        payload: JsonObject,
        tenantId: String
    ): InternalToolResult {
        val mergedArgs = buildMap {
            putAll(options)
            put("op", operation)
            payload.forEach { (k, v) -> put(k, jsonContent(v)) }
        }
        val toolResult = engine.tools.impl.RealtimeWsTool().invoke(
            args = ToolArgs(mergedArgs),
            ctx = ToolContext(workspaceId = tenantId)
        )
        return internalFromToolResult(
            toolResult = toolResult,
            label = "Realtime channel",
            successSummary = "Realtime channel $operation completed"
        )
    }

    // -------------------------------------------------------------------------
    // NETWORK TRANSFER (Ktor HTTP + LAN HUB)
    // -------------------------------------------------------------------------
    private suspend fun executeNet(
        operation: String,
        options: Map<String, String>,
        payload: JsonObject,
        tenantId: String
    ): InternalToolResult {
        val mergedArgs = buildMap {
            putAll(options)
            put("op", operation)
            payload.forEach { (k, v) -> put(k, jsonContent(v)) }
        }
        val toolResult = netTool.invoke(
            args = ToolArgs(mergedArgs),
            ctx = ToolContext(workspaceId = tenantId)
        )
        return internalFromToolResult(
            toolResult = toolResult,
            label = "Network transfer",
            successSummary = "Network $operation completed"
        )
    }

    // -------------------------------------------------------------------------
    // BLUETOOTH PRINTER / POS BRIDGE
    // -------------------------------------------------------------------------
    private suspend fun executePrint(
        operation: String,
        options: Map<String, String>,
        payload: JsonObject,
        tenantId: String
    ): InternalToolResult {
        val mergedArgs = buildMap {
            putAll(options)
            put("op", operation)
            payload.forEach { (k, v) -> put(k, jsonContent(v)) }
        }
        val toolResult = printTool.invoke(
            args = ToolArgs(mergedArgs),
            ctx = ToolContext(workspaceId = tenantId)
        )
        return internalFromToolResult(
            toolResult = toolResult,
            label = "Bluetooth printer",
            successSummary = "Printer $operation completed"
        )
    }

    // -------------------------------------------------------------------------
    // BARCODE / QR CODE GENERATOR (ZXing)
    // -------------------------------------------------------------------------
    private suspend fun executeCodeGenerate(
        operation: String,
        options: Map<String, String>,
        payload: JsonObject,
        tenantId: String
    ): InternalToolResult {
        val mergedArgs = buildMap {
            putAll(options)
            put("op", operation)
            payload.forEach { (k, v) -> put(k, jsonContent(v)) }
        }
        val toolResult = engine.tools.impl.CodeGenerateTool().invoke(
            args = ToolArgs(mergedArgs),
            ctx = ToolContext(workspaceId = tenantId)
        )
        return internalFromToolResult(
            toolResult = toolResult,
            label = "Code generator",
            successSummary = "Code $operation generated"
        )
    }

    // -------------------------------------------------------------------------
    // SCREEN: KEEP-AWAKE / SCREENSHOT / RECORDING
    // -------------------------------------------------------------------------
    private suspend fun executeScreen(
        operation: String,
        options: Map<String, String>,
        payload: JsonObject,
        tenantId: String
    ): InternalToolResult {
        val mergedArgs = buildMap {
            putAll(options)
            if (operation !in setOf("default", "invoke")) {
                put("op", operation)
            }
            payload.forEach { (k, v) -> put(k, jsonContent(v)) }
        }
        val toolResult = engine.tools.impl.ScreenTool().invoke(
            ToolArgs(mergedArgs),
            ToolContext(workspaceId = tenantId)
        )
        return internalFromToolResult(
            toolResult = toolResult,
            label = "Screen",
            successSummary = "Screen ${mergedArgs["op"] ?: operation} executed"
        )
    }

    private suspend fun executeClipboard(
        operation: String,
        options: Map<String, String>,
        payload: JsonObject
    ): InternalToolResult {
        val mergedArgs = buildMap {
            putAll(options)
            put("op", operation)
            payload.forEach { (k, v) -> put(k, jsonContent(v)) }
        }
        val toolResult = engine.tools.impl.ClipboardTool().invoke(ToolArgs(mergedArgs), ToolContext())
        return internalFromToolResult(
            toolResult = toolResult,
            label = "Clipboard",
            successSummary = "Clipboard $operation completed"
        )
    }

    private suspend fun executeBiometrics(
        operation: String,
        options: Map<String, String>,
        payload: JsonObject
    ): InternalToolResult {
        val mergedArgs = buildMap {
            putAll(options)
            put("op", operation)
            payload.forEach { (k, v) -> put(k, jsonContent(v)) }
        }
        val toolResult = engine.tools.impl.BiometricsTool().invoke(ToolArgs(mergedArgs), ToolContext())
        return internalFromToolResult(
            toolResult = toolResult,
            label = "Biometrics",
            successSummary = "Biometric gate passed"
        )
    }

    /** Reports a capability the host cannot provide, instead of inventing a plausible result. */
    private fun unsupported(label: String, reason: String): InternalToolResult = InternalToolResult(
        summary = "$label unsupported: $reason",
        dataMap = mapOf("status" to "UNSUPPORTED", "reason" to reason),
        resultJson = buildJsonObject {
            put("status", "UNSUPPORTED")
            put("reason", reason)
        }
    )

    /**
     * Reached only for a tool id with no implementation. Reporting SUCCESS here made any typo
     * or unimplemented capability look like it had run, so the honest answer is UNSUPPORTED.
     */
    private fun executeGenericFallback(toolId: String, operation: String, payload: JsonObject): InternalToolResult =
        unsupported("Tool '$toolId'", "No implementation is registered for '$toolId/$operation'")

    /**
     * Renders any JSON value as a flat string argument. Primitives keep their
     * literal content; nested objects/arrays are serialized, never unwrapped
     * with `jsonPrimitive` (which throws on non-primitive elements).
     */
    private fun jsonContent(value: JsonElement): String =
        (value as? JsonPrimitive)?.contentOrNull ?: value.toString()

    private fun JsonElement.jsonPrimitiveOrNull(): String? =
        (this as? JsonPrimitive)?.contentOrNull

    /**
     * Adapts a capability [ToolResult] into the suite's internal result shape,
     * preserving the tool's native data types so output mapping can emit
     * correctly-typed variables (IntVal/DoubleVal/Bool/Str).
     */
    private fun internalFromToolResult(
        toolResult: ToolResult,
        label: String,
        successSummary: String
    ): InternalToolResult = when (toolResult) {
        is ToolResult.Success -> InternalToolResult(
            summary = successSummary,
            dataMap = toolResult.data.filterValues { it != null }.mapValues { it.value!! },
            resultJson = buildJsonObject { toolResult.data.forEach { (k, v) -> putJsonValue(k, v) } }
        )
        is ToolResult.Failed -> InternalToolResult(
            summary = "$label failed: ${toolResult.message}",
            dataMap = mapOf("status" to "FAILED", "error" to toolResult.message),
            resultJson = buildJsonObject { put("status", "FAILED"); put("error", toolResult.message) }
        )
        is ToolResult.Unsupported -> InternalToolResult(
            summary = "$label unsupported: ${toolResult.reason}",
            dataMap = mapOf("status" to "UNSUPPORTED", "reason" to toolResult.reason),
            resultJson = buildJsonObject { put("status", "UNSUPPORTED"); put("reason", toolResult.reason) }
        )
        is ToolResult.PermissionDenied -> InternalToolResult(
            summary = "$label permission denied: ${toolResult.permission}",
            dataMap = mapOf("status" to "DENIED", "permission" to toolResult.permission),
            resultJson = buildJsonObject { put("status", "DENIED"); put("permission", toolResult.permission) }
        )
    }

    private fun JsonObjectBuilder.putJsonValue(key: String, value: Any?) {
        when (value) {
            null -> put(key, JsonNull)
            is Boolean -> put(key, value)
            is Int -> put(key, value)
            is Long -> put(key, value)
            is Double -> put(key, value)
            is Float -> put(key, value.toDouble())
            is JsonElement -> put(key, value)
            else -> put(key, value.toString())
        }
    }
}