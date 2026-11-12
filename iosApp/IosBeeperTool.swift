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
        let op = (args["op"] ?? args["action"] ?? args["operation"] ?? "tone").lowercased()

        if op == "stop" || op == "mute" || op == "cancel" {
            engine.stop()
            isPlaying = false
            return ToolResultSuccess(data: ["status": "MUTED", "timestamp": Int64(Date().timeIntervalSince1970 * 1000)])
        }

        if op == "status" || op == "capabilities" {
            return ToolResultSuccess(data: [
                "sample_rate": 44100,
                "supported_waveforms": ["sine", "square", "triangle", "sawtooth"],
                "min_frequency": 20,
                "max_frequency": 20000,
                "status": "READY"
            ])
        }

        let waveform = (args["waveform"] ?? "sine").lowercased()
        let volume = Double(args["volume"] ?? "0.85") ?? 0.85

        // Sequence Mode (e.g., "880:60,1760:100")
        if let sequenceRaw = args["sequence"] ?? args["melody"], !sequenceRaw.isEmpty {
            let steps = Self.parseSequence(sequenceRaw)
            playSequence(steps: steps, volume: volume, waveform: waveform)
            return ToolResultSuccess(data: [
                "status": "SUCCESS",
                "mode": "sequence",
                "steps": Int32(steps.count),
                "waveform": waveform,
                "timestamp": Int64(Date().timeIntervalSince1970 * 1000)
            ])
        }

        // Semantic profile or explicit frequency/duration
        let profile = (args["profile"] ?? args["preset"] ?? args["tone"] ?? op).lowercased()
        let frequency = Double(args["frequency_hz"] ?? "1200") ?? 1200.0
        let durationMs = Int(args["duration_ms"] ?? "120") ?? 120

        playTone(frequency: frequency, durationMs: durationMs, volume: volume, waveform: waveform)

        return ToolResultSuccess(data: [
            "status": "SUCCESS",
            "profile": profile,
            "frequency_hz": frequency,
            "duration_ms": Int32(durationMs),
            "volume": volume,
            "waveform": waveform,
            "timestamp": Int64(Date().timeIntervalSince1970 * 1000)
        ])
    }

    private static func parseSequence(_ raw: String) -> [(Double, Int)] {
        let steps = raw.split(separator: ",").compactMap { step -> (Double, Int)? in
            let parts = step.trimmingCharacters(in: .whitespaces).split(separator: ":")
            guard parts.count == 2 else { return nil }
            let freq = Double(parts[0]) ?? 1000.0
            let dur = Int(parts[1]) ?? 80
            return (freq, dur)
        }
        return steps.isEmpty ? [(1200.0, 100)] : steps
    }

    private func playSequence(steps: [(Double, Int)], volume: Double, waveform: String) {
        for (freq, dur) in steps {
            playTone(frequency: freq, durationMs: dur, volume: volume, waveform: waveform)
        }
    }

    private func playTone(frequency: Double, durationMs: Int, volume: Double, waveform: String) {
        let sampleRate = 44100.0
        let format = AVAudioFormat(standardFormatWithSampleRate: sampleRate, channels: 1)!
        let numFrames = AVAudioFrameCount((Double(durationMs) * sampleRate) / 1000.0)

        guard let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: numFrames) else { return }
        buffer.frameLength = numFrames

        let channels = buffer.floatChannelData![0]
        let angularFreq = 2.0 * Double.pi * frequency / sampleRate
        let safeVolume = max(0.0, min(1.0, volume))

        for frame in 0..<Int(numFrames) {
            let phase = (angularFreq * Double(frame)).truncatingRemainder(dividingBy: 2.0 * Double.pi)
            let sample: Double
            switch waveform {
            case "square": sample = phase < Double.pi ? 1.0 : -1.0
            case "triangle": sample = 2.0 * abs(2.0 * (phase / (2.0 * Double.pi) - (phase / (2.0 * Double.pi) + 0.5).rounded(.down))) - 1.0
            case "sawtooth": sample = 2.0 * (phase / (2.0 * Double.pi) - (phase / (2.0 * Double.pi) + 0.5).rounded(.down))
            default: sample = sin(phase)
            }
            channels[frame] = Float(sample * safeVolume)
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