# Architectural Model

```
┌────────────────────────────────────────────────────────────────────────┐
│                        SDUI DOCUMENT / APP                             │
│   (Save a render, cache JSON, export CSV, persist a scan capture)      │
│                                                                        │
│  Calls: tools://files/write                                            │
│  Payload: { op: "write", path: "notes/hello.txt", content: "..." }     │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│                       WORKSPACE FILE STORE                             │
│                                                                        │
│  1. Operations: list, info, read, write, append, copy, move,           │
│     delete, mkdir, exists, uri                                         │
│  2. Sandbox: files/workspaces/<tenantId>/ (Android)                    │
│               Application Support/apex-workspaces/workspaces/<t>/ (iOS)│
│               <app data>/workspaces/<tenantId>/ (desktop)              │
│  3. Paths are workspace-relative; `..` and absolute paths are rejected │
│  4. Bytes in, bytes out — content is never parsed, sniffed or rewritten│
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼ Generic JSON Output
┌────────────────────────────────────────────────────────────────────────┐
│  { "status": "SUCCESS", "path": "notes/hello.txt", "size_bytes": 5,    │
│    "uri": "file:///…/notes/hello.txt" }                                │
└────────────────────────────────────────────────────────────────────────┘
```

# Workspace File Store (`tools://files` / `storage.files@1`)

The **Workspace File Store** is an unopinionated capability: it moves bytes and reports
metadata. It carries no product or vendor coupling, so the same tool backs image output,
cached JSON, exported CSV, and anything else a card needs to persist.

---

## 1. Parameters & Options

Invoked via `tools://files/<operation>`, or `tools://files` with an `op` argument.

| Argument | Type | Notes |
| --- | --- | --- |
| `op` | string | Operation name (default `list`). |
| `path` | string | Workspace-relative path. Absolute paths and `file://` URIs are rejected. |
| `from` / `to` | string | Source and destination for `copy` and `move`. Aliases: `source`/`path`, `destination`/`target`. |
| `content` | string | Payload for `write`/`append`. Aliases: `data`, `text`. |
| `encoding` | string | `utf8` (default) or `base64`. |
| `recursive` | bool | Recurse in `list`; allow removing a non-empty directory in `delete`. |
| `pattern` | string | Glob filter for `list` (`*` spans any run, `?` matches one character). |
| `limit` | int | Maximum entries returned by `list` (1–5000, default 500). |
| `overwrite` | bool | Allow `copy`/`move` to replace an existing destination. |

### Operations

| Operation | Aliases | Effect |
| --- | --- | --- |
| `list` | `ls` | Enumerate a directory; returns the entries plus a `listing` preview. |
| `info` | `stat` | Metadata for one path: type, size, mtime, readability, writability. |
| `read` | — | Return file content. Binary files require `encoding=base64`. |
| `write` | `put` | Create or replace a file. |
| `append` | — | Append to an existing file. |
| `copy` | `cp` | Copy a file (files only; use `mkdir` for directories). |
| `move` | `mv`, `rename` | Move or rename a file. |
| `delete` | `rm` | Remove a file, or a directory when empty or `recursive=true`. |
| `mkdir` | — | Create a directory, including parents. |
| `exists` | `has` | Report whether a path exists and its type. |
| `uri` | — | Return the `file://` URI for a path without reading it. |

`list` returns the same fields on every platform, with one exception forced by the type system:
Android and desktop return `entries` as a structured array, while iOS returns `entries_json`
(a JSON string) because `ToolResult.success` only carries `AnyHashable` scalars. Both carry the
same `count`, `total_bytes`, `truncated` and `listing` fields, so a card that renders `listing`
is portable without branching.

---

## 2. Output Variables

`NativeToolsSuite` maps these onto DivKit variables by default:

| Tool field | Variable |
| --- | --- |
| `uri` | `user.files_last_uri` |
| `path` | `user.files_last_path` |
| `status` | `user.files_status` |

Cards can remap any field through the action's `output` object, e.g.
`{ "output": { "listing": "user.files_listing" } }`.

---

## 3. Example

```json
{
  "type": "button",
  "text": "Write note",
  "action": {
    "url": "tools://files/write",
    "payload": {
      "operation": "write",
      "options": {
        "op": "write",
        "path": "notes/hello.txt",
        "content": "SGVsbG8gZnJvbSB0aGUgd29ya3NwYWNlIGZpbGUgc3RvcmUu",
        "encoding": "base64"
      },
      "output": { "status": "user.files_status", "path": "user.files_path" }
    }
  }
}
```

---

## 4. Safety Rules

- **Tenant isolation.** Every path resolves inside `workspaces/<tenantId>/`. A second tenant
  reading the same relative path sees its own sandbox, or nothing.
- **No traversal.** `..` segments, absolute paths, and `file://` URIs are rejected before the
  filesystem is touched, so a card cannot reach app-private data or another workspace.
- **Honest failures.** Missing files, binary reads without `encoding=base64`, and non-empty
  directory deletes without `recursive=true` return a failure message rather than an empty
  success.
- **No rewriting.** Content is stored and returned verbatim. The tool never normalises
  line endings, re-encodes text, or parses JSON.

---

## 5. Platform Notes

| Platform | Root |
| --- | --- |
| Android | `filesDir/workspaces/<tenantId>/` |
| iOS | `Application Support/apex-workspaces/workspaces/<tenantId>/` |
| Desktop | app data dir `workspaces/<tenantId>/` |

All three implementations expose the same operations and result fields. Desktop gates the
capability behind the `storage` permission in `required_permission`.
