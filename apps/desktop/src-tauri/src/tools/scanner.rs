use serde_json::{json, Value};
use tauri::AppHandle;

use crate::commands::ToolError;

/// vision.scan_code@1 — barcode/QR scan. On mobile this wraps `tauri-plugin-barcode-scanner`;
/// the desktop POS has no camera scanner, so it reports unsupported rather than guessing.
pub async fn invoke(_app: &AppHandle, _args: Value) -> Result<Value, ToolError> {
    #[cfg(mobile)]
    {
        use tauri_plugin_barcode_scanner::BarcodeScannerExt;
        let _ = _app.barcode_scanner();
        return Err(ToolError::Failed {
            tool: "vision.scan_code@1".into(),
            message: "scanner invoked on mobile; wire the plugin's scan command here".into(),
        });
    }
    #[cfg(not(mobile))]
    {
        let _ = json!({});
        Err(ToolError::Failed {
            tool: "vision.scan_code@1".into(),
            message: "no camera scanner on this platform".into(),
        })
    }
}
