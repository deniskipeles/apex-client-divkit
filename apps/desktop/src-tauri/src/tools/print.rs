//! Bluetooth printer / POS bridge ("device.printer@1"). Mirrors Android `PrintTool` and iOS
//! `PrintTool`: the ESC/POS byte construction (init, alignment, bold, wrap, feed, cut) is
//! identical so the emitted payload is byte-compatible. Bluetooth transport is a platform
//! concern wired by the mobile app targets; without it the tool degrades to the same
//! deterministic emulation the mobile engines use in unit tests.

use std::collections::BTreeSet;
use std::sync::Mutex;
use std::sync::OnceLock;

use serde_json::{json, Value};
use tauri::AppHandle;

use crate::commands::ToolError;

use super::support::{first_str, flag_arg, int_arg, now_ms, op, str_arg};

fn connections() -> &'static Mutex<BTreeSet<String>> {
    static CONNECTIONS: OnceLock<Mutex<BTreeSet<String>>> = OnceLock::new();
    CONNECTIONS.get_or_init(|| Mutex::new(BTreeSet::new()))
}

pub async fn invoke(_app: &AppHandle, args: Value) -> Result<Value, ToolError> {
    match op(&args, "scan").as_str() {
        "scan" | "discover" | "list" | "devices" | "printers" => execute_scan(),
        "pair" | "bond" => execute_pair(&args),
        "connect" => execute_connect(&args),
        "disconnect" | "close" => execute_disconnect(&args),
        "print" | "print_text" | "receipt" | "write" => execute_print(&args),
        "status" | "capabilities" | "get_status" => execute_status(),
        other => Err(ToolError::Failed {
            tool: "device.printer@1".into(),
            message: format!("Unsupported printer operation '{other}'"),
        }),
    }
}

fn address(args: &Value) -> Option<&str> {
    first_str(args, &["address", "mac", "device"])
}

fn execute_scan() -> Result<Value, ToolError> {
    Ok(json!({
        "status": "SUCCESS",
        "mode": "emulated",
        "devices": [],
        "count": 0,
        "timestamp": now_ms(),
    }))
}

fn execute_pair(args: &Value) -> Result<Value, ToolError> {
    let address = address(args).ok_or_else(|| fail("missing address argument"))?;
    Ok(json!({ "status": "PAIRED", "address": address, "mode": "emulated", "timestamp": now_ms() }))
}

fn execute_connect(args: &Value) -> Result<Value, ToolError> {
    let address = address(args).ok_or_else(|| fail("missing address argument"))?;
    connections().lock().unwrap().insert(address.to_string());
    Ok(json!({ "status": "CONNECTED", "address": address, "mode": "emulated", "timestamp": now_ms() }))
}

fn execute_disconnect(args: &Value) -> Result<Value, ToolError> {
    let address = address(args).ok_or_else(|| fail("missing address argument"))?;
    let removed = connections().lock().unwrap().remove(address);
    Ok(json!({
        "status": if removed { "DISCONNECTED" } else { "NOT_CONNECTED" },
        "address": address,
        "timestamp": now_ms(),
    }))
}

fn execute_print(args: &Value) -> Result<Value, ToolError> {
    let address = address(args);
    let payload = build_esc_pos(args);
    Ok(json!({
        "status": "SUCCESS",
        "mode": "emulated",
        "address": address.unwrap_or(""),
        "bytes": payload.len(),
        "preview_hex": payload
            .iter()
            .take(32)
            .map(|b| format!("{b:02X}"))
            .collect::<Vec<_>>()
            .join(" "),
        "timestamp": now_ms(),
    }))
}

fn execute_status() -> Result<Value, ToolError> {
    let conns: Vec<String> = connections().lock().unwrap().iter().cloned().collect();
    Ok(json!({
        "status": "READY",
        "available": true,
        "enabled": true,
        "bonded_printers": 0,
        "connections": conns,
        "supported_operations": ["scan", "pair", "connect", "disconnect", "print", "status"],
        "timestamp": now_ms(),
    }))
}

/// Builds the ESC/POS byte stream (mirrors `PrintTool.buildEscPos`).
pub fn build_esc_pos(args: &Value) -> Vec<u8> {
    let mut out: Vec<u8> = vec![0x1B, 0x40];
    out.extend_from_slice(&align_command(
        str_arg(args, "align").unwrap_or("left").to_lowercase().as_str(),
    ));
    if flag_arg(args, "bold") {
        out.extend_from_slice(&[0x1B, 0x45, 0x01]);
    } else {
        out.extend_from_slice(&[0x1B, 0x45, 0x00]);
    }

    let width = int_arg(args, "width", 32).clamp(16, 80) as usize;
    for line in resolve_lines(args) {
        out.extend_from_slice(wrap(&line, width).as_bytes());
        out.push(0x0A);
    }
    let feed_lines = int_arg(args, "feed_lines", 3).clamp(0, 10) as usize;
    out.extend(std::iter::repeat_n(0x0A, feed_lines));
    if flag_arg(args, "cut") || str_arg(args, "cut") == Some("true") {
        out.extend_from_slice(&[0x1D, 0x56, 0x00]);
    }
    out
}

fn resolve_lines(args: &Value) -> Vec<String> {
    if let Some(text) = str_arg(args, "text") {
        return split_lines(text);
    }
    if let Some(lines) = str_arg(args, "lines") {
        return lines
            .split(['\n', ',', '|'])
            .map(|s| s.trim().to_string())
            .filter(|s| !s.is_empty())
            .collect();
    }
    if let Some(receipt) = str_arg(args, "receipt") {
        return split_lines(receipt);
    }
    vec![String::new()]
}

fn split_lines(raw: &str) -> Vec<String> {
    raw.replace("\\n", "\n").split('\n').map(str::to_string).collect()
}

pub(crate) fn wrap(line: &str, width: usize) -> String {
    if line.chars().count() <= width {
        return line.to_string();
    }
    let mut out = String::new();
    let mut count = 0;
    for ch in line.chars() {
        if count == width {
            out.push('\n');
            count = 0;
        }
        out.push(ch);
        count += 1;
    }
    out
}

pub(crate) fn align_command(align: &str) -> [u8; 3] {
    match align {
        "center" | "centre" => [0x1B, 0x61, 0x01],
        "right" => [0x1B, 0x61, 0x02],
        _ => [0x1B, 0x61, 0x00],
    }
}

fn fail(message: &str) -> ToolError {
    ToolError::Failed {
        tool: "device.printer@1".into(),
        message: message.into(),
    }
}
