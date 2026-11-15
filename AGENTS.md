# AGENTS.md

Repository-specific knowledge for the Apex Client DivKit SDUI POS app.

## Build & test

- Only `:app` is registered in `settings.gradle.kts`; its `projectDir` points at
  `apps/android`. All Android work targets that directory.
- The former KMP `shared/` module has been **removed**. Cross-platform contract identity is
  now enforced by shared fixtures + mirrored implementations under `packages/contract/`
  (Android tests, iOS `Sources/Contract`, Web `src/contract`), not by shared Kotlin.
- Toolchain: Gradle 9.3.1, AGP 9.1.1, Kotlin 2.2.10, compileSdk/targetSdk 36, minSdk 24, Java 11.
- `./gradlew` runs are slow (30s–2min). Prefer running them in the background and
  polling a log file; foreground commands are capped below the idle timeout.

```bash
./gradlew :app:compileDebugKotlin          # fast compile check
./gradlew :app:testDebugUnitTest           # unit + Robolectric tests
./gradlew :app:assembleDebug               # produces APKs (universal + per-ABI)
```

### Environment

`JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64`, `ANDROID_HOME=/home/openhands/Android/Sdk`.
The sandbox can be reset between sessions, wiping the JDK and Android SDK. To restore:

```bash
sudo apt-get install -y openjdk-21-jdk-headless unzip curl
# cmdline-tools 11076708 → /home/openhands/Android/Sdk/cmdline-tools/latest
sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0"
```

`assembleRelease` needs `STORE_PASSWORD`/`KEY_PASSWORD` (see `build-apks.sh`; the default
keystore `my-upload-key.jks` uses `storepass123`). R8 itself runs fine without them —
only `packageRelease` fails when the passwords are unset.

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
3. `com/example/ui/NotificationTray.kt` — Compose UI. `NotificationTrayOverlay` builds a
   `NotificationTrayActions` bundle and renders `NotificationTrayPanel`, a **dumb,
   state-driven** composable driven entirely by injected callbacks (so tests render it without
   a ViewModel). Group glyphs use `engine.icons.nativeIconVector(reference)`.
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

Adding a notification source = feed items through `NotificationCenter.add` into
`setNotifications`, or add a seed in `NotificationSeed`. New notification kinds add a
`NotificationType` whose `icon` names an existing catalog icon. Reference:
`docs/tools/notifications-tray.md`.

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
  without them `:app:minifyReleaseWithR8` fails with "Missing class" errors.

## Demo showcase contract

- `WorkspaceProvisioner.buildDefaultDivKitCardJson` is the built-in showcase and must exercise
  the **full** tool inventory. `ExampleUnitTest.demoCard_surfacesEveryRegisteredNativeTool`
  asserts every `NativeToolsSuite.registeredSchemas` toolId appears as a `tools://<id>/` trigger.
  Adding a tool to the registry without a demo block fails that test.
