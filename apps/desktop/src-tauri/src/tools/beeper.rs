use serde_json::{json, Value};
use tauri::AppHandle;

use crate::commands::ToolError;

use super::audio::{self, Waveform};
use super::support::{float_arg, int_arg, now_ms, op};

/// Universal audio tone synthesizer ("hardware.beeper@1"). Mirrors Android `BeeperTool` and iOS
/// `BeeperTool`: identical profiles, waveforms, volume clamping and sequence parsing. Playback
/// is delegated to the platform audio sink (via `AudioToneSynthesizer`).
pub async fn invoke(_app: &AppHandle, args: Value) -> Result<Value, ToolError> {
    match op(&args, "tone").as_str() {
        "stop" | "mute" | "cancel" => {
            audio::stop_active_tone();
            Ok(json!({ "status": "MUTED", "timestamp": now_ms() }))
        }
        "status" | "capabilities" => Ok(json!({
            "sample_rate": audio::SAMPLE_RATE,
            "supported_waveforms": ["sine", "square", "triangle", "sawtooth"],
            "min_frequency": 20,
            "max_frequency": 20000,
            "status": "READY",
        })),
        other => Ok(tone(&args, other)),
    }
}

fn tone(args: &Value, operation: &str) -> Value {
    let profile = super::support::first_str(args, &["profile", "preset", "tone"])
        .unwrap_or(operation)
        .to_lowercase();
    let volume = float_arg(args, "volume", 0.85).clamp(0.0, 1.0);
    let waveform_str = super::support::str_arg(args, "waveform").unwrap_or("sine").to_lowercase();
    let waveform = Waveform::from_str(&waveform_str);

    if let Some(sequence_raw) = super::support::first_str(args, &["sequence", "melody"]) {
        if !sequence_raw.is_empty() {
            let steps = parse_sequence(sequence_raw);
            audio::play_sequence(&steps, volume, waveform);
            return json!({
                "status": "SUCCESS",
                "mode": "sequence",
                "steps": steps.len(),
                "waveform": waveform_str,
                "timestamp": now_ms(),
            });
        }
    }

    match profile.as_str() {
        "click" | "blip" => play(1800.0, 30, volume, waveform, &waveform_str),
        "beep" => play(1600.0, 70, volume, waveform, &waveform_str),
        "chime" | "notice" => {
            audio::play_sequence(&[(880.0, 60), (1760.0, 90)], volume, waveform);
            json!({ "status": "SUCCESS", "profile": "chime", "waveform": waveform_str })
        }
        "success" => {
            audio::play_sequence(&[(1046.5, 50), (1318.5, 50), (1567.98, 90)], volume, waveform);
            json!({ "status": "SUCCESS", "profile": "success", "waveform": waveform_str })
        }
        "warning" | "alert" => play(2400.0, 160, volume, waveform, &waveform_str),
        "error" | "buzz" => play(440.0, 200, volume, waveform, &waveform_str),
        _ => {
            let f = float_arg(args, "frequency_hz", 1200.0);
            let d = int_arg(args, "duration_ms", 120);
            play(f, d, volume, waveform, &waveform_str)
        }
    }
}

fn play(frequency_hz: f64, duration_ms: i64, volume: f64, waveform: Waveform, waveform_str: &str) -> Value {
    audio::play_tone(frequency_hz, duration_ms, volume, waveform);
    json!({
        "status": "SUCCESS",
        "frequency_hz": frequency_hz,
        "duration_ms": duration_ms,
        "volume": volume,
        "waveform": waveform_str,
        "timestamp": now_ms(),
    })
}

pub(crate) fn parse_sequence(raw: &str) -> Vec<(f64, i64)> {
    let steps: Vec<(f64, i64)> = raw
        .split(',')
        .filter_map(|step| {
            let parts: Vec<&str> = step.trim().split(':').collect();
            if parts.len() == 2 {
                Some((
                    parts[0].parse::<f64>().unwrap_or(1000.0),
                    parts[1].parse::<i64>().unwrap_or(80),
                ))
            } else {
                None
            }
        })
        .collect();
    if steps.is_empty() {
        vec![(1200.0, 100)]
    } else {
        steps
    }
}
