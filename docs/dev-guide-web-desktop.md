# Dev Guide — Web Dashboard & Desktop POS on a Shared `pos-ui` + `contract` Monorepo

This guide adds **two independent applications** that consume the same `packages/pos-ui` and
`packages/contract` modules:

- `apps/web` — a Nitro-backed cloud dashboard.
- `apps/desktop` — a Tauri v2 point-of-sale app with a Rust tool bridge.

The two apps share only the render layer and the contract. **Neither app renders the other's
UI.** The native mobile apps (`apps/android`, `apps/ios`) are untouched.

---

## 1. Monorepo Layout — Add to the existing root (after the mobile apps)

The repository already contains `apps/android`, `apps/ios`, and `packages/contract`. Add two
app directories and one shared package, and register them in `pnpm-workspace.yaml`:

```text
apps/
  android/                        # untouched native mobile app
  ios/                            # untouched native mobile app
  web/                            # NEW — Nitro cloud dashboard (skills: deno, vercel)
    index.html
    vite.config.ts                # Vite builds the React SPA into dist/
    nitro.config.ts               # Nitro serves the SPA + /api routes
    src/                          # React SPA (fetch-driven, no hardware bridge)
    server/                       # Nitro (h3) API: schema + sync
    tests/contract/               # Vitest against the shared fixtures + server libs
  desktop/                        # NEW — Tauri v2 POS app (Rust tool bridge)
    index.html
    vite.config.ts
    src/                          # React frontend, TauriToolBridge + OutboxSync
    src-tauri/                    # Rust engine: commands, tools, capabilities
packages/
  pos-ui/                         # NEW — shared DivKit render layer
    src/DivKitView.tsx
    src/dispatchTool.ts
    src/EngineStateBridge.ts
    src/customBlocks/             # sdui_chart, sdui_signature, sdui_numpad
    src/extensions.ts
    src/index.ts
  contract/                       # existing — now a real workspace package
    index.ts                      # NEW — canonicalCapabilityId() over tools.json
    tools.json                    # capability ids + aliases (source of truth)
    fixtures/canonical/v1/        # byte-exact canonical JSON fixtures
```

```yaml
# pnpm-workspace.yaml
packages:
  - apps/web
  - apps/desktop
  - packages/pos-ui
  - packages/contract
```

Add a root `package.json` so the whole workspace can be driven from one place:

```json
{
  "name": "apex-client-divkit",
  "private": true,
  "packageManager": "pnpm@9.15.0",
  "scripts": {
    "typecheck": "pnpm -r --filter './packages/*' --filter './apps/web' --filter './apps/desktop' run typecheck",
    "test:web": "pnpm -C apps/web exec vitest run",
    "build:web": "pnpm -C apps/web run build",
    "build:desktop:web": "pnpm -C apps/desktop run build:web",
    "test:desktop": "cargo test --manifest-path apps/desktop/src-tauri/Cargo.toml --lib",
    "check:desktop": "cargo check --manifest-path apps/desktop/src-tauri/Cargo.toml"
  }
}
```

Extend `.gitignore` with the new build outputs (the mobile rules are already present):

```gitignore
# --- apps/desktop (Tauri) ---
apps/desktop/node_modules
apps/desktop/dist
apps/desktop/src-tauri/target
apps/desktop/src-tauri/gen

# --- apps/web (Nitro) ---
apps/web/.nitro
apps/web/.output

# --- pnpm workspace ---
node_modules/
.pnpm-store/
```

> `packages/contract` must have a `package.json` to be a real pnpm project. Without one, pnpm
> silently ignores the directory listed in `pnpm-workspace.yaml`, and every consumer falls back
> to fragile relative filesystem paths (`../../contract/tools.json`). Give it a name and an
> `exports` map, and depend on it as `"@apex/contract": "workspace:*"`.

---

## 2. `packages/contract` — Make the Contract Importable

`tools.json` stays the single source of truth for capability ids and aliases. Wrap it in a
tiny typed module so consumers resolve capabilities instead of re-implementing the lookup:

```ts
// packages/contract/package.json
{
  "name": "@apex/contract",
  "version": "0.0.1",
  "type": "module",
  "private": true,
  "exports": {
    ".": "./index.ts",
    "./tools.json": "./tools.json",
    "./fixtures/*": "./fixtures/*"
  },
  "files": ["tools.json", "fixtures", "docs"]
}
```

```ts
// packages/contract/index.ts
import tools from './tools.json'

export interface Capability {
  id: string
  aliases: string[]
}

export const capabilities = tools.capabilities as Capability[]

/** Resolves a canonical capability id or any of its aliases to the canonical id. */
export function canonicalCapabilityId(alias: string): string | undefined {
  return capabilities.find((c) => c.id === alias || c.aliases.includes(alias))?.id
}
```

This one function is what keeps the web and desktop capability sets from drifting: both the
browser bridge and the Rust router are held to the same `tools.json`.

---

## 3. `packages/pos-ui` — The Shared DivKit Render Layer

`pos-ui` is the only code both apps render. It owns the DivKit wrapper, the tool-dispatch
contract, the `user.*` variable bridge, and the custom blocks.

### 3.1 The tool bridge contract

Both apps implement `ToolBridge`; `pos-ui` never knows which one it is talking to:

```ts
// packages/pos-ui/src/ToolBridge.ts
export interface ToolBridge {
  invoke(capability: string, args: Record<string, unknown>): Promise<ToolResult>
}

// packages/pos-ui/src/ToolResult.ts — mirrors Android ToolResult.kt and iOS ToolProtocols.swift
export type ToolResult =
  | { kind: 'success'; data: Record<string, unknown> }
  | { kind: 'unsupported'; tool: string; reason: string }
  | { kind: 'permissionDenied'; tool: string; permission: string }
  | { kind: 'failed'; tool: string; message: string }
```

### 3.2 Capability dispatch

`dispatchTool` resolves any canonical id **or alias** through the contract, so a card may call
`tools://haptics` or `tools://hardware.haptics@1` and both reach the same handler:

```ts
// packages/pos-ui/src/dispatchTool.ts
import { canonicalCapabilityId } from '@apex/contract'
import type { ToolBridge } from './ToolBridge'
import type { ToolResult } from './ToolResult'

export async function dispatchTool(
  bridge: ToolBridge,
  alias: string,
  args: Record<string, unknown>,
): Promise<ToolResult> {
  const id = canonicalCapabilityId(alias)
  if (!id) return { kind: 'unsupported', tool: alias, reason: 'unknown capability' }
  return bridge.invoke(id, args)
}
```

### 3.3 The `user.*` variable bridge

DivKit variables written by the engine must be `user.*`-namespaced (see
`packages/contract/docs/variables.md`). Enforce it at the boundary:

```ts
// packages/pos-ui/src/EngineStateBridge.ts
export class EngineStateBridge {
  private state = new Map<string, unknown>()

  pushUpdate(name: string, value: unknown): void {
    if (!name.startsWith('user.')) throw new Error(`Variable must be user.*-namespaced: ${name}`)
    this.state.set(name, value)
  }

  pushBatch(updates: Record<string, unknown>): void {
    for (const key of Object.keys(updates)) {
      if (!key.startsWith('user.')) throw new Error(`Variable must be user.*: ${key}`)
    }
    for (const [key, value] of Object.entries(updates)) this.state.set(key, value)
  }

  getVariable(name: string): unknown {
    return this.state.get(name)
  }

  clear(): void {
    this.state.clear()
  }
}
```

### 3.4 Custom blocks

DivKit custom components are registered as web components and mapped by `custom_type`. The
`customElement.ts` helper reads `custom_props` and defines each element exactly once:

```ts
// packages/pos-ui/src/customBlocks/index.ts
import type { CustomComponentDescription } from '@divkitframework/divkit'
import { defineOnce } from './customElement'
import { ApexChartElement } from './sdui_chart'
import { ApexSignatureElement } from './sdui_signature'
import { ApexNumpadElement } from './sdui_numpad'

defineOnce('apex-chart', ApexChartElement)
defineOnce('apex-signature', ApexSignatureElement)
defineOnce('apex-numpad', ApexNumpadElement)

export const customComponents = new Map<string, CustomComponentDescription>([
  ['sdui_chart', { element: 'apex-chart' }],
  ['sdui_signature', { element: 'apex-signature' }],
  ['sdui_numpad', { element: 'apex-numpad' }],
])
```

### 3.5 The DivKit view

`DivKitView` is the seam: it renders the card, intercepts `tools://` actions, and forwards
them through the injected bridge. It is the only file that imports `@divkitframework/react`.

```tsx
// packages/pos-ui/src/DivKitView.tsx
import { DivKit } from '@divkitframework/react'
import type { DivJson } from '@divkitframework/divkit'
import type { ToolBridge } from './ToolBridge'
import { dispatchTool } from './dispatchTool'
import { customComponents } from './customBlocks/index'
import { extensions } from './extensions'

interface Props {
  card: DivJson
  bridge: ToolBridge
  onToolResult?: (url: string, result: unknown) => void
}

export function DivKitView({ card, bridge, onToolResult }: Props) {
  return (
    <DivKit
      id="apex-pos-card"
      json={card}
      customComponents={customComponents}
      extensions={extensions}
      onCustomAction={async (action) => {
        const { url, payload } = action
        if (typeof url === 'string' && url.startsWith('tools://')) {
          const result = await dispatchTool(
            bridge,
            url.replace('tools://', ''),
            (payload ?? {}) as Record<string, unknown>,
          )
          onToolResult?.(url, result)
        }
      }}
    />
  )
}
```

### 3.6 Package entry point

```ts
// packages/pos-ui/src/index.ts
export { DivKitView } from './DivKitView'
export { dispatchTool } from './dispatchTool'
export type { ToolBridge } from './ToolBridge'
export type { ToolResult } from './ToolResult'
export { EngineStateBridge } from './EngineStateBridge'
export { customComponents } from './customBlocks/index'
export { extensions } from './extensions'
export type { DivJson } from '@divkitframework/divkit'
```

**Re-export `DivJson`.** With pnpm's isolated `node_modules`, `apps/web` and `apps/desktop`
cannot resolve `@divkitframework/divkit` transitively. They must import the type from
`@apex/pos-ui`, which does declare the dependency.

```json
// packages/pos-ui/package.json (dependencies)
{
  "@apex/contract": "workspace:*",
  "@divkitframework/react": "^2.0.0",
  "@divkitframework/divkit": "^33.5.0",
  "react": "^18.3.0",
  "react-dom": "^18.3.0"
}
```

---

## 4. `apps/web` — Nitro Cloud Dashboard

The dashboard is a read-only console. It renders schemas, validates publishes, and accepts
sync batches. It has **no hardware bridge** — tool calls return `unsupported`.

### 4.1 App shell

```tsx
// apps/web/src/App.tsx
import { useEffect, useState } from 'react'
import { DivKitView } from '@apex/pos-ui'
import type { DivJson } from '@apex/pos-ui'
import { NoopToolBridge } from './NoopToolBridge'

export function App() {
  const [card, setCard] = useState<DivJson | null>(null)
  useEffect(() => {
    fetch('/api/schema/current')
      .then((r) => r.json())
      .then(setCard)
  }, [])
  if (!card) return <div>Loading…</div>
  return <DivKitView card={card} bridge={NoopToolBridge} />
}
```

```ts
// apps/web/src/NoopToolBridge.ts
import type { ToolBridge, ToolResult } from '@apex/pos-ui'

export const NoopToolBridge: ToolBridge = {
  async invoke(capability): Promise<ToolResult> {
    return {
      kind: 'unsupported',
      tool: capability,
      reason: 'Web dashboard is read-only; run the desktop POS for hardware access.',
    }
  },
}
```

### 4.2 Server routes

| Route | Method | Purpose |
| :--- | :--- | :--- |
| `/api/schema/[tenantId]` | GET | Returns the tenant's DivKit card (`current` = latest). |
| `/api/schema/publish` | POST | Validates a card against the subset schema, then stores it. |
| `/api/sync` | POST | Applies an idempotent batch of pending ops. |

The store is in-memory; the *contract* (idempotency by `client_uuid`, per-tenant schema) is
what matters and is what a durable backend must preserve:

```ts
// apps/web/server/lib/store.ts
const applied = new Set<string>()
const schemas = new Map<string, unknown>()
let latest: unknown = null

export const isApplied = (uuid: string) => applied.has(uuid)
export const markApplied = (uuid: string) => void applied.add(uuid)
export const getSchema = (tenantId: string) => schemas.get(tenantId)
export const getLatestSchema = () => latest
export function putSchema(tenantId: string, divkit: unknown) {
  schemas.set(tenantId, divkit)
  latest = divkit
}
```

### 4.3 Schema validation (the draft-2020-12 trap)

`packages/divkit-subset/schema.json` declares `$schema: draft/2020-12`. The default Ajv build
is draft-07 and **cannot compile it**. Use the 2020 entry point, and note the `.js` extension —
the ESM build of the compiled server needs it:

```ts
// apps/web/server/lib/validateCard.ts
import Ajv2020 from 'ajv/dist/2020.js'   // NOT 'ajv' and NOT 'ajv/dist/2020'
import subsetSchema from '../../../../packages/divkit-subset/schema.json'

const ajv = new Ajv2020({ strict: false, allErrors: true })
const validateComponent = ajv.compile(subsetSchema)

/** Each `card.states[].div` is a component tree; types outside the subset are rejected. */
export function validateCard(divkit: unknown): { ok: boolean; errors: string[] } {
  /* …see the source; validates log_id, states, and every state's div tree… */
}
```

### 4.4 Idempotent sync

Retry classification follows `packages/contract/docs/sync-batch.md`: `5xx`/`timeout`/
`rate_limited` are retryable; everything else is terminal.

```ts
// apps/web/server/lib/sync.ts
export function isRetryable(reason: string): boolean {
  return reason.startsWith('5') || reason === 'timeout' || reason === 'rate_limited'
}

export async function applyBatch(
  batch: SyncItem[],
  isApplied: (uuid: string) => boolean,
  markApplied: (uuid: string) => void,
): Promise<SyncOutcome> {
  /* an already-applied client_uuid is accepted without re-running the op */
}
```

```ts
// apps/web/server/api/sync.post.ts
export default defineEventHandler(async (event) => {
  const body = await readBody<{ batch: SyncItem[] }>(event)
  if (!body?.batch?.length) throw createError({ statusCode: 400, message: 'empty batch' })
  if (body.batch.length > 50) throw createError({ statusCode: 413, message: 'batch too large' })
  return applyBatch(body.batch, isApplied, markApplied)
})
```

### 4.5 Nitro config — serve the SPA and the API

Nitro owns the server. Point its `publicAssets` at the Vite output. **The path is relative to
`srcDir`** (`server/`), so the `../` is required or the dashboard 404s at `/`:

```ts
// apps/web/nitro.config.ts
import { defineNitroConfig } from 'nitropack/config'

export default defineNitroConfig({
  srcDir: 'server',
  compatibilityDate: '2026-11-01',
  preset: process.env.NITRO_PRESET,
  publicAssets: [{ dir: '../dist', baseURL: '/' }], // relative to srcDir
  routeRules: { '/api/**': { cors: true, headers: { 'cache-control': 'no-store' } } },
})
```

`preset` is read from the environment so the same config deploys to Vercel
(`NITRO_PRESET=vercel`) or runs locally with the default `node-server` preset.

### 4.6 Web package.json

```json
{
  "name": "@apex/web",
  "private": true,
  "type": "module",
  "scripts": {
    "dev": "nitro dev",
    "build": "vite build && nitro build",
    "preview": "node .output/server/index.mjs",
    "test:contract": "vitest run tests/contract",
    "typecheck": "tsc --noEmit"
  },
  "dependencies": {
    "@apex/contract": "workspace:*",
    "@apex/pos-ui": "workspace:*",
    "ajv": "^8.17.1",
    "canonicalize": "^2.0.0",
    "h3": "^1.13.0",
    "react": "^18.3.0",
    "react-dom": "^18.3.0"
  },
  "devDependencies": {
    "@types/node": "^20.14.0",
    "@types/react": "^18.3.0",
    "@types/react-dom": "^18.3.0",
    "@vitejs/plugin-react": "^4.3.4",
    "jsdom": "^31.0.1",
    "nitropack": "^2.10.0",
    "typescript": "^5.6.0",
    "vite": "^6.0.0",
    "vitest": "^2.1.0"
  }
}
```

> **Typing the test globals.** Vitest runs with `globals: true`, so `describe`/`test`/`expect`
> are runtime globals. Add `vitest/globals` to `types` or `tsc --noEmit` fails:
>
> ```json
> { "compilerOptions": { "types": ["node", "vitest/globals"] } }
> ```
>
> The `pos-ui` tests touch `customElements`, so they need a DOM environment. Keep the default
> `node` environment for the contract/server tests and opt the shared-UI test into `jsdom`:
>
> ```ts
> // apps/web/vitest.config.ts
> export default defineConfig({
>   test: {
>     globals: true,
>     environment: 'node',
>     environmentMatchGlobs: [['tests/**/pos-ui.test.ts', 'jsdom']],
>     include: ['tests/**/*.test.ts'],
>   },
> })
> ```

### 4.7 Optional: verify signed publishes

If publishes arrive from the edge as signed envelopes, add a middleware that verifies them
before the route runs. It is inert until `APEX_EDGE_PUBLIC_KEY` is set:

```ts
// apps/web/server/middleware/verify-signature.ts
import { defineEventHandler, getHeader, readRawBody, createError } from 'h3'
import { PayloadVerifier } from '../../src/contract/payloadVerifier'

const PUBLIC_KEY = process.env.APEX_EDGE_PUBLIC_KEY ?? ''

export default defineEventHandler(async (event) => {
  if (event.method !== 'POST' || !event.path.startsWith('/api/schema')) return
  if (!PUBLIC_KEY) return

  const envelope = await readRawBody(event, 'utf8')
  if (!envelope) throw createError({ statusCode: 400, message: 'missing signed envelope' })

  const tenant = getHeader(event, 'x-apex-tenant') ?? 'current'
  try {
    event.context.payload = await new PayloadVerifier(tenant, PUBLIC_KEY).verify(envelope)
  } catch (e) {
    throw createError({ statusCode: 401, message: `Envelope rejected: ${(e as Error).message}` })
  }
})
```

---

## 5. `apps/desktop` — Tauri v2 POS App

The desktop app is the POS terminal. It shares the same `DivKitView`, but its bridge is a real
Rust tool bridge.

### 5.1 The Tauri bridge

The frontend calls a single command, `tool_invoke`, and maps Rust's tagged error into the
shared `ToolResult` union:

```ts
// apps/desktop/src/TauriToolBridge.ts
import { invoke } from '@tauri-apps/api/core'
import type { ToolBridge, ToolResult } from '@apex/pos-ui'

export const TauriToolBridge: ToolBridge = {
  async invoke(capability, args): Promise<ToolResult> {
    try {
      const data = await invoke<Record<string, unknown>>('tool_invoke', { capability, args })
      return { kind: 'success', data }
    } catch (e) {
      const err = e as { kind?: string; permission?: string; message?: string }
      if (err?.kind === 'permissionDenied') {
        return { kind: 'permissionDenied', tool: capability, permission: err.permission ?? 'unknown' }
      }
      return { kind: 'failed', tool: capability, message: String(err?.message ?? e) }
    }
  },
}
```

### 5.2 The Rust command and error type

`ToolError` is a `#[serde(tag = "kind")]` enum so the frontend can branch on `kind` without
string matching:

```rust
// apps/desktop/src-tauri/src/commands.rs
use serde::{Deserialize, Serialize};
use serde_json::Value;
use tauri::AppHandle;

#[derive(Debug, Deserialize)]
pub struct ToolInvokeArgs {
    pub capability: String,
    pub args: Value,
}

#[derive(Debug, Serialize)]
#[serde(tag = "kind")]
pub enum ToolError {
    #[serde(rename = "permissionDenied")]
    PermissionDenied { tool: String, permission: String },
    #[serde(rename = "failed")]
    Failed { tool: String, message: String },
}

#[tauri::command]
pub async fn tool_invoke(
    app: AppHandle,
    capability: String,
    args: Value,
) -> Result<Value, ToolError> {
    let req = ToolInvokeArgs { capability, args };
    crate::tools::dispatch(&app, &req.capability, req.args).await
}
```

### 5.3 Capability routing with a build-failing guard

`tools::route` maps every canonical id **and every alias** to a handler. A guard test parses
`packages/contract/tools.json` and fails the build if any contract capability is unrouted —
this is what keeps the Rust engine from silently dropping a documented tool:

```rust
// apps/desktop/src-tauri/src/tools/mod.rs
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Route { Haptics, Beeper, Scanner, Camera, Signal, Vectorize, Clipboard, NotImplemented, Unknown }

pub fn route(capability: &str) -> Route {
    match capability {
        "hardware.haptics@1" | "device.haptics@1" | "haptics.perform@1" | "haptics"
        | "device.haptics" => Route::Haptics,
        // …beeper, scanner, camera, signal, vectorize, clipboard, and the
        // contract capabilities owned by mobile/edge (Route::NotImplemented)…
        _ => Route::Unknown,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    const TOOLS_JSON: &str = include_str!("../../../../../packages/contract/tools.json");

    #[test]
    fn every_contract_capability_routes() {
        let tools: Value = serde_json::from_str(TOOLS_JSON).expect("tools.json parses");
        for capability in tools["capabilities"].as_array().expect("capabilities") {
            let id = capability["id"].as_str().expect("id");
            assert_ne!(route(id), Route::Unknown, "canonical id not routed: {id}");
            for alias in capability["aliases"].as_array().expect("aliases") {
                let alias = alias.as_str().expect("alias");
                assert_ne!(route(alias), Route::Unknown, "alias not routed: {alias}");
            }
        }
    }
}
```

`dispatch` gates on a permission table, then routes. Tauri v2 is deny-by-default, so this
gate lets a capability report `permissionDenied` cleanly instead of failing opaquely:

```rust
pub async fn dispatch(app: &AppHandle, capability: &str, args: Value) -> Result<Value, ToolError> {
    if let Some(permission) = required_permission(capability) {
        if !GRANTED_PERMISSIONS.contains(&permission) {
            return Err(ToolError::PermissionDenied { tool: capability.into(), permission: permission.into() });
        }
    }
    match route(capability) {
        Route::Haptics => haptics::invoke(app, args).await,
        Route::Clipboard => { /* read/write via tauri_plugin_clipboard_manager */ }
        Route::NotImplemented => Err(ToolError::Failed { tool: capability.into(), message: "not implemented in the Tauri engine".into() }),
        Route::Unknown => Err(ToolError::Failed { tool: capability.into(), message: "unknown capability".into() }),
        // …one module per capability family
    }
}
```

### 5.4 Registering plugins

```rust
// apps/desktop/src-tauri/src/lib.rs
mod commands;
mod tools;

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_haptics::init())
        .plugin(tauri_plugin_clipboard_manager::init())
        .plugin(tauri_plugin_sql::Builder::new().build())
        .invoke_handler(tauri::generate_handler![commands::tool_invoke])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
```

### 5.5 Capabilities — deny-by-default

Every plugin the engine calls must be granted here, with the individual `allow-*` ids where
the plugin ships no default set:

```json
// apps/desktop/src-tauri/capabilities/default.json
{
  "$schema": "../gen/schemas/desktop-schema.json",
  "identifier": "default",
  "description": "Capabilities granted to the ApexClient POS main window. Tauri v2 is deny-by-default: without these entries the tool_invoke command and every plugin are blocked.",
  "windows": ["main"],
  "permissions": [
    "core:default",
    "haptics:allow-vibrate",
    "haptics:allow-impact-feedback",
    "haptics:allow-notification-feedback",
    "haptics:allow-selection-feedback",
    "clipboard-manager:allow-read-text",
    "clipboard-manager:allow-write-text",
    "sql:default",
    "sql:allow-execute"
  ]
}
```

> `tauri-plugin-haptics` ships **no** `permissions/default.toml`, so listing the plugin is not
> enough — each `allow-*` id must be named. The same is true of `tauri-plugin-camera`.

### 5.6 Cargo manifest — desktop vs. mobile dependencies

```toml
# apps/desktop/src-tauri/Cargo.toml
[dependencies]
tauri = { version = "2", features = [] }
serde = { version = "1", features = ["derive"] }
serde_json = "1"
tokio = { version = "1", features = ["full"] }

tauri-plugin-haptics = "2"
tauri-plugin-clipboard-manager = "2"
tauri-plugin-sql = { version = "2", features = ["sqlite"] }

# Mobile-only camera plugins. The desktop POS builds without them; scanning and photo
# capture on mobile are covered by apps/android and apps/ios.
[target.'cfg(mobile)'.dependencies]
tauri-plugin-barcode-scanner = "2"
tauri-plugin-camera = "0.1"
```

> `tauri-plugin-barcode-scanner` and `tauri-plugin-camera` are `#![cfg(mobile)]`. Put them
> under the `cfg(mobile)` target or the desktop build links stubs. There is **no**
> `tauri-plugin-camera` v2 on crates.io — the only published crate is a third-party `0.1.x`
> that is Android-only. Do not attempt a v2 bump.

### 5.7 Offline-first outbox

`OutboxSync` reads `pending_sync` rows, POSTs a batch, and applies `accepted`/`rejected` per
the sync-batch contract. Constants match the contract: `BATCH_SIZE = 50`, `MAX_ATTEMPTS = 10`.

```ts
// apps/desktop/src/sync/OutboxSync.ts
const BATCH_SIZE = 50
const MAX_ATTEMPTS = 10

export function isRetryable(reason: string): boolean {
  return reason.startsWith('5') || reason === 'timeout' || reason === 'rate_limited'
}

export class OutboxSync {
  constructor(private readonly edgeEndpoint: string, private readonly db: Database) {}
  static open(path = 'sqlite:apex.db'): Promise<Database> { return Database.load(path) }
  async push(): Promise<{ accepted: number; deadLettered: number }> { /* … */ }
}
```

### 5.8 Desktop package.json and Vite config

```json
{
  "name": "@apex/desktop",
  "private": true,
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build:web": "vite build",
    "build:tauri": "tauri build",
    "tauri": "tauri",
    "typecheck": "tsc --noEmit"
  },
  "dependencies": {
    "@apex/contract": "workspace:*",
    "@apex/pos-ui": "workspace:*",
    "@tauri-apps/api": "^2.0.0",
    "@tauri-apps/plugin-sql": "^2.0.0",
    "react": "^18.3.0",
    "react-dom": "^18.3.0"
  },
  "devDependencies": {
    "@tauri-apps/cli": "^2.0.0",
    "@types/react": "^18.3.0",
    "@types/react-dom": "^18.3.0",
    "@vitejs/plugin-react": "^4.3.4",
    "typescript": "^5.6.0",
    "vite": "^6.0.0"
  }
}
```

```ts
// apps/desktop/vite.config.ts — the dev server must be a fixed port for Tauri's devUrl
export default defineConfig({
  plugins: [react()],
  clearScreen: false,
  server: { port: 5173, strictPort: true },
  build: { outDir: 'dist', emptyOutDir: true },
})
```

```json
// apps/desktop/src-tauri/tauri.conf.json (excerpt)
{
  "build": { "devUrl": "http://localhost:5173", "frontendDist": "../dist" },
  "app": { "windows": [{ "title": "ApexClient POS", "width": 1280, "height": 800 }] }
}
```

---

## 6. Independence Guarantees

| Concern | `apps/web` | `apps/desktop` |
| :--- | :--- | :--- |
| Shared package | `@apex/pos-ui`, `@apex/contract` | `@apex/pos-ui`, `@apex/contract` |
| Bridge | `NoopToolBridge` (read-only) | `TauriToolBridge` (`invoke('tool_invoke')`) |
| Server | Nitro / h3 | none (native Rust) |
| Rendering | its own SPA | its own SPA |

Neither app declares the other as a dependency, and neither imports from the other's source
tree. The only shared code is `packages/pos-ui` and `packages/contract`. `apps/android` and
`apps/ios` are not modified.

---

## 7. Tests and CI

Add a workflow so both apps are built and tested on every push. Keep the mobile release
workflow (`build-release.yml`) as-is; this is a separate, faster pipeline.

```yaml
# .github/workflows/web-desktop.yml
name: Build & Test Web + Desktop
on:
  push: { branches: ['**'] }
  pull_request:
  workflow_dispatch:

jobs:
  web:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: pnpm/action-setup@v4
        with: { version: 9 }
      - uses: actions/setup-node@v4
        with: { node-version: 24, cache: pnpm }
      - run: pnpm install --frozen-lockfile
      - run: |
          pnpm -C packages/pos-ui exec tsc --noEmit
          pnpm -C apps/web exec tsc --noEmit
          pnpm -C apps/desktop exec tsc --noEmit
      - run: pnpm -C apps/web exec vitest run
      - run: pnpm -C apps/web run build
      - run: pnpm -C apps/desktop run build:web

  desktop:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - run: |
          sudo apt-get update
          sudo apt-get install -y libwebkit2gtk-4.1-dev libgtk-3-dev libsoup-3.0-dev \
            libjavascriptcoregtk-4.1-dev libayatana-appindicator3-dev librsvg2-dev \
            build-essential curl wget file libssl-dev
      - uses: dtolnay/rust-toolchain@stable
      - uses: Swatinem/rust-cache@v2
        with: { workspaces: apps/desktop/src-tauri }
      - run: cargo check --manifest-path apps/desktop/src-tauri/Cargo.toml
      - run: cargo test  --manifest-path apps/desktop/src-tauri/Cargo.toml --lib
```

The desktop job needs the Tauri Linux system libraries (WebKitGTK, GTK, libsoup, appindicator,
librsvg) or `cargo check` fails at `tauri-build`.

---

## 8. Verification Checklist

Run these from the repository root; all must pass.

```bash
pnpm install --frozen-lockfile          # 5 workspace projects resolve

pnpm -C packages/pos-ui  exec tsc --noEmit   # shared render layer typechecks
pnpm -C apps/web         exec tsc --noEmit   # dashboard typechecks
pnpm -C apps/desktop     exec tsc --noEmit   # POS frontend typechecks

pnpm -C apps/web exec vitest run             # 29 tests: fixtures, server libs, pos-ui
pnpm -C apps/web run build                   # vite build + nitro build
pnpm -C apps/desktop run build:web           # vite build

cargo check --manifest-path apps/desktop/src-tauri/Cargo.toml   # 0 warnings
cargo test  --manifest-path apps/desktop/src-tauri/Cargo.toml --lib   # routing guard
```

Smoke-test the built dashboard server end to end:

```bash
node apps/web/.output/server/index.mjs &
curl -s localhost:3000/                                   # 200 text/html (SPA)
curl -s -X POST localhost:3000/api/schema/publish \
  -H 'content-type: application/json' \
  -d '{"tenantId":"acme","divkit":{"card":{"log_id":"t","states":[{"state_id":0,"div":{"type":"text","text":"hi"}}]}}}'
curl -s localhost:3000/api/schema/current                 # the card just published
curl -s -X POST localhost:3000/api/sync \
  -H 'content-type: application/json' \
  -d '{"batch":[{"client_uuid":"u1","op":"insert","payload":{}}]}'   # accepted once, then idempotent
```

A publish of a component type outside the subset (e.g. `"type":"video"`) returns **422**, and
re-posting the same `client_uuid` returns it in `accepted` again without re-applying the op.
