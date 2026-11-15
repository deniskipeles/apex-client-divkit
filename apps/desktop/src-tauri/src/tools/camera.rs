use serde_json::Value;
use tauri::AppHandle;

use crate::commands::ToolError;

/// vision.capture_photo@1 — camera capture. Mobile wraps `tauri-plugin-camera`; the desktop
/// POS reports unsupported so the caller can fall back to the mobile apps.
pub async fn invoke(_app: &AppHandle, _args: Value) -> Result<Value, ToolError> {
    #[cfg(mobile)]
    {
        use tauri_plugin_camera::CameraExt;
        let _ = _app.camera();
        return Err(ToolError::Failed {
            tool: "vision.capture_photo@1".into(),
            message: "camera invoked on mobile; wire take_picture here".into(),
        });
    }
    #[cfg(not(mobile))]
    {
        Err(ToolError::Failed {
            tool: "vision.capture_photo@1".into(),
            message: "no camera on this platform".into(),
        })
    }
}
