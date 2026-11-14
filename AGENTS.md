# AGENTS.md

Repository-specific knowledge for the Apex Client DivKit SDUI POS app.

## Build & test

- Only `:app` is registered in `settings.gradle.kts`. `:shared` exists on disk but is
  **not** built — all work targets `app/`.
- Toolchain: Gradle 9.3.1, AGP 9.1.1, Kotlin 2.2.10, compileSdk/targetSdk 36, minSdk 24, Java 11.
- `./gradlew` runs are slow (30s–2min). Prefer running them in the background and
  polling a log file; foreground commands are capped below the idle timeout.

```bash
./gradlew :app:compileDebugKotlin          # fast compile check
./gradlew :app:testDebugUnitTest           # unit + Robolectric tests
./gradlew :app:assembleDebug               # produces APKs (universal + per-ABI)
```

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
5. `app/src/main/AndroidManifest.xml` — declare any new runtime permissions.
6. `README.md` — add a row to the `tools://*` table.
7. `docs/tools/<name>.md` — add a per-tool reference doc and a row in `docs/tools/README.md`.

`NativeToolsSuite.execute(...)` returns output mapped into `user.*` DivKit variables via
`defaultOutputMapping`; types are preserved (IntVal/DoubleVal/Bool/Str).

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
- `DivKitMultiplatform.resolveIcon(name)` — map any new `icon` names to a Material icon.

`local-db://op/<name>` is the SQLite data plane (insert/query/count/delete), dispatched by
`EngineRouter` and documented in `docs/tools/local-db.md`.

## Testing conventions

- Tests live in `app/src/test/java/com/example/ExampleUnitTest.kt` (plain JVM) and
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
