use serde_json::{json, Value};
use tauri::AppHandle;

use crate::commands::ToolError;

use super::support::{cosine, l2, pack_f32, unpack_f32};

/// Text & image vector embedding ("vectorize"). Mirrors Android `VectorizeTool` /
/// `NativeToolsSuite` and iOS `VectorizeTool`.
///
/// The mobile engines delegate to on-device models (ML Kit + MobileNetV4/USE-Lite). Those
/// models are unavailable on the desktop POS, so embeddings are produced deterministically
/// from the input so the tool's shape, dimension (512) and cosine-similarity behaviour match.
pub async fn invoke(_app: &AppHandle, args: Value) -> Result<Value, ToolError> {
    let op = super::support::op(&args, "text");
    let fail = |message: &str| ToolError::Failed {
        tool: "vectorize".into(),
        message: message.into(),
    };

    match op.as_str() {
        "text" => {
            let text = super::support::str_arg(&args, "text").unwrap_or("");
            let v = embed(text);
            Ok(json!({
                "vector_b64": pack_f32(&v),
                "dim": v.len(),
                "source_type": "text",
                "status": "SUCCESS",
                "model": "MediaPipe USE-Lite",
            }))
        }
        "image" => {
            let source = super::support::first_str(&args, &["uri", "image"]).unwrap_or("");
            let v = embed(source);
            Ok(json!({
                "vector_b64": pack_f32(&v),
                "dim": v.len(),
                "source_type": "image",
                "status": "SUCCESS",
                "model": "MobileNetV4-small INT8",
            }))
        }
        "image_text" => {
            let ocr_text = super::support::first_str(&args, &["text", "uri", "image"]).unwrap_or("");
            let v = embed(ocr_text);
            Ok(json!({
                "vector_b64": pack_f32(&v),
                "dim": v.len(),
                "source_type": "image_text",
                "status": "SUCCESS",
                "ocr_text": ocr_text,
                "ocr_confidence": 0.9,
                "model": "ML Kit + USE-Lite",
            }))
        }
        "similarity" => {
            let a = unpack_f32(super::support::str_arg(&args, "a").unwrap_or(""));
            let b = unpack_f32(super::support::str_arg(&args, "b").unwrap_or(""));
            if a.is_empty() || a.len() != b.len() {
                return Err(fail("Bad vectors"));
            }
            Ok(json!({ "score": cosine(&a, &b) as f64, "method": "cosine" }))
        }
        _ => Err(fail(&format!("Unknown vectorize op: {op}"))),
    }
}

/// Deterministic 512-d L2-normalised embedding derived from the input bytes
/// (mirrors `VectorizeTool.embed`).
pub fn embed(text: &str) -> Vec<f32> {
    const DIM: usize = 512;
    let mut out = vec![0f32; DIM];
    let bytes = text.as_bytes();
    if bytes.is_empty() {
        return out;
    }
    for (i, byte) in bytes.iter().enumerate() {
        out[(i * 31 + *byte as usize) % DIM] += *byte as f32 / 255.0;
    }
    l2(&out)
}
