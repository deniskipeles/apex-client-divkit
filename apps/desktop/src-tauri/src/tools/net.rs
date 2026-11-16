//! Universal network transfer capability ("network.transfer@1"). Mirrors Android `NetTool`
//! (Ktor client + embedded LAN hub) and iOS `NetTool` (`URLSession` + in-process hub registry):
//! outbound HTTP request/download/upload plus the in-memory share registry
//! (`serve`/`share`/`list`/`unshare`). The desktop engine uses `reqwest` for the outbound
//! client; the hub server socket is a platform concern, but the registry and reported contract
//! match the mobile engines.

use std::collections::HashMap;
use std::sync::Mutex;
use std::sync::OnceLock;

use serde_json::{json, Value};
use tauri::AppHandle;

use crate::commands::ToolError;

use super::support::{b64_decode, b64_encode, first_str, int_arg, new_id, now_ms, op, str_arg};

struct SharedItem {
    id: String,
    name: String,
    mime: String,
    bytes: Vec<u8>,
}

struct Hub {
    running: bool,
    port: i64,
    hub_id: Option<String>,
    shared: HashMap<String, SharedItem>,
}

fn hub() -> &'static Mutex<Hub> {
    static HUB: OnceLock<Mutex<Hub>> = OnceLock::new();
    HUB.get_or_init(|| {
        Mutex::new(Hub {
            running: false,
            port: 0,
            hub_id: None,
            shared: HashMap::new(),
        })
    })
}

pub async fn invoke(_app: &AppHandle, args: Value) -> Result<Value, ToolError> {
    let operation = op(&args, "request");
    match operation.as_str() {
        "request" | "http" | "get" | "post" | "put" | "patch" | "delete" | "head" | "fetch" => {
            execute_request(&args, &operation).await
        }
        "download" => execute_download(&args).await,
        "upload" | "send" => execute_upload(&args, &operation).await,
        "serve" | "hub" | "host" | "start_server" => execute_serve(&args),
        "stop" | "stop_server" | "shutdown" => execute_stop(),
        "status" | "capabilities" => execute_status(),
        "share" | "put_share" | "add" => execute_share(&args),
        "list" | "list_share" | "ls" => execute_list(),
        "unshare" | "remove" => execute_unshare(&args),
        other => Err(ToolError::Failed {
            tool: "network.transfer@1".into(),
            message: format!("Unsupported net operation '{other}'"),
        }),
    }
}

fn client() -> reqwest::Client {
    reqwest::Client::builder()
        .build()
        .unwrap_or_else(|_| reqwest::Client::new())
}

fn resolve_method(args: &Value, operation: &str) -> String {
    let raw = str_arg(args, "method").map(str::to_string).unwrap_or_else(|| {
        if ["get", "post", "put", "patch", "delete", "head"].contains(&operation) {
            operation.to_string()
        } else {
            "GET".to_string()
        }
    });
    raw.to_uppercase()
}

fn decode_payload(args: &Value) -> Option<Vec<u8>> {
    for key in ["content_base64", "payload_b64"] {
        if let Some(s) = str_arg(args, key) {
            if let Some(bytes) = b64_decode(s) {
                return Some(bytes);
            }
        }
    }
    for key in ["body", "payload", "content"] {
        if let Some(s) = str_arg(args, key) {
            return Some(s.as_bytes().to_vec());
        }
    }
    if let Some(path) = str_arg(args, "file_path") {
        if let Ok(bytes) = std::fs::read(path) {
            return Some(bytes);
        }
    }
    None
}

fn is_text_like(content_type: &str) -> bool {
    let ct = content_type.to_lowercase();
    ct.starts_with("text/")
        || ct.contains("json")
        || ct.contains("xml")
        || ct.contains("javascript")
        || ct.contains("x-www-form-urlencoded")
}

async fn execute_request(args: &Value, operation: &str) -> Result<Value, ToolError> {
    let url = first_str(args, &["url", "endpoint", "target"]).ok_or_else(|| fail("missing url argument"))?;
    let method_str = resolve_method(args, operation);
    let body = decode_payload(args);
    let timeout_ms = int_arg(args, "timeout_ms", 30_000).max(1) as u64;

    let method = reqwest::Method::from_bytes(method_str.as_bytes()).map_err(|_| fail("invalid method"))?;
    let mut request = client()
        .request(method, url)
        .timeout(std::time::Duration::from_millis(timeout_ms));

    for (k, v) in args.as_object().into_iter().flatten() {
        if let Some(name) = k.strip_prefix("header_") {
            if let Some(value) = v.as_str() {
                request = request.header(name, value);
            }
        }
    }
    if let Some(ct) = str_arg(args, "content_type") {
        request = request.header("content-type", ct);
    }
    let body_allowed = method_str != "GET" && method_str != "HEAD";
    if let Some(body) = body {
        if body_allowed {
            request = request.body(body);
        }
    }

    let response = request.send().await.map_err(|e| fail(&e.to_string()))?;
    let status = response.status().as_u16() as i64;
    let content_type = response
        .headers()
        .get("content-type")
        .and_then(|v| v.to_str().ok())
        .unwrap_or("application/octet-stream")
        .to_string();
    let mut headers = serde_json::Map::new();
    for (k, v) in response.headers() {
        headers.insert(k.as_str().to_string(), Value::String(v.to_str().unwrap_or("").to_string()));
    }
    let data = response.bytes().await.map_err(|e| fail(&e.to_string()))?;

    let mut out = serde_json::Map::new();
    out.insert("status".into(), json!("SUCCESS"));
    out.insert("http_status".into(), json!(status));
    out.insert("url".into(), json!(url));
    out.insert("content_type".into(), json!(content_type));
    out.insert("bytes".into(), json!(data.len()));
    out.insert("headers".into(), Value::Object(headers));
    out.insert("timestamp".into(), json!(now_ms()));
    if is_text_like(&content_type) {
        out.insert("body".into(), json!(String::from_utf8_lossy(&data)));
    } else {
        out.insert("body_b64".into(), json!(b64_encode(&data)));
    }
    Ok(Value::Object(out))
}

async fn execute_download(args: &Value) -> Result<Value, ToolError> {
    let url = first_str(args, &["url", "endpoint", "target"]).ok_or_else(|| fail("missing url argument"))?;
    let timeout_ms = int_arg(args, "timeout_ms", 60_000).max(1) as u64;
    let mut request = client()
        .get(url)
        .timeout(std::time::Duration::from_millis(timeout_ms));
    for (k, v) in args.as_object().into_iter().flatten() {
        if let Some(name) = k.strip_prefix("header_") {
            if let Some(value) = v.as_str() {
                request = request.header(name, value);
            }
        }
    }
    let response = request.send().await.map_err(|e| fail(&e.to_string()))?;
    let status = response.status().as_u16() as i64;
    let content_type = response
        .headers()
        .get("content-type")
        .and_then(|v| v.to_str().ok())
        .unwrap_or("application/octet-stream")
        .to_string();
    let data = response.bytes().await.map_err(|e| fail(&e.to_string()))?;
    Ok(json!({
        "status": "SUCCESS",
        "http_status": status,
        "url": url,
        "file_name": str_arg(args, "file_name").map(str::to_string).unwrap_or_else(|| derive_file_name(url)),
        "content_type": content_type,
        "bytes": data.len(),
        "content_b64": b64_encode(&data),
        "timestamp": now_ms(),
    }))
}

async fn execute_upload(args: &Value, operation: &str) -> Result<Value, ToolError> {
    let url = first_str(args, &["url", "endpoint", "target"]).ok_or_else(|| fail("missing url argument"))?;
    let body = decode_payload(args)
        .ok_or_else(|| fail("missing content_base64/payload/file_path argument"))?;
    let method = resolve_method(args, if operation == "send" { "post" } else { operation });
    let timeout_ms = int_arg(args, "timeout_ms", 60_000).max(1) as u64;
    let method = reqwest::Method::from_bytes(method.as_bytes()).map_err(|_| fail("invalid method"))?;

    let mut request = client()
        .request(method, url)
        .timeout(std::time::Duration::from_millis(timeout_ms))
        .header(
            "content-type",
            str_arg(args, "content_type").unwrap_or("application/octet-stream"),
        )
        .body(body.clone());
    for (k, v) in args.as_object().into_iter().flatten() {
        if let Some(name) = k.strip_prefix("header_") {
            if let Some(value) = v.as_str() {
                request = request.header(name, value);
            }
        }
    }
    let response = request.send().await.map_err(|e| fail(&e.to_string()))?;
    let status = response.status().as_u16() as i64;
    let data = response.bytes().await.map_err(|e| fail(&e.to_string()))?;
    Ok(json!({
        "status": "SUCCESS",
        "http_status": status,
        "url": url,
        "uploaded_bytes": body.len(),
        "response_body": String::from_utf8_lossy(&data),
        "timestamp": now_ms(),
    }))
}

fn execute_serve(args: &Value) -> Result<Value, ToolError> {
    let mut hub = hub().lock().unwrap();
    if hub.running {
        return Ok(json!({
            "status": "SERVING",
            "note": "hub already running",
            "hub_id": hub.hub_id.clone().unwrap_or_default(),
            "port": hub.port,
            "share_count": hub.shared.len(),
        }));
    }
    let port = int_arg(args, "port", 8089).clamp(0, 65535);
    let host = str_arg(args, "host").unwrap_or("0.0.0.0");
    let label = first_str(args, &["label", "name"]).unwrap_or("ApexClient Transfer Hub");
    hub.port = port;
    hub.running = true;
    if hub.hub_id.is_none() {
        hub.hub_id = Some(new_id("hub_", 12));
    }
    let hub_id = hub.hub_id.clone().unwrap_or_default();
    Ok(json!({
        "status": "SERVING",
        "hub_id": hub_id,
        "label": label,
        "host": host,
        "port": port,
        "share_count": hub.shared.len(),
        "urls": [
            format!("http://<device-ip>:{port}/"),
            format!("http://<device-ip>:{port}/health"),
            format!("http://<device-ip>:{port}/list"),
        ],
        "timestamp": now_ms(),
    }))
}

fn execute_stop() -> Result<Value, ToolError> {
    let mut hub = hub().lock().unwrap();
    let was_running = hub.running;
    let stopped_port = hub.port;
    hub.running = false;
    hub.port = 0;
    Ok(json!({
        "status": if was_running { "STOPPED" } else { "NOT_RUNNING" },
        "port": stopped_port,
        "timestamp": now_ms(),
    }))
}

fn execute_status() -> Result<Value, ToolError> {
    let hub = hub().lock().unwrap();
    Ok(json!({
        "status": if hub.running { "SERVING" } else { "IDLE" },
        "running": hub.running,
        "hub_id": hub.hub_id.clone().unwrap_or_default(),
        "port": hub.port,
        "share_count": hub.shared.len(),
        "supported_operations": [
            "request", "get", "post", "download", "upload",
            "serve", "stop", "status", "share", "list", "unshare"
        ],
        "timestamp": now_ms(),
    }))
}

fn execute_share(args: &Value) -> Result<Value, ToolError> {
    let bytes = decode_payload(args)
        .ok_or_else(|| fail("missing content_base64/payload/file_path argument"))?;
    let mut hub = hub().lock().unwrap();
    let id = new_id("", 12);
    let name = first_str(args, &["name", "file_name"])
        .map(str::to_string)
        .unwrap_or_else(|| format!("item_{id}"));
    let mime = str_arg(args, "content_type")
        .unwrap_or("application/octet-stream")
        .to_string();
    let count = bytes.len();
    hub.shared.insert(
        id.clone(),
        SharedItem {
            id: id.clone(),
            name: name.clone(),
            mime: mime.clone(),
            bytes,
        },
    );
    Ok(json!({
        "status": "SHARED",
        "id": id,
        "name": name,
        "mime": mime,
        "bytes": count,
        "path": format!("/file/{id}"),
        "timestamp": now_ms(),
    }))
}

fn execute_list() -> Result<Value, ToolError> {
    let hub = hub().lock().unwrap();
    let items: Vec<Value> = hub
        .shared
        .values()
        .map(|i| {
            json!({ "id": i.id, "name": i.name, "mime": i.mime, "bytes": i.bytes.len() })
        })
        .collect();
    Ok(json!({
        "status": "OK",
        "count": items.len(),
        "items": items,
        "timestamp": now_ms(),
    }))
}

fn execute_unshare(args: &Value) -> Result<Value, ToolError> {
    let id = first_str(args, &["id", "share_id"]).ok_or_else(|| fail("missing id argument"))?;
    let mut hub = hub().lock().unwrap();
    let removed = hub.shared.remove(id).is_some();
    Ok(json!({
        "status": if removed { "REMOVED" } else { "NOT_FOUND" },
        "id": id,
        "remaining": hub.shared.len(),
        "timestamp": now_ms(),
    }))
}

fn derive_file_name(url: &str) -> String {
    let path = url
        .split('?')
        .next()
        .unwrap_or("")
        .split('/')
        .next_back()
        .unwrap_or("");
    if path.is_empty() {
        format!("download_{}", now_ms())
    } else {
        path.to_string()
    }
}

fn fail(message: &str) -> ToolError {
    ToolError::Failed {
        tool: "network.transfer@1".into(),
        message: message.into(),
    }
}
