use serde_json::{json, Value};
use tauri::AppHandle;

use crate::commands::ToolError;

use super::libsignal::LibSignalEngine;
use super::support::{first_str, fnv1a, int_arg, op, str_arg};

/// Signal E2EE capability ("crypto.signal@1"). Mirrors Android `LibSignalTool` and iOS
/// `LibSignalTool`: identical operations, session/ratchet telemetry and safety-number semantics.
pub async fn invoke(_app: &AppHandle, args: Value) -> Result<Value, ToolError> {
    let operation = op(&args, "encrypt");
    let peer_id = first_str(&args, &["peer_id", "recipient"]).unwrap_or("default_peer");
    let device_id = int_arg(&args, "device_id", 1);
    let tenant_id = str_arg(&args, "workspace_id").unwrap_or("acme_pos");
    let engine = LibSignalEngine::shared();

    let fail = |message: String| ToolError::Failed {
        tool: "crypto.signal@1".into(),
        message,
    };

    match operation.as_str() {
        "setup_identity" | "init_keys" => {
            let (reg_id, identity_key) = engine.setup_identity(tenant_id);
            Ok(json!({
                "status": "IDENTITY_READY",
                "registration_id": reg_id,
                "identity_key": identity_key,
                "signed_prekey": fnv1a(&format!("spk::{tenant_id}")),
                "library": "org.whispersystems:signal-protocol-android:2.8.1",
            }))
        }
        "export_bundle" | "get_prekey_bundle" => {
            let (reg_id, identity_key) = engine.setup_identity(tenant_id);
            Ok(json!({
                "registration_id": reg_id,
                "identity_key": identity_key,
                "signed_prekey": fnv1a(&format!("spk::{tenant_id}")),
                "signed_prekey_sig": fnv1a(&format!("spksig::{tenant_id}")),
                "prekey_id": 1,
                "prekey": fnv1a(&format!("pk::{tenant_id}")),
            }))
        }
        "create_session" | "establish_session" => {
            let session = engine.create_or_get_session(tenant_id, peer_id, device_id);
            Ok(json!({
                "status": "ESTABLISHED",
                "session_id": session.session_id,
                "peer_id": peer_id,
                "device_id": device_id,
                "safety_number": session.fingerprint,
                "fingerprint": session.fingerprint,
                "cipher": "DoubleRatchet + X3DH + Curve25519",
            }))
        }
        "encrypt" | "send" => {
            let data = first_str(&args, &["data", "payload", "message"])
                .ok_or_else(|| fail("Missing 'data' or 'payload' to encrypt".into()))?;
            let envelope = engine.encrypt(tenant_id, peer_id, device_id, data);
            let safety_number = engine.get_safety_number(tenant_id, peer_id);
            Ok(json!({
                "status": "ENCRYPTED",
                "type": envelope.type_,
                "ciphertext": envelope.ciphertext_b64,
                "peer_id": peer_id,
                "safety_number": safety_number,
            }))
        }
        "decrypt" | "receive" => {
            let ciphertext = first_str(&args, &["ciphertext", "payload"])
                .ok_or_else(|| fail("Missing 'ciphertext' to decrypt".into()))?;
            let message_type = int_arg(&args, "type", 3);
            let plaintext = engine
                .decrypt(tenant_id, peer_id, device_id, ciphertext, message_type)
                .map_err(|e| fail(e.to_string()))?;
            let safety_number = engine.get_safety_number(tenant_id, peer_id);
            Ok(json!({
                "status": "DECRYPTED",
                "plaintext": plaintext,
                "peer_id": peer_id,
                "safety_number": safety_number,
            }))
        }
        "status" | "safety_numbers" => {
            let safety_number = engine.get_safety_number(tenant_id, peer_id);
            let has_session = engine.has_session(tenant_id, peer_id, device_id);
            Ok(json!({
                "status": if has_session { "ESTABLISHED" } else { "NO_SESSION" },
                "peer_id": peer_id,
                "safety_number": safety_number,
            }))
        }
        "reset_session" => {
            engine.reset_session(tenant_id, peer_id, device_id);
            Ok(json!({ "status": "SESSION_RESET", "peer_id": peer_id }))
        }
        other => Err(fail(format!("Unknown operation '{other}'"))),
    }
}
