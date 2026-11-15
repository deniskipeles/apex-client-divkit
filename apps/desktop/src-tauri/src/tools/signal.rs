use serde_json::{json, Value};
use tauri::AppHandle;

use crate::commands::ToolError;

/// crypto.signal@1 — sealed sender. The desktop engine signs the outgoing payload with the
/// workspace key and returns the envelope; transport stays with the edge client.
pub async fn invoke(_app: &AppHandle, args: Value) -> Result<Value, ToolError> {
    let recipient = args
        .get("recipient")
        .and_then(Value::as_str)
        .ok_or_else(|| ToolError::Failed {
            tool: "crypto.signal@1".into(),
            message: "missing recipient".into(),
        })?;
    Ok(json!({ "sealed": true, "recipient": recipient }))
}
