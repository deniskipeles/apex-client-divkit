# AGENTS.md

Repository-specific knowledge for the Apex Client DivKit SDUI POS app.

## Build & test

- `apps/android` is a **self-contained, single-module Gradle project**: that directory is
  the Gradle root and the app is the root project, so the sources live at
  `apps/android/src` (no nested `:app` module). Run all Gradle commands from
  `apps/android`, and drop the `:app:` task prefix (`assembleDebug`, not
  `:app:assembleDebug`).
- The former KMP `shared/` module has been **removed**. Cross-platform contract identity is
  now enforced by shared fixtures + mirrored implementations under `packages/contract/`
  (Android tests, iOS `Sources/Contract`, Web `src/contract`), not by shared Kotlin.
- Toolchain: Gradle 9.3.1, AGP 9.1.1, Kotlin 2.2.10, compileSdk/targetSdk 36, minSdk 24, Java 11.
- `./gradlew` runs are slow (30s–2min). Prefer running them in the background and
  polling a log file; foreground commands are capped below the idle timeout.

```bash
cd apps/android
./gradlew compileDebugKotlin          # fast compile check
./gradlew testDebugUnitTest           # unit + Robolectric tests
./gradlew assembleDebug               # produces APKs (universal + per-ABI)
```

### Environment

`apps/android/build-apks.sh` is self-bootstrapping: it needs only `bash`, `curl` (or `wget`)
and `tar`. On a bare machine it downloads Eclipse Temurin 21 and the Android command-line
tools into `~/.apex-toolchain` (override with `APEX_TOOLCHAIN_DIR`), installs
`platform-tools`, `platforms;android-36.1` and `build-tools;36.0.0`, and writes the SDK
licence hashes. Zip extraction falls back to `python3`/`jar` when `unzip` is missing.

Provisioning is additive — a usable `JAVA_HOME`/`ANDROID_HOME`/`java` on `PATH` always wins
and is never overwritten, and only missing SDK packages are installed. `local.properties`
`sdk.dir` is rewritten only when the recorded path no longer exists.

`assembleRelease` needs `STORE_PASSWORD`/`KEY_PASSWORD` (see `build-apks.sh`; the default
keystore `my-upload-key.jks` uses `storepass123`). R8 itself runs fine without them —
only `packageRelease` fails when the passwords are unset.

The prod channel also needs `APEX_WORKSPACE_KEY` (64 hex chars) or `-PapexWorkspaceKey=…`;
without it `encryptWorkspaces` fails the build. `build-apks.sh` forwards the env var as a
Gradle property, and the release workflow reads the `APEX_WORKSPACE_KEY` secret — minting a
per-run key when the secret is absent so the pipeline still ships encrypted bundles.
`build-apks.sh --channel prod` builds only the prod variants; the release workflow uses it so
the plaintext dev APKs are never published.

To provision the toolchains by hand instead (e.g. to share them across projects):

```bash
sudo apt-get install -y openjdk-21-jdk-headless unzip curl
# cmdline-tools → $ANDROID_HOME/cmdline-tools/latest
sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-36.1" "build-tools;36.0.0"
```

ML assets (`src/main/assets/*.tflite`) are fetched on demand by the script and gitignored;
set `SKIP_ML_ASSETS=true` to reuse whatever is already there.

### Desktop (Tauri v2) and iOS Swift builds

Both toolchains are absent by default and can be installed into `$HOME/toolchain`:

```bash
# Rust (desktop): install with rustup, then PATH=$HOME/toolchain/cargo/bin:$PATH
# Tauri Linux deps (needs sudo):
sudo apt-get install -y libwebkit2gtk-4.1-dev libgtk-3-dev libsoup-3.0-dev \
  libjavascriptcoregtk-4.1-dev libayatana-appindicator3-dev librsvg2-dev \
  build-essential pkg-config libssl-dev
cargo check --manifest-path apps/desktop/src-tauri/Cargo.toml
cargo test  --manifest-path apps/desktop/src-tauri/Cargo.toml --lib

# Swift (iOS): download swift-6.x-RELEASE-debian12 from swift.org into $HOME/toolchain
sudo apt-get install -y binutils-gold libcurl4-openssl-dev libncurses-dev libsqlite3-dev libxml2-dev
cd apps/ios && swift build && swift test   # Engine + Contract build/test on Linux
```

The iOS Xcode app target (`apps/ios/*.swift`) cannot be compiled on Linux; only the
SwiftPM `Contract`/`Engine` package under `apps/ios/Sources` is verifiable there. When
editing `Sources/Contract/TerminalRegistry.swift` or `Sources/Engine/Tools/TerminalApp.swift`,
keep `aliases` byte-identical to `packages/contract/tools.json` — `ContractTests.testAliasesMatchToolsJson`
and the Android `CapabilityContractTest` both assert this.

- `apps/ios` is a SwiftPM package (`Package.swift`) that builds and tests on Linux CI:
  `swift build` / `swift test`. The `CSQLite` system-library target binds the platform
  `libsqlite3`, so `libsqlite3-dev` (for `sqlite3.h`) must be installed. A Swift 6.x toolchain
  plus `libncurses6` are required; download from swift.org and add its `usr/bin` to `PATH`.


## Workspace packages (dev / prod channels)

A workspace is data, not Kotlin. `apps/android/src/dev/resources/demo/<id>/` holds
`workspace.json` (identity, `tools` flags, `urls`, `cards.order`, `collections.files`),
`card.json` (hub + every `user.*` variable), `tool_catalog.json`, `tools/<card>.json` and
`collections/*.json` (schema + `"seed"` rows). `engine/workspace/WorkspaceRepository` loads
them and `WorkspaceProvisioner.defaultWorkspaces` maps each bundle to a `TenantWorkspaceSpec`,
so adding a workspace is a folder plus `demo/workspaces.json`, never a Kotlin change.

- Channel `dev` ships the plaintext folders; `prod` ships only
  `assets/demo/<id>/workspace.bin` (`APXW` | version | nonce | AES-256-GCM(gzip(json))).
  `encryptWorkspaces` (in `buildSrc`) runs for prod variants and fails without
  `-PapexWorkspaceKey=<64 hex>` / `APEX_WORKSPACE_KEY`; `BuildConfig.WORKSPACE_KEY` is empty in
  dev, and `WorkspaceRepository.loadEncrypted` fails closed when it is missing.
- Tasks are channel-qualified: `testDevDebugUnitTest`, `assembleDevDebug`, `assembleProdDebug`
  (there is no `testDebugUnitTest`/`assembleDebug` any more).
- Dev authoring lives in the launcher's `top_bar_dev_tools_button` dialog
  (`ui/DevWorkspaceTools.kt` + `engine/workspace/DevWorkspaceImport`). Imports materialise the
  workspace under `filesDir/workspaces/<id>`, shadow the packaged copy (`DemoCardRepository`
  checks `importedBundle` first), and `unload` deletes the folder.
- Collection `"seed"` rows are inserted by `ApexKitDatabaseEngine.importTenantSchema` when the
  collection is empty; that is why the demo workspaces need no Kotlin seed data.
- Custom icons travel as base64 PNG/JPEG in `icons/icons.json` (dev zip import supported) and
  land in `engine/icons/CustomIconRegistry`.

## Tool capability architecture (`tools://*`)

New terminal capabilities must be wired through **all** of these:

1. `engine/tools/impl/<Name>Tool.kt` — implements `ToolHandler`
   (`capability`, `requiredPermissions`, `suspend invoke(ToolArgs, ToolContext): ToolResult`).
2. `engine/tools/TerminalApp.kt` — add the handler to the list and register canonical
   capability + shorthand aliases in the `when (h.capability)` map.
3. `engine/tools/ToolRegistry.kt` — add the canonical id to `ProvenanceChecker.publisherAllowlist`
   so `Provenance.Publisher` callers may invoke it.
4. `engine/tools/NativeToolsSuite.kt` — add a `ToolSchema` (toolId, displayName, description,
   supportedOperations, defaultOptions, defaultOutputMapping), add a dispatch branch in
   `execute(...)`, add aliases in the tool-id normalizer, and add an `execute<Name>(...)` helper.
   Stateful tools (open sockets/connections) should be shared instances, not per-call.
5. `apps/android/src/main/AndroidManifest.xml` — declare any new runtime permissions.
6. `README.md` — add a row to the `tools://*` table.
7. `docs/tools/<name>.md` — add a per-tool reference doc and a row in `docs/tools/README.md`.

`NativeToolsSuite.execute(...)` returns output mapped into `user.*` DivKit variables via
`defaultOutputMapping`; types are preserved (IntVal/DoubleVal/Bool/Str).

## Icon scheme (`icons://native/<name>`)

Icons are **not** `tools://` capabilities — they are a resolver over a bundled catalog, wired
through two files:

1. `engine/icons/NativeIconCatalog.kt` — pure-Kotlin catalog (`NativeIcon(name, material,
   category, aliases)`), plus `normalize`/`find`/`findByMaterial`/`uriFor`. No Compose import,
   so it is unit-testable on plain JVM.
2. `engine/icons/NativeIconVectors.kt` — `resolve(material): ImageVector?`, the only file that
   imports `androidx.compose.material.icons.*`.

Consumer points: `DivKitMultiplatform.resolveIcon(name)` (used by `button.icon`, the `icon`
node, and the `image` node) and `EngineRouter.handleIcons()` (the `icons://` action branch,
which publishes `user.icon_name` / `user.icon_material` / `user.icon_category`).

Invariants enforced by `NativeIconCatalogTest` + `ExampleRobolectricTest`:

- Every canonical name and alias is unique across the catalog and resolves to one icon.
- Every `material` id is unique and is not shadowed by another icon's key (keys and material
  ids are both matched case-insensitively).
- Every catalog entry maps to a Compose vector, so a missing `NativeIconVectors` branch fails
  the build.

Adding an icon = append one catalog entry + one `NativeIconVectors` branch. Keep the catalog
asset-free (vectors only) so the APK stays well under the 4 MB budget. The catalog is
**285 icons** (285 unique materials, 670 keys); R8 inlines the vector table, so the whole
scheme adds roughly **64 KB** to the release APK (measured by building with and without
`NativeIconVectors`).

## Demo workspace showcase

The built-in demo tenants (`WorkspaceProvisioner.defaultWorkspaces`) are the live showcase
of the SDUI surface. `acme_pos` renders `demo/apex`; `apex_kiosk` renders
`demo/apex-kiosk` (`demoRoot` + `toolCardOrder` on the spec) and exercises the SQLite data
plane end to end. When a tool gains a user-facing operation, surface it in the appropriate
tenant too:

- `SduiOnFlyBuilder.build<Name>ToolBlock(spec)` — a DivKit container with live `user.*` readouts
  and `buildNativeToolTriggerBlock(...)` trigger buttons.
- `WorkspaceProvisioner.buildDefaultDivKitCardJson(spec)` — declare every `user.*` variable the
  block reads, and `add(...)` the block into the card's item list.
- `SduiEngineViewModel` — seed the same variables in the `EngineStateBridge.pushBatch(...)`
  reset so the card renders populated before the first tool call.
- `SduiEngineViewModel` + `DeveloperDiagnosticsSheet` — the workspace's developer surface is a
  `⋮ → Developer diagnostics` overlay, not a block under the card. There is no on-the-fly builder
  and no manual refresh; the signed cached schema is the only source of truth.
- `DivKitMultiplatform.resolveIcon(name)` — map any new `icon` names to a Material icon; the
  demo also showcases the `icons://native/<name>` scheme via `buildNativeIconSchemeBlock`.

Demo cards are data (`src/main/resources/demo/apex/tools/*.json`), so nothing type-checks their
action payloads. A handler reads `payload["options"]` as flat string args — a button that omits
one fails at runtime with e.g. `missing url argument` instead of failing the build.
`demoCards_supplyTheArgumentsTheirToolsRequire` dispatches every `tools://` action in every
card and fails on those errors, so add the required argument when you add a button.

Naming: `inventory` is the ApexKit **collection/table** name in `WorkspaceProvisioner` and the
SQL payloads — it is schema and must not be renamed. User-visible demo content (card labels,
`user.*` variables, resource file names, icon refs) uses `catalog`.

`local-db://op/<name>` is the SQLite data plane (insert/query/count/delete), dispatched by
`EngineRouter` and documented in `docs/tools/local-db.md`.

`icons://native/<name>` is the icon resolver scheme, documented in `docs/tools/icons.md`.

## Storage engine (SQLite, schema v4)

`engine/db/WorkspaceDatabase.kt` (Android) and `Sources/Engine/DB/WorkspaceDatabase.swift`
(iOS) own the per-workspace SQLite schema. Both are mirrored; keep them in step. The v4
contract is in `packages/contract/docs/schema.md`. Key invariants:

- **`records.id` is `TEXT`** so callers may supply identifiers (`"customer_abc"`, `"42"`).
  A missing `id` is drawn from the `_record_id_sequence` AUTOINCREMENT table. `OpResult.RowId`
  and `Record.id` are therefore `String`.
- **Uniqueness is enforced by schema-generated expression indexes**, not a shadow table. For
  each collection the engine derives `idx_u_<colId>_<field>` (UNIQUE) per `unique` field,
  `idx_c_<colId>_<n>` (UNIQUE) per `composite_unique` list and `idx_n_<colId>_<field>` per
  `sql_indexed` field, each `(collection_id, json_extract(data, '$.<field>'))`. `_applied_indexes`
  records the live set and `ensureIndexes` reconciles it (create/drop/recreate) on schema
  import, refusing a UNIQUE index when duplicates already exist.
- **`_relations`** holds only the edges that need storage: `cascade_on_target_delete`,
  `relation_type = many`, or `has_properties`. Endpoints are text ids with `ON DELETE CASCADE`
  FKs, so deleting either endpoint removes the edge. A plain single reference lives in
  `records.data` only. Application cascade walks cascade-marked origins in the engine (the FK
  cascade removes the edge rows first); a visited-set guards cycles.
- `SqlDriver` gained `beginTransaction()`/`setTransactionSuccessful()`/`endTransaction()` (and
  `lastErrorMessage()` on iOS). Enable `PRAGMA foreign_keys = ON` per connection — it is off by
  default and is what makes the cascades fire.

## Workspace notifications tray

A native Compose surface (not a `tools://` capability) that groups notifications by workspace
with per-notification actions. Wired through four files:

1. `engine/notifications/NotificationCenter.kt` — pure-Kotlin model (`WorkspaceNotification`,
   `NotificationGroup`, `NotificationType`) plus the reducer (`group`, `markRead`,
   `markAllRead`, `markWorkspaceRead`, `mute`/`muteWorkspace`/`unmuteWorkspace`, `dismiss`,
   `toggleExpanded`, `add`, `relativeTime`). **No Android/Compose imports**, so it is
   unit-testable on plain JVM.
2. `engine/notifications/NotificationSeed.kt` — Android-free demo seed (`demo(now)`,
   `incoming(workspaceId, now, sequence)`), keyed by the demo tenant ids.
3. `com/example/ui/NotificationTray.kt` — Compose UI. `rememberNotificationTrayActions`
   builds the `NotificationTrayActions` bundle shared by both surfaces;
   `NotificationTrayOverlay` renders `NotificationTrayPanel` (the dropdown) and `NotificationFeed`
   is the grouped/expandable list they both reuse. Both panels are **dumb, state-driven**
   composables driven entirely by injected callbacks (so tests render them without a ViewModel).
   Group glyphs use `engine.icons.nativeIconVector(reference)`.
   `com/example/ui/NotificationsTab.kt` adds the full-page surface: `NotificationsTab` wraps
   `NotificationsTabContent` (also state-driven) with a summary header, per-workspace filter
   chips and the same feed. It is reachable from the launcher bottom nav (`nav_notifications`,
   badged with the global unread count) and from the in-tenant app bar bell / overflow menu,
   where it renders inside the host bottom sheet with `showHeader = false` to avoid a duplicate
   title.
4. `com/example/enginehost/SduiEngineViewModel.kt` — state (`notifications`,
   `expandedNotificationGroups`, `notificationShowRead`, ...) + actions. All mutations funnel
   through `mutateNotifications { ... }`, so unread counts, group ordering and the top-bar
   badge update in one recomposition. `SduiEngineUiState.unreadFor(tenantId, fallback)` feeds
   the launcher cards' unread badges from the live tray count.

Invariants enforced by `NotificationCenterTest` + `NotificationTrayUiTest`:

- Grouping is newest-group-first / newest-item-first; `unreadCount` counts only unread; a group
  is `isMuted` only when every item is muted.
- Reducers are copy-on-write (input list never mutated); `add` drops items whose workspace is
  muted; `relativeTime` clamps future timestamps to `"now"`.
- Every seeded workspace icon and every `NotificationType.icon` resolves through
  `NativeIconCatalog` — a typo'd icon name fails the build.
- The tray panel renders grouped headers, expansion revealing rows with the right-aligned
  workspace glyph (`notification_row_glyph_<id>`) and View/Mark read/Mute actions.
- `NotificationsTabUiTest` pins the tab: header summary copy, the "All" + per-workspace filter
  chips narrowing the feed, the settings panel, embedded (`showHeader = false`) mode, and the
  empty/loading states. The chip row is a `LazyRow`, so those assertions scroll with
  `performScrollToNode` instead of assuming every chip is composed.

Adding a notification source = feed items through `NotificationCenter.add` into
`setNotifications`, or add a seed in `NotificationSeed`. New notification kinds add a
`NotificationType` whose `icon` names an existing catalog icon. Reference:
`docs/tools/notifications-tray.md`.

## Web dashboard (`apps/web`), Desktop POS (`apps/desktop`) & DivKit dev server (`apps/dev-server`)

Three independent pnpm-workspace apps consume the same `packages/pos-ui` and `packages/contract`
modules. None renders another's UI. `apps/android` and `apps/ios` are untouched.

- `packages/pos-ui` — shared DivKit render layer: `DivKitView` (wraps `@divkitframework/react`),
  `dispatchTool` (resolves capability ids/aliases from `packages/contract/tools.json`), the
  `ToolBridge`/`ToolResult` interface both apps implement, `EngineStateBridge`, and the
  `sdui_chart` / `sdui_signature` / `sdui_numpad` custom blocks. Exports the `DivJson` type so
  apps never import `@divkitframework/divkit` directly (pnpm isolation would break resolution).
- `apps/web` — Nitro (h3) cloud dashboard. `server/api/**` holds `/api/schema/[tenantId]`,
  `/api/schema/publish`, `/api/sync`; `src/` is the Vite+React SPA. `ToolBridge` is a no-op
  (the dashboard is read-only). Nitro serves the SPA via `publicAssets: [{ dir: '../dist' }]`
  — the path is relative to `srcDir` (`server/`), so the `../` is required.
- `apps/desktop` — Tauri v2 POS. Frontend `src/` uses `TauriToolBridge` (`invoke('tool_invoke')`).
  Rust engine in `src-tauri/`: `commands::tool_invoke` → `tools::dispatch` → one module per
  capability family. `tools::route` maps every canonical id **and alias** to a handler; the
  `every_contract_capability_routes`, `aliases_resolve_to_canonical_handler` and
  `desktop_mirrors_full_mobile_tool_inventory` tests fail the build if a contract capability is
  unrouted or a permission gate is not granted.
  Tauri v2 is deny-by-default: a capability's plugin must be listed in
  `capabilities/default.json` or the call is blocked before it reaches Rust.
- **Desktop tool parity.** The desktop engine is the same terminal as `apps/android` /
  `apps/ios` — it mirrors the **full** mobile tool inventory, one module per family:
  `haptics`, `beeper`, `audio` (shared tone synthesizer), `scanner`, `camera`, `vision`
  (`embed_image` / `remove_background` / `u-2-Net` / `identify_product` / `image_manipulation`),
  `security` (`secure.send` / `secure.status` / `secure.verify_identity` / `biometrics`),
  `signal` + `libsignal` (Double-Ratchet mirror), `vectorize`, `session` (`push` /
  `offline_cache` / `realtime`), `net` (`reqwest` HTTP + in-memory LAN hub registry), `print`
  (ESC/POS), `code_generate` and `support` (shared helpers). Device-model inference, camera
  frames, Bluetooth sockets and the hub server socket are platform concerns; the desktop
  modules reproduce the same **observable result shapes** so DivKit cards render identically.
  When adding a capability, mirror the mobile change in the matching Rust module and add the
  canonical id + aliases to `tools::route`.
- `apps/dev-server` — `@apex/dev-server`, the Boda DivKit playground (imported from a standalone
  Bun project). Nitro (h3) serves `/api/health`, `/api/schemas` and `/api/schemas/[id]`; the
  Vite SPA in `client/` renders those schemas in a device simulator with a variables inspector
  and event log. Card generators live in `server/utils/schemas/` and are pure JS, so the same
  module runs on Node, prerenders for the `static` preset and bundles for Cloudflare Pages.
  `scripts/build-schemas.mjs` imports `registry.ts` directly, which needs Node's type stripping
  (Node >= 22.18) and therefore explicit `.ts` extensions in `registry.ts` imports — the
  extensionless form only resolved under Bun. It has no test runner; `pnpm -C apps/dev-server
  run typecheck` (`tsc --noEmit`) is the check. It does not depend on `packages/pos-ui` or
  `packages/contract`; it renders `@divkitframework/divkit` directly.

Commands (root `package.json`):

```bash
pnpm install                 # pnpm workspace (apps/web, apps/desktop, apps/dev-server, packages/*)
pnpm test:web                # vitest: contract fixtures, server libs, shared pos-ui
pnpm build:web               # vite build + nitro build
pnpm dev:dev-server          # Nitro dev server on :3000 (PORT overrides) + live schema API
pnpm build:dev-server        # vite build + schema snapshots + nitro build (.output/)
pnpm test:desktop            # cargo test (capability-routing guard)
pnpm check:desktop           # cargo check
```

### Dependency / plugin notes (web + desktop)

- The `packages/divkit-subset/schema.json` schema declares `$schema: draft/2020-12`, so Ajv
  must be the `ajv/dist/2020.js` entrypoint; the default Ajv (draft-07) cannot compile it.
- Tauri plugins are deny-by-default. `tauri-plugin-haptics` and `tauri-plugin-camera` ship
  **no** `permissions/default.toml`, so capabilities must list each `allow-*` id explicitly
  (e.g. `haptics:allow-vibrate`). `clipboard-manager` grants nothing by default.
- `tauri-plugin-barcode-scanner` and `tauri-plugin-camera` are `#![cfg(mobile)]`; the desktop
  build declares them under `[target.'cfg(mobile)'.dependencies]` only.
- `tauri-plugin-camera` v2 does **not** exist on crates.io — the only published crate is a
  third-party `0.1.x` that is Android-only. Do not attempt a v2 bump.
- Nitro/Nitro-pack outputs (`.output`, `.nitro`), `apps/desktop/dist`, and
  `apps/desktop/src-tauri/{target,gen}` are gitignored.

## Testing conventions

- **Text-only APK debugging.** Use `tools/android_ui_debug` and
  `docs/testing/android-apk-debugging.md`. The harness drives the real APK through
  `adb` + `uiautomator dump`, parses the XML tree, taps bounds centers, polls async
  results, and writes UI/logcat artifacts on failure. Do not rely on screenshots.
  `scenarios/libsignal.json` and `scenarios/embedding.json` are the device regression
  flows; `python3 -m tools.android_ui_debug run <scenario> --apk <apk>` exits non-zero
  on failure.
- **Embedding tool naming.** The user-facing capability is `tools://embedding`
  (`EmbeddingTool`, schema `toolId = "embedding"`). The shared contract still names the
  canonical id `vectorize`; `VectorizeTool` is a deprecated subclass that keeps that
  handler and its aliases (`vector`, `embed`, `embed-text`, `embed-image`) resolving.
  Both dispatch to the same `executeEmbedding` implementation. The image path returns a
  1000-d MobileNetV4 `class_logits` descriptor, not a metric-learning embedding; do not
  document it as 512-d or as semantic image search. MediaPipe `tasks-text` 0.10.14 ships
  no x86_64 text JNI, so `TextVectorizer` latches the load failure and falls back to
  `HashedTextEmbedding` (512-d word/trigram hashing). The output `model` names the
  fallback; never hard-code `MediaPipe USE-Lite` in a card or doc.

- Tests live in `apps/android/src/test/java/com/example/ExampleUnitTest.kt` (plain JVM) and
  `ExampleRobolectricTest.kt` (Robolectric, for Android APIs).
- Robolectric provides an `Application` context; `engine.db.currentAndroidContext` is set by
  `ApexPosApplication`. Use `androidx.test.core.app.ApplicationProvider` and
  `org.robolectric.Shadows.shadowOf(application)` for permission grants/denials.
- `ShadowBluetoothAdapter` defaults to **disabled** — call `setEnabled(true)` when testing
  Bluetooth scan paths.

### Verifying every platform from Linux

The Android and desktop suites run natively; iOS needs a toolchain that is not preinstalled.

```bash
# Android (needs JDK 21 + the SDK)
cd apps/android && ./gradlew :testDebugUnitTest :assembleDebug
# iOS: install Swift 6.1.2 for Linux, then `swift build` / `swift test` in apps/ios
# Desktop: rustup + Tauri Linux deps (libwebkit2gtk-4.1-dev, libgtk-3-dev, libsoup-3.0-dev)
cargo test --manifest-path apps/desktop/src-tauri/Cargo.toml --lib
```

A fresh sandbox may ship without a JDK or the Android SDK. `apps/android/local.properties`
expects `sdk.dir=$HOME/.apex-toolchain/android-sdk`, so restore both there:

```bash
sudo apt-get update && sudo apt-get install -y --no-install-recommends openjdk-21-jdk-headless
# then unpack commandlinetools into $HOME/.apex-toolchain/android-sdk/cmdline-tools/latest
yes | sdkmanager --sdk_root=$HOME/.apex-toolchain/android-sdk --licenses
sdkmanager --sdk_root=$HOME/.apex-toolchain/android-sdk \
  "platforms;android-36" "build-tools;36.0.0" "platform-tools"
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ANDROID_HOME=$HOME/.apex-toolchain/android-sdk
```

- The only expected compiler warnings are Compose `Icons.Filled.*` deprecation notices
  ("Use the AutoMirrored version"). Anything else is new and should be fixed.
- Stateful tools must be registered as one instance. `NativeToolsSuite` owns `netTool` and
  `printTool` and `TerminalApp` registers those exact objects, so a hub started through
  `tools://net/serve` is the one `div-action://…tool=network.transfer@1` reports on.

- The desktop `storage.files@1` sandbox lives under the app-data dir
  (`app_data_dir()/workspaces/<tenantId>`), not the temp dir, so written files survive a
  restart. Tests call the operation helpers directly with a temp root, so they need no
  `AppHandle`.
- `storage.files@1` refuses to return a text body for non-UTF-8 or NUL-containing bytes on all
  three native platforms; callers must pass `encoding=base64`. Android's
  `ByteArray.toString(Charset)` substitutes U+FFFD instead of failing, so the Android tool
  decodes through a strict `CharsetDecoder`.
- iOS `ToolResult.success` only carries `AnyHashable`, so `files/list` returns `entries_json`
  (a string) where Android and desktop return a structured `entries` array. Keep `listing`,
  `count`, `total_bytes` and `truncated` identical across platforms so cards stay portable.

## Dependency notes

- Ktor 3.3.1 powers `tools://net`. Use the **CIO** engine (pure Kotlin); the OkHttp engine
  would pull OkHttp 5.x and conflict with the project's OkHttp 4.10.0 in `engine/sync/SyncEngine.kt`.
- ZXing `com.google.zxing:core:3.5.3` powers `tools://code-generate`. It is pure JVM with no
  Play Services dependency, so `CodeGeneratorEngine` renders bitmaps and encodes symbols in
  plain JVM unit tests (`Bitmap`/`Color` are Robolectric shadows there).

## APK packaging (ABI splits)

- `splits.abi.include(...)` in `app/build.gradle.kts` only constrains the **per-ABI split
  APKs**. It does not filter the **universal** APK, which packages every ABI found in
  dependencies. `curve25519-android` (transitive via `signal-protocol-android`) ships dead
  `armeabi`/`mips`/`x86` libraries, so `ndk.abiFilters` is also set to keep them out of all
  outputs. Do not remove it or the universal APK balloons (~+26 MB).
- Release builds run R8 with `isMinifyEnabled`. Ktor's debugger detector references JVM-only
  `java.lang.management` types, so `proguard-rules.pro` carries matching `-dontwarn` rules;
  without them `minifyReleaseWithR8` fails with "Missing class" errors.
- `-repackageclasses` is enabled, so anything loaded **by name** must have a keep rule.
  `org.whispersystems.curve25519.**` is the important one: `Curve25519.constructClass` does
  `Class.forName("org.whispersystems.curve25519.NativeCurve25519Provider")`, so R8 sees no
  reference and strips the providers. The debug APK works and the release APK throws
  `NoClassDefFoundError` on the first `tools://libsignal` call. When adding a dependency that
  loads classes reflectively, add a keep rule and confirm the class is still in the minified
  dex (`unzip -p app-release.apk classes.dex | strings | grep <name>`).
- Keep rules pulled in for classes that are only *referenced* (not reflectively loaded) can
  break the build: `-keep class com.google.mediapipe.framework.Graph { *; }` dragged in
  `com.google.mediapipe.proto.GraphTemplateProto$CalculatorGraphTemplate`, which is not on the
  classpath, and `minifyReleaseWithR8` failed. MediaPipe/TFLite/CameraX already ship consumer
  ProGuard rules, so they need no project-side keeps.
- **A `-keep` rule does not preserve constructors.** This caused the long-lived "release APK
  crashes instantly on open" bug. Library rules such as Room's
  `-keep class * extends androidx.room.RoomDatabase` keep the *class* but let R8 drop the
  implicit no-arg `<init>`; the class is then instantiated by reflection with
  `getDeclaredConstructor(new Class[0])` and dies with `NoSuchMethodException: <init> []`.
  The startup chain was `WorkManagerInitializer` → `WorkManager.initialize` →
  `WorkDatabase_Impl`. The same shape broke ML Kit's component registrars (silently disabling
  OCR/barcode/text recognition) and `androidx.startup` initializers. Rules that are easy to
  get wrong:

  ```proguard
  -keep class * extends androidx.room.RoomDatabase { *; }
  -keepclassmembers class * extends androidx.room.RoomDatabase { <init>(...); }
  ```

  Always use `{ *; }` or an explicit `<init>(...);` member list. Plain `-keep class X` keeps
  only the class, not members.
- **How to debug a release-only crash properly.** Static reading of `mapping.txt` is not
  enough — R8 vertical class merging makes obfuscated frames ambiguous, and a name that looks
  present can belong to a merged sibling. Reproduce instead: run an x86_64 emulator, install
  the release APK, and read `adb logcat -b crash`. To confirm a specific class kept its
  constructor, parse the APK's dex directly:

  ```bash
  python3 -c "
  import zipfile, logging; logging.disable(logging.CRITICAL)
  from androguard.core.dex import DEX
  z = zipfile.ZipFile('app-release.apk')
  for n in z.namelist():
      if n.endswith('.dex'):
          for c in DEX(z.read(n)).get_classes():
              if c.get_name() == 'Landroidx/work/impl/WorkDatabase_Impl;':
                  print([m.get_descriptor() for m in c.get_methods() if m.get_name() == '<init>'])
  "
  ```

  An empty list means the constructor was stripped. Absence of a `<init>` in
  `mapping.txt` is the same signal. There is no `/dev/kvm` in this environment, so the
  emulator must run with `-accel off` (`ANDROID_I_WANT_MY_TCG=yes`); the android-30 image's
  `system_server` crashes, android-28 boots.

- Verifying native-tool demos headlessly: swipe to scroll (tapping buttons never scrolls), then
  `uiautomator dump`. Async tools such as ML Kit OCR finish tens of seconds after the tap that
  starts them, so an immediate dump still shows the seed variable values - re-dump before
  concluding a tool failed. `PipelineManager: OCR process succeeded` in logcat is the real
  signal that recognition ran.

## Demo showcase contract

- `WorkspaceProvisioner.buildDefaultDivKitCardJson` is the built-in showcase and must exercise
  the **full** tool inventory. `ExampleUnitTest.demoCard_surfacesEveryRegisteredNativeTool`
  asserts every `NativeToolsSuite.registeredSchemas` toolId appears as a `tools://<id>/` trigger.
  Adding a tool to the registry without a demo block fails that test.
- `resolveJsonExpressions` coerces only short numeric literals (`isSafeNumericLiteral`).
  A 60-digit Signal safety number is an identifier: parsing it as a `Double` rounds it, so
  `verify_identity` reports `MISMATCH` for the number the card just displayed.
  `DemoCardRenderTest.libsignalVerifyPayloadKeepsLongSafetyNumberAsText` pins this.

## Camera and hardware capabilities

- Tools cannot own hardware. `CameraTool` and `NativeToolsSuite.executeScan` run without an
  Activity, lifecycle, or surface, so frame acquisition is delegated through `captureSink` /
  `liveScanSink`. `MainActivity` installs both, backed by `com.example.camera.CameraHost`, and
  `CameraHostOverlay()` renders the real CameraX preview at the root of the composition.
  Both sinks are null in unit tests; tests that need a frame install a stand-in.
- Never synthesise a hardware result. `CameraTool.executeCapture` previously returned a
  solid-colour bitmap, which looked like a successful capture and shipped a blank photo. It now
  returns `UNSUPPORTED` with no host and `FAILED` when the user cancels. `handleHardwareCapability`
  likewise no longer invents scanner hits, NFC tokens, or cash-drawer pulses.
- `InternalToolResult.success` is derived from `FAILURE_STATUSES` (a denylist), not an allowlist,
  because tool statuses are domain-specific (`ENCRYPTED`, `ALREADY_PAIRED`, `CANCELLED` for a
  successful `haptics/stop`). Add new failure statuses there rather than enumerating successes.
- Generated 1D symbologies need a quiet zone: `CodeGeneratorEngine.effectiveMargin` raises the
  ZXing margin to at least 10 modules for linear formats. At the old default of 4, generated
  EAN-13 images did not decode back. `codeGenerateTool_barcodeIsScannableBackByTheDecoder`
  guards this with a real encode/decode round trip.
- Do not publish the raw module matrix to DivKit. A 512px QR matrix serialises to ~262k
  characters and bloats every state push; only `user.code_matrix_rows` is bound.

## No fabricated ML, auth, or fallback results

- `mobilenetv4_small_int8.tflite` is a real **ImageNet-1k** classifier (UINT8 `1x224x224x3` in,
  UINT8 `1x1000` out), not a POS SKU model. `ImageClassifier` runs the graph and maps outputs to
  `assets/imagenet_labels.txt` (1000 lines, index-aligned). The card must not claim SKU detection.
  When no image is supplied the tool opens the real camera via `CameraTool.captureSink`; with no
  sink and no image it returns `UNSUPPORTED` rather than a canned prediction.
- The MobileNetV4 output tensor holds **logits**, not probabilities: quantization is
  `(scale=0.0807, zero_point=213)`, giving a dequantized range of about -17..+6. Run a softmax
  before reporting a confidence, or the card shows negative scores and values above 1. Verified
  end-to-end with a real image (Samoyed at 92%): the `imagenet_labels.txt` order is TF/ImageNet
  (index 0 `tench`), matching this graph.
- `ImageVectorizer` had a latent crash: it declared a `FloatArray(1024)` output while the graph
  emits 1000 `UINT8` scores, so `Interpreter.run` threw on every call. Both the output type and
  the dimension must match the graph; read `getOutputTensor(0).quantizationParams()` for
  dequantization instead of assuming float logits.
- U-2-Net is **not** bundled (smallest public weights are ~176MB ONNX, and there is no ONNX
  runtime here). `executeU2Net` returns `UNSUPPORTED` with a reason and no `mask_uri`. Point users
  at `image-manipulation/remove_background`, which is a real implementation.
- `auth.biometrics@1` must be able to refuse. It previously authorised everyone via
  `pin == null || pin == "1234" || pin.isNotBlank()`, so every refund and void was "supervisor
  approved". Verification now goes through `BiometricsTool.sink`, installed by `MainActivity` and
  backed by `com.example.auth.CredentialGate` (OS `createConfirmDeviceCredentialIntent`). With no
  sink or no lock screen it fails closed. `handleHardwareCapability("biometrics")` routes through
  the same tool instead of asserting success.
- `executeGenericFallback` returns `UNSUPPORTED`: an unknown tool id previously reported `SUCCESS`,
  which made typos and unimplemented capabilities look like they had run. Same for
  `vision.embed_image@1`, which returned `SecureRandom` floats unrelated to the image.
- JVM unit tests cannot load `tflite_jni`, so `ImageClassifier.classify` is exercised only on
  device. The pure scoring path is `ImageClassifier.rank(scores, scale, zeroPoint, ...)` and is
  unit-tested directly. Inference failures are caught as `Throwable`, because
  `UnsatisfiedLinkError` is an `Error` and would otherwise crash the terminal.
- Demo cards must describe what the tool really does. Tests previously asserted the fabrications
  (`nativeTools_u2NetProducesAlphaMatte`, `biometricsTool_authorizesSupervisorRole`,
  `nativeTools_mobileNetV4DetectsSkuInLowLatency`); they now assert `UNSUPPORTED`/denial and real
  ranking instead. `DEMO_CARD_REVISION` is not the refresh trigger - `needsDemoCardRefresh` compares
  card content - so edited cards re-seed on the next launch.
