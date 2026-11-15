pub mod beeper;
pub mod camera;
pub mod haptics;
pub mod scanner;
pub mod signal;
pub mod vectorize;

use serde_json::{json, Value};
use tauri::AppHandle;
use tauri_plugin_clipboard_manager::ClipboardExt;

use crate::commands::ToolError;

/// Where a capability is handled in the desktop POS engine.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Route {
    Haptics,
    Beeper,
    Scanner,
    Camera,
    Signal,
    Vectorize,
    Clipboard,
    /// In the contract, but owned by the mobile apps or the edge rather than this engine.
    NotImplemented,
    /// Not a capability in the contract.
    Unknown,
}

/// Permissions the desktop engine grants, mirroring `capabilities/default.json`.
const GRANTED_PERMISSIONS: &[&str] = &["haptics", "clipboard", "storage", "audio"];

fn required_permission(capability: &str) -> Option<&'static str> {
    match route(capability) {
        Route::Haptics => Some("haptics"),
        Route::Clipboard => Some("clipboard"),
        Route::Scanner | Route::Camera => Some("camera"),
        _ => None,
    }
}

/// Maps a canonical capability id or any contract alias to its handler.
pub fn route(capability: &str) -> Route {
    match capability {
        "hardware.haptics@1" | "device.haptics@1" | "haptics.perform@1" | "haptics"
        | "device.haptics" => Route::Haptics,
        "hardware.beeper@1" | "device.beeper@1" | "beeper" | "device.beeper" | "tone" => {
            Route::Beeper
        }
        "vision.scan_code@1" | "scan-code" | "scan_barcode" | "scan-qr" => Route::Scanner,
        "vision.capture_photo@1" | "camera" | "capture_photo" => Route::Camera,
        "crypto.signal@1" | "secure.send@1" | "signal" | "crypto.signal" | "libsignal"
        | "secure_send" => Route::Signal,
        "vectorize" | "vision.embed_image@1" | "vector" | "embed" | "embed-text"
        | "embed-image" => Route::Vectorize,
        "device.clipboard@1" | "clipboard" | "device.clipboard" => Route::Clipboard,

        // Recognized by the contract but implemented by the mobile apps or the edge.
        "vision.remove_background@1"
        | "vision.identify_product@1"
        | "secure.status@1"
        | "secure.verify_identity@1"
        | "auth.biometrics@1"
        | "network.push@1"
        | "storage.offline_cache@1"
        | "network.realtime@1"
        | "network.transfer@1"
        | "device.printer@1"
        | "vision.generate_code@1"
        | "image-background-remove"
        | "u-2-Net.tflite"
        | "MobileNetV4-small"
        | "identify_product"
        | "secure_status"
        | "signal_status"
        | "secure_verify"
        | "verify_identity"
        | "biometrics"
        | "auth.biometrics"
        | "supervisor_auth"
        | "push"
        | "network.push"
        | "alerts"
        | "offline-cache"
        | "offline_cache"
        | "storage.offline_cache"
        | "realtime-ws"
        | "realtime_ws"
        | "network.realtime"
        | "realtime"
        | "net"
        | "network.transfer"
        | "tools.net"
        | "print"
        | "printer"
        | "device.printer"
        | "code-generate"
        | "code_gen"
        | "generate-code"
        | "barcode-generate"
        | "qr-generate"
        | "vision.generate_code" => Route::NotImplemented,

        _ => Route::Unknown,
    }
}

/// Executes a capability on the desktop engine.
pub async fn dispatch(app: &AppHandle, capability: &str, args: Value) -> Result<Value, ToolError> {
    if let Some(permission) = required_permission(capability) {
        if !GRANTED_PERMISSIONS.contains(&permission) {
            return Err(ToolError::PermissionDenied {
                tool: capability.into(),
                permission: permission.into(),
            });
        }
    }

    match route(capability) {
        Route::Haptics => haptics::invoke(app, args).await,
        Route::Beeper => beeper::invoke(app, args).await,
        Route::Scanner => scanner::invoke(app, args).await,
        Route::Camera => camera::invoke(app, args).await,
        Route::Signal => signal::invoke(app, args).await,
        Route::Vectorize => vectorize::invoke(app, args).await,
        Route::Clipboard => match args.get("text").and_then(Value::as_str) {
            Some(text) => app
                .clipboard()
                .write_text(text)
                .map(|_| json!({ "written": true }))
                .map_err(|e| ToolError::Failed {
                    tool: capability.into(),
                    message: e.to_string(),
                }),
            None => app
                .clipboard()
                .read_text()
                .map(|text| json!({ "text": text }))
                .map_err(|e| ToolError::Failed {
                    tool: capability.into(),
                    message: e.to_string(),
                }),
        },
        Route::NotImplemented => Err(ToolError::Failed {
            tool: capability.into(),
            message: "not implemented in the Tauri engine".into(),
        }),
        Route::Unknown => Err(ToolError::Failed {
            tool: capability.into(),
            message: "unknown capability".into(),
        }),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const TOOLS_JSON: &str = include_str!("../../../../../packages/contract/tools.json");

    /// Every capability and alias in the contract must route to something other than Unknown,
    /// otherwise the desktop engine silently drops a documented capability.
    #[test]
    fn every_contract_capability_routes() {
        let tools: Value = serde_json::from_str(TOOLS_JSON).expect("tools.json parses");
        let capabilities = tools["capabilities"].as_array().expect("capabilities array");
        assert!(!capabilities.is_empty());

        for capability in capabilities {
            let id = capability["id"].as_str().expect("id");
            assert_ne!(route(id), Route::Unknown, "canonical id not routed: {id}");

            for alias in capability["aliases"].as_array().expect("aliases") {
                let alias = alias.as_str().expect("alias");
                assert_ne!(route(alias), Route::Unknown, "alias not routed: {alias}");
            }
        }
    }

    #[test]
    fn unknown_capability_is_unknown() {
        assert_eq!(route("does.not.exist@1"), Route::Unknown);
    }
}
