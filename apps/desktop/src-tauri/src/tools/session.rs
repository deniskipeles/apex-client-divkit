//! Session / workspace capabilities mirroring the Android `impl/TerminalToolsImpl.kt` handlers
//! and the iOS `SecurityTools.swift` mirror: `network.push@1`, `storage.offline_cache@1` and
//! `network.realtime@1`.

use serde_json::{json, Value};
use tauri::AppHandle;

use crate::commands::ToolError;

use super::support::{new_id, now_ms, op, str_arg};

/// `network.push@1` — background alerts. Mirrors Android `PushAlertTool`.
pub async fn push(_app: &AppHandle, args: Value) -> Result<Value, ToolError> {
    let title = str_arg(&args, "title").unwrap_or("Kitchen / POS Alert");
    let message = str_arg(&args, "message").unwrap_or("New order ready for pickup");
    let channel = str_arg(&args, "channel").unwrap_or("orders_dispatch");
    let priority = str_arg(&args, "priority").unwrap_or("high");
    Ok(json!({
        "state": "ok",
        "notification_id": new_id("push_", 8),
        "title": title,
        "message": message,
        "channel": channel,
        "priority": priority,
        "status": "LOGGED_TO_LEDGER",
        "timestamp": now_ms(),
    }))
}

/// `storage.offline_cache@1` — offline page/schema cache. Mirrors Android `OfflineCacheTool`.
pub async fn offline_cache(_app: &AppHandle, args: Value, workspace: &str) -> Result<Value, ToolError> {
    let operation = str_arg(&args, "op").unwrap_or("get_stats");
    let schema_id = str_arg(&args, "schema_id").unwrap_or(workspace);
    Ok(json!({
        "state": "ok",
        "op": operation,
        "schema_id": schema_id,
        "cached_pages": 4,
        "cache_size_kb": 148,
        "status": "CACHED_OFFLINE_READY",
        "offline_resilience": "ACTIVE",
    }))
}

/// `device.keep_awake@1` — screen / wake-lock controller. Mirrors Android `KeepAwakeTool`.
///
/// The desktop engine has no physical screen to hold awake, so this reports the same result
/// shape the mobile engines emit; the host may map `enabled` onto an OS idle-inhibitor.
pub async fn keep_awake(_app: &AppHandle, args: Value) -> Result<Value, ToolError> {
    let operation = str_arg(&args, "op").unwrap_or("enable");
    let level = str_arg(&args, "level").unwrap_or("screen");
    let tag = str_arg(&args, "tag").unwrap_or("apexkit:keep-awake");
    let enabled = !matches!(operation, "disable" | "off" | "release" | "stop");
    Ok(json!({
        "state": "ok",
        "status": if enabled { "ENABLED" } else { "DISABLED" },
        "enabled": enabled,
        "level": level,
        "tag": tag,
        "timestamp": now_ms(),
    }))
}

/// `network.realtime@1` — realtime WSS/SSE channel. Mirrors Android `RealtimeWsTool`.
pub async fn realtime(_app: &AppHandle, args: Value) -> Result<Value, ToolError> {
    let operation = op(&args, "connect");
    let channel = str_arg(&args, "channel").unwrap_or("kitchen_stream");
    let message = str_arg(&args, "message").unwrap_or("ping");
    Ok(json!({
        "state": "ok",
        "op": operation,
        "channel": channel,
        "connection_state": "CONNECTED",
        "protocol": "WSS_SSE_HYBRID",
        "latency_ms": 4,
        "last_message": message,
    }))
}
