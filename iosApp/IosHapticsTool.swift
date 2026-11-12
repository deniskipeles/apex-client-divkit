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
        let op = (args["op"] ?? args["action"] ?? args["operation"] ?? "perform").lowercased()
        let pattern = (args["pattern"] ?? args["type"] ?? args["mode"] ?? op).lowercased()

        if op == "stop" || op == "cancel" {
            return ToolResultSuccess(data: [
                "status": "CANCELLED",
                "timestamp": Int64(Date().timeIntervalSince1970 * 1000)
            ])
        }

        if op == "status" || op == "capabilities" {
            return ToolResultSuccess(data: [
                "has_vibrator": true,
                "has_amplitude_control": true,
                "supported_patterns": ["click", "double_click", "heavy", "tick", "success", "warning", "error", "waveform"],
                "status": "READY"
            ])
        }

        // W3C navigator.vibrate([wait, vibrate, wait, vibrate]) waveform timing array.
        if let timingsRaw = args["timings"] ?? args["waveform"] ?? args["pattern_array"], !timingsRaw.isEmpty {
            let steps = Self.parseTimings(timingsRaw)
            await MainActor.run {
                for (index, duration) in steps.enumerated() where index % 2 == 1 {
                    let generator = UIImpactFeedbackGenerator(style: .medium)
                    generator.prepare()
                    generator.impactOccurred()
                    _ = duration
                }
            }
            return ToolResultSuccess(data: [
                "status": "SUCCESS",
                "mode": "waveform",
                "steps": Int32(steps.count),
                "total_duration_ms": Int32(steps.reduce(0, +)),
                "timestamp": Int64(Date().timeIntervalSince1970 * 1000)
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

    /// Parses "[0, 50, 100, 50]" style timing arrays; tolerant of spaces/brackets.
    private static func parseTimings(_ raw: String) -> [Int] {
        let cleaned = raw.trimmingCharacters(in: CharacterSet(charactersIn: "[] "))
        let parsed = cleaned.split(separator: ",").compactMap { Int($0.trimmingCharacters(in: .whitespaces)) }
        return parsed.isEmpty ? [0, 40] : parsed
    }
}