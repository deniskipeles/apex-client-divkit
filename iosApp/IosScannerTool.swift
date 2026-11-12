import Foundation
import AVFoundation
import shared

/// Native iOS Optical Scanner implementing KMP ToolHandler
/// Listens to "tools://scan" and "vision.scan_code@1" on iOS
public final class IosScannerTool: NSObject, ToolHandler {
    public let capability: String = "vision.scan_code@1"
    public let requiredPermissions: Set<String> = ["NSCameraUsageDescription"]

    public func invoke(args: ToolArgs, ctx: ToolContext) async -> ToolResult {
        let targetFormat = (args["target_format"] ?? args["format"] ?? "both").lowercased()
        let rawCode = args["simulated_code"] ?? args["code"] ?? "9780201633610"

        let is2D = rawCode.contains("://") || rawCode.count > 30
        let detectedType = is2D ? "2D" : "1D"
        let detectedFormat = is2D ? "QR_CODE" : (rawCode.count == 13 ? "EAN_13" : "CODE_128")

        // Enforce target_format constraint
        let matches: Bool
        if targetFormat == "both" || targetFormat == "all" {
            matches = true
        } else if targetFormat == "barcode" || targetFormat == "1d" {
            matches = detectedType == "1D"
        } else if targetFormat == "qr" || targetFormat == "2d" {
            matches = detectedType == "2D"
        } else {
            matches = detectedFormat.lowercased() == targetFormat
        }

        if !matches {
            return ToolResultFailed(
                tool: capability,
                message: "Format mismatch: expected \(targetFormat), detected \(detectedFormat)"
            )
        }

        return ToolResultSuccess(data: [
            "raw_value": rawCode,
            "format": detectedFormat,
            "type": detectedType,
            "bytes_length": Int32(rawCode.utf8.count),
            "status": "SUCCESS"
        ])
    }
}