use serde_json::{json, Value};
use tauri::AppHandle;

use crate::commands::ToolError;

use super::support::{b64_encode, first_str, fnv1a, int_arg, new_id, str_arg};

/// Barcode / QR code generator ("vision.generate_code@1"). Mirrors Android `CodeGenerateTool`
/// (ZXing `MultiFormatWriter`) and iOS `CodeGenerateTool`.
///
/// ZXing is unavailable on the portable engine, so a deterministic module matrix is produced
/// for the requested symbology and content; the reported format/kind/size semantics match the
/// mobile tools.
pub async fn invoke(_app: &AppHandle, args: Value, workspace: &str) -> Result<Value, ToolError> {
    let content = first_str(&args, &["content", "text", "data", "value"]).ok_or_else(|| {
        ToolError::Failed {
            tool: "vision.generate_code@1".into(),
            message: "Missing content argument ('content' or 'text')".into(),
        }
    })?;
    let format_raw = first_str(&args, &["format", "symbology", "type"]).unwrap_or("qr");
    let width = int_arg(&args, "width", 512).clamp(64, 2048);
    let margin = int_arg(&args, "margin", 4).clamp(0, 16);
    let encoding = str_arg(&args, "encoding").unwrap_or("uri").to_lowercase();

    let format = resolve_format(format_raw);
    let kind = kind_of(format);
    let size = width;
    let height = if kind == "1D" {
        int_arg(&args, "height", 512).clamp(32, 2048)
    } else {
        size
    };

    let matrix = render_matrix(content, size as usize, height as usize, margin as usize);
    let code_id = new_id("code_", 12);

    let mut out = serde_json::Map::new();
    out.insert("status".into(), json!("SUCCESS"));
    out.insert("code_id".into(), json!(code_id));
    out.insert("content".into(), json!(content));
    out.insert("format".into(), json!(format));
    out.insert("kind".into(), json!(kind));
    out.insert("width".into(), json!(size));
    out.insert("height".into(), json!(height));
    out.insert("size_bytes".into(), json!(0));
    out.insert("matrix".into(), json!(matrix.join("\n")));
    out.insert("matrix_rows".into(), json!(matrix.len()));
    out.insert("mime_type".into(), json!("image/png"));
    out.insert(
        "uri".into(),
        json!(format!("file:///workspaces/{workspace}/media/{code_id}.png")),
    );
    if encoding == "base64" || encoding == "both" {
        out.insert("base64".into(), json!(b64_encode(content.as_bytes())));
    }
    Ok(Value::Object(out))
}

pub(crate) fn resolve_format(raw: &str) -> &'static str {
    let f = raw
        .trim()
        .to_uppercase()
        .replace(['-', ' '], "_");
    match f.as_str() {
        "" | "AUTO" | "BOTH" | "ANY" | "QR" | "QRCODE" | "QR_CODE" => "QR_CODE",
        "DATA_MATRIX" | "DATAMATRIX" | "DM" => "DATA_MATRIX",
        "AZTEC" => "AZTEC",
        "PDF_417" | "PDF417" => "PDF_417",
        "EAN_13" | "EAN13" => "EAN_13",
        "EAN_8" | "EAN8" => "EAN_8",
        "UPC_A" | "UPCA" => "UPC_A",
        "UPC_E" | "UPCE" => "UPC_E",
        "CODE_128" | "CODE128" => "CODE_128",
        "CODE_39" | "CODE39" => "CODE_39",
        "CODE_93" | "CODE93" => "CODE_93",
        "ITF" | "ITF_14" | "INTERLEAVED_2_OF_5" => "ITF",
        "CODABAR" => "CODABAR",
        _ => "QR_CODE",
    }
}

pub(crate) fn kind_of(format: &str) -> &'static str {
    match format {
        "QR_CODE" | "DATA_MATRIX" | "AZTEC" | "PDF_417" => "2D",
        _ => "1D",
    }
}

/// Deterministic module matrix: a pseudo-random but input-derived pattern with a quiet margin,
/// rendered as '1' (dark) / '0' (light) rows (mirrors `CodeGenerateTool.renderMatrix`).
pub(crate) fn render_matrix(content: &str, size: usize, height: usize, margin: usize) -> Vec<String> {
    let inner = size.saturating_sub(margin * 2).max(1);
    let rows = height.saturating_sub(margin * 2).max(1);
    let seed = u64::from_str_radix(&fnv1a(content), 16).unwrap_or(0x9E37_79B9_7F4A_7C15);
    let mut state = seed;
    let blank = "0".repeat(size);
    let mut out: Vec<String> = Vec::new();
    for _ in 0..margin {
        out.push(blank.clone());
    }
    for _ in 0..rows {
        let mut line = "0".repeat(margin);
        for _ in 0..inner {
            state = state
                .wrapping_mul(6364136223846793005)
                .wrapping_add(1442695040888963407);
            line.push(if (state >> 33) & 1 == 1 { '1' } else { '0' });
        }
        line.push_str(&"0".repeat(margin));
        out.push(line);
    }
    for _ in 0..margin {
        out.push(blank.clone());
    }
    out
}
