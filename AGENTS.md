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

The single built-in demo tenant (`WorkspaceProvisioner.defaultWorkspaces`) is the live
showcase of the SDUI surface. When a tool gains a user-facing operation, surface it there too:

- `SduiOnFlyBuilder.build<Name>ToolBlock(spec)` — a DivKit container with live `user.*` readouts
  and `buildNativeToolTriggerBlock(...)` trigger buttons.
- `WorkspaceProvisioner.buildDefaultDivKitCardJson(spec)` — declare every `user.*` variable the
  block reads, and `add(...)` the block into the card's item list.
- `SduiEngineViewModel` — seed the same variables in the `EngineStateBridge.pushBatch(...)`
  reset so the card renders populated before the first tool call.
- `SduiBuilderTab` — expose an inject button for the block in the on-the-fly builder.
- `DivKitMultiplatform.resolveIcon(name)` — map any new `icon` names to a Material icon; the
  demo also showcases the `icons://native/<name>` scheme via `buildNativeIconSchemeBlock`.

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

## Web dashboard (`apps/web`) & Desktop POS (`apps/desktop`)

Two independent pnpm-workspace apps consume the same `packages/pos-ui` and `packages/contract`
modules. Neither renders the other's UI. `apps/android` and `apps/ios` are untouched.

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

Commands (root `package.json`):

```bash
pnpm install                 # pnpm workspace (apps/web, apps/desktop, packages/pos-ui)
pnpm test:web                # vitest: contract fixtures, server libs, shared pos-ui
pnpm build:web               # vite build + nitro build
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

- Tests live in `apps/android/src/test/java/com/example/ExampleUnitTest.kt` (plain JVM) and
  `ExampleRobolectricTest.kt` (Robolectric, for Android APIs).
- Robolectric provides an `Application` context; `engine.db.currentAndroidContext` is set by
  `ApexPosApplication`. Use `androidx.test.core.app.ApplicationProvider` and
  `org.robolectric.Shadows.shadowOf(application)` for permission grants/denials.
- `ShadowBluetoothAdapter` defaults to **disabled** — call `setEnabled(true)` when testing
  Bluetooth scan paths.

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

## Demo showcase contract

- `WorkspaceProvisioner.buildDefaultDivKitCardJson` is the built-in showcase and must exercise
  the **full** tool inventory. `ExampleUnitTest.demoCard_surfacesEveryRegisteredNativeTool`
  asserts every `NativeToolsSuite.registeredSchemas` toolId appears as a `tools://<id>/` trigger.
  Adding a tool to the registry without a demo block fails that test.
