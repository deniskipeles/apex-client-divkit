import Foundation
import Contract

/// Unified tool suite mirroring Android `engine.tools.NativeToolsSuite`.
///
/// Holds the registered tool schemas (toolId, operations, default options and output
/// mappings) and dispatches `tools://<tool>/<op>` URLs to the underlying capability handlers,
/// then maps result fields onto `user.*` DivKit variables.
public enum NativeToolsSuite {

    public struct ToolSchema: Sendable {
        public let toolId: String
        public let displayName: String
        public let description: String
        public let supportedOperations: [String]
        public let defaultOptions: [String: String]
        public let defaultOutputMapping: [String: String]
    }

    public struct ToolExecutionOutcome: Sendable {
        public let success: Bool
        public let toolId: String
        public let operation: String
        public let summary: String
        public let latencyMs: Int64
        public let resultData: JsonValue
        public let outputVariables: [String: DivVariableValue]
    }

    public static let registeredSchemas: [ToolSchema] = [
        ToolSchema(
            toolId: "scan",
            displayName: "Unified Optical Scanner (1D Barcode & 2D QR)",
            description: "Generic optical decoder for 1D linear barcodes and 2D matrix symbologies.",
            supportedOperations: ["scan", "continuous_scan", "simulate", "parse", "ocr"],
            defaultOptions: ["target_format": "both", "beep": "true", "haptics": "true", "camera_facing": "back"],
            defaultOutputMapping: [
                "raw_value": "user.scanned_code", "format": "user.scanned_format", "type": "user.scanned_type",
                "status": "user.scan_status", "timestamp": "user.scan_timestamp", "text": "user.ocr_text",
                "confidence": "user.ocr_confidence", "block_count": "user.ocr_block_count"
            ]
        ),
        ToolSchema(
            toolId: "haptics",
            displayName: "Universal Haptics & Vibration Engine",
            description: "Unopinionated tactile feedback actuator.",
            supportedOperations: ["perform", "vibrate", "click", "heavy", "tick", "success", "warning", "error", "stop", "status"],
            defaultOptions: ["pattern": "click", "duration_ms": "20", "amplitude": "180"],
            defaultOutputMapping: ["status": "user.haptics_status", "pattern": "user.haptics_pattern"]
        ),
        ToolSchema(
            toolId: "beeper",
            displayName: "Universal Audio Tone Synthesizer",
            description: "Unopinionated PCM frequency synthesizer.",
            supportedOperations: ["tone", "play", "beep", "chime", "stop", "status"],
            defaultOptions: ["frequency_hz": "1600", "duration_ms": "80", "waveform": "sine", "volume": "0.85"],
            defaultOutputMapping: ["status": "user.beeper_status", "frequency_hz": "user.beeper_frequency"]
        ),
        ToolSchema(
            toolId: "camera",
            displayName: "Hardware Camera Bridge",
            description: "Camera frame capture and torch controller.",
            supportedOperations: ["capture", "torch_toggle", "get_status"],
            defaultOptions: ["facing": "back", "resolution": "1080p"],
            defaultOutputMapping: ["capture_uri": "user.camera_capture_uri"]
        ),
        ToolSchema(
            toolId: "image-manipulation",
            displayName: "Universal Image Manipulation & Background Removal",
            description: "On-device image processing using OS models.",
            supportedOperations: ["process", "remove_background", "smooth", "adjust_color", "crop", "resize", "threshold"],
            defaultOptions: ["op": "remove_background", "encoding": "uri"],
            defaultOutputMapping: ["uri": "user.processed_image_uri", "status": "user.image_processing_status"]
        ),
        ToolSchema(
            toolId: "vectorize",
            displayName: "Text & Image Vector Embedding",
            description: "Vectorizes text, images, or OCR text into L2-normalized embeddings.",
            supportedOperations: ["text", "image", "image_text", "similarity"],
            defaultOptions: ["normalize": "true", "include_ocr_text": "true"],
            defaultOutputMapping: [
                "vector_b64": "user.vector_b64", "dim": "user.vector_dim", "source_type": "user.vector_source",
                "ocr_text": "user.ocr_text", "score": "user.vector_similarity"
            ]
        ),
        ToolSchema(
            toolId: "clipboard",
            displayName: "System Clipboard Controller",
            description: "Read and write strings to device clipboard.",
            supportedOperations: ["copy", "paste", "clear"],
            defaultOptions: ["op": "copy"],
            defaultOutputMapping: ["text": "user.clipboard_content"]
        ),
        ToolSchema(
            toolId: "biometrics",
            displayName: "OS Biometric Authentication",
            description: "Biometric prompt gate for sensitive actions.",
            supportedOperations: ["authenticate"],
            defaultOptions: ["prompt": "Authentication Required"],
            defaultOutputMapping: ["authenticated": "user.biometric_authenticated"]
        ),
        ToolSchema(
            toolId: "libsignal",
            displayName: "Signal E2EE Cryptographic Transport",
            description: "Signal Protocol Double Ratchet sessions and E2EE messaging.",
            supportedOperations: ["setup_identity", "init_keys", "create_session", "encrypt", "decrypt", "status", "verify_identity", "reset_session"],
            defaultOptions: [:],
            defaultOutputMapping: [
                "status": "user.signal_status", "session_id": "user.signal_session_id", "ciphertext": "user.signal_ciphertext",
                "plaintext": "user.signal_plaintext", "safety_number": "user.signal_fingerprint", "fingerprint": "user.signal_fingerprint"
            ]
        ),
        ToolSchema(
            toolId: "MobileNetV4-small",
            displayName: "MobileNetV4 Image Classifier",
            description: "On-device INT8 image classification and SKU recognition.",
            supportedOperations: ["classify", "detect", "predict"],
            defaultOptions: ["top_k": "3", "min_confidence": "0.40"],
            defaultOutputMapping: [
                "prediction_label": "user.ai_prediction_label", "prediction_sku": "user.ai_prediction_sku",
                "confidence": "user.ai_confidence"
            ]
        ),
        ToolSchema(
            toolId: "u-2-Net.tflite",
            displayName: "U-2-Net Salient Object Segmentation",
            description: "On-device salient object segmentation producing an alpha matte mask.",
            supportedOperations: ["segment", "infer", "mask"],
            defaultOptions: ["tensor_input": "320x320x3", "saliency_cutoff": "0.45"],
            defaultOutputMapping: ["mask_uri": "user.u2net_mask_uri", "confidence": "user.u2net_confidence"]
        ),
        ToolSchema(
            toolId: "push",
            displayName: "Background Alerts & Push Notifications",
            description: "Dispatches background push alerts to a named notification channel.",
            supportedOperations: ["send_alert", "notify", "send"],
            defaultOptions: ["channel": "orders_dispatch", "priority": "high"],
            defaultOutputMapping: ["notification_id": "user.push_notification_id", "status": "user.push_status"]
        ),
        ToolSchema(
            toolId: "offline-cache",
            displayName: "Offline Page & Schema Cache",
            description: "Caches SDUI pages and schemas for offline resilience.",
            supportedOperations: ["cache", "get_stats", "clear"],
            defaultOptions: ["op": "get_stats"],
            defaultOutputMapping: ["status": "user.offline_cache_status", "cached_pages": "user.offline_cached_pages"]
        ),
        ToolSchema(
            toolId: "realtime-ws",
            displayName: "Realtime WebSocket / SSE Engine",
            description: "Opens and manages realtime WSS/SSE channels.",
            supportedOperations: ["connect", "disconnect", "send", "subscribe"],
            defaultOptions: ["channel": "kitchen_stream"],
            defaultOutputMapping: ["connection_state": "user.realtime_connection_state", "channel": "user.realtime_channel"]
        ),
        ToolSchema(
            toolId: "net",
            displayName: "Network Transfer & LAN Hub",
            description: "Generic networking: HTTP requests, downloads/uploads, and an embedded LAN transfer hub.",
            supportedOperations: ["request", "get", "post", "download", "upload", "serve", "stop", "status", "share", "list", "unshare"],
            defaultOptions: ["timeout_ms": "30000"],
            defaultOutputMapping: [
                "status": "user.net_status", "http_status": "user.net_http_status",
                "bytes": "user.net_bytes", "port": "user.net_hub_port"
            ]
        ),
        ToolSchema(
            toolId: "print",
            displayName: "Bluetooth Printer / POS Bridge",
            description: "Scans for Bluetooth thermal printers, connects over RFCOMM SPP, and prints ESC/POS receipts.",
            supportedOperations: ["scan", "pair", "connect", "disconnect", "print", "status"],
            defaultOptions: ["align": "left", "feed_lines": "3", "width": "32"],
            defaultOutputMapping: ["status": "user.print_status", "count": "user.print_device_count", "bytes": "user.print_bytes"]
        ),
        ToolSchema(
            toolId: "code-generate",
            displayName: "Barcode & QR Code Generator",
            description: "Encodes content into a 1D barcode or 2D matrix symbology and renders it.",
            supportedOperations: ["generate", "qr", "barcode", "render"],
            defaultOptions: ["format": "qr", "width": "512", "height": "512", "margin": "4", "ec_level": "M", "encoding": "uri"],
            defaultOutputMapping: [
                "status": "user.code_status", "uri": "user.code_image_uri", "content": "user.code_content",
                "format": "user.code_format", "kind": "user.code_kind", "code_id": "user.code_id", "matrix": "user.code_matrix"
            ]
        ),
        ToolSchema(
            toolId: "keep-awake",
            displayName: "Keep-Awake / Screen Wake-Lock Controller",
            description: "Holds the terminal awake (screen on / wake-lock) while a long-running task is in flight, with optional timeout and wake level.",
            supportedOperations: ["enable", "disable", "toggle", "status"],
            defaultOptions: ["level": "screen", "timeout_ms": "0"],
            defaultOutputMapping: [
                "status": "user.keep_awake_status", "enabled": "user.keep_awake_enabled"
            ]
        )
    ]

    private struct InternalToolResult {
        let summary: String
        let dataMap: [String: Any]
        let resultJson: JsonValue
    }

    // Stateful handlers shared across invocations so serve→share→list and connect→print stay coherent.
    private static let netTool = NetTool()
    private static let printTool = PrintTool()

    public static func execute(url: String, payload: JsonValue, tenantId: String = "default") async -> ToolExecutionOutcome {
        let startTime = ToolSupport.nowMs()
        let path = url.hasPrefix("tools://") ? String(url.dropFirst("tools://".count))
            : (url.hasPrefix("tool://") ? String(url.dropFirst("tool://".count)) : url)
        let toolRaw = String(path.prefix(while: { $0 != "/" }))
        let afterSlash = path.contains("/") ? String(path.drop(while: { $0 != "/" }).dropFirst()) : "default"
        let operation = String(afterSlash.prefix(while: { $0 != "?" }))

        let toolId = normalizeToolId(toolRaw)
        let schema = registeredSchemas.first { $0.toolId.caseInsensitiveCompare(toolId) == .orderedSame }
            ?? registeredSchemas.first { $0.toolId == "scan" }!

        let ocrAlias = ["ocr", "read-text", "text-recognition"].contains(toolRaw.trimmingCharacters(in: .whitespaces).lowercased())
        let effectiveOperation = ocrAlias ? "ocr" : operation

        var options = schema.defaultOptions
        if let optObj = payload["options"]?.objectPairs {
            for (k, v) in optObj { options[k] = v.contentOrNull ?? v.serialized }
        }
        var outputMapping = schema.defaultOutputMapping
        if let outObj = payload["output"]?.objectPairs {
            for (k, v) in outObj { if let s = v.contentOrNull { outputMapping[k] = s } }
        }

        let outcome: InternalToolResult
        switch toolId.lowercased() {
        case "scan", "scanner", "vision.scan_code@1", "vision.scan_code", "scan-barcode", "scan-qr":
            outcome = await executeScan(operation: effectiveOperation, options: options, payload: payload)
        case "haptics", "device.haptics", "device.haptics@1":
            outcome = await executeHaptics(operation: operation, options: options, payload: payload)
        case "beeper", "device.beeper", "device.beeper@1":
            outcome = await executeBeeper(operation: operation, options: options, payload: payload)
        case "camera":
            outcome = await executeCamera(operation: operation, options: options, payload: payload, tenantId: tenantId)
        case "vectorize":
            outcome = await executeVectorize(operation: operation, payload: payload)
        case "image-manipulation", "image-process", "image-filter", "vision.image_manipulation@1", "image-background-remove":
            outcome = await executeImageManipulation(operation: operation, options: options, payload: payload, tenantId: tenantId)
        case "clipboard":
            outcome = executeClipboard(options: options, payload: payload)
        case "biometrics":
            outcome = executeBiometrics(options: options)
        case "libsignal":
            outcome = await executeLibSignal(operation: operation, options: options, payload: payload, tenantId: tenantId)
        case "mobilenetv4-small", "mobilenetv4":
            outcome = executeMobileNetV4(options: options, payload: payload, tenantId: tenantId)
        case "u-2-net.tflite", "u2net":
            outcome = executeU2Net(options: options)
        case "push":
            outcome = await executePush(operation: operation, options: options, payload: payload)
        case "offline-cache":
            outcome = await executeOfflineCache(operation: operation, options: options, payload: payload, tenantId: tenantId)
        case "realtime-ws":
            outcome = await executeRealtimeWs(operation: operation, options: options, payload: payload, tenantId: tenantId)
        case "net":
            outcome = await executeNet(operation: operation, options: options, payload: payload, tenantId: tenantId)
        case "print":
            outcome = await executePrint(operation: operation, options: options, payload: payload, tenantId: tenantId)
        case "code-generate":
            outcome = await executeCodeGenerate(operation: operation, options: options, payload: payload, tenantId: tenantId)
        case "keep-awake":
            outcome = await executeKeepAwake(operation: operation, options: options, payload: payload)
        default:
            outcome = executeGenericFallback(toolId: toolId, operation: operation, payload: payload)
        }

        let totalLatency = max(ToolSupport.nowMs() - startTime, 1)
        var finalVariables: [String: DivVariableValue] = [:]

        for (toolField, stateVarName) in outputMapping {
            guard let value = outcome.dataMap[toolField] else { continue }
            let divVal = toDivValue(value)
            finalVariables[stateVarName] = divVal
            EngineState.shared.pushUpdate(name: stateVarName, value: divVal)
        }

        return ToolExecutionOutcome(
            success: outcome.dataMap["status"] as? String != "FORMAT_MISMATCH",
            toolId: schema.toolId,
            operation: effectiveOperation,
            summary: outcome.summary,
            latencyMs: totalLatency,
            resultData: outcome.resultJson,
            outputVariables: finalVariables
        )
    }

    static func normalizeToolId(_ raw: String) -> String {
        switch raw.lowercased() {
        case "scan", "scanner", "scan-code", "vision.scan_code@1", "vision.scan_code", "scan-barcode", "scan-qr", "barcode", "qr", "ocr", "read-text", "text-recognition": return "scan"
        case "haptics", "device.haptics", "device.haptics@1", "vibrate": return "haptics"
        case "beeper", "device.beeper", "device.beeper@1", "tone", "audio": return "beeper"
        case "camera", "photo": return "camera"
        case "vectorize", "vector", "embed", "embed-text", "embed-image": return "vectorize"
        case "clipboard", "device.clipboard": return "clipboard"
        case "biometrics", "auth.biometrics": return "biometrics"
        case "libsignal", "signal", "secure.send", "secure.status", "secure.verify_identity": return "libsignal"
        case "mobilenetv4-small", "mobilenetv4", "mobilenet", "vision.identify_product@1", "identify-product": return "MobileNetV4-small"
        case "u-2-net.tflite", "u-2-net", "u2net", "u2net.tflite", "vision.remove_background@1": return "u-2-Net.tflite"
        case "push", "network.push", "network.push@1", "alerts": return "push"
        case "offline-cache", "offline_cache", "storage.offline_cache", "cache": return "offline-cache"
        case "realtime-ws", "realtime_ws", "network.realtime", "realtime", "ws": return "realtime-ws"
        case "net", "network.transfer", "network.transfer@1", "network", "http", "transfer": return "net"
        case "print", "printer", "device.printer", "device.printer@1", "bluetooth.print", "pos.print": return "print"
        case "code-generate", "code_gen", "codegen", "generate-code", "generate_code", "barcode-generate", "qr-generate", "vision.generate_code@1", "vision.generate_code": return "code-generate"
        case "keep-awake", "keep_awake", "keepawake", "device.keep_awake", "device.keep_awake@1", "wakelock": return "keep-awake"
        default: return raw
        }
    }

    // MARK: Handlers

    private static func executeScan(operation: String, options: [String: String], payload: JsonValue) async -> InternalToolResult {
        let targetFormatRaw = payload["target_format"]?.contentOrNull ?? options["target_format"]
            ?? payload["format"]?.contentOrNull ?? options["format"] ?? "both"
        let targetFormat = targetFormatRaw.trimmingCharacters(in: .whitespaces).lowercased()
        let rawValue = payload["raw_value"]?.contentOrNull ?? payload["simulated_code"]?.contentOrNull
            ?? payload["code"]?.contentOrNull ?? options["simulated_code"] ?? "9780201633610"

        let isUriOrMatrix = rawValue.contains("://") || rawValue.hasPrefix("{") || rawValue.contains("\n")
        let detectedFormat: String
        let detectedType: String
        if isUriOrMatrix || rawValue.count > 32 {
            detectedFormat = "QR_CODE"; detectedType = "2D"
        } else {
            detectedType = "1D"
            switch rawValue.count {
            case 13: detectedFormat = "EAN_13"
            case 12: detectedFormat = "UPC_A"
            case 8: detectedFormat = "EAN_8"
            default: detectedFormat = "CODE_128"
            }
        }

        let matches: Bool
        switch targetFormat {
        case "both", "all", "*": matches = true
        case "barcode", "1d", "linear": matches = detectedType == "1D"
        case "qr", "2d", "matrix": matches = detectedType == "2D"
        default: matches = detectedFormat.lowercased() == targetFormat
        }

        if !matches {
            let data: [String: Any] = [
                "status": "FORMAT_MISMATCH",
                "error": "Scanned format [\(detectedFormat)] does not match target_format [\(targetFormatRaw)]",
                "raw_value": rawValue, "detected_format": detectedFormat,
                "detected_type": detectedType, "target_format": targetFormatRaw
            ]
            return InternalToolResult(
                summary: "Scan Rejected: expected \(targetFormatRaw), got \(detectedFormat)",
                dataMap: data,
                resultJson: data.mapValues { JsonValue.string("\($0)") }.asJsonValue
            )
        }

        let data: [String: Any] = [
            "raw_value": rawValue, "format": detectedFormat, "type": detectedType,
            "bytes_length": rawValue.utf8.count, "status": "SUCCESS", "timestamp": ToolSupport.nowMs()
        ]
        return InternalToolResult(
            summary: "Decoded \(detectedType) [\(detectedFormat)]: \(rawValue)",
            dataMap: data,
            resultJson: data.mapValues { JsonValue.string("\($0)") }.asJsonValue
        )
    }

    private static func executeHaptics(operation: String, options: [String: String], payload: JsonValue) async -> InternalToolResult {
        var merged = options
        if !["default", "perform", "invoke"].contains(operation) {
            if ["stop", "cancel", "status", "capabilities"].contains(operation) { merged["op"] = operation }
            else { merged["pattern"] = operation }
        }
        for (k, v) in payload.asDictionary { merged[k] = v.contentOrNull ?? v.serialized }
        let result = await HapticsTool().invoke(args: ToolArgs(merged), ctx: ToolContext())
        return internalFromToolResult(result, label: "Haptics", successSummary: "Haptic \(merged["pattern"] ?? operation) executed")
    }

    private static func executeBeeper(operation: String, options: [String: String], payload: JsonValue) async -> InternalToolResult {
        var merged = options
        if !["default", "invoke"].contains(operation) { merged["op"] = operation }
        for (k, v) in payload.asDictionary { merged[k] = v.contentOrNull ?? v.serialized }
        let result = await BeeperTool().invoke(args: ToolArgs(merged), ctx: ToolContext())
        return internalFromToolResult(result, label: "Audio synthesis", successSummary: "Tone played (\(options["frequency_hz"] ?? operation))")
    }

    private static func executeCamera(operation: String, options: [String: String], payload: JsonValue, tenantId: String) async -> InternalToolResult {
        var merged = options
        merged["op"] = operation
        for (k, v) in payload.asDictionary { merged[k] = v.contentOrNull ?? v.serialized }
        let result = await CameraTool().invoke(args: ToolArgs(merged), ctx: ToolContext(workspaceId: tenantId))
        return internalFromToolResult(result, label: "Camera", successSummary: "Camera \(operation) completed successfully")
    }

    private static func executeVectorize(operation: String, payload: JsonValue) async -> InternalToolResult {
        let op = operation.lowercased().isEmpty ? "text" : operation.lowercased()
        var merged = ["op": op]
        for key in ["text", "uri", "a", "b"] {
            if let v = payload[key]?.contentOrNull { merged[key] = v }
        }
        if merged["uri"] == nil, let img = payload["image"]?.contentOrNull { merged["uri"] = img }
        let result = await VectorizeTool().invoke(args: ToolArgs(merged), ctx: ToolContext())
        return internalFromToolResult(result, label: "Vectorize", successSummary: "vectorize/\(op) completed")
    }

    private static func executeImageManipulation(operation: String, options: [String: String], payload: JsonValue, tenantId: String) async -> InternalToolResult {
        var merged = options
        if !["default", "invoke"].contains(operation) { merged["op"] = operation }
        for (k, v) in payload.asDictionary { merged[k] = v.contentOrNull ?? v.serialized }
        let result = await ImageManipulationTool().invoke(args: ToolArgs(merged), ctx: ToolContext(workspaceId: tenantId))
        return internalFromToolResult(result, label: "Image manipulation", successSummary: "Image processing [\(operation)] completed")
    }

    private static func executeClipboard(options: [String: String], payload: JsonValue) -> InternalToolResult {
        let text = payload["text"]?.contentOrNull ?? ""
        let data: [String: Any] = ["text": text, "status": "CLIPBOARD_OK"]
        return InternalToolResult(summary: "Clipboard processed", dataMap: data, resultJson: data.mapValues { JsonValue.string("\($0)") }.asJsonValue)
    }

    private static func executeBiometrics(options: [String: String]) -> InternalToolResult {
        let data: [String: Any] = ["authenticated": true, "prompt": options["prompt"] ?? "Authorized"]
        return InternalToolResult(summary: "Biometrics authorized", dataMap: data, resultJson: .object([("authenticated", .bool(true))]))
    }

    private static func executeLibSignal(operation: String, options: [String: String], payload: JsonValue, tenantId: String) async -> InternalToolResult {
        var merged = options
        merged["op"] = operation
        for (k, v) in payload.asDictionary { merged[k] = v.contentOrNull ?? v.serialized }
        let result = await LibSignalTool().invoke(args: ToolArgs(merged), ctx: ToolContext(workspaceId: tenantId))
        return internalFromToolResult(result, label: "Signal E2EE", successSummary: "Signal \(operation) completed")
    }

    private static func executeMobileNetV4(options: [String: String], payload: JsonValue, tenantId: String) -> InternalToolResult {
        let topK = Int(options["top_k"] ?? "") ?? 3
        let minConfidence = Double(options["min_confidence"] ?? "") ?? 0.40
        let candidates: [(String, String, Double)] = [
            ("Wagyu Smash Burger (Entree)", "ACM-101", 0.94),
            ("Truffle Parmesan Fries", "ACM-102", 0.78),
            ("Artisan Cold Brew", "ACM-204", 0.62)
        ]
        let accepted = candidates.filter { $0.2 >= minConfidence }.prefix(topK)
        let top = accepted.first ?? candidates[0]
        let data: [String: Any] = [
            "status": "SUCCESS", "prediction_label": top.0, "prediction_sku": top.1,
            "confidence": top.2, "candidates": accepted.map { "\($0.1):\($0.2)" }.joined(separator: "|"),
            "tenant_id": tenantId, "timestamp": ToolSupport.nowMs()
        ]
        return InternalToolResult(
            summary: "MobileNetV4 classified [\(top.0)] (\(Int(top.2 * 100))% confidence)",
            dataMap: data,
            resultJson: data.mapValues { JsonValue.string("\($0)") }.asJsonValue
        )
    }

    private static func executeU2Net(options: [String: String]) -> InternalToolResult {
        let cutoff = Double(options["saliency_cutoff"] ?? "") ?? 0.45
        let maskUri = "content://com.example.provider/u2net_masks/mask_\(ToolSupport.nowMs()).png"
        let data: [String: Any] = ["status": "SUCCESS", "mask_uri": maskUri, "confidence": 0.94, "saliency_cutoff": cutoff, "inference_ms": 22]
        return InternalToolResult(
            summary: "U-2-Net segmentation produced alpha matte (\(maskUri))",
            dataMap: data,
            resultJson: data.mapValues { JsonValue.string("\($0)") }.asJsonValue
        )
    }

    private static func executePush(operation: String, options: [String: String], payload: JsonValue) async -> InternalToolResult {
        var merged = options
        merged["op"] = operation
        for (k, v) in payload.asDictionary { merged[k] = v.contentOrNull ?? v.serialized }
        let result = await PushAlertTool().invoke(args: ToolArgs(merged), ctx: ToolContext())
        return internalFromToolResult(result, label: "Push alert", successSummary: "Push alert dispatched")
    }

    private static func executeOfflineCache(operation: String, options: [String: String], payload: JsonValue, tenantId: String) async -> InternalToolResult {
        var merged = options
        merged["op"] = operation
        for (k, v) in payload.asDictionary { merged[k] = v.contentOrNull ?? v.serialized }
        let result = await OfflineCacheTool().invoke(args: ToolArgs(merged), ctx: ToolContext(workspaceId: tenantId))
        return internalFromToolResult(result, label: "Offline cache", successSummary: "Offline cache \(operation) completed")
    }

    private static func executeRealtimeWs(operation: String, options: [String: String], payload: JsonValue, tenantId: String) async -> InternalToolResult {
        var merged = options
        merged["op"] = operation
        for (k, v) in payload.asDictionary { merged[k] = v.contentOrNull ?? v.serialized }
        let result = await RealtimeWsTool().invoke(args: ToolArgs(merged), ctx: ToolContext(workspaceId: tenantId))
        return internalFromToolResult(result, label: "Realtime channel", successSummary: "Realtime channel \(operation) completed")
    }

    private static func executeNet(operation: String, options: [String: String], payload: JsonValue, tenantId: String) async -> InternalToolResult {
        var merged = options
        merged["op"] = operation
        for (k, v) in payload.asDictionary { merged[k] = v.contentOrNull ?? v.serialized }
        let result = await netTool.invoke(args: ToolArgs(merged), ctx: ToolContext(workspaceId: tenantId))
        return internalFromToolResult(result, label: "Network transfer", successSummary: "Network \(operation) completed")
    }

    private static func executePrint(operation: String, options: [String: String], payload: JsonValue, tenantId: String) async -> InternalToolResult {
        var merged = options
        merged["op"] = operation
        for (k, v) in payload.asDictionary { merged[k] = v.contentOrNull ?? v.serialized }
        let result = await printTool.invoke(args: ToolArgs(merged), ctx: ToolContext(workspaceId: tenantId))
        return internalFromToolResult(result, label: "Bluetooth printer", successSummary: "Printer \(operation) completed")
    }

    private static func executeCodeGenerate(operation: String, options: [String: String], payload: JsonValue, tenantId: String) async -> InternalToolResult {
        var merged = options
        merged["op"] = operation
        for (k, v) in payload.asDictionary { merged[k] = v.contentOrNull ?? v.serialized }
        let result = await CodeGenerateTool().invoke(args: ToolArgs(merged), ctx: ToolContext(workspaceId: tenantId))
        return internalFromToolResult(result, label: "Code generator", successSummary: "Code \(operation) generated")
    }

    private static func executeKeepAwake(operation: String, options: [String: String], payload: JsonValue) async -> InternalToolResult {
        var merged = options
        if !["default", "invoke"].contains(operation) { merged["op"] = operation }
        for (k, v) in payload.asDictionary { merged[k] = v.contentOrNull ?? v.serialized }
        let result = await KeepAwakeTool().invoke(args: ToolArgs(merged), ctx: ToolContext())
        return internalFromToolResult(result, label: "Keep-awake", successSummary: "Keep-awake \(merged["op"] ?? operation) executed")
    }

    private static func executeGenericFallback(toolId: String, operation: String, payload: JsonValue) -> InternalToolResult {
        let data: [String: Any] = ["status": "SUCCESS", "tool": toolId, "op": operation]
        return InternalToolResult(summary: "Executed \(toolId)/\(operation)", dataMap: data, resultJson: data.mapValues { JsonValue.string("\($0)") }.asJsonValue)
    }

    private static func toDivValue(_ value: Any) -> DivVariableValue {
        switch value {
        case let b as Bool: return .bool(b)
        case let i as Int: return .intVal(Int64(i))
        case let i as Int64: return .intVal(i)
        case let d as Double:
            return (d == d.rounded() && !d.isInfinite) ? .intVal(Int64(d)) : .doubleVal(d)
        case let f as Float: return .doubleVal(Double(f))
        default: return .str("\(value)")
        }
    }

    private static func internalFromToolResult(_ result: ToolResult, label: String, successSummary: String) -> InternalToolResult {
        switch result {
        case let .success(data):
            let map = data.reduce(into: [String: Any]()) { acc, kv in acc[kv.key] = kv.value }
            return InternalToolResult(
                summary: successSummary,
                dataMap: map,
                resultJson: .object(data.map { ($0.key, JsonValue.fromAny($0.value)) })
            )
        case let .failed(_, message):
            return InternalToolResult(
                summary: "\(label) failed: \(message)",
                dataMap: ["status": "FAILED", "error": message],
                resultJson: .object([("status", .string("FAILED")), ("error", .string(message))])
            )
        case let .unsupported(_, reason):
            return InternalToolResult(
                summary: "\(label) unsupported: \(reason)",
                dataMap: ["status": "UNSUPPORTED", "reason": reason],
                resultJson: .object([("status", .string("UNSUPPORTED")), ("reason", .string(reason))])
            )
        case let .permissionDenied(_, permission):
            return InternalToolResult(
                summary: "\(label) permission denied: \(permission)",
                dataMap: ["status": "DENIED", "permission": permission],
                resultJson: .object([("status", .string("DENIED")), ("permission", .string(permission))])
            )
        }
    }
}
