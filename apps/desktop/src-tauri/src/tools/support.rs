//! Shared helpers for the portable desktop tool engine.
//!
//! Mirrors the cross-platform `ToolSupport` / `ToolArgs` / `VectorMath` helpers used by the
//! Android (`engine.tools`) and iOS (`Engine.Tools`) implementations so every tool produces
//! the same argument parsing, id generation and encoding semantics.

use base64::{engine::general_purpose::STANDARD, Engine as _};
use serde_json::Value;
use uuid::Uuid;

/// Milliseconds since the Unix epoch (`ToolSupport.nowMs`).
pub fn now_ms() -> u64 {
    use std::time::{SystemTime, UNIX_EPOCH};
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

/// FNV-1a hash rendered as lowercase hex (deterministic across platforms).
pub fn fnv1a(value: &str) -> String {
    let mut hash: u64 = 0xcbf2_9ce4_8422_2325;
    for byte in value.bytes() {
        hash ^= byte as u64;
        hash = hash.wrapping_mul(0x0000_0100_0000_01b3);
    }
    format!("{hash:x}")
}

/// Random identifier with a prefix, mirroring `img_<8>`, `push_<8>`, `code_<12>`, etc.
pub fn new_id(prefix: &str, len: usize) -> String {
    let raw = Uuid::new_v4().simple().to_string();
    let n = len.min(raw.len());
    format!("{prefix}{}", &raw[..n])
}

pub fn str_arg<'a>(args: &'a Value, key: &str) -> Option<&'a str> {
    args.get(key).and_then(Value::as_str)
}

/// First present string among `keys` (mirrors the `args["a"] ?: args["b"]` idiom).
pub fn first_str<'a>(args: &'a Value, keys: &[&str]) -> Option<&'a str> {
    keys.iter().find_map(|k| str_arg(args, k))
}

pub fn int_arg(args: &Value, key: &str, default: i64) -> i64 {
    match args.get(key) {
        Some(Value::Number(n)) => n.as_i64().unwrap_or(default),
        Some(Value::String(s)) => s.parse().unwrap_or(default),
        _ => default,
    }
}

pub fn float_arg(args: &Value, key: &str, default: f64) -> f64 {
    match args.get(key) {
        Some(Value::Number(n)) => n.as_f64().unwrap_or(default),
        Some(Value::String(s)) => s.parse().unwrap_or(default),
        _ => default,
    }
}

pub fn flag_arg(args: &Value, key: &str) -> bool {
    match args.get(key) {
        Some(Value::Bool(b)) => *b,
        Some(Value::String(s)) => s == "true" || s == "1",
        _ => false,
    }
}

/// Resolves the operation from `op` / `action` / `operation` (lowercased).
pub fn op(args: &Value, default: &str) -> String {
    first_str(args, &["op", "action", "operation"])
        .unwrap_or(default)
        .to_lowercase()
}

pub fn b64_encode(bytes: &[u8]) -> String {
    STANDARD.encode(bytes)
}

pub fn b64_decode(s: &str) -> Option<Vec<u8>> {
    STANDARD.decode(s).ok()
}

/// Little-endian `f32` packing used for `vector_b64` (mirrors `FloatPacking.pack`).
pub fn pack_f32(values: &[f32]) -> String {
    let mut bytes = Vec::with_capacity(values.len() * 4);
    for v in values {
        bytes.extend_from_slice(&v.to_le_bytes());
    }
    b64_encode(&bytes)
}

pub fn unpack_f32(s: &str) -> Vec<f32> {
    let Some(bytes) = b64_decode(s) else {
        return Vec::new();
    };
    bytes
        .as_chunks::<4>()
        .0
        .iter()
        .map(|c| f32::from_le_bytes(*c))
        .collect()
}

/// Cosine similarity clamped to [-1, 1] (mirrors `VectorMath.cosine`).
pub fn cosine(a: &[f32], b: &[f32]) -> f32 {
    if a.len() != b.len() {
        return 0.0;
    }
    let (mut dot, mut na, mut nb) = (0.0f32, 0.0f32, 0.0f32);
    for i in 0..a.len() {
        dot += a[i] * b[i];
        na += a[i] * a[i];
        nb += b[i] * b[i];
    }
    if na == 0.0 || nb == 0.0 {
        return 0.0;
    }
    (dot / (na.sqrt() * nb.sqrt())).clamp(-1.0, 1.0)
}

/// L2 normalisation (mirrors `VectorMath.l2`).
pub fn l2(v: &[f32]) -> Vec<f32> {
    let norm: f32 = v.iter().map(|x| x * x).sum();
    if norm == 0.0 {
        return v.to_vec();
    }
    let inv = 1.0 / norm.sqrt();
    v.iter().map(|x| x * inv).collect()
}
