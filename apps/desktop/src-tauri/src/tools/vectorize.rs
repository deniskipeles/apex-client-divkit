use serde_json::{json, Value};
use tauri::AppHandle;

use crate::commands::ToolError;

/// vectorize — deterministic local embedding. Produces a fixed-width vector from the input
/// text so the contract shape matches the edge and the mobile engines.
pub async fn invoke(_app: &AppHandle, args: Value) -> Result<Value, ToolError> {
    const DIM: usize = 8;
    let text = args
        .get("text")
        .or_else(|| args.get("input"))
        .and_then(Value::as_str)
        .unwrap_or("");
    let mut vector = [0u32; DIM];
    for (i, byte) in text.bytes().enumerate() {
        vector[i % DIM] = vector[i % DIM].wrapping_add(byte as u32);
    }
    let embedding: Vec<u32> = vector.iter().map(|v| v % 1000).collect();
    Ok(json!({ "embedding": embedding, "dim": DIM }))
}
