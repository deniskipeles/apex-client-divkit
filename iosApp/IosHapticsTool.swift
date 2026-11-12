import Foundation
import UIKit
import AudioToolbox
import shared

/// Native iOS Tactile Actuator implementing KMP ToolHandler
/// Maps to "tools://haptics" and "hardware.haptics@1" on iOS
public final class IosHapticsTool: NSObject, ToolHandler {
    public let capability: String = "hardware.haptics@1"
    public let requiredPermissions: Set<String> = []

    public func invoke(args: ToolArgs, ctx: ToolContext) async -> ToolResult {
        let op = (args["op"] ?? args["action"] ?? "perform").lowercased()
        let pattern = (args["pattern"] ?? args["type"] ?? op).lowercased()

        if op == "stop" || op == "cancel" {
            return ToolResultSuccess(data: ["status": "CANCELLED"])
        }

        if op == "status" || op == "capabilities" {
            return ToolResultSuccess(data: [
                "has_vibrator": true,
                "supported_patterns": ["click", "light", "medium", "heavy", "tick", "success", "warning", "error"]
            ])
        }

        await MainActor.run {
            switch pattern {
            case "click", "light":
                let generator = UIImpactFeedbackGenerator(style: .light)
                generator.prepare()
                generator.impactOccurred()

            case "medium":
                let generator = UIImpactFeedbackGenerator(style: .medium)
                generator.prepare()
                generator.impactOccurred()

            case "heavy", "heavy_click":
                let generator = UIImpactFeedbackGenerator(style: .heavy)
                generator.prepare()
                generator.impactOccurred()

            case "tick", "selection":
                let generator = UISelectionFeedbackGenerator()
                generator.prepare()
                generator.selectionChanged()

            case "success":
                let generator = UINotificationFeedbackGenerator()
                generator.prepare()
                generator.notificationOccurred(.success)

            case "warning":
                let generator = UINotificationFeedbackGenerator()
                generator.prepare()
                generator.notificationOccurred(.warning)

            case "error", "failure", "buzz":
                let generator = UINotificationFeedbackGenerator()
                generator.prepare()
                generator.notificationOccurred(.error)

            default:
                // Fallback to standard iOS system vibration
                AudioServicesPlaySystemSound(kSystemSoundID_Vibrate)
            }
        }

        return ToolResultSuccess(data: [
            "status": "SUCCESS",
            "pattern": pattern,
            "timestamp": Int64(Date().timeIntervalSince1970 * 1000)
        ])
    }
}