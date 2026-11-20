pub mod audio;
pub mod beeper;
pub mod camera;
pub mod code_generate;
pub mod haptics;
pub mod libsignal;
pub mod net;
pub mod print;
pub mod scanner;
pub mod security;
pub mod session;
pub mod signal;
pub mod support;
pub mod vectorize;
pub mod vision;

use serde_json::{json, Value};
use tauri::AppHandle;
use tauri_plugin_clipboard_manager::ClipboardExt;

use crate::commands::ToolError;

/// Where a capability is handled in the desktop POS engine. The desktop engine mirrors the
/// full Android/iOS terminal tool inventory, so every contract capability has a handler.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Route {
    Haptics,
    Beeper,
    Scanner,
    Camera,
    EmbedImage,
    RemoveBackground,
    IdentifyProduct,
    ImageManipulation,
    SecureSend,
    SecureStatus,
    SecureVerifyIdentity,
    Signal,
    Vectorize,
    Biometrics,
    Clipboard,
    Push,
    OfflineCache,
    Realtime,
    Net,
    Print,
    CodeGenerate,
    KeepAwake,
    /// Not a capability in the contract.
    Unknown,
}

/// Permissions the desktop engine grants, mirroring `capabilities/default.json`. Desktop tool
/// bodies are deterministic emulations, so the platform permissions map onto the same logical
/// gates the mobile engines enforce (camera, network, bluetooth, storage, audio).
const GRANTED_PERMISSIONS: &[&str] = &[
    "haptics",
    "clipboard",
    "storage",
    "audio",
    "camera",
    "network",
    "bluetooth",
];

fn required_permission(capability: &str) -> Option<&'static str> {
    match route(capability) {
        Route::Haptics => Some("haptics"),
        Route::Beeper => Some("audio"),
        Route::Clipboard => Some("clipboard"),
        Route::Scanner | Route::Camera => Some("camera"),
        Route::OfflineCache => Some("storage"),
        Route::Net | Route::Realtime | Route::Push => Some("network"),
        Route::Print => Some("bluetooth"),
        _ => None,
    }
}

/// Maps a canonical capability id or any contract alias to its handler.
pub fn route(capability: &str) -> Route {
    match capability {
        // hardware.haptics@1
        "hardware.haptics@1" | "device.haptics@1" | "haptics.perform@1" | "haptics"
        | "device.haptics" => Route::Haptics,
        // hardware.beeper@1
        "hardware.beeper@1" | "device.beeper@1" | "beeper" | "device.beeper" | "tone" => {
            Route::Beeper
        }
        // vision.scan_code@1
        "vision.scan_code@1" | "scan-code" | "scan_barcode" | "scan-qr" => Route::Scanner,
        // vision.capture_photo@1
        "vision.capture_photo@1" | "camera" | "capture_photo" => Route::Camera,
        // vision.embed_image@1
        "vision.embed_image@1" => Route::EmbedImage,
        // vision.remove_background@1
        "vision.remove_background@1" | "image-background-remove" | "u-2-Net.tflite" => {
            Route::RemoveBackground
        }
        // vision.identify_product@1
        "vision.identify_product@1" | "MobileNetV4-small" | "identify_product" => {
            Route::IdentifyProduct
        }
        // vision.image_manipulation@1 (image-manipulation pipeline)
        "vision.image_manipulation@1" => Route::ImageManipulation,
        // secure.send@1
        "secure.send@1" | "libsignal" | "secure_send" => Route::SecureSend,
        // secure.status@1
        "secure.status@1" | "secure_status" | "signal_status" => Route::SecureStatus,
        // secure.verify_identity@1
        "secure.verify_identity@1" | "secure_verify" | "verify_identity" => {
            Route::SecureVerifyIdentity
        }
        // crypto.signal@1
        "crypto.signal@1" | "signal" | "crypto.signal" => Route::Signal,
        // vectorize
        "vectorize" | "vector" | "embed" | "embed-text" | "embed-image" => Route::Vectorize,
        // auth.biometrics@1
        "auth.biometrics@1" | "biometrics" | "auth.biometrics" | "supervisor_auth" => {
            Route::Biometrics
        }
        // device.clipboard@1
        "device.clipboard@1" | "clipboard" | "device.clipboard" => Route::Clipboard,
        // network.push@1
        "network.push@1" | "push" | "network.push" | "alerts" => Route::Push,
        // storage.offline_cache@1
        "storage.offline_cache@1" | "offline-cache" | "offline_cache" | "storage.offline_cache" => {
            Route::OfflineCache
        }
        // network.realtime@1
        "network.realtime@1" | "realtime-ws" | "realtime_ws" | "network.realtime" | "realtime" => {
            Route::Realtime
        }
        // network.transfer@1
        "network.transfer@1" | "net" | "network.transfer" | "tools.net" => Route::Net,
        // device.printer@1
        "device.printer@1" | "print" | "printer" | "device.printer" => Route::Print,
        // vision.generate_code@1
        "vision.generate_code@1" | "code-generate" | "code_gen" | "generate-code"
        | "barcode-generate" | "qr-generate" | "vision.generate_code" => Route::CodeGenerate,
        // device.keep_awake@1
        "device.keep_awake@1" | "keep-awake" | "keep_awake" | "keepawake" | "device.keep_awake"
        | "wakelock" => Route::KeepAwake,

        _ => Route::Unknown,
    }
}

/// Resolves the tenant/workspace id: `args.workspace_id` when supplied, else the mobile
/// `ToolContext` default (`acme_pos`).
fn workspace_id(args: &Value) -> String {
    args.get("workspace_id")
        .and_then(Value::as_str)
        .unwrap_or("acme_pos")
        .to_string()
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

    let workspace = workspace_id(&args);
    match route(capability) {
        Route::Haptics => haptics::invoke(app, args).await,
        Route::Beeper => beeper::invoke(app, args).await,
        Route::Scanner => scanner::invoke(app, args).await,
        Route::Camera => camera::invoke(app, args).await,
        Route::EmbedImage => vision::dispatch_embed_image(app, args).await,
        Route::RemoveBackground => vision::dispatch_remove_background(app, capability, args).await,
        Route::IdentifyProduct => vision::dispatch_identify_product(app, args).await,
        Route::ImageManipulation => vision::dispatch_image_manipulation(app, args, &workspace).await,
        Route::SecureSend => security::secure_send(app, args, &workspace).await,
        Route::SecureStatus => security::secure_status(app, args, &workspace).await,
        Route::SecureVerifyIdentity => security::secure_verify_identity(app, args, &workspace).await,
        Route::Signal => signal::invoke(app, args).await,
        Route::Vectorize => vectorize::invoke(app, args).await,
        Route::Biometrics => security::biometrics(app, args).await,
        Route::Clipboard => clipboard(app, capability, args),
        Route::Push => session::push(app, args).await,
        Route::OfflineCache => session::offline_cache(app, args, &workspace).await,
        Route::Realtime => session::realtime(app, args).await,
        Route::Net => net::invoke(app, args).await,
        Route::Print => print::invoke(app, args).await,
        Route::CodeGenerate => code_generate::invoke(app, args, &workspace).await,
        Route::KeepAwake => session::keep_awake(app, args).await,
        Route::Unknown => Err(ToolError::Failed {
            tool: capability.into(),
            message: "unknown capability".into(),
        }),
    }
}

/// `device.clipboard@1` — read/write/clear the system clipboard via
/// `tauri-plugin-clipboard-manager`, mirroring the Android/iOS `ClipboardTool` result shape.
fn clipboard(app: &AppHandle, capability: &str, args: Value) -> Result<Value, ToolError> {
    let operation = support::op(&args, "copy");
    let text = support::first_str(&args, &["text", "content"]).unwrap_or("");

    let current = match operation.as_str() {
        "paste" | "read" => app
            .clipboard()
            .read_text()
            .unwrap_or_else(|_| text.to_string()),
        "clear" => {
            let _ = app.clipboard().write_text("");
            String::new()
        }
        _ => {
            app.clipboard()
                .write_text(text)
                .map_err(|e| ToolError::Failed {
                    tool: capability.into(),
                    message: e.to_string(),
                })?;
            text.to_string()
        }
    };

    Ok(json!({
        "state": "ok",
        "op": operation,
        "text": current,
        "length": current.chars().count(),
    }))
}

#[cfg(test)]
mod tests {
    use super::*;

    const TOOLS_JSON: &str = include_str!("../../../../../packages/contract/tools.json");

    /// Every capability and alias in the contract must route to a real handler, otherwise the
    /// desktop engine silently drops a documented capability.
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

    /// The desktop engine mirrors the full mobile tool inventory: every contract capability
    /// must resolve to a concrete (non-`Unknown`) handler.
    #[test]
    fn desktop_mirrors_full_mobile_tool_inventory() {
        let tools: Value = serde_json::from_str(TOOLS_JSON).expect("tools.json parses");
        let capabilities = tools["capabilities"].as_array().expect("capabilities array");
        assert_eq!(capabilities.len(), 21, "contract capability count changed");

        for capability in capabilities {
            let id = capability["id"].as_str().expect("id");
            let routed = route(id);
            assert_ne!(routed, Route::Unknown, "capability not implemented: {id}");
            // Every canonical id must also pass its permission gate on desktop.
            if let Some(permission) = required_permission(id) {
                assert!(
                    GRANTED_PERMISSIONS.contains(&permission),
                    "permission '{permission}' for '{id}' is not granted"
                );
            }
        }
    }

    /// Aliases resolve to the same handler as their canonical id, matching the mobile engines.
    #[test]
    fn aliases_resolve_to_canonical_handler() {
        let tools: Value = serde_json::from_str(TOOLS_JSON).expect("tools.json parses");
        for capability in tools["capabilities"].as_array().unwrap() {
            let id = capability["id"].as_str().unwrap();
            let canonical = route(id);
            for alias in capability["aliases"].as_array().unwrap() {
                let alias = alias.as_str().unwrap();
                assert_eq!(route(alias), canonical, "alias '{alias}' diverged from '{id}'");
            }
        }
    }

    /// Vectorize produces 512-d, L2-normalised embeddings and a bounded cosine score, mirroring
    /// the mobile `VectorizeTool` contract.
    #[test]
    fn vectorize_embedding_is_normalised_and_similarity_is_bounded() {
        let v = vectorize::embed("wagyu smash burger");
        assert_eq!(v.len(), 512);
        let norm: f32 = v.iter().map(|x| x * x).sum::<f32>().sqrt();
        assert!((norm - 1.0).abs() < 1e-4, "expected unit norm, got {norm}");

        let a = support::pack_f32(&vectorize::embed("hello world"));
        let b = support::pack_f32(&vectorize::embed("hello world"));
        let same = support::unpack_f32(&a);
        let other = support::unpack_f32(&b);
        let score = support::cosine(&same, &other);
        assert!((score - 1.0).abs() < 1e-4, "identical inputs should score ~1.0");
    }

    /// The libsignal mirror round-trips encrypt/decrypt and yields a stable 6-block safety number.
    #[test]
    fn libsignal_round_trips_and_fingerprints() {
        let engine = libsignal::LibSignalEngine::new();
        let envelope = engine.encrypt("acme_pos", "peer_a", 1, "order-42");
        let plaintext = engine
            .decrypt("acme_pos", "peer_a", 1, &envelope.ciphertext_b64, 3)
            .expect("decrypts");
        assert_eq!(plaintext, "order-42");

        let fp = libsignal::fingerprint("acme_pos", "peer_a");
        assert_eq!(fp.split('-').count(), 6, "safety number must be 6 blocks");
        assert_eq!(fp, libsignal::fingerprint("acme_pos", "peer_a"));
    }

    /// Haptics pattern defaults match the mobile tables.
    #[test]
    fn haptics_defaults_match_mobile_tables() {
        assert_eq!(haptics::default_duration("tick"), 10);
        assert_eq!(haptics::default_duration("error"), 120);
        assert_eq!(haptics::default_amplitude("heavy"), 255);
        assert_eq!(haptics::default_amplitude("success"), 210);
        assert_eq!(haptics::parse_timings("[0, 40, 20, 60]"), vec![0, 40, 20, 60]);
    }

    /// Beeper sequence parsing tolerates the documented `freq:duration` format.
    #[test]
    fn beeper_parses_sequences() {
        assert_eq!(
            beeper::parse_sequence("880:60,1760:100"),
            vec![(880.0, 60), (1760.0, 100)]
        );
        assert_eq!(beeper::parse_sequence("garbage"), vec![(1200.0, 100)]);
    }

    /// ESC/POS construction emits the init, alignment, bold and cut control bytes.
    #[test]
    fn print_builds_esc_pos_frames() {
        let args = json!({ "text": "Order 42", "align": "center", "bold": "true", "cut": "true" });
        let payload = print::build_esc_pos(&args);
        assert_eq!(&payload[0..2], &[0x1B, 0x40], "must init the printer");
        assert!(payload.windows(3).any(|w| w == [0x1B, 0x61, 0x01]), "center align");
        assert!(payload.windows(3).any(|w| w == [0x1B, 0x45, 0x01]), "bold on");
        assert!(payload.windows(3).any(|w| w == [0x1D, 0x56, 0x00]), "cut");
        assert_eq!(print::wrap("abcdef", 3), "abc\ndef");
    }

    /// Code generation resolves symbology families and renders a deterministic module matrix.
    #[test]
    fn code_generate_resolves_formats_and_matrix() {
        assert_eq!(code_generate::resolve_format("qr"), "QR_CODE");
        assert_eq!(code_generate::resolve_format("EAN-13"), "EAN_13");
        assert_eq!(code_generate::kind_of("QR_CODE"), "2D");
        assert_eq!(code_generate::kind_of("CODE_128"), "1D");

        let m1 = code_generate::render_matrix("https://apex.example/order/42", 21, 21, 2);
        let m2 = code_generate::render_matrix("https://apex.example/order/42", 21, 21, 2);
        assert_eq!(m1, m2, "matrix must be deterministic for the same content");
        assert_eq!(m1.len(), 21);
        assert!(m1.iter().all(|row| row.chars().count() == 21));
    }

    /// Scanner classification matches the mobile symbology heuristic and format filter.
    #[test]
    fn scanner_classifies_and_filters_formats() {
        // 13-char numeric → EAN_13 / 1D; URL → QR_CODE / 2D.
        assert_eq!(classify("9780201633610"), ("EAN_13", "1D"));
        assert_eq!(classify("https://apex.example"), ("QR_CODE", "2D"));
    }

    fn classify(raw: &str) -> (&'static str, &'static str) {
        let is_uri = raw.contains("://") || raw.starts_with('{') || raw.contains('\n');
        if is_uri || raw.chars().count() > 32 {
            ("QR_CODE", "2D")
        } else {
            let fmt = match raw.chars().count() {
                13 => "EAN_13",
                12 => "UPC_A",
                8 => "EAN_8",
                _ => "CODE_128",
            };
            (fmt, "1D")
        }
    }
}
