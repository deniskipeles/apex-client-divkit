import Foundation
import Contract

/// Shared helpers for the portable tool implementations.
enum ToolSupport {
    static func nowMs() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    /// Maps a result data map to `ToolResult.success`, preserving native types so output
    /// mapping can emit correctly-typed variables (IntVal/DoubleVal/Bool/Str).
    static func success(_ data: [String: Any?]) -> ToolResult {
        var out: [String: AnyHashable] = [:]
        for (k, v) in data {
            guard let v else { continue }
            out[k] = hashable(v)
        }
        return .success(out)
    }

    static func hashable(_ value: Any) -> AnyHashable {
        switch value {
        case let b as Bool: return AnyHashable(b)
        case let i as Int: return AnyHashable(i)
        case let i as Int64: return AnyHashable(i)
        case let d as Double: return AnyHashable(d)
        case let f as Float: return AnyHashable(Double(f))
        case let s as String: return AnyHashable(s)
        case let a as [String]: return AnyHashable(a.joined(separator: ","))
        default: return AnyHashable(String(describing: value))
        }
    }

    /// Normalizes a `user.*`-free variable name onto the engine state bridge.
    static func push(_ key: String, _ value: String) {
        let safe = key.hasPrefix("user.") ? key : "user.\(key)"
        EngineState.shared.pushUpdate(name: safe, value: .str(value))
    }
}

/// Generic optical scanner ("vision.scan_code@1"). Mirrors Android `ScanCodeTool` +
/// the `NativeToolsSuite.executeScan` symbology classifier.
public final class ScanCodeTool: ToolHandler {
    public let capability = "vision.scan_code@1"
    public let requiredPermissions: Set<String> = ["NSCameraUsageDescription"]

    public init() {}

    public func invoke(args: ToolArgs, ctx: ToolContext) async -> ToolResult {
        let targetFormatRaw = args["target_format"] ?? args["format"] ?? args["mode"] ?? "both"
        let targetFormat = targetFormatRaw.lowercased()
        let rawValue = args["raw_value"] ?? args["simulated_code"] ?? args["code"] ?? "9780201633610"

        let isUriOrMatrix = rawValue.contains("://") || rawValue.hasPrefix("{") || rawValue.contains("\n")
        let detectedType: String
        let detectedFormat: String
        if isUriOrMatrix || rawValue.count > 32 {
            detectedType = "2D"
            detectedFormat = "QR_CODE"
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
            return .failed(
                tool: capability,
                message: "Scan Rejected: expected \(targetFormatRaw), got \(detectedFormat)"
            )
        }

        return ToolSupport.success([
            "raw_value": rawValue,
            "format": detectedFormat,
            "type": detectedType,
            "bytes_length": rawValue.utf8.count,
            "status": "SUCCESS",
            "timestamp": ToolSupport.nowMs()
        ])
    }
}

/// 8.2 vision.capture_photo@1. Mirrors Android `CapturePhotoTool`.
public final class CapturePhotoTool: ToolHandler {
    public let capability = "vision.capture_photo@1"
    public let requiredPermissions: Set<String> = ["NSCameraUsageDescription"]

    public init() {}

    public func invoke(args: ToolArgs, ctx: ToolContext) async -> ToolResult {
        let lens = args["lens"] ?? "back"
        let imageId = "img_" + String(UUID().uuidString.replacingOccurrences(of: "-", with: "").prefix(8))
        let fileUri = "content://com.example.provider/photos/\(imageId).jpg"
        return ToolSupport.success([
            "state": "ok",
            "image_id": imageId,
            "uri": fileUri,
            "lens": lens
        ])
    }
}

/// 8.3 vision.embed_image@1. Mirrors Android `EmbedImageTool`.
public final class EmbedImageTool: ToolHandler {
    public let capability = "vision.embed_image@1"
    public let requiredPermissions: Set<String> = []

    public init() {}

    public func invoke(args: ToolArgs, ctx: ToolContext) async -> ToolResult {
        guard let imageId = args["image"] ?? args["image_id"] else {
            return .failed(tool: capability, message: "missing image argument")
        }
        var sample: [String] = []
        for _ in 0..<8 { sample.append(String(format: "%.4f", Double.random(in: -1...1))) }
        return ToolSupport.success([
            "state": "ok",
            "image_id": imageId,
            "dim": 512,
            "vector_sample": sample.joined(separator: ",")
        ])
    }
}

/// 8.4 vision.remove_background@1. Mirrors Android `RemoveBackgroundTool`.
public final class RemoveBackgroundTool: ToolHandler {
    public let capability = "vision.remove_background@1"
    public let requiredPermissions: Set<String> = []

    public init() {}

    public func invoke(args: ToolArgs, ctx: ToolContext) async -> ToolResult {
        guard let imageId = args["image"] ?? args["image_id"] else {
            return .failed(tool: capability, message: "missing image argument")
        }
        let suffix = imageId.hasPrefix("img_") ? String(imageId.dropFirst(4)) : imageId
        let outId = "cutout_\(suffix)"
        let maskUri = "content://com.example.provider/cutouts/\(outId).png"
        return ToolSupport.success([
            "state": "ok",
            "image_id": outId,
            "uri": maskUri,
            "format": "PNG_TRANSPARENT"
        ])
    }
}

/// 8.5 vision.identify_product@1. Mirrors Android `ProductClassifierTool` + the
/// `NativeToolsSuite.executeMobileNetV4` candidate set.
public final class ProductClassifierTool: ToolHandler {
    public let capability = "vision.identify_product@1"
    public let requiredPermissions: Set<String> = []

    public init() {}

    public func invoke(args: ToolArgs, ctx: ToolContext) async -> ToolResult {
        let imageId = args["image"] ?? args["image_id"] ?? "img_snapshot_live"
        let topK = args.getInt("top_k", default: 3)
        let minConfidence = args.getDouble("min_confidence", default: 0.40)

        let candidates: [(label: String, sku: String, confidence: Double)] = [
            ("Wagyu Smash Burger (Entree)", "ACM-101", 0.94),
            ("Truffle Parmesan Fries", "ACM-102", 0.78),
            ("Artisan Cold Brew", "ACM-204", 0.62)
        ]
        let accepted = candidates.filter { $0.confidence >= minConfidence }.prefix(topK)
        let top = accepted.first ?? candidates[0]

        return ToolSupport.success([
            "state": "ok",
            "status": "SUCCESS",
            "image_id": imageId,
            "prediction_label": top.label,
            "prediction_sku": top.sku,
            "confidence": top.confidence,
            "candidates": accepted.map { "\($0.sku):\($0.confidence)" }.joined(separator: "|"),
            "tenant_id": ctx.workspaceId,
            "timestamp": ToolSupport.nowMs()
        ])
    }
}
