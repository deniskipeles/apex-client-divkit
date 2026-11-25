# Workspace packages

A workspace is data, not Kotlin. Everything the app renders for a tenant — the hub card, every
tool card, the collection schema, seed rows and any custom icons — lives in one package.

```
demo/<workspace-id>/
  workspace.json          # manifest: identity, capabilities, urls, card order, collections
  card.json               # hub DivKit document (declares every user.* variable)
  tool_catalog.json       # hub catalog entries (nav://push links)
  tools/<card>.json       # one DivKit document per screen
  collections/*.json      # ApexKit collection schemas + "seed" rows
  icons/icons.json        # optional: {"icon_name": "icon_name.png"} + the image files
demo/workspaces.json      # index of packaged workspace ids
```

`workspace.json`:

| field | meaning |
| --- | --- |
| `format` | `apex.workspace/1` |
| `id` | tenant id; must equal the folder name and match `[a-z0-9_]{2,40}` |
| `title`, `subtitle`, `terminal`, `accent` | launcher + header identity |
| `mode` | `HYBRID` (default), `ONLINE`, `OFFLINE` |
| `capabilities` | base capability ids for the workspace |
| `tools` | `WorkspaceToolPermissions` flags (`hapticVibration`, `cameraBarcodeScanner`, …) |
| `urls` | `tunnel`, `lan`, `client_id` |
| `unread` | seeded notification badge |
| `hub`, `catalog` | file names inside the package |
| `cards` | `{ "dir": "tools", "order": ["local_db", …] }` |
| `collections` | `{ "dir": "collections", "files": [...] }` (the loader also unions `index.json`) |
| `icons` | optional path to the icon manifest |

Collections may carry `"seed": [ { …row… }, … ]`. The rows are inserted the first time the
collection is created, so a workspace ships populated with no Kotlin seeding.

## Build channels

| channel | ships | runtime imports |
| --- | --- | --- |
| `dev` | plaintext `demo/<id>/*` JSON | allowed |
| `prod` | `assets/demo/<id>/workspace.bin` only | refused |

```bash
./gradlew assembleDevDebug
./gradlew assembleProdDebug -PapexWorkspaceKey=<64 hex chars>
# or export APEX_WORKSPACE_KEY
```

`encryptWorkspaces` (`buildSrc/src/main/kotlin/EncryptWorkspacesTask.kt`) packs each folder into
one JSON bundle — `{format, manifest, hub, catalog, cards, collections, icons}` — gzips it and
seals it with AES-256-GCM:

```
"APXW" | version (1) | nonce (12) | AES-256-GCM(gzip(bundle JSON))
```

The key never lives in the repository. A production build without a key fails at configuration
time, and the app refuses to start a prod workspace without one, so plaintext can never be
silently used as a fallback.

## Dev authoring (in the app)

The launcher's dev-only toolbar button (`top_bar_dev_tools_button`, dev channel only) opens
**Workspace packages**:

* **Load DivKit card JSON** — pick a `.json` document; it is validated (`type`/`card`) and
  appended to the selected workspace's card order.
* **Load collection schema JSON** — one collection or a `{"schema":{"collections":[…]}}`
  package; written to the workspace and added to `collections/index.json`.
* **Load icon pack (.zip)** — `icons.json` plus image files; stored as base64 PNG/JPEG for the
  runtime icon registry.
* **Unload** — deletes the imported copy under `filesDir/workspaces/<id>`; the packaged
  workspace is used again.

Imports are materialised as a full copy of the packaged workspace, so the APK's own resources
are never mutated. `DemoCardRepository` prefers the imported copy (hub, catalog, cards,
variables), and `SduiEngineViewModel.reloadWorkspacePackages()` republishes the list, the card
stack and the icons.
