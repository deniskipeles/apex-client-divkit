use serde_json::{json, Value};
use tauri::AppHandle;

use tauri_plugin_haptics::HapticsExt;

use crate::commands::ToolError;

/// device.haptics@1 — wraps `tauri-plugin-haptics`. No-op on desktop, real feedback on mobile.
pub async fn invoke(app: &AppHandle, args: Value) -> Result<Value, ToolError> {
    let duration = args.get("duration").and_then(Value::as_u64).unwrap_or(50) as u32;
    let haptics = app.haptics();
    haptics.vibrate(duration).map_err(|e| ToolError::Failed {
        tool: "device.haptics@1".into(),
        message: e.to_string(),
    })?;
    Ok(json!({ "performed": true, "duration": duration }))
}
