# DivKit SDUI POS — Multi-Tenancy KMP App Engine

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
   - **Sandboxed Native Hardware Bridge**: By default, all 8 native bridged tools (Haptic Vibration, Hardware Beeper Audio, Camera Barcode & QR Scanner, Supervisor Biometric Auth, Clipboard Access, Background Alerts & Push, Offline Page Caching, Realtime Engine WS/SSE) are turned **OFF** for security and can be selectively enabled per workspace in **Workspace Settings**.

---

## 🚀 Home Launcher, Engine Settings & Dedicated Workspace Flow

1. **Multi-Tenant Hub Launcher (2-Column Grid Default + 1-Column List Toggle + Live Search)**:
   - On launch, the app presents a **two-column grid launcher** (`TenantLauncherScreen`, toggleable to a **1-column list view** via the top-bar layout icon) and a live search bar (`"Search terminals, registers, URLs..."`):
     - **Apex POS Terminal Demo** (`acme_pos` • `⚡ Built-in Demo` • `🛠️ 6 tools active` • `Schema v1.1 (2 cached)`)
     - **Nova Electronics Express** (`nova_retail` • `🌍 Tunnel: apexkit-drive.hf.space` • `🔒 Tools off (default)`)
     - **Zenith Rx Clinic Kiosk** (`zen_pharmacy` • `🌍 Tunnel: inspower-kiosk.local` • `🔒 Tools off (default)`)
     - **ApexKit Administration** (`metro_coffee` • `🌍 Tunnel: kipeles-apexkit.hf.space` • `🔒 Tools off (default)`)
   - **Tenant Card 3-Dot Menu (`⋮`)**: Every tenant card includes a context menu for **Workspace Settings**, **Open Terminal**, and **Disconnect Workspace**.
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

app/
  src/main/java/
    engine/...                              # Android runtime compilation of the KMP engine
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

