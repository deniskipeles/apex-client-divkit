use serde_json::{json, Value};
use tauri::AppHandle;

use crate::commands::ToolError;

use super::support::{first_str, now_ms, new_id, op};

/// Hardware camera bridge ("vision.capture_photo@1"). Mirrors Android `CameraTool` and iOS
/// `CameraTool`: identical `capture` / `torch_toggle` / `get_status` result shapes.
///
/// Frame capture and torch control are platform concerns (the mobile app targets wire
/// `tauri-plugin-camera`); the desktop POS reports the same deterministic metadata the mobile
/// tools produce so the DivKit cards render identically.
pub async fn invoke(_app: &AppHandle, args: Value) -> Result<Value, ToolError> {
    match op(&args, "capture").as_str() {
        "capture" | "take_photo" => {
            let facing = first_str(&args, &["facing", "lens"]).unwrap_or("back");
            let image_id = new_id("img_", 8);
            Ok(json!({
                "state": "ok",
                "status": "SUCCESS",
                "uri": format!("file:///apex/photos/{image_id}.jpg"),
                "image_id": image_id,
                "mime_type": "image/jpeg",
                "width": 1280,
                "height": 720,
                "facing": facing,
                "timestamp": now_ms(),
            }))
        }
        "torch" | "torch_toggle" => {
            let enabled = super::support::flag_arg(&args, "torch")
                || first_str(&args, &["state"]).map(|s| s == "on").unwrap_or(false);
            Ok(json!({ "torch_state": enabled, "status": "SUCCESS" }))
        }
        "status" | "capabilities" | "get_status" => Ok(json!({
            "available": true,
            "has_back_camera": true,
            "has_front_camera": true,
            "has_flash": true,
            "status": "READY",
        })),
        other => Err(ToolError::Failed {
            tool: "vision.capture_photo@1".into(),
            message: format!("Unsupported operation '{other}'"),
        }),
    }
}
