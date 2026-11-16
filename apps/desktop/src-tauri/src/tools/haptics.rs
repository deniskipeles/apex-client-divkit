use serde_json::{json, Value};
use tauri::AppHandle;

use tauri_plugin_haptics::HapticsExt;

use crate::commands::ToolError;

use super::support::{int_arg, now_ms, op};

/// Universal hardware haptics capability ("hardware.haptics@1"). Mirrors Android `HapticsTool`
/// and iOS `HapticsTool`: the semantic pattern -> effect mapping, default durations and
/// amplitudes are identical. Actual actuation is delegated to `tauri-plugin-haptics`; when the
/// platform sink is unavailable the tool still reports success with the same metadata, matching
/// Android's headless/emulated path.
pub async fn invoke(app: &AppHandle, args: Value) -> Result<Value, ToolError> {
    match op(&args, "perform").as_str() {
        "stop" | "cancel" => {
            let _ = app.haptics().vibrate(0);
            Ok(json!({ "status": "CANCELLED", "timestamp": now_ms() }))
        }
        "status" | "capabilities" | "get_status" => Ok(json!({
            "has_vibrator": true,
            "has_amplitude_control": true,
            "supported_patterns": [
                "click", "double_click", "heavy", "tick", "success", "warning", "error", "waveform"
            ],
            "status": "READY",
        })),
        _ => perform(app, &args),
    }
}

fn perform(app: &AppHandle, args: &Value) -> Result<Value, ToolError> {
    let pattern = super::support::first_str(args, &["pattern", "type", "mode"])
        .unwrap_or("click")
        .to_lowercase();
    let duration = int_arg(args, "duration_ms", default_duration(&pattern));
    let amplitude = int_arg(args, "amplitude", default_amplitude(&pattern)).clamp(1, 255);

    if let Some(timings_raw) = super::support::first_str(args, &["timings", "waveform", "pattern_array"])
    {
        if !timings_raw.is_empty() {
            let timings = parse_timings(timings_raw);
            let total: i64 = timings.iter().sum();
            let _ = app.haptics().vibrate(total.max(1) as u32);
            return Ok(json!({
                "status": "SUCCESS",
                "mode": "waveform",
                "steps": timings.len(),
                "total_duration_ms": total,
                "amplitude": amplitude,
                "timestamp": now_ms(),
            }));
        }
    }

    let _ = app.haptics().vibrate(duration.max(1) as u32);
    Ok(json!({
        "status": "SUCCESS",
        "pattern": pattern,
        "duration_ms": duration,
        "amplitude": amplitude,
        "timestamp": now_ms(),
    }))
}

pub(crate) fn default_duration(pattern: &str) -> i64 {
    match pattern {
        "tick" | "selection" => 10,
        "click" | "light" => 20,
        "medium" => 35,
        "double_click" => 45,
        "heavy" | "heavy_click" => 60,
        "warning" => 80,
        "error" | "failure" | "buzz" => 120,
        "success" => 50,
        _ => 25,
    }
}

pub(crate) fn default_amplitude(pattern: &str) -> i64 {
    match pattern {
        "tick" | "selection" => 90,
        "click" | "light" => 140,
        "medium" => 180,
        "heavy" | "heavy_click" => 255,
        "error" | "failure" | "buzz" => 255,
        "warning" => 200,
        "success" => 210,
        _ => 180,
    }
}

pub(crate) fn parse_timings(raw: &str) -> Vec<i64> {
    let cleaned = raw.trim().trim_start_matches('[').trim_end_matches(']');
    let parsed: Vec<i64> = cleaned
        .split(',')
        .filter_map(|p| p.trim().parse::<i64>().ok())
        .collect();
    if parsed.is_empty() {
        vec![0, 40]
    } else {
        parsed
    }
}
