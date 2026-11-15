import Foundation

/// Universal PCM audio tone synthesizer. Mirrors `engine.tools.audio.AudioToneSynthesizer`.
///
/// The Android implementation feeds an `AudioTrack`; the sample-generation maths (44.1 kHz,
/// 16-bit mono, cosine attack/decay envelope) is identical here so the produced waveform and
/// the tool's reported metadata match. Playback is delegated to a platform sink.
public enum AudioToneSynthesizer {

    public enum Waveform: String, Sendable {
        case sine, square, triangle, sawtooth
    }

    /// Platform playback hook. On iOS this is wired to `AVAudioEngine`; when unset (unit
    /// tests, Linux) tone generation still succeeds and is reported as synthesised.
    public nonisolated(unsafe) static var sink: ((_ pcm: [Int16], _ sampleRate: Int) -> Void)?

    public static let sampleRate = 44100

    /// Generates the PCM frame buffer for a tone (no playback).
    public static func synthesize(
        frequencyHz: Double = 1200.0,
        durationMs: Int = 120,
        volume: Double = 0.85,
        waveform: Waveform = .sine
    ) -> [Int16] {
        let rate = sampleRate
        let numSamples = max((durationMs * rate) / 1000, 1)
        let clampedFreq = min(max(frequencyHz, 20.0), 20_000.0)
        let angularFreq = 2.0 * Double.pi * clampedFreq / Double(rate)

        let attackSamples = min(Int(Double(rate) * 0.005), numSamples / 4)
        let decaySamples = min(Int(Double(rate) * 0.010), numSamples / 4)
        let safeVolume = min(max(volume, 0.0), 1.0)

        var pcm = [Int16](repeating: 0, count: numSamples)
        for i in 0..<numSamples {
            let phase = (angularFreq * Double(i)).truncatingRemainder(dividingBy: 2.0 * Double.pi)
            let rawSample: Double
            switch waveform {
            case .sine:
                rawSample = sin(phase)
            case .square:
                rawSample = phase < Double.pi ? 1.0 : -1.0
            case .triangle:
                rawSample = 2.0 * abs(2.0 * (phase / (2.0 * Double.pi) - (phase / (2.0 * Double.pi) + 0.5).rounded(.down))) - 1.0
            case .sawtooth:
                rawSample = 2.0 * (phase / (2.0 * Double.pi) - (phase / (2.0 * Double.pi) + 0.5).rounded(.down))
            }

            // Smooth cosine envelope prevents audio pops/clicks at start and end.
            let envelope: Double
            if attackSamples > 0 && i < attackSamples {
                envelope = 0.5 * (1.0 - cos(Double.pi * Double(i) / Double(attackSamples)))
            } else if decaySamples > 0 && i > numSamples - decaySamples {
                envelope = 0.5 * (1.0 - cos(Double.pi * Double(numSamples - i) / Double(decaySamples)))
            } else {
                envelope = 1.0
            }

            let sample = rawSample * envelope * Double(Int16.max) * safeVolume
            pcm[i] = Int16(clamping: Int(sample))
        }
        return pcm
    }

    /// Synthesises and plays a single tone.
    public static func playTone(
        frequencyHz: Double = 1200.0,
        durationMs: Int = 120,
        volume: Double = 0.85,
        waveform: Waveform = .sine
    ) {
        let pcm = synthesize(frequencyHz: frequencyHz, durationMs: durationMs, volume: volume, waveform: waveform)
        sink?(pcm, sampleRate)
    }

    /// Plays a sequence of (frequency, duration) steps.
    public static func playSequence(_ steps: [(Double, Int)], volume: Double = 0.85, waveform: Waveform = .sine) {
        for (freq, dur) in steps {
            playTone(frequencyHz: freq, durationMs: dur, volume: volume, waveform: waveform)
        }
    }

    /// Stops any in-flight tone.
    public static func stopActiveTone() {
        sink?([], sampleRate)
    }
}
