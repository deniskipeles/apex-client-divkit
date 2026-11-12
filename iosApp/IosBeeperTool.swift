import Foundation
import AVFoundation
import shared

/// Native iOS Tone Audio Synthesizer implementing KMP ToolHandler
/// Maps to "tools://beeper", "tools://audio", and "hardware.beeper@1" on iOS
public final class IosBeeperTool: NSObject, ToolHandler {
    public let capability: String = "hardware.beeper@1"
    public let requiredPermissions: Set<String> = []

    private let engine = AVAudioEngine()
    private var isPlaying = false

    public func invoke(args: ToolArgs, ctx: ToolContext) async -> ToolResult {
        let op = (args["op"] ?? args["action"] ?? "tone").lowercased()

        if op == "stop" || op == "mute" {
            engine.stop()
            isPlaying = false
            return ToolResultSuccess(data: ["status": "MUTED"])
        }

        if op == "status" || op == "capabilities" {
            return ToolResultSuccess(data: [
                "sample_rate": 44100,
                "supported_waveforms": ["sine", "square"],
                "status": "READY"
            ])
        }

        let frequency = Double(args["frequency_hz"] ?? "1200") ?? 1200.0
        let durationMs = Int(args["duration_ms"] ?? "100") ?? 100

        playTone(frequency: frequency, durationMs: durationMs)

        return ToolResultSuccess(data: [
            "status": "SUCCESS",
            "frequency_hz": frequency,
            "duration_ms": Int32(durationMs),
            "timestamp": Int64(Date().timeIntervalSince1970 * 1000)
        ])
    }

    private func playTone(frequency: Double, durationMs: Int) {
        let sampleRate = 44100.0
        let format = AVAudioFormat(standardFormatWithSampleRate: sampleRate, channels: 1)!
        let numFrames = AVAudioFrameCount((Double(durationMs) * sampleRate) / 1000.0)

        guard let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: numFrames) else { return }
        buffer.frameLength = numFrames

        let channels = buffer.floatChannelData![0]
        let angularFreq = 2.0 * Double.pi * frequency / sampleRate

        for frame in 0..<Int(numFrames) {
            channels[frame] = Float(sin(angularFreq * Double(frame)) * 0.85)
        }

        let player = AVAudioPlayerNode()
        engine.attach(player)
        engine.connect(player, to: engine.mainMixerNode, format: format)

        try? engine.start()
        player.play()
        player.scheduleBuffer(buffer, at: nil, options: .interrupts) { [weak self] in
            player.stop()
            self?.engine.detach(player)
        }
    }
}