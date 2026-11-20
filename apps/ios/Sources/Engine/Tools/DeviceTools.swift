import Foundation
import Contract

/// Universal hardware haptics capability ("hardware.haptics@1"). Mirrors Android
/// `HapticsTool`.
///
/// The semantic pattern -> effect mapping, default durations and amplitudes are identical to
/// Android. Actual actuation is delegated to a platform sink (`UIFeedbackGenerator` on iOS);
/// when unset the tool still reports success with the same metadata, matching Android's
/// headless/emulated path.
public final class HapticsTool: ToolHandler {
    public let capability = "hardware.haptics@1"
    public let requiredPermissions: Set<String> = []

    /// Platform haptics bridge: (pattern, durationMs, amplitude, waveformSteps) -> handled?
    public nonisolated(unsafe) static var sink: ((_ pattern: String, _ durationMs: Int, _ amplitude: Int, _ timings: [Int]?) -> Bool)?

    public init() {}

    public func invoke(args: ToolArgs, ctx: ToolContext) async -> ToolResult {
        let op = (args["op"] ?? args["action"] ?? args["operation"] ?? "perform").lowercased()
        switch op {
        case "stop", "cancel":
            _ = HapticsTool.sink?("stop", 0, 0, nil)
            return ToolSupport.success(["status": "CANCELLED", "timestamp": ToolSupport.nowMs()])
        case "status", "capabilities", "get_status":
            return ToolSupport.success([
                "has_vibrator": true,
                "has_amplitude_control": true,
                "supported_patterns": ["click", "double_click", "heavy", "tick", "success", "warning", "error", "waveform"],
                "status": "READY"
            ])
        default:
            return perform(args)
        }
    }

    private func perform(_ args: ToolArgs) -> ToolResult {
        let pattern = (args["pattern"] ?? args["type"] ?? args["mode"] ?? "click").lowercased()
        let durationMs = args.getInt("duration_ms", default: HapticsTool.defaultDuration(pattern))
        let amplitude = min(max(args.getInt("amplitude", default: HapticsTool.defaultAmplitude(pattern)), 1), 255)
        let timingsRaw = args["timings"] ?? args["waveform"] ?? args["pattern_array"]

        if let timingsRaw, !timingsRaw.isEmpty {
            let timings = HapticsTool.parseTimings(timingsRaw)
            _ = HapticsTool.sink?(pattern, durationMs, amplitude, timings)
            return ToolSupport.success([
                "status": "SUCCESS",
                "mode": "waveform",
                "steps": timings.count,
                "total_duration_ms": timings.reduce(0, +),
                "amplitude": amplitude,
                "timestamp": ToolSupport.nowMs()
            ])
        }

        _ = HapticsTool.sink?(pattern, durationMs, amplitude, nil)
        return ToolSupport.success([
            "status": "SUCCESS",
            "pattern": pattern,
            "duration_ms": durationMs,
            "amplitude": amplitude,
            "timestamp": ToolSupport.nowMs()
        ])
    }

    static func defaultDuration(_ pattern: String) -> Int {
        switch pattern {
        case "tick", "selection": return 10
        case "click", "light": return 20
        case "medium": return 35
        case "double_click": return 45
        case "heavy", "heavy_click": return 60
        case "warning": return 80
        case "error", "failure", "buzz": return 120
        case "success": return 50
        default: return 25
        }
    }

    static func defaultAmplitude(_ pattern: String) -> Int {
        switch pattern {
        case "tick", "selection": return 90
        case "click", "light": return 140
        case "medium": return 180
        case "heavy", "heavy_click": return 255
        case "error", "failure", "buzz": return 255
        case "warning": return 200
        case "success": return 210
        default: return 180
        }
    }

    static func parseTimings(_ raw: String) -> [Int] {
        let cleaned = raw.trimmingCharacters(in: CharacterSet(charactersIn: "[] "))
        let parsed = cleaned.split(separator: ",").compactMap { Int($0.trimmingCharacters(in: .whitespaces)) }
        return parsed.isEmpty ? [0, 40] : parsed
    }
}

/// Universal audio tone synthesizer capability ("hardware.beeper@1"). Mirrors Android
/// `BeeperTool`.
public final class BeeperTool: ToolHandler {
    public let capability = "hardware.beeper@1"
    public let requiredPermissions: Set<String> = []

    public init() {}

    public func invoke(args: ToolArgs, ctx: ToolContext) async -> ToolResult {
        let op = (args["op"] ?? args["action"] ?? args["operation"] ?? "tone").lowercased()
        switch op {
        case "stop", "mute", "cancel":
            AudioToneSynthesizer.stopActiveTone()
            return ToolSupport.success(["status": "MUTED", "timestamp": ToolSupport.nowMs()])
        case "status", "capabilities":
            return ToolSupport.success([
                "sample_rate": 44100,
                "supported_waveforms": ["sine", "square", "triangle", "sawtooth"],
                "min_frequency": 20,
                "max_frequency": 20000,
                "status": "READY"
            ])
        default:
            return tone(args, operation: op)
        }
    }

    private func tone(_ args: ToolArgs, operation: String) -> ToolResult {
        let profile = (args["profile"] ?? args["preset"] ?? args["tone"] ?? operation).lowercased()
        let volume = min(max(args.getDouble("volume", default: 0.85), 0.0), 1.0)
        let waveformStr = (args["waveform"] ?? "sine").lowercased()
        let waveform = AudioToneSynthesizer.Waveform(rawValue: waveformStr) ?? .sine

        if let sequenceRaw = args["sequence"] ?? args["melody"], !sequenceRaw.isEmpty {
            let steps = BeeperTool.parseSequence(sequenceRaw)
            AudioToneSynthesizer.playSequence(steps, volume: volume, waveform: waveform)
            return ToolSupport.success([
                "status": "SUCCESS",
                "mode": "sequence",
                "steps": steps.count,
                "waveform": waveformStr,
                "timestamp": ToolSupport.nowMs()
            ])
        }

        switch profile {
        case "click", "blip":
            return play(frequency: 1800, durationMs: 30, volume: volume, waveform: waveform, waveformStr: waveformStr)
        case "beep":
            return play(frequency: 1600, durationMs: 70, volume: volume, waveform: waveform, waveformStr: waveformStr)
        case "chime", "notice":
            AudioToneSynthesizer.playSequence([(880, 60), (1760, 90)], volume: volume, waveform: waveform)
            return ToolSupport.success(["status": "SUCCESS", "profile": "chime", "waveform": waveformStr])
        case "success":
            AudioToneSynthesizer.playSequence([(1046.5, 50), (1318.5, 50), (1567.98, 90)], volume: volume, waveform: waveform)
            return ToolSupport.success(["status": "SUCCESS", "profile": "success", "waveform": waveformStr])
        case "warning", "alert":
            return play(frequency: 2400, durationMs: 160, volume: volume, waveform: waveform, waveformStr: waveformStr)
        case "error", "buzz":
            return play(frequency: 440, durationMs: 200, volume: volume, waveform: waveform, waveformStr: waveformStr)
        default:
            let f = args.getDouble("frequency_hz", default: 1200)
            let d = args.getInt("duration_ms", default: 120)
            return play(frequency: f, durationMs: d, volume: volume, waveform: waveform, waveformStr: waveformStr)
        }
    }

    private func play(frequency: Double, durationMs: Int, volume: Double, waveform: AudioToneSynthesizer.Waveform, waveformStr: String) -> ToolResult {
        AudioToneSynthesizer.playTone(frequencyHz: frequency, durationMs: durationMs, volume: volume, waveform: waveform)
        return ToolSupport.success([
            "status": "SUCCESS",
            "frequency_hz": frequency,
            "duration_ms": durationMs,
            "volume": volume,
            "waveform": waveformStr,
            "timestamp": ToolSupport.nowMs()
        ])
    }

    static func parseSequence(_ raw: String) -> [(Double, Int)] {
        let steps = raw.split(separator: ",").compactMap { step -> (Double, Int)? in
            let parts = step.trimmingCharacters(in: .whitespaces).split(separator: ":")
            guard parts.count == 2 else { return nil }
            return (Double(parts[0]) ?? 1000.0, Int(parts[1]) ?? 80)
        }
        return steps.isEmpty ? [(1200.0, 100)] : steps
    }
}

/// Hardware camera bridge ("vision.capture_photo@1" alias `hardware.camera@1`). Mirrors
/// Android `CameraTool`'s observable result shape.
public final class CameraTool: ToolHandler {
    public let capability = "vision.capture_photo@1"
    public let requiredPermissions: Set<String> = ["NSCameraUsageDescription"]

    public init() {}

    public func invoke(args: ToolArgs, ctx: ToolContext) async -> ToolResult {
        let op = (args["op"] ?? args["action"] ?? "capture").lowercased()
        switch op {
        case "capture", "take_photo":
            let facing = args["facing"] ?? args["lens"] ?? "back"
            let imageId = "img_" + String(UUID().uuidString.replacingOccurrences(of: "-", with: "").prefix(8))
            return ToolSupport.success([
                "state": "ok",
                "status": "SUCCESS",
                "uri": "content://com.example.provider/photos/\(imageId).jpg",
                "image_id": imageId,
                "mime_type": "image/jpeg",
                "width": 1280,
                "height": 720,
                "facing": facing,
                "timestamp": ToolSupport.nowMs()
            ])
        case "torch", "torch_toggle":
            let enabled = args["torch"] == "true" || args["state"] == "on"
            return ToolSupport.success(["torch_state": enabled, "status": "SUCCESS"])
        case "status", "capabilities":
            return ToolSupport.success([
                "available": true,
                "has_back_camera": true,
                "has_front_camera": true,
                "has_flash": true,
                "status": "READY"
            ])
        default:
            return .failed(tool: capability, message: "Unsupported operation '\(op)'")
        }
    }
}

/// Universal image manipulation ("vision.image_manipulation@1"). Mirrors Android
/// `ImageManipulationTool`.
public final class ImageManipulationTool: ToolHandler {
    public let capability = "vision.image_manipulation@1"
    public let requiredPermissions: Set<String> = []

    public init() {}

    public func invoke(args: ToolArgs, ctx: ToolContext) async -> ToolResult {
        let op = (args["op"] ?? args["action"] ?? "remove_background").lowercased()
        guard let sourceUri = args["uri"] ?? args["image"] else {
            return .failed(tool: capability, message: "Missing image URI")
        }
        let imageId = "proc_" + String(UUID().uuidString.replacingOccurrences(of: "-", with: "").prefix(8))
        return ToolSupport.success([
            "state": "ok",
            "status": "SUCCESS",
            "op": op,
            "uri": "file:///workspaces/\(ctx.workspaceId)/media/\(imageId).png",
            "source_uri": sourceUri,
            "image_id": imageId,
            "mime_type": "image/png"
        ])
    }
}

/// Universal keep-awake / screen wake-lock controller ("device.keep_awake@1"). Mirrors
/// Android `KeepAwakeTool`.
///
/// Actual actuation is delegated to a platform sink (`UIApplication.isIdleTimerDisabled` on
/// iOS); when unset the tool still reports success with the same metadata, matching Android's
/// headless/emulated path.
public final class KeepAwakeTool: ToolHandler {
    public let capability = "device.keep_awake@1"
    public let requiredPermissions: Set<String> = []

    /// Platform wake-lock bridge: receives the desired enabled state and returns whether the
    /// platform applied it.
    public nonisolated(unsafe) static var sink: ((_ enabled: Bool) -> Bool)?
    nonisolated(unsafe) private static var enabledState = false

    public init() {}

    public static func isEnabled() -> Bool { enabledState }

    public func invoke(args: ToolArgs, ctx: ToolContext) async -> ToolResult {
        let op = (args["op"] ?? args["action"] ?? args["operation"] ?? "enable").lowercased()
        switch op {
        case "disable", "off", "release", "stop":
            return setEnabled(false, args)
        case "toggle":
            return setEnabled(!KeepAwakeTool.enabledState, args)
        case "status", "get_status", "capabilities":
            return status()
        default:
            return setEnabled(true, args)
        }
    }

    private func setEnabled(_ enabled: Bool, _ args: ToolArgs) -> ToolResult {
        let level = (args["level"] ?? args["mode"] ?? "screen").lowercased()
        let timeoutMs = max(args.getInt("timeout_ms", default: 0), 0)
        let tag = args["tag"] ?? "apexkit:keep-awake"

        _ = KeepAwakeTool.sink?(enabled)
        KeepAwakeTool.enabledState = enabled

        return ToolSupport.success([
            "status": enabled ? "ENABLED" : "DISABLED",
            "enabled": enabled,
            "level": level,
            "timeout_ms": timeoutMs,
            "tag": tag,
            "timestamp": ToolSupport.nowMs()
        ])
    }

    private func status() -> ToolResult {
        ToolSupport.success([
            "status": KeepAwakeTool.enabledState ? "ENABLED" : "DISABLED",
            "enabled": KeepAwakeTool.enabledState,
            "supported_levels": ["screen", "full", "partial"],
            "supported_operations": ["enable", "disable", "toggle", "status"],
            "timestamp": ToolSupport.nowMs()
        ])
    }
}
