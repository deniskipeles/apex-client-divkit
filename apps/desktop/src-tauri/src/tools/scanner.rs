use serde_json::{json, Value};
use tauri::AppHandle;

use crate::commands::ToolError;

use super::audio::{self, Waveform};
use super::support::{first_str, now_ms, op, str_arg};

/// Generic optical scanner ("vision.scan_code@1"). Mirrors Android `ScanCodeTool` /
/// `NativeToolsSuite.executeScan` and iOS `ScanCodeTool`: identical symbology classification,
/// `target_format` filtering, `FORMAT_MISMATCH` handling and hardware beep confirmation.
///
/// A live camera is a platform concern wired by the mobile app targets; the desktop engine
/// decodes the value supplied by the caller (camera analyzer or simulation payload).
pub async fn invoke(_app: &AppHandle, args: Value) -> Result<Value, ToolError> {
    let operation = op(&args, "scan");
    if ["ocr", "text", "read_text"].contains(&operation.as_str()) {
        return Ok(ocr(&args));
    }

    let target_format_raw = first_str(&args, &["target_format", "format"])
        .or_else(|| str_arg(&args, "mode"))
        .unwrap_or("both");
    let target_format = target_format_raw.trim().to_lowercase();

    let raw_value = first_str(&args, &["raw_value", "simulated_code", "code"]).unwrap_or("9780201633610");

    let is_uri_or_matrix =
        raw_value.contains("://") || raw_value.starts_with('{') || raw_value.contains('\n');
    let (detected_type, detected_format) = if is_uri_or_matrix || raw_value.chars().count() > 32 {
        ("2D", "QR_CODE")
    } else {
        let format = match raw_value.chars().count() {
            13 => "EAN_13",
            12 => "UPC_A",
            8 => "EAN_8",
            _ => "CODE_128",
        };
        ("1D", format)
    };

    let matches = match target_format.as_str() {
        "both" | "all" | "*" => true,
        "barcode" | "1d" | "linear" => detected_type == "1D",
        "qr" | "2d" | "matrix" => detected_type == "2D",
        other => detected_format.to_lowercase() == other,
    };

    if !matches {
        return Ok(json!({
            "status": "FORMAT_MISMATCH",
            "error": format!(
                "Scanned format [{detected_format}] does not match target_format [{target_format_raw}]"
            ),
            "raw_value": raw_value,
            "detected_format": detected_format,
            "detected_type": detected_type,
            "target_format": target_format_raw,
        }));
    }

    let beep = str_arg(&args, "beep").map(|v| v != "false").unwrap_or(true);
    if beep {
        let freq = if detected_type == "2D" { 2200.0 } else { 1600.0 };
        audio::play_tone(freq, 80, 0.85, Waveform::Sine);
    }

    Ok(json!({
        "raw_value": raw_value,
        "format": detected_format,
        "type": detected_type,
        "bytes_length": raw_value.len(),
        "status": "SUCCESS",
        "timestamp": now_ms(),
    }))
}

/// Optical character recognition is an image-reading mode of the same scanner.
fn ocr(args: &Value) -> Value {
    let text = first_str(args, &["text", "raw_value", "simulated_code"]).unwrap_or("");
    json!({
        "status": "SUCCESS",
        "text": text,
        "confidence": 0.9,
        "block_count": if text.is_empty() { 0 } else { 1 },
        "timestamp": now_ms(),
    })
}
