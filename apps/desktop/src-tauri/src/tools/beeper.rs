use serde_json::{json, Value};
use tauri::AppHandle;

use crate::commands::ToolError;

/// device.beeper@1 — terminal tone. The desktop engine emits the console bell and reports
/// the requested tone so the card can render an acknowledgement.
pub async fn invoke(_app: &AppHandle, args: Value) -> Result<Value, ToolError> {
    let frequency = args.get("frequency").and_then(Value::as_u64).unwrap_or(880);
    let duration_ms = args.get("duration_ms").and_then(Value::as_u64).unwrap_or(150);
    print!("\x07");
    Ok(json!({ "beeped": true, "frequency": frequency, "duration_ms": duration_ms }))
}
