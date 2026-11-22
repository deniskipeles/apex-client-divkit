//! `storage.files@1` — tenant-scoped workspace file store. Mirrors Android `FilesTool` and the
//! iOS `FilesTool` mirror: it moves bytes and reports metadata, and never parses or rewrites
//! content, so the same capability backs image output, cached JSON and exported CSV.
//!
//! Paths are resolved inside a per-workspace root and rejected if they escape it, so a card
//! cannot read another workspace or reach arbitrary files on the host.

use std::fs;
use std::path::{Component, Path, PathBuf};

use serde_json::{json, Value};

use crate::commands::ToolError;

use super::support::{b64_decode, b64_encode, first_str, flag_arg, int_arg, op};

const MAX_INLINE_READ: u64 = 8 * 1024 * 1024;

/// Base directory for workspace sandboxes.
///
/// Uses the app-data directory so written files survive a restart — this capability exists to
/// persist card state, and the temp dir would silently lose it. Falls back to the temp dir only
/// when no app-data directory can be resolved.
fn sandbox_base(app: &tauri::AppHandle) -> PathBuf {
    use tauri::Manager;
    app.path()
        .app_data_dir()
        .map(|dir| dir.join("workspaces"))
        .unwrap_or_else(|_| std::env::temp_dir().join("apex-workspaces"))
}

/// Resolves a workspace-relative path inside `root`, rejecting absolute paths and any `..`
/// segment. Normalisation happens before the containment check because `..` can only be
/// detected once the path is lexically resolved.
fn resolve(root: &Path, raw: &str) -> Result<PathBuf, String> {
    let raw = raw.trim();
    if raw.is_empty() {
        return Err("Missing 'path' argument".into());
    }
    if raw.starts_with("file://") {
        return Err(format!("Pass a workspace-relative path, not a file:// URI: '{raw}'"));
    }
    let candidate = Path::new(raw);
    if candidate.is_absolute() {
        return Err(format!("Path must be workspace-relative: '{raw}'"));
    }

    let mut out = root.to_path_buf();
    for component in candidate.components() {
        match component {
            Component::Normal(part) => out.push(part),
            Component::CurDir => {}
            _ => return Err(format!("Path escapes the workspace sandbox: '{raw}'")),
        }
    }

    if !out.starts_with(root) {
        return Err(format!("Path escapes the workspace sandbox: '{raw}'"));
    }
    Ok(out)
}

fn relative(root: &Path, file: &Path) -> String {
    file.strip_prefix(root)
        .map(|p| p.to_string_lossy().replace('\\', "/"))
        .unwrap_or_default()
}

fn entry_json(root: &Path, file: &Path) -> Value {
    let meta = fs::metadata(file).ok();
    json!({
        "name": file.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_default(),
        "path": relative(root, file),
        "type": if file.is_dir() { "directory" } else { "file" },
        "size_bytes": if file.is_file() { meta.as_ref().map(|m| m.len()).unwrap_or(0) } else { 0 },
        "modified_ms": modified_ms(&meta),
    })
}

fn modified_ms(meta: &Option<fs::Metadata>) -> u64 {
    meta.as_ref()
        .and_then(|m| m.modified().ok())
        .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

fn glob_to_matcher(glob: &str) -> impl Fn(&str) -> bool + '_ {
    move |name: &str| {
        // Linear wildcard match: '*' spans any run, '?' matches one character.
        let pattern: Vec<char> = glob.chars().collect();
        let target: Vec<char> = name.chars().collect();
        let (mut p, mut t) = (0usize, 0usize);
        let (mut star, mut mark) = (None, 0usize);
        while t < target.len() {
            if p < pattern.len() && (pattern[p] == '?' || pattern[p] == target[t]) {
                p += 1;
                t += 1;
            } else if p < pattern.len() && pattern[p] == '*' {
                star = Some(p);
                mark = t;
                p += 1;
            } else if let Some(sp) = star {
                p = sp + 1;
                mark += 1;
                t = mark;
            } else {
                return false;
            }
        }
        while p < pattern.len() && pattern[p] == '*' {
            p += 1;
        }
        p == pattern.len()
    }
}

fn collect(dir: &Path, recursive: bool, out: &mut Vec<PathBuf>, limit: usize) {
    let Ok(entries) = fs::read_dir(dir) else { return };
    let mut children: Vec<PathBuf> = entries.filter_map(|e| e.ok()).map(|e| e.path()).collect();
    children.sort();
    for child in children {
        if out.len() >= limit {
            return;
        }
        let is_dir = child.is_dir();
        out.push(child.clone());
        if recursive && is_dir {
            collect(&child, true, out, limit);
        }
    }
}

fn list(root: &Path, args: &Value) -> Result<Value, String> {
    let dir = resolve(root, first_str(args, &["path", "dir"]).unwrap_or("."))?;
    if !dir.exists() {
        return Err(format!("No such directory: {}", relative(root, &dir)));
    }
    if !dir.is_dir() {
        return Err(format!("Not a directory: {}", relative(root, &dir)));
    }

    let recursive = flag_arg(args, "recursive");
    let limit = int_arg(args, "limit", 500).clamp(1, 5000) as usize;
    let pattern = first_str(args, &["pattern"]).filter(|p| !p.is_empty());

    let mut entries = Vec::new();
    collect(&dir, recursive, &mut entries, limit);

    let matches: Vec<PathBuf> = entries
        .iter()
        .filter(|p| match pattern {
            Some(glob) => {
                let matcher = glob_to_matcher(glob);
                matcher(&p.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_default())
            }
            None => true,
        })
        .cloned()
        .collect();

    let mut sorted = matches;
    sorted.sort_by(|a, b| {
        let dir_a = a.is_dir();
        let dir_b = b.is_dir();
        dir_b.cmp(&dir_a).then_with(|| a.file_name().cmp(&b.file_name()))
    });
    sorted.truncate(limit);

    let total_bytes: u64 = sorted
        .iter()
        .filter(|p| p.is_file())
        .filter_map(|p| fs::metadata(p).ok())
        .map(|m| m.len())
        .sum();

    let entries: Vec<Value> = sorted.iter().map(|p| entry_json(root, p)).collect();
    // Human-readable rendering so a card can show the listing without parsing `entries`.
    let listing = entries
        .iter()
        // Same `d name` / `- name` shape as Android and iOS so a card renders one format.
        .map(|e| {
            let marker = if e["type"] == "directory" { "d" } else { "-" };
            format!("{marker} {}", e["name"].as_str().unwrap_or_default())
        })
        .collect::<Vec<_>>()
        .join("\n");
    let rel = relative(root, &dir);
    Ok(json!({
        "status": "SUCCESS",
        "path": if rel.is_empty() { ".".to_string() } else { rel },
        "count": entries.len(),
        "total_bytes": total_bytes,
        "truncated": entries.len() >= limit,
        "entries": entries,
        "listing": listing,
    }))
}

fn info(root: &Path, args: &Value) -> Result<Value, String> {
    let raw = first_str(args, &["path"]).ok_or("Missing 'path' argument")?;
    let file = resolve(root, raw)?;
    if !file.exists() {
        return Err(format!("No such file: {raw}"));
    }
    let meta = fs::metadata(&file).map_err(|e| e.to_string())?;
    Ok(json!({
        "status": "SUCCESS",
        "name": file.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_default(),
        "path": relative(root, &file),
        "type": if file.is_dir() { "directory" } else { "file" },
        "size_bytes": if file.is_file() { meta.len() } else { 0 },
        "modified_ms": modified_ms(&Some(meta.clone())),
        "readable": true,
        "writable": !meta.permissions().readonly(),
    }))
}

fn read(root: &Path, args: &Value) -> Result<Value, String> {
    let raw = first_str(args, &["path"]).ok_or("Missing 'path' argument")?;
    let file = resolve(root, raw)?;
    if !file.is_file() {
        return Err(format!("No such file: {raw}"));
    }
    let meta = fs::metadata(&file).map_err(|e| e.to_string())?;
    if meta.len() > MAX_INLINE_READ {
        return Err(format!(
            "File is {} bytes; inline reads are capped at {MAX_INLINE_READ}",
            meta.len()
        ));
    }

    let bytes = fs::read(&file).map_err(|e| e.to_string())?;
    let encoding = first_str(args, &["encoding"]).unwrap_or("utf8").to_lowercase();
    let wants_base64 = encoding == "base64" || encoding == "binary";

    let content = if wants_base64 {
        b64_encode(&bytes)
    } else {
        // Refuse binary content rather than lossy-decoding it: a mangled string would corrupt
        // the file on the way back out.
        match std::str::from_utf8(&bytes) {
            Ok(text) if !bytes.contains(&0) => text.to_string(),
            _ => return Err("File looks binary; pass encoding=base64 to read it verbatim".into()),
        }
    };

    Ok(json!({
        "status": "SUCCESS",
        "path": relative(root, &file),
        "size_bytes": bytes.len(),
        "encoding": if wants_base64 { "base64" } else { "utf8" },
        "content": content,
        "uri": format!("file://{}", file.display()),
    }))
}

fn write(root: &Path, args: &Value, append: bool) -> Result<Value, String> {
    let raw = first_str(args, &["path"]).ok_or("Missing 'path' argument")?;
    let file = resolve(root, raw)?;
    if file.is_dir() {
        return Err(format!("Path is a directory: {raw}"));
    }

    let encoding = first_str(args, &["encoding"]).unwrap_or("utf8").to_lowercase();
    let payload = first_str(args, &["content", "data", "text"]).unwrap_or("");
    let bytes = if encoding == "base64" || encoding == "binary" {
        b64_decode(payload).ok_or("Content is not valid base64")?
    } else {
        payload.as_bytes().to_vec()
    };

    if let Some(parent) = file.parent() {
        fs::create_dir_all(parent).map_err(|e| e.to_string())?;
    }
    if append {
        use std::io::Write;
        let mut handle = fs::OpenOptions::new()
            .create(true)
            .append(true)
            .open(&file)
            .map_err(|e| e.to_string())?;
        handle.write_all(&bytes).map_err(|e| e.to_string())?;
    } else {
        fs::write(&file, &bytes).map_err(|e| e.to_string())?;
    }

    Ok(json!({
        "status": "SUCCESS",
        "path": relative(root, &file),
        "size_bytes": fs::metadata(&file).map(|m| m.len()).unwrap_or(0),
        "bytes_written": bytes.len(),
        "encoding": if encoding == "base64" || encoding == "binary" { "base64" } else { "utf8" },
        "uri": format!("file://{}", file.display()),
    }))
}

fn copy(root: &Path, args: &Value) -> Result<Value, String> {
    let from_raw = first_str(args, &["from", "source", "path"]).ok_or("Missing 'from' argument")?;
    let to_raw = first_str(args, &["to", "destination", "target"]).ok_or("Missing 'to' argument")?;
    let from = resolve(root, from_raw)?;
    let to = resolve(root, to_raw)?;

    if !from.exists() {
        return Err(format!("No such source: {from_raw}"));
    }
    if from.is_dir() {
        return Err("copy only handles files; use mkdir for directories".into());
    }
    if to.exists() && !flag_arg(args, "overwrite") {
        return Err(format!("Destination exists: {to_raw} (pass overwrite=true)"));
    }
    if let Some(parent) = to.parent() {
        fs::create_dir_all(parent).map_err(|e| e.to_string())?;
    }
    fs::copy(&from, &to).map_err(|e| e.to_string())?;

    Ok(json!({
        "status": "SUCCESS",
        "from": relative(root, &from),
        "path": relative(root, &to),
        "size_bytes": fs::metadata(&to).map(|m| m.len()).unwrap_or(0),
    }))
}

fn move_path(root: &Path, args: &Value) -> Result<Value, String> {
    let from_raw = first_str(args, &["from", "source", "path"]).ok_or("Missing 'from' argument")?;
    let to_raw = first_str(args, &["to", "destination", "target"]).ok_or("Missing 'to' argument")?;
    let from = resolve(root, from_raw)?;
    let to = resolve(root, to_raw)?;

    if !from.exists() {
        return Err(format!("No such source: {from_raw}"));
    }
    if to.exists() && !flag_arg(args, "overwrite") {
        return Err(format!("Destination exists: {to_raw} (pass overwrite=true)"));
    }
    if let Some(parent) = to.parent() {
        fs::create_dir_all(parent).map_err(|e| e.to_string())?;
    }
    fs::rename(&from, &to).or_else(|_| {
        // rename fails across filesystems; fall back to copy + delete.
        fs::copy(&from, &to).and_then(|_| fs::remove_file(&from))
    }).map_err(|e| e.to_string())?;

    Ok(json!({
        "status": "SUCCESS",
        "from": relative(root, &from),
        "path": relative(root, &to),
    }))
}

fn delete(root: &Path, args: &Value) -> Result<Value, String> {
    let raw = first_str(args, &["path"]).ok_or("Missing 'path' argument")?;
    let file = resolve(root, raw)?;
    if !file.exists() {
        return Err(format!("No such file: {raw}"));
    }
    if file.is_dir() && !flag_arg(args, "recursive") {
        let empty = fs::read_dir(&file).map(|mut d| d.next().is_none()).unwrap_or(false);
        if !empty {
            return Err("Directory is not empty; pass recursive=true to delete it".into());
        }
    }
    let removed = if file.is_dir() {
        fs::remove_dir_all(&file).is_ok()
    } else {
        fs::remove_file(&file).is_ok()
    };

    Ok(json!({
        "status": if removed { "SUCCESS" } else { "FAILED" },
        "path": relative(root, &file),
        "deleted": removed,
    }))
}

fn mkdir(root: &Path, args: &Value) -> Result<Value, String> {
    let raw = first_str(args, &["path"]).ok_or("Missing 'path' argument")?;
    let dir = resolve(root, raw)?;
    let created = !dir.exists();
    fs::create_dir_all(&dir).map_err(|e| e.to_string())?;

    Ok(json!({
        "status": "SUCCESS",
        "path": relative(root, &dir),
        "created": created,
    }))
}

fn exists(root: &Path, args: &Value) -> Result<Value, String> {
    let raw = first_str(args, &["path"]).ok_or("Missing 'path' argument")?;
    let file = resolve(root, raw)?;
    let kind = if !file.exists() {
        "none"
    } else if file.is_dir() {
        "directory"
    } else {
        "file"
    };
    Ok(json!({
        "status": "SUCCESS",
        "path": relative(root, &file),
        "exists": file.exists(),
        "type": kind,
    }))
}

fn uri(root: &Path, args: &Value) -> Result<Value, String> {
    let raw = first_str(args, &["path"]).ok_or("Missing 'path' argument")?;
    let file = resolve(root, raw)?;
    Ok(json!({
        "status": if file.exists() { "SUCCESS" } else { "MISSING" },
        "path": relative(root, &file),
        "exists": file.exists(),
        "uri": format!("file://{}", file.display()),
    }))
}

pub async fn invoke(
    app: &tauri::AppHandle,
    args: Value,
    workspace: &str,
) -> Result<Value, ToolError> {
    let operation = op(&args, "list");
    let tenant: String = workspace
        .chars()
        .map(|c| if c.is_ascii_alphanumeric() || c == '.' || c == '_' || c == '-' { c } else { '_' })
        .collect();
    let root = sandbox_base(app).join(if tenant.is_empty() { "default" } else { &tenant });
    fs::create_dir_all(&root).map_err(|e| ToolError::Failed {
        tool: "storage.files@1".into(),
        message: e.to_string(),
    })?;

    let outcome = match operation.as_str() {
        "list" | "ls" => list(&root, &args),
        "info" | "stat" => info(&root, &args),
        "read" => read(&root, &args),
        "write" | "put" => write(&root, &args, false),
        "append" => write(&root, &args, true),
        "copy" | "cp" => copy(&root, &args),
        "move" | "mv" | "rename" => move_path(&root, &args),
        "delete" | "rm" => delete(&root, &args),
        "mkdir" => mkdir(&root, &args),
        "exists" | "has" => exists(&root, &args),
        "uri" => uri(&root, &args),
        other => Err(format!("Unsupported files operation '{other}'")),
    };

    outcome.map_err(|message| ToolError::Failed {
        tool: "storage.files@1".into(),
        message,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn temp_root(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("apex-files-test-{name}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[test]
    fn resolve_rejects_parent_traversal() {
        let root = temp_root("traversal");
        assert!(resolve(&root, "../escape.txt").is_err());
        assert!(resolve(&root, "nested/../../escape.txt").is_err());
        assert!(resolve(&root, "/etc/passwd").is_err());
        assert!(resolve(&root, "file:///etc/passwd").is_err());
        assert!(resolve(&root, "nested/ok.txt").is_ok());
    }

    #[test]
    fn write_then_read_round_trips_bytes() {
        let root = temp_root("roundtrip");
        let payload = b64_encode(b"hello workspace");
        let written = write(&root, &json!({ "path": "notes/hello.txt", "content": payload, "encoding": "base64" }), false).unwrap();
        assert_eq!(written["size_bytes"], 15);

        let read_back = read(&root, &json!({ "path": "notes/hello.txt", "encoding": "base64" })).unwrap();
        assert_eq!(read_back["content"], payload);
    }

    #[test]
    fn binary_read_requires_base64() {
        let root = temp_root("binary");
        let bytes = vec![0u8, 1, 2, 3];
        write(&root, &json!({ "path": "blob.bin", "content": b64_encode(&bytes), "encoding": "base64" }), false).unwrap();
        assert!(read(&root, &json!({ "path": "blob.bin" })).is_err());
        assert!(read(&root, &json!({ "path": "blob.bin", "encoding": "base64" })).is_ok());
    }

    #[test]
    fn invalid_utf8_is_refused_instead_of_lossily_decoded() {
        let root = temp_root("utf8");
        // 0xFF is never valid UTF-8 but contains no NUL byte, so a NUL-only check would pass.
        let bytes = vec![0x41u8, 0xFF, 0x42];
        write(&root, &json!({ "path": "latin1.txt", "content": b64_encode(&bytes), "encoding": "base64" }), false).unwrap();
        assert!(read(&root, &json!({ "path": "latin1.txt" })).is_err());
        assert!(read(&root, &json!({ "path": "latin1.txt", "encoding": "base64" })).is_ok());
    }

    #[test]
    fn list_reports_entries_and_supports_globs() {
        let root = temp_root("list");
        write(&root, &json!({ "path": "a.txt", "content": "a" }), false).unwrap();
        write(&root, &json!({ "path": "b.json", "content": "b" }), false).unwrap();
        mkdir(&root, &json!({ "path": "sub" })).unwrap();

        let all = list(&root, &json!({ "path": "." })).unwrap();
        assert_eq!(all["count"], 3);

        let txt = list(&root, &json!({ "path": ".", "pattern": "*.txt" })).unwrap();
        assert_eq!(txt["count"], 1);
        assert_eq!(txt["entries"][0]["name"], "a.txt");
        assert_eq!(txt["listing"], "- a.txt");
        assert!(all["listing"].as_str().unwrap().contains("d sub"));
    }

    #[test]
    fn copy_move_and_delete_keep_the_sandbox_consistent() {
        let root = temp_root("mutations");
        write(&root, &json!({ "path": "one.txt", "content": "payload" }), false).unwrap();
        copy(&root, &json!({ "from": "one.txt", "to": "two.txt" })).unwrap();
        assert!(root.join("two.txt").is_file());

        move_path(&root, &json!({ "from": "two.txt", "to": "three.txt" })).unwrap();
        assert!(root.join("three.txt").is_file());
        assert!(!root.join("two.txt").exists());

        let removed = delete(&root, &json!({ "path": "three.txt" })).unwrap();
        assert_eq!(removed["deleted"], true);
        assert!(!root.join("three.txt").exists());
    }

    #[test]
    fn delete_refuses_non_empty_directory_without_recursive() {
        let root = temp_root("rmdir");
        mkdir(&root, &json!({ "path": "sub" })).unwrap();
        write(&root, &json!({ "path": "sub/x.txt", "content": "x" }), false).unwrap();
        assert!(delete(&root, &json!({ "path": "sub" })).is_err());
        assert!(delete(&root, &json!({ "path": "sub", "recursive": true })).is_ok());
    }
}
