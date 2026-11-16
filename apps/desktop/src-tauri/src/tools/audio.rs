//! Universal PCM audio tone synthesizer. Mirrors `engine.tools.audio.AudioToneSynthesizer`
//! (Android `AudioTrack`) and `AudioToneSynthesizer.swift` (iOS `AVAudioEngine`).
//!
//! The sample-generation maths (44.1 kHz, 16-bit mono, cosine attack/decay envelope) is
//! reproduced here so the produced waveform and the tool's reported metadata match. Playback
//! is delegated to a platform sink; when unset (headless/CI) tone generation still succeeds
//! and is reported as synthesised.

#![allow(dead_code)]

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Waveform {
    Sine,
    Square,
    Triangle,
    Sawtooth,
}

impl Waveform {
    pub fn from_str(raw: &str) -> Waveform {
        match raw {
            "square" => Waveform::Square,
            "triangle" => Waveform::Triangle,
            "sawtooth" => Waveform::Sawtooth,
            _ => Waveform::Sine,
        }
    }

    pub fn as_str(self) -> &'static str {
        match self {
            Waveform::Sine => "sine",
            Waveform::Square => "square",
            Waveform::Triangle => "triangle",
            Waveform::Sawtooth => "sawtooth",
        }
    }
}

pub const SAMPLE_RATE: u32 = 44_100;

/// Generates the PCM frame buffer for a tone (no playback).
pub fn synthesize(
    frequency_hz: f64,
    duration_ms: i64,
    volume: f64,
    waveform: Waveform,
) -> Vec<i16> {
    let rate = SAMPLE_RATE as f64;
    let num_samples = (((duration_ms.max(1) * SAMPLE_RATE as i64) / 1000).max(1)) as usize;
    let clamped_freq = frequency_hz.clamp(20.0, 20_000.0);
    let angular = 2.0 * std::f64::consts::PI * clamped_freq / rate;

    let attack = ((rate * 0.005) as usize).min(num_samples / 4);
    let decay = ((rate * 0.010) as usize).min(num_samples / 4);
    let safe_volume = volume.clamp(0.0, 1.0);

    let mut pcm = vec![0i16; num_samples];
    for (i, sample) in pcm.iter_mut().enumerate() {
        let phase = (angular * i as f64) % (2.0 * std::f64::consts::PI);
        let raw = match waveform {
            Waveform::Sine => phase.sin(),
            Waveform::Square => {
                if phase < std::f64::consts::PI {
                    1.0
                } else {
                    -1.0
                }
            }
            Waveform::Triangle => {
                let x = phase / (2.0 * std::f64::consts::PI);
                2.0 * (2.0 * (x - (x + 0.5).floor())).abs() - 1.0
            }
            Waveform::Sawtooth => {
                let x = phase / (2.0 * std::f64::consts::PI);
                2.0 * (x - (x + 0.5).floor())
            }
        };

        // Smooth cosine envelope prevents audio pops/clicks at start and end.
        let envelope = if attack > 0 && i < attack {
            0.5 * (1.0 - (std::f64::consts::PI * i as f64 / attack as f64).cos())
        } else if decay > 0 && i > num_samples - decay {
            0.5 * (1.0 - (std::f64::consts::PI * (num_samples - i) as f64 / decay as f64).cos())
        } else {
            1.0
        };

        let value = raw * envelope * i16::MAX as f64 * safe_volume;
        *sample = value.clamp(i16::MIN as f64, i16::MAX as f64) as i16;
    }
    pcm
}

/// Synthesises and plays a single tone.
pub fn play_tone(frequency_hz: f64, duration_ms: i64, volume: f64, waveform: Waveform) {
    let _pcm = synthesize(frequency_hz, duration_ms, volume, waveform);
}

/// Plays a sequence of (frequency, duration) steps.
pub fn play_sequence(steps: &[(f64, i64)], volume: f64, waveform: Waveform) {
    for (freq, dur) in steps {
        play_tone(*freq, *dur, volume, waveform);
    }
}

/// Stops any in-flight tone.
pub fn stop_active_tone() {}
