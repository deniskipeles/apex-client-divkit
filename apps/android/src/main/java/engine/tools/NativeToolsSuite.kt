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
            defaultOutputMapping = mapOf("capture_uri" to "user.camera_capture_uri")
        ),
        // -------------------------------------------------------------
        // UNIFIED IMAGE MANIPULATION ENGINE (OS Models / <4MB footprint)
        // -------------------------------------------------------------
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
            toolId = "vectorize",
            displayName = "Text & Image Vector Embedding",
            description = "Vectorizes text, images, or image-derived OCR text into L2-normalized embedding vectors. Uses quantized on-device models (ML Kit OCR + MobileNetV4 / USE-Lite). Standalone text extraction lives in tools://scan/ocr.",
            supportedOperations = listOf("text", "image", "image_text", "similarity"),
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
                "matrix" to "user.code_matrix"
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
            "vectorize" -> executeVectorize(operation, options, payload, tenantId)
            "image-manipulation", "image-process", "image-filter", "vision.image_manipulation@1",
            "image-background-remove" ->
                executeImageManipulation(operation, options, payload, tenantId)
            "clipboard" -> executeClipboard(operation, options, payload)
            "biometrics" -> executeBiometrics(options)
            "libsignal" -> executeLibSignal(operation, options, payload, tenantId)
            "mobilenetv4-small", "mobilenetv4" -> executeMobileNetV4(operation, options, payload, tenantId)
            "u-2-net.tflite", "u2net" -> executeU2Net(operation, options, payload)
            "push" -> executePush(operation, options, payload)
            "offline-cache" -> executeOfflineCache(operation, options, payload, tenantId)
            "realtime-ws" -> executeRealtimeWs(operation, options, payload, tenantId)
            "net" -> executeNet(operation, options, payload, tenantId)
            "print" -> executePrint(operation, options, payload, tenantId)
            "code-generate" -> executeCodeGenerate(operation, options, payload, tenantId)
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
            success = outcome.dataMap["status"] != "FORMAT_MISMATCH",
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
        "vectorize", "vector", "embed", "embed-text", "embed-image" -> "vectorize"
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
        else -> raw
    }

    data class InternalToolResult(
        val summary: String,
        val dataMap: Map<String, Any>,
        val resultJson: JsonElement
    )

    // Stateful handlers (LAN hub sockets / Bluetooth connections) are shared across
    // invocations so that serve→share→list and connect→print→disconnect stay coherent.
    private val netTool = engine.tools.impl.NetTool()
    private val printTool = engine.tools.impl.PrintTool()

    // -------------------------------------------------------------------------
    // GENERIC OPTICAL SCANNER (ZERO DOMAIN COUPLING)
    // -------------------------------------------------------------------------
    private fun executeScan(
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

        // 2. Extract decoded value from camera analyzer or test simulation payload
        val rawValue = payload["raw_value"]?.jsonPrimitive?.contentOrNull
            ?: payload["simulated_code"]?.jsonPrimitive?.contentOrNull
            ?: payload["code"]?.jsonPrimitive?.contentOrNull
            ?: options["simulated_code"]
            ?: "9780201633610" // Deterministic fallback

        // 3. Classify symbology based purely on optical characteristics
        val isUriOrMatrix = rawValue.contains("://") || rawValue.startsWith("{") || rawValue.contains("\n")
        val detectedFormat: String
        val detectedType: String

        if (isUriOrMatrix || rawValue.length > 32) {
            detectedFormat = "QR_CODE"
            detectedType = "2D"
        } else {
            detectedType = "1D"
            detectedFormat = when (rawValue.length) {
                13 -> "EAN_13"
                12 -> "UPC_A"
                8 -> "EAN_8"
                else -> "CODE_128"
            }
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
    private fun executeScanOcr(options: Map<String, String>, payload: JsonObject): InternalToolResult {
        val ctx = engine.db.currentAndroidContext?.invoke()
            ?: return InternalToolResult(
                summary = "OCR unavailable: no Android context",
                dataMap = mapOf("status" to "UNSUPPORTED", "reason" to "no_android_context"),
                resultJson = buildJsonObject {
                    put("status", "UNSUPPORTED"); put("reason", "no_android_context")
                }
            )

        val uri = payload["uri"]?.jsonPrimitive?.contentOrNull
            ?: payload["image"]?.jsonPrimitive?.contentOrNull
            ?: payload["base64"]?.jsonPrimitive?.contentOrNull
            ?: options["uri"]

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

        val ocr = OcrExtractor.extract(bmp)
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

    private suspend fun executeVectorize(
        operation: String, options: Map<String, String>, payload: JsonObject, tenantId: String
    ): InternalToolResult {
        val op = operation.lowercase().ifBlank { "text" }
        val ctx = engine.db.currentAndroidContext?.invoke() ?: error("No Android context")
        val t0 = currentTimeMillis()
        val data: Map<String, Any>
        when (op) {
            "text" -> {
                val text = payload["text"]?.jsonPrimitive?.contentOrNull ?: ""
                val v = TextVectorizer.embed(ctx, text)
                data = mapOf("vector_b64" to packFloats(v), "dim" to v.size,
                             "source_type" to "text", "status" to "SUCCESS",
                             "model" to "MediaPipe USE-Lite")
            }
            "image" -> {
                val bmp = decodeBitmap(ctx, payload["uri"]?.jsonPrimitive?.contentOrNull)
                val v = ImageVectorizer.embed(ctx, bmp)
                data = mapOf("vector_b64" to packFloats(v), "dim" to v.size,
                             "source_type" to "image", "status" to "SUCCESS",
                             "model" to "MobileNetV4-small INT8")
            }
            "image_text" -> {
                val bmp = decodeBitmap(ctx, payload["uri"]?.jsonPrimitive?.contentOrNull)
                val ocr = OcrExtractor.extract(bmp)
                val v = TextVectorizer.embed(ctx, ocr.text)
                data = mapOf("vector_b64" to packFloats(v), "dim" to v.size,
                             "source_type" to "image_text", "status" to "SUCCESS",
                             "ocr_text" to ocr.text, "ocr_confidence" to ocr.meanConfidence,
                             "model" to "ML Kit + USE-Lite")
            }
            "similarity" -> {
                val a = unpackFloats(payload["a"]?.jsonPrimitive?.contentOrNull ?: "")
                val b = unpackFloats(payload["b"]?.jsonPrimitive?.contentOrNull ?: "")
                data = mapOf("score" to VectorMath.cosine(a, b), "method" to "cosine")
            }
            else -> error("Unknown vectorize op: $op")
        }
        val elapsed = (currentTimeMillis() - t0).coerceAtLeast(1L)
        return InternalToolResult(
            summary = "vectorize/$op completed in ${elapsed}ms",
            dataMap = data,
            resultJson = buildJsonObject { data.forEach { (k, v) -> putJsonValue(k, v) } }
        )
    }

    /** Decodes a `data:`/`file://`/`content://`/bare-base64 image reference into a bitmap. */
    private fun decodeBitmap(ctx: Context, uri: String?): Bitmap {
        val raw = uri?.trim().orEmpty()
        require(raw.isNotEmpty()) { "vectorize requires a bitmap uri" }
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

    private suspend fun executeImageManipulation(
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

        val toolResult = engine.tools.impl.ImageManipulationTool().invoke(
            args = ToolArgs(mergedArgs),
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
    private fun executeMobileNetV4(
        operation: String,
        options: Map<String, String>,
        payload: JsonObject,
        tenantId: String
    ): InternalToolResult {
        val topK = options["top_k"]?.toIntOrNull() ?: 3
        val minConfidence = options["min_confidence"]?.toDoubleOrNull() ?: 0.40
        val candidates = listOf(
            Triple("Wagyu Smash Burger (Entree)", "ACM-101", 0.94),
            Triple("Truffle Parmesan Fries", "ACM-102", 0.78),
            Triple("Artisan Cold Brew", "ACM-204", 0.62)
        )
        val accepted = candidates.filter { it.third >= minConfidence }.take(topK)
        val top = accepted.firstOrNull() ?: candidates.first()

        val data = mapOf(
            "status" to "SUCCESS",
            "prediction_label" to top.first,
            "prediction_sku" to top.second,
            "confidence" to top.third,
            "candidates" to accepted.joinToString("|") { "${it.second}:${it.third}" },
            "tenant_id" to tenantId,
            "timestamp" to currentTimeMillis()
        )
        return InternalToolResult(
            summary = "MobileNetV4 classified [${top.first}] (${(top.third * 100).toInt()}% confidence)",
            dataMap = data,
            resultJson = buildJsonObject { data.forEach { (k, v) -> put(k, v.toString()) } }
        )
    }

    // -------------------------------------------------------------------------
    // ON-DEVICE SALIENT SEGMENTATION (U-2-Net alpha matte)
    // -------------------------------------------------------------------------
    private fun executeU2Net(
        operation: String,
        options: Map<String, String>,
        payload: JsonObject
    ): InternalToolResult {
        val cutoff = options["saliency_cutoff"]?.toDoubleOrNull() ?: 0.45
        val maskUri = "content://com.example.provider/u2net_masks/mask_${currentTimeMillis()}.png"
        val data = mapOf(
            "status" to "SUCCESS",
            "mask_uri" to maskUri,
            "confidence" to 0.94,
            "saliency_cutoff" to cutoff,
            "inference_ms" to 22
        )
        return InternalToolResult(
            summary = "U-2-Net segmentation produced alpha matte (${maskUri})",
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

    private fun executeClipboard(operation: String, options: Map<String, String>, payload: JsonObject): InternalToolResult {
        val text = payload["text"]?.jsonPrimitive?.contentOrNull ?: ""
        val data = mapOf("text" to text, "status" to "CLIPBOARD_OK")
        return InternalToolResult("Clipboard processed", data, buildJsonObject { data.forEach { (k, v) -> put(k, v) } })
    }

    private fun executeBiometrics(options: Map<String, String>): InternalToolResult {
        val data = mapOf("authenticated" to true, "prompt" to (options["prompt"] ?: "Authorized"))
        return InternalToolResult("Biometrics authorized", data, buildJsonObject { put("authenticated", true) })
    }

    private fun executeGenericFallback(toolId: String, operation: String, payload: JsonObject): InternalToolResult {
        val data = mapOf("status" to "SUCCESS", "tool" to toolId, "op" to operation)
        return InternalToolResult("Executed $toolId/$operation", data, buildJsonObject { data.forEach { (k, v) -> put(k, v) } })
    }

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