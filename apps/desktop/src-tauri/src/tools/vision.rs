//! Vision capabilities that are pure metadata on the portable engine, mirroring the Android
//! `impl/*Tool.kt` handlers and the iOS `SimpleTools.swift` / `DeviceTools.swift` mirrors:
//! `vision.embed_image@1`, `vision.remove_background@1`, `vision.identify_product@1`,
//! `vision.image_manipulation@1` and the `u-2-Net.tflite` segmentation model. On-device model
//! inference (TFLite / ML Kit) is a platform concern; the desktop engine reproduces the same
//! observable result shape so the DivKit cards render identically.

use serde_json::{json, Value};
use tauri::AppHandle;

use crate::commands::ToolError;

use super::support::{first_str, int_arg, new_id, now_ms, op};

/// `vision.embed_image@1` — 512-dim image embedding. Mirrors Android `EmbedImageTool`.
pub fn embed_image(args: &Value) -> Result<Value, ToolError> {
    let image_id = first_str(args, &["image", "image_id"]).ok_or_else(|| ToolError::Failed {
        tool: "vision.embed_image@1".into(),
        message: "missing image argument".into(),
    })?;
    let sample: Vec<String> = (0..8).map(|_| format!("{:.4}", pseudo_unit())).collect();
    Ok(json!({
        "state": "ok",
        "image_id": image_id,
        "dim": 512,
        "vector_sample": sample.join(","),
    }))
}

/// `vision.remove_background@1` — alpha matte. Mirrors Android `RemoveBackgroundTool`.
pub fn remove_background(args: &Value) -> Result<Value, ToolError> {
    let image_id = first_str(args, &["image", "image_id"]).ok_or_else(|| ToolError::Failed {
        tool: "vision.remove_background@1".into(),
        message: "missing image argument".into(),
    })?;
    let suffix = image_id.strip_prefix("img_").unwrap_or(image_id);
    let out_id = format!("cutout_{suffix}");
    Ok(json!({
        "state": "ok",
        "image_id": out_id,
        "uri": format!("file:///apex/cutouts/{out_id}.png"),
        "format": "PNG_TRANSPARENT",
    }))
}

/// `vision.identify_product@1` — product classification. Mirrors Android `ProductClassifierTool`
/// and the `executeMobileNetV4` candidate set.
pub fn identify_product(args: &Value) -> Value {
    let image_id = first_str(args, &["image", "image_id"]).unwrap_or("img_snapshot_live");
    let top_k = int_arg(args, "top_k", 3).max(0) as usize;
    let min_confidence = super::support::float_arg(args, "min_confidence", 0.40);

    let candidates: [(&str, &str, f64); 3] = [
        ("Wagyu Smash Burger (Entree)", "ACM-101", 0.94),
        ("Truffle Parmesan Fries", "ACM-102", 0.78),
        ("Artisan Cold Brew", "ACM-204", 0.62),
    ];
    let accepted: Vec<(&str, &str, f64)> = candidates
        .iter()
        .copied()
        .filter(|c| c.2 >= min_confidence)
        .take(top_k)
        .collect();
    let top = accepted.first().copied().unwrap_or(candidates[0]);

    json!({
        "state": "ok",
        "status": "SUCCESS",
        "image_id": image_id,
        "prediction_label": top.0,
        "prediction_sku": top.1,
        "confidence": top.2,
        "candidates": accepted
            .iter()
            .map(|c| format!("{}:{}", c.1, c.2))
            .collect::<Vec<_>>()
            .join("|"),
        "timestamp": now_ms(),
    })
}

/// `u-2-Net.tflite` — salient object segmentation. Mirrors Android `executeU2Net`.
pub fn u2net(args: &Value) -> Value {
    let cutoff = super::support::float_arg(args, "saliency_cutoff", 0.45);
    let mask_uri = format!("file:///apex/masks/mask_{}.png", now_ms());
    json!({
        "status": "SUCCESS",
        "mask_uri": mask_uri,
        "confidence": 0.94,
        "saliency_cutoff": cutoff,
        "inference_ms": 22,
    })
}

/// `vision.image_manipulation@1` — background removal / smoothing / tone / geometry pipeline.
/// Mirrors Android `ImageManipulationTool`.
pub fn image_manipulation(args: &Value, workspace: &str) -> Result<Value, ToolError> {
    let operation = op(args, "remove_background");
    let source_uri = first_str(args, &["uri", "image", "base64", "data_url"]).ok_or_else(|| {
        ToolError::Failed {
            tool: "vision.image_manipulation@1".into(),
            message: "Missing source image argument ('uri' or 'base64')".into(),
        }
    })?;
    let image_id = new_id("proc_", 8);
    Ok(json!({
        "state": "ok",
        "status": "SUCCESS",
        "op": operation,
        "uri": format!("file:///workspaces/{workspace}/media/{image_id}.png"),
        "source_uri": source_uri,
        "image_id": image_id,
        "mime_type": "image/png",
    }))
}

pub async fn dispatch_embed_image(_app: &AppHandle, args: Value) -> Result<Value, ToolError> {
    embed_image(&args)
}

pub async fn dispatch_remove_background(
    _app: &AppHandle,
    capability: &str,
    args: Value,
) -> Result<Value, ToolError> {
    // `u-2-Net.tflite` is a segmentation entry point on the same capability; the contract
    // exposes it as an alias of `vision.remove_background@1`.
    if capability == "u-2-Net.tflite" {
        return Ok(u2net(&args));
    }
    remove_background(&args)
}

pub async fn dispatch_identify_product(_app: &AppHandle, args: Value) -> Result<Value, ToolError> {
    Ok(identify_product(&args))
}

pub async fn dispatch_image_manipulation(
    _app: &AppHandle,
    args: Value,
    workspace: &str,
) -> Result<Value, ToolError> {
    image_manipulation(&args, workspace)
}

/// Deterministic pseudo-random value in [-1, 1] derived from the current clock, so the
/// `vector_sample` preview is stable in shape without pulling a RNG dependency.
fn pseudo_unit() -> f64 {
    let n = now_ms() as f64;
    let x = (n * 0.000_000_1).fract();
    x * 2.0 - 1.0
}
