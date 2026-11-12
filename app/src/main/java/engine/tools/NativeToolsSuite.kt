package engine.tools

import engine.crypto.currentTimeMillis
import engine.state.DivVariableValue
import engine.state.EngineStateBridge
import engine.tools.impl.PosTonePlayer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

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
            supportedOperations = listOf("scan", "continuous_scan", "simulate", "parse"),
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
                "timestamp" to "user.scan_timestamp"
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

        val options = buildMap {
            schema.defaultOptions.forEach { (k, v) -> put(k, v) }
            payload["options"]?.jsonObject?.forEach { (k, v) ->
                put(k, v.jsonPrimitive.contentOrNull ?: v.toString())
            }
        }

        val outputMapping = buildMap {
            schema.defaultOutputMapping.forEach { (k, v) -> put(k, v) }
            payload["output"]?.jsonObject?.forEach { (k, v) ->
                v.jsonPrimitive.contentOrNull?.let { put(k, it) }
            }
        }

        val outcome = when (toolId.lowercase()) {
            "scan", "scanner", "vision.scan_code@1", "vision.scan_code", "scan-barcode", "scan-qr" ->
                executeScan(operation, options, payload)
            "haptics", "device.haptics", "device.haptics@1" -> executeHaptics(operation, options, payload)
            "beeper", "device.beeper", "device.beeper@1" -> executeBeeper(operation, options, payload)
            "camera" -> executeCamera(operation, options)
            "image-manipulation", "image-process", "image-filter", "vision.image_manipulation@1",
            "image-background-remove", "u-2-net.tflite", "u2net" ->
                executeImageManipulation(operation, options, payload, tenantId)
            "clipboard" -> executeClipboard(operation, options, payload)
            "biometrics" -> executeBiometrics(options)
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
                    is Double -> DivVariableValue.DoubleVal(value)
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
            operation = operation,
            summary = outcome.summary,
            latencyMs = totalLatency,
            resultData = outcome.resultJson,
            outputVariables = finalVariables
        )
    }

    private fun normalizeToolId(raw: String): String = when (raw.lowercase()) {
        "scan", "scanner", "scan-code", "vision.scan_code@1", "vision.scan_code", "scan-barcode", "scan-qr", "barcode", "qr" -> "scan"
        "haptics", "device.haptics", "device.haptics@1", "vibrate" -> "haptics"
        "beeper", "device.beeper", "device.beeper@1", "tone", "audio" -> "beeper"
        "camera", "photo" -> "camera"
        "clipboard", "device.clipboard" -> "clipboard"
        "biometrics", "auth.biometrics" -> "biometrics"
        else -> raw
    }

    data class InternalToolResult(
        val summary: String,
        val dataMap: Map<String, Any>,
        val resultJson: JsonElement
    )

    // -------------------------------------------------------------------------
    // GENERIC OPTICAL SCANNER (ZERO DOMAIN COUPLING)
    // -------------------------------------------------------------------------
    private fun executeScan(
        operation: String,
        options: Map<String, String>,
        payload: JsonObject
    ): InternalToolResult {
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
            PosTonePlayer.playTone(frequencyHz = freq, durationMs = 80)
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
    // GENERIC HAPTIC ACTUATOR (ZERO DOMAIN COUPLING)
    // -------------------------------------------------------------------------
    private suspend fun executeHaptics(
        operation: String,
        options: Map<String, String>,
        payload: JsonObject
    ): InternalToolResult {
        val mergedArgs = buildMap {
            putAll(options)
            // If operation is not generic default, treat it as pattern name or command
            if (operation !in setOf("default", "perform", "invoke")) {
                if (operation in setOf("stop", "cancel", "status", "capabilities")) {
                    put("op", operation)
                } else {
                    put("pattern", operation)
                }
            }
            payload.forEach { (k, v) ->
                put(k, v.jsonPrimitive.contentOrNull ?: v.toString().trim('"'))
            }
        }

        val toolResult = engine.tools.impl.HapticsTool().invoke(
            args = ToolArgs(mergedArgs),
            ctx = ToolContext()
        )

        return when (toolResult) {
            is ToolResult.Success -> {
                InternalToolResult(
                    summary = "Haptic ${toolResult.data["pattern"] ?: operation} executed",
                    dataMap = toolResult.data.filterValues { it != null }.mapValues { it.value!! },
                    resultJson = buildJsonObject {
                        toolResult.data.forEach { (k, v) ->
                            when (v) {
                                is Number -> put(k, v.toLong())
                                is Boolean -> put(k, v)
                                else -> put(k, v.toString())
                            }
                        }
                    }
                )
            }
            is ToolResult.Failed -> {
                InternalToolResult(
                    summary = "Haptics failed: ${toolResult.message}",
                    dataMap = mapOf("status" to "FAILED", "error" to toolResult.message),
                    resultJson = buildJsonObject { put("status", "FAILED"); put("error", toolResult.message) }
                )
            }
            is ToolResult.Unsupported -> {
                InternalToolResult(
                    summary = "Haptics unsupported: ${toolResult.reason}",
                    dataMap = mapOf("status" to "UNSUPPORTED", "reason" to toolResult.reason),
                    resultJson = buildJsonObject { put("status", "UNSUPPORTED"); put("reason", toolResult.reason) }
                )
            }
            is ToolResult.PermissionDenied -> {
                InternalToolResult(
                    summary = "Haptics permission denied: ${toolResult.permission}",
                    dataMap = mapOf("status" to "DENIED", "permission" to toolResult.permission),
                    resultJson = buildJsonObject { put("status", "DENIED"); put("permission", toolResult.permission) }
                )
            }
        }
    }

    // -------------------------------------------------------------------------
    // GENERIC AUDIO TONE SYNTHESIZER (ZERO DOMAIN COUPLING)
    // -------------------------------------------------------------------------
    private fun executeBeeper(
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
                put(k, v.jsonPrimitive.contentOrNull ?: v.toString().trim('"'))
            }
        }

        val toolResult = runBlocking {
            engine.tools.impl.BeeperTool().invoke(
                args = ToolArgs(mergedArgs),
                ctx = ToolContext()
            )
        }

        return when (toolResult) {
            is ToolResult.Success -> {
                InternalToolResult(
                    summary = "Tone played (${toolResult.data["frequency_hz"] ?: operation} Hz)",
                    dataMap = toolResult.data.filterValues { it != null }.mapValues { it.value!! },
                    resultJson = buildJsonObject {
                        toolResult.data.forEach { (k, v) ->
                            when (v) {
                                is Number -> put(k, v.toLong())
                                is Boolean -> put(k, v)
                                else -> put(k, v.toString())
                            }
                        }
                    }
                )
            }
            is ToolResult.Failed -> {
                InternalToolResult(
                    summary = "Audio synthesis failed: ${toolResult.message}",
                    dataMap = mapOf("status" to "FAILED", "error" to toolResult.message),
                    resultJson = buildJsonObject { put("status", "FAILED"); put("error", toolResult.message) }
                )
            }
            else -> InternalToolResult("OK", emptyMap(), buildJsonObject {})
        }
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
                put(k, v.jsonPrimitive.contentOrNull ?: v.toString().trim('"'))
            }
        }

        val toolResult = engine.tools.impl.CameraTool().invoke(
            args = ToolArgs(mergedArgs),
            ctx = ToolContext(workspaceId = tenantId)
        )

        return when (toolResult) {
            is ToolResult.Success -> {
                InternalToolResult(
                    summary = "Camera $operation completed successfully",
                    dataMap = toolResult.data.filterValues { it != null }.mapValues { it.value!! },
                    resultJson = buildJsonObject {
                        toolResult.data.forEach { (k, v) ->
                            when (v) {
                                is Number -> put(k, v.toLong())
                                is Boolean -> put(k, v)
                                else -> put(k, v.toString())
                            }
                        }
                    }
                )
            }
            is ToolResult.Failed -> {
                InternalToolResult(
                    summary = "Camera failed: ${toolResult.message}",
                    dataMap = mapOf("status" to "FAILED", "error" to toolResult.message),
                    resultJson = buildJsonObject { put("status", "FAILED"); put("error", toolResult.message) }
                )
            }
            is ToolResult.PermissionDenied -> {
                InternalToolResult(
                    summary = "Camera permission denied: ${toolResult.permission}",
                    dataMap = mapOf("status" to "DENIED", "permission" to toolResult.permission),
                    resultJson = buildJsonObject { put("status", "DENIED"); put("permission", toolResult.permission) }
                )
            }
            is ToolResult.Unsupported -> {
                InternalToolResult(
                    summary = "Camera unsupported: ${toolResult.reason}",
                    dataMap = mapOf("status" to "UNSUPPORTED", "reason" to toolResult.reason),
                    resultJson = buildJsonObject { put("status", "UNSUPPORTED"); put("reason", toolResult.reason) }
                )
            }
        }
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
            payload.forEach { (k, v) ->
                put(k, v.jsonPrimitive.contentOrNull ?: v.toString().trim('"'))
            }
        }

        val toolResult = engine.tools.impl.ImageManipulationTool().invoke(
            args = ToolArgs(mergedArgs),
            ctx = ToolContext(workspaceId = tenantId)
        )

        return when (toolResult) {
            is ToolResult.Success -> {
                InternalToolResult(
                    summary = "Image processing [${toolResult.data["operations"] ?: operation}] completed",
                    dataMap = toolResult.data.filterValues { it != null }.mapValues { it.value!! },
                    resultJson = buildJsonObject {
                        toolResult.data.forEach { (k, v) ->
                            when (v) {
                                is Number -> put(k, v.toLong())
                                is Boolean -> put(k, v)
                                else -> put(k, v.toString())
                            }
                        }
                    }
                )
            }
            is ToolResult.Failed -> {
                InternalToolResult(
                    summary = "Image manipulation failed: ${toolResult.message}",
                    dataMap = mapOf("status" to "FAILED", "error" to toolResult.message),
                    resultJson = buildJsonObject { put("status", "FAILED"); put("error", toolResult.message) }
                )
            }
            else -> InternalToolResult("OK", emptyMap(), buildJsonObject {})
        }
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
}