# ApexClient Terminal — DivKit SDUI POS Multi-Tenancy KMP App Engine

A Kotlin Multiplatform (KMP) Server-Driven UI (SDUI) application engine that uses **DivKit** for dynamic, on-the-fly component rendering across **Android** and **iOS**.

---

## 🏛️ 3-Tier Architecture: Desktop Client-Server + App Engine Owner Gateway + Hybrid Mobile SDUI Frontend

1. **Desktop App (Client-Server POS Backend)**:
   - The Desktop application is the **Client-Server POS & Workspace Host** running on the local store Wi-Fi LAN (e.g., `http://192.168.1.42:8080`) or exposed via a Public Tunnel (Cloudflare / FRP / HuggingFace Space).
   - Scanning the **Desktop Pairing QR Code** from the mobile app links the mobile terminal to the Desktop Client-Server's LAN/Tunnel endpoints and tenant identity.
2. **App Engine Owner Gateway Server (FCM Provisioning & Signed SDUI Schema Vault)**:
   - The **App Engine Owner** operates the central **Gateway Server** (`Push Gateway Base`, e.g., `https://kipeles-apexkit.hf.space/tenant/apexapp-push`).
   - **FCM Push & Webhook Routing**: The App Engine Owner provisions Firebase Cloud Messaging (`FCM Push Token` + origin-scoped `publicClientId` `pk_live_...`) so the Desktop Client-Server app can dispatch real-time kitchen/POS alerts to paired mobile terminals.
   - **Gateway SDUI Schema Storage & Ed25519 Security**: For verification and zero-trust security, the tenant's DivKit SDUI schemas are stored and cryptographically signed (`CanonicalJson` + `Ed25519`) on the App Engine Owner's Gateway Server. The mobile app verifies the Gateway envelope via `PayloadVerifier` before any component is allowed to reach `DivView`.
3. **Hybrid Tenant Mobile App (Self-Contained SDUI Frontend)**:
   - The mobile app is a lightweight, multi-tenant **hybrid frontend** for the Desktop Client-Server app.
   - **Schema-Driven Initiation, SQLite Caching & Version Rollback**: A tenant is initiated when its Gateway-verified DivKit JSON schema is received. The verified schema is cached locally in `workspace_<tenant_id>.db` (`sdui_schema_versions`) until a newer schema version arrives from the Gateway Server—enabling instant **version rollback** (`v1.0`, `v1.1`, `v1.2`, ...) at any time.
   - **Sandboxed Native Hardware Bridge**: By default, all 8 native bridged tools (Haptic Vibration, Hardware Beeper Audio, Unified Optical Scanner (Barcode & QR), Supervisor Biometric Auth, Clipboard Access, Background Alerts & Push, Offline Page Caching, Realtime Engine WS/SSE) are turned **OFF** for security and can be selectively enabled per workspace in **Workspace Settings**.

---

## 🚀 Home Launcher, Engine Settings & Dedicated Workspace Flow

1. **Multi-Tenant Hub Launcher (2-Column Grid Default + 1-Column List Toggle + Live Search)**:
   - On launch, the app presents a **two-column grid launcher** (`TenantLauncherScreen`, toggleable to a **1-column list view** via the top-bar layout icon) and a live search bar (`"Search terminals, registers, URLs..."`).
   - Each **tenant card is a square**: the tenant icon occupies the **top third** (horizontally centered and flush with the top edge) with a soft accent wash behind it, while the title, status and schema badge sit at the bottom.
   - A single built-in demo tenant ships by default — **Apex POS Terminal** (`acme_pos` • `⚡ DivKit SDUI Showcase` • all 8 native tools active • `Schema v1.1 (2 cached)`) — which showcases the DivKit SDUI surface (live charts, `json_list`, hardware testers) and the full unopinionated tool suite. Additional tenants are added at runtime through **Connect New Workspace** rather than shipped as static demos.
   - **Tenant Context Menu (long-press)**: There is no 3-dot overflow button; **long-pressing a tenant card** opens the context menu with **Workspace Settings**, **Open Terminal**, and **Disconnect Workspace**.
   - **Most-Used Ordering**: Tenants are sorted by usage — most recently opened first, then most visited — so dormant tenants sink to the bottom.
   - **Resume on Relaunch**: The last-opened workspace is remembered and **re-opened automatically on the next launch**; returning to the Hub (or exiting from the Hub) shows the launcher instead.
   - **Back Button Exits the App**: Inside a tenant, the device Back button first dismisses any open popup (overlay sheets, the `⋮` menu, the expanded AppBar); once nothing is left to pop it **exits the app rather than the tenant**, so the workspace feels like a real launcher app.
   - **Connect New Workspace (`+` FAB & Top-Bar QR Button)**: Opens the **Connect New Workspace** bottom sheet featuring **Scan Desktop Pairing QR Code** (pairs with the Desktop Client-Server app and verifies the Gateway SDUI schema), **Fill Demo Terminal Preset**, `App / POS Terminal Name *`, `Local Wi-Fi LAN URL (Desktop Client-Server)`, `Public Tunnel / Cloudflare / FRP URL`, `Custom Background Pull Endpoint`, and **Save Workspace & Connect**.
2. **Engine / App Settings with Theme Switching (Defaults to System)**:
   - Clicking the top-right **Settings (`⚙️`)** gear icon opens **App Settings** (`AppSettingsBottomSheet`):
     - **Engine Theme Switcher**: Defaults to **`System`** (`isSystemInDarkTheme()`), with instant one-tap switching between **`System`**, **`Light`**, and **`Dark`** modes across both the host engine and all DivKit workspaces.
     - **Push Gateway Base (FCM & SDUI Schema Vault)**: Configurable App Engine Owner gateway endpoint (`https://kipeles-apexkit.hf.space/tenant/apexapp-push`).
     - **Hardware Device ID & FCM Push Token**: With one-tap **Copy** buttons (`ClipboardManager`), **Re-sync FCM**, and **Reconnect WS** actions.
3. **Full-Screen Self-Contained Tenant Workspace & Standout Pill**:
   - Clicking any tenant card opens a **dedicated full-screen workspace** for that tenant with all screen space available to its dynamically rendered DivKit UI (`EngineScreen` -> `DivView`), plus interactive **Public Client ID (`pk_live_...`) & cURL Webhook Dispatch**, **Active Route Path & URL Queries (Preserved on Refresh)**, and **Hardware Audio & Haptics Tester**.
   - **Standout Pill (Top-Center)**: A high-contrast, zero-shadow pill (`workspace_standout_pill`) sits at the top-center of the workspace, visible across both Light and Dark themes without shadowing any workspace content.
   - **Persistent AppBar on Pill Click**: Clicking the standout pill opens the top AppBar overlay (`< Hub` back button, QR Scanner, Refresh `↻`, Notification Bell, and `⋮` menu with `Refresh Page`, `Workspace Settings`, `Clear Offline Cache`, `Schema Cache & Rollback`, `On-the-Fly SDUI Builder`, `Workspace SQLite & Sync`, `Security & KMP Inspector`, and `Return to Hub`), which **remains open unless the active workspace screen is clicked or the exit button is clicked**.

---

## 📦 Project Structure

```text
shared/
  src/commonMain/kotlin/engine/
    config/EngineConfig.kt                  # Signed config.json model & HTTPS/tenantId invariants
    crypto/PayloadVerifier.kt               # Ed25519 envelope verification, 5m iat window, jti LRU
    crypto/CanonicalJson.kt                 # Deterministic byte-for-byte JSON canonicalisation
    crypto/Ed25519.kt                       # expect object for platform Ed25519 verification
    db/WorkspaceManager.kt                  # Physical per-tenant SQLite file & directory isolation
    db/EngineRegistry.kt                    # Whitelisted SQL ops + PRAGMA table_info column guards
    db/OpResult.kt                          # Typed DB results mapped to user.* DivKit variables
    db/WorkspaceDatabase.sq                 # SQLDelight schema (sdui_schema_versions, op_log, pending_sync)
    db/Migrations.sq                        # Tenant schema migrations
    router/EngineRouter.kt                  # Intercepts local-db://, hardware://, workspace://clear_all
    state/EngineStateBridge.kt              # StateFlow snapshot for user.* reactive variables
    tools/NativeToolsSuite.kt               # Unopinionated tool schemas + unified `tools://` dispatch
    tools/TerminalApp.kt                    # Composition root: capability + shorthand-alias registration
    tools/ToolRegistry.kt                   # Versioned capability registry & ProvenanceChecker gate
    tools/DivActionRouter.kt                # div-action://terminal.tool.invoke dispatcher
    tools/impl/HapticsTool.kt               # hardware.haptics@1 (semantic patterns + W3C waveform arrays)
    tools/impl/BeeperTool.kt                # hardware.beeper@1 (PCM tone synthesis, profiles, sequences)
    tools/impl/TerminalToolsImpl.kt         # Shared tool implementations (crypto, clipboard, cache, …)
    sync/SyncEngine.kt                      # Idempotent batch outbox sync with backoff & dead-letter
    sync/BackgroundSyncScheduler.kt         # expect class for WorkManager (Android) / BGTaskScheduler (iOS)
    ui/EngineScreen.kt                      # Shared Compose entry point wrapping DivKit & DivView
    ui/DivChartSpec.kt                      # KMP custom DivKit chart AST parser (sdui_chart, bar, line, area, donut, pie)
  src/androidMain/kotlin/engine/
    crypto/Ed25519.android.kt               # Android JCA Ed25519 + X.509 SPKI DER key parser
    db/WorkspaceDriverFactory.android.kt    # AndroidSqliteDriver per workspace_<id>.db
    sync/BackgroundSyncScheduler.android.kt # WorkManager PeriodicWorkRequest + OneTimeWorkRequest
    ui/EngineScreen.android.kt              # rememberDivKitDependencies() via DivKitEnvironment
  src/iosMain/kotlin/engine/
    crypto/Ed25519.ios.kt                   # iOS Security.framework SecKeyVerifySignature (EdDSA)
    db/WorkspaceDriverFactory.ios.kt        # NativeSqliteDriver per workspace_<id>.db
    sync/BackgroundSyncScheduler.ios.kt     # BGTaskScheduler BGAppRefreshTask + registerIosSyncHandler
    ui/EngineScreen.ios.kt                  # DivKitKMP.createDependencies()

iosApp/
  Info.plist                                # BGTaskSchedulerPermittedIdentifiers (com.example.engine.sync)
  EngineCustomBlockFactory.swift            # DivCustomBlockFactory for json_list (UITableView) & sdui_chart (Swift Charts + DGCharts)
  AppDelegate.swift                         # Registers KMP sync handler on launch
  IosHapticsTool.swift                      # hardware.haptics@1 — UIFeedbackGenerator + waveform timings
  IosBeeperTool.swift                       # hardware.beeper@1 — AVAudioEngine PCM synthesis, profiles, sequences
  IosScannerTool.swift                      # vision.scan_code@1 — AVFoundation optical scanner
  IosCameraTool.swift                       # vision.capture_photo@1 — camera capture & torch
  IosSignalTool.swift                       # crypto.signal@1 — deterministic session identity & safety number
  IosImageManipulationTool.swift            # vision.image_manipulation@1 — on-device image processing

app/
  src/main/java/
    engine/...                              # Android runtime compilation of the KMP engine
    engine/tools/NativeToolsSuite.kt        # Android tool schemas & unified `tools://` dispatch
    engine/tools/TerminalApp.kt             # Android capability registry (mirrors shared TerminalApp)
    engine/tools/impl/HapticsTool.kt        # hardware.haptics@1 — Vibrator/VibratorManager
    engine/tools/impl/BeeperTool.kt         # hardware.beeper@1 — AudioToneSynthesizer PCM tones
    engine/tools/impl/NetTool.kt            # network.transfer@1 — Ktor HTTP client + LAN transfer hub
    engine/tools/impl/PrintTool.kt          # device.printer@1 — Bluetooth printer scan/connect/ESC-POS print
    engine/tools/audio/AudioToneSynthesizer.kt  # Shared PCM tone/waveform/sequence generator
    engine/tools/image/ImageManipulationEngine.kt # On-device image ops (<4MB OS-model footprint)
    com/yandex/divkit/multiplatform/
      DivKitMultiplatform.kt                # Compose DivKit SDUI renderer, json_list & sdui_chart custom blocks
      DivKitChartRenderer.kt                # Android Vico M3 + Compose Canvas interactive chart renderer
    com/example/
      MainActivity.kt                       # Root navigation, EngineThemeMode switching & Launcher TopBar
      enginehost/WorkspaceProvisioner.kt    # Connected tenant specs, tool permissions & seed data
      enginehost/SduiOnFlyBuilder.kt        # On-the-fly DivKit AST component & chart injector
      enginehost/SduiEngineViewModel.kt     # Schema cache, rollback, theme mode, settings & Ed25519 lab
      ui/TenantLauncherScreen.kt            # 2-column grid / 1-column list launcher, search & + FAB
      ui/EngineModalsAndSheets.kt           # App Settings, Connect Workspace & Workspace Settings sheets
      ui/PosTerminalTab.kt                  # Dedicated tenant workspace, standout pill, AppBar & rollback sheet
      ui/SduiBuilderTab.kt                  # Interactive DivKit component & chart builder, JSON editor & rollback
      ui/DatabaseAndSyncTab.kt              # SQLite PRAGMA, op_log & pending_sync inspector
      ui/SecurityAndKmpTab.kt               # Zero-trust threat simulator & KMP/Swift code browser
```

---

## Custom DivKit Chart Components (`sdui_chart`)

Tenants can declare interactive charts directly inside their signed DivKit JSON schema using `"type": "custom"` with `"custom_type": "sdui_chart"` (or `"bar_chart"`, `"line_chart"`, `"area_chart"`, `"donut_chart"`, `"pie_chart"`):

- **KMP Common (`shared/src/commonMain/kotlin/engine/ui/DivChartSpec.kt` + `io.github.koalaplot:koalaplot-core:0.6.3`)**:
  - `DivChartParser` parses `custom_props` and binds to either a live reactive `EngineStateBridge` JSON array variable (`"data_var": "user.orders_json"` or `"user.inventory_json"` with automatic SKU aggregation) or inline `"points"`.
- **Android (`DivKitChartRenderer.kt` + Vico `com.patrykandpatrick.vico:compose-m3:2.0.0` + Jetpack Compose Canvas)**:
  - Renders interactive Material 3 **Bar**, **Line**, **Area**, and **Donut/Pie** charts with live chart-type switcher pills, KPI chips (`TOTAL`, `PEAK`, selected point), tap-to-inspect data points (writes to `user.selected_chart_point`), and animated transitions.
- **iOS (`iosApp/EngineCustomBlockFactory.swift` — `EngineChartBlockFactory: DivCustomBlockFactory`)**:
  - Wired to Apple **Swift Charts** (`Charts.framework`: `BarMark`, `LineMark`, `AreaMark`, `SectorMark` via `UIHostingController`) on iOS 16+ and **DGCharts** (`~> 5.1.0`: `BarChartView`, `LineChartView`, `PieChartView`) fallback on iOS 15, subscribed to `DivVariableStorage.publisher(for: dataVar)`.

---

## Native Tools & AI Vision Suite (`tools://*`)

Tenants can invoke hardware bridges, optical scanning, on-device AI vision models, and cryptographic protocols directly through standard DivKit actions using the `tools://` scheme:

```json
{
  "log_id": "trigger_mobilenetv4",
  "url": "tools://MobileNetV4-small/classify",
  "payload": {
    "operation": "classify",
    "options": {
      "top_k": 3,
      "min_confidence": "0.40"
    },
    "output": {
      "prediction_label": "user.ai_prediction_label",
      "prediction_sku": "user.ai_prediction_sku",
      "confidence": "user.ai_confidence_score"
    }
  }
}
```

### Supported Tools & Generalized APIs

Every tool is deliberately **unopinionated**: it exposes a generic capability contract (a capability id, a set of operations, and free-form options) rather than any product-, vendor-, or SDK-specific logic. The same schema is registered on Android and iOS via the shared KMP `TerminalApp` composition root.

| Tool URI | Display Name | Operations | Customizable Options | Default Output Mappings |
|---|---|---|---|---|
| `tools://scan` | **Unified Optical Scanner (`vision.scan_code@1`)** | `scan`, `continuous_scan`, `simulate`, `parse` | `target_format` (`both`\|`barcode`\|`qr`\|`EAN_13`\|`UPC_A`\|`CODE_128`\|`QR_CODE`), `beep`, `haptics`, `camera_facing` | `user.scanned_code`, `user.scanned_format`, `user.scanned_type`, `user.scan_status`, `user.scan_timestamp` |
| `tools://haptics` | **Universal Haptics & Vibration Engine (`hardware.haptics@1`)** | `perform`, `vibrate`, `click`, `heavy`, `tick`, `success`, `warning`, `error`, `stop`, `status` | `pattern` (semantic name), `duration_ms` (10-1000ms), `amplitude` (1-255), `timings` (W3C `[wait, vibrate, …]` waveform array) | `user.haptics_status`, `user.haptics_pattern` |
| `tools://beeper` | **Universal Audio Tone Synthesizer (`hardware.beeper@1`)** | `tone`, `play`, `beep`, `chime`, `stop`, `status` | `frequency_hz`, `duration_ms`, `waveform` (`sine`\|`square`\|`triangle`\|`sawtooth`), `volume` (0-1), `profile` (semantic preset), `sequence` (`freq:ms,…`) | `user.beeper_status`, `user.beeper_frequency` |
| `tools://camera` | **Hardware Camera Bridge (`vision.capture_photo@1`)** | `capture`, `torch_toggle`, `get_status` | `facing` (`back`\|`front`), `resolution` (`1080p`) | `user.camera_capture_uri` |
| `tools://image-manipulation` | **Universal Image Manipulation & Background Removal (`vision.remove_background@1`)** | `process`, `remove_background`, `smooth`, `adjust_color`, `crop`, `resize`, `threshold` | `op`, `encoding` (`uri`) | `user.processed_image_uri`, `user.image_processing_status` |
| `tools://clipboard` | **System Clipboard Controller (`device.clipboard@1`)** | `copy`, `paste`, `clear` | `op` | `user.clipboard_content` |
| `tools://biometrics` | **OS Biometric Authentication (`auth.biometrics@1`)** | `authenticate` | `prompt` | `user.biometric_authenticated` |
| `tools://libsignal` | **Signal E2EE Cryptographic Transport (`secure.*@1`)** | `setup_identity`, `init_keys`, `create_session`, `encrypt`, `decrypt`, `status`, `verify_identity`, `reset_session` | `cipher`, `key_derivation`, `ratchet_steps` | `user.signal_status`, `user.signal_session_id`, `user.signal_ciphertext`, `user.signal_plaintext`, `user.signal_fingerprint` |
| `tools://MobileNetV4-small` | **MobileNetV4 Image Classifier (`vision.identify_product@1`)** | `classify`, `detect`, `predict` | `top_k`, `min_confidence` | `user.ai_prediction_label`, `user.ai_prediction_sku`, `user.ai_confidence` |
| `tools://u-2-Net.tflite` | **U-2-Net Salient Object Segmentation (`vision.remove_background@1`)** | `segment`, `infer`, `mask` | `tensor_input` (320x320x3), `saliency_cutoff` | `user.u2net_mask_uri`, `user.u2net_confidence` |
| `tools://push` | **Background Alerts & Push Notifications (`network.push@1`)** | `send_alert`, `notify`, `send` | `channel`, `priority` | `user.push_notification_id`, `user.push_status` |
| `tools://offline-cache` | **Offline Page & Schema Cache (`storage.offline_cache@1`)** | `cache`, `get_stats`, `clear` | `op` | `user.offline_cache_status`, `user.offline_cached_pages` |
| `tools://realtime-ws` | **Realtime WebSocket / SSE Engine (`network.realtime@1`)** | `connect`, `disconnect`, `send`, `subscribe` | `channel` | `user.realtime_connection_state`, `user.realtime_channel` |
| `tools://net` | **Network Transfer (Ktor) & LAN Hub (`network.transfer@1`)** | `request`, `get`, `post`, `download`, `upload`, `serve`, `stop`, `status`, `share`, `list`, `unshare` | `url`, `method`, `header_*`, `content_type`, `content_base64`\|`body`, `file_path`, `timeout_ms`, `port`, `host`, `label`, `name` | `user.net_status`, `user.net_http_status`, `user.net_bytes`, `user.net_hub_port` |
| `tools://print` | **Bluetooth Printer / POS Bridge (`device.printer@1`)** | `scan`, `pair`, `connect`, `disconnect`, `print`, `status` | `address`, `all`, `discover`, `text`\|`lines`\|`receipt`, `align`, `bold`, `width`, `feed_lines`, `cut` | `user.print_status`, `user.print_device_count`, `user.print_bytes` |

> **Unified scanner.** The former split tools `tools://scan-barcode` (1D) and `tools://scan-qr` (2D) are replaced by a single `tools://scan` tool that decodes both symbologies by default. `target_format` restricts recognition (`both` by default, or `barcode`/`qr`/an explicit format), and a non-matching code returns `status = "FORMAT_MISMATCH"`. The legacy URIs are still accepted as aliases for backward compatibility.
>
> **Unopinionated feedback APIs.** `tools://haptics` and `tools://beeper` carry no vendor or SDK coupling: the caller supplies generic parameters (patterns, frequencies, waveforms, volumes, timing arrays, sequences) and the platform chooses the physical implementation. There is no dedicated "POS tone" API — `PosTonePlayer`/`playPosTone` were removed and the haptic entry point is the platform-neutral `triggerHaptic`.
>
> **Generic networking (`tools://net`).** Built on Ktor (CIO engine), it covers both client calls (`request`/`get`/`post`/`download`/`upload`) and an embedded LAN transfer hub (`serve`/`stop`/`status`/`share`/`list`/`unshare`). `serve` starts a Ktor CIO server that hosts a discovery page at `/` and downloadable items at `/file/{id}`, so the same tool powers hubs and Xender-style device-to-device sharing.
>
> **Bluetooth printing (`tools://print`).** Scans bonded and discoverable Bluetooth thermal printers / POS peers (name heuristics: `printer`, `thermal`, `pos`, `rpt`, `goojprt`, `xprinter`, …), bonds (`pair`), opens an RFCOMM SPP socket (`connect`), and writes ESC/POS receipt bytes (`print` with `align`, `bold`, `width`, `feed_lines`, `cut`). On Android 12+ it requires `BLUETOOTH_CONNECT`/`BLUETOOTH_SCAN`; on older releases it falls back to `BLUETOOTH`/`BLUETOOTH_ADMIN`.

---

## 📳 Terminal Tool Architecture & `div-action://` Invocation Contract

The terminal engine implements the zero-trust **Terminal Tool Architecture**:

1. **Globally Versioned Capabilities**:
   - Capabilities are declared and registered with strict version tags (e.g. `hardware.haptics@1`, `hardware.beeper@1`, `vision.scan_code@1`, `secure.send@1`).
   - Shorthand aliases (e.g. `device.haptics@1`, `haptics`, `beeper`, `scan`, `tools://haptics`) map to the primary capability handler; legacy ids such as `scan-barcode`/`scan-qr` resolve to the unified `scan` tool.
2. **Action Invocations (`div-action://terminal.tool.invoke`)**:
   - `div-action://terminal.tool.invoke?tool=hardware.haptics@1&pattern=heavy&result_var=haptics_status`
   - Non-blocking, fire-and-forget execution dispatched through `DivActionRouter`.
3. **Four-Way Typed Result Resolution (`ToolResult`)**:
   - `ToolResult.Success(data)`: Operation succeeded; bound into `${result_var}` and `${result_var}_<field>`.
   - `ToolResult.Unsupported(capability, reason)`: Hardware or feature absent on this platform; writes `"unsupported"`.
   - `ToolResult.PermissionDenied(capability, permission)`: Required permission denied; writes `"denied"`.
   - `ToolResult.Failed(capability, message)`: Unexpected runtime failure; writes `"failed"`.
4. **Provenance & Access Control**:
   - `Provenance.Agency`: Full access to administrative, internal, and external capabilities.
   - `Provenance.Publisher`: Restricted strictly to the pre-approved tenant capabilities in `ProvenanceChecker` (`hardware.haptics@1`, `hardware.beeper@1`, `vision.scan_code@1`, `vision.capture_photo@1`, `vision.identify_product@1`, `vision.embed_image@1`, `vision.remove_background@1`, `secure.send@1`, `secure.status@1`, `secure.verify_identity@1`, `auth.biometrics@1`, `device.clipboard@1`, `network.push@1`, `storage.offline_cache@1`, `network.realtime@1`, `network.transfer@1`, `device.printer@1`).
5. **Unopinionated Platform Haptics & Tones**:
   - Haptics uses Android `Vibrator` / `VibratorManager` with `VibrationEffect.createPredefined` (Android 10+), dual-pulse waveforms (Android 8+), and legacy millisecond one-shots; iOS uses `UIFeedbackGenerator` (`UIImpactFeedbackGenerator`/`UINotificationFeedbackGenerator`).
   - The beeper synthesizes PCM tones (sine/square/triangle/sawtooth) with `AudioToneSynthesizer` on Android and `AVAudioEngine` on iOS. Both degrade gracefully when hardware or permissions are unavailable.

## Multi-Architecture Packaging & R8 Minification

The build produces **3 standalone architecture APKs** + **1 universal APK** + **1 AAB bundle**:

- **`arm64-v8a`**: 64-bit modern Android devices and flagship POS hardware.
- **`armeabi-v7a`**: 32-bit legacy POS terminals and embedded handhelds.
- **`x86_64`**: Modern x86 kiosks, emulators, and ChromeOS devices.
- **`universal`**: Fat APK bundling all 3 ABIs for single-file deployment.

### R8 Minification & High-Speed Tuning

Configured via `app/build.gradle.kts` and `app/proguard-rules.pro`:
- `isMinifyEnabled = true` and `isShrinkResources = true` in `release`.
- Preserves all KotlinX Serialization, SQLDelight/SQLite, DivKit, and crypto reflection.
- `-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*` prevents excessive obfuscation loops, ensuring fast JIT/AOT warmup and deterministic performance.

### 🛠️ One-Click Zero-Config Build Script (`build-apks.sh`)

Run `./build-apks.sh` to automatically detect and configure the environment (JDK 17/21, Android SDK, Licenses, Keystores, and Gradle wrapper) and build all multi-architecture APKs without manual OS setup:

```bash
# Build debug APK suite (arm64-v8a, armeabi-v7a, x86_64, universal)
./build-apks.sh

# Build minified production release APKs with R8 and resource shrinking
./build-apks.sh --release

# Build both debug and release suites into a custom directory
./build-apks.sh --all --output-dir ./my-apks
```

Artifacts are automatically harvested to `./dist-apks/` with a verified `checksums.sha256` manifest.


