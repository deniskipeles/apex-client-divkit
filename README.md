# DivKit SDUI POS — Multi-Tenancy KMP App Engine

A Kotlin Multiplatform (KMP) Server-Driven UI (SDUI) application engine that uses **DivKit** for dynamic, on-the-fly component rendering across **Android** and **iOS**. Designed for multi-tenant Point-of-Sale (POS) terminals and isolated workspaces, every tenant's UI is cryptographically verified and rendered dynamically from its signed schema while operating inside its own physically isolated SQLite database.

---

## 🚀 Home Launcher & Dedicated Workspace Flow

1. **2-Column Tenant Grid Launcher (Home Screen)**:
   - On launch, the app presents a **two-column grid launcher** (`TenantLauncherScreen`) listing all connected tenants:
     - **Acme Bistro & Grill** (`acme_pos` • `POS-TERM-01` • `HYBRID`)
     - **Nova Electronics Express** (`nova_retail` • `REG-04-WEST` • `ONLINE`)
     - **Zenith Rx Clinic Kiosk** (`zen_pharmacy` • `KIOSK-RX-02` • `OFFLINE`)
     - **Metro Roasters Cafe** (`metro_coffee` • `BAR-POS-03` • `HYBRID`)
   - Users can also connect and provision new tenants on the fly via the **+ New Tenant** button.
2. **Full-Screen Self-Contained Tenant Workspace & Standout Pill (On-Click)**:
   - Clicking any tenant card in the 2-column grid opens a **dedicated full-screen workspace** for that tenant with all screen space available to its dynamically rendered DivKit UI (`EngineScreen` -> `DivView`).
   - **Standout Pill (Top-Center)**: A high-contrast, zero-shadow pill (`workspace_standout_pill`) sits unobtrusively at the top-center of the workspace, visible across both Light and Dark system themes without shadowing any workspace content.
   - **Expandable `< Hub` AppBar**: Clicking the standout pill opens a top AppBar overlay containing:
     - **`< Hub` Exit Button**: Returns immediately to the 2-column Tenant Hub launcher.
     - **Tenant Identity & Tunnel Status**: Displays the active tenant name, mode (`HYBRID`, `ONLINE`, `OFFLINE`), and terminal code.
     - **Refresh (`↻`) Button**: Re-verifies the signed DivKit schema and refreshes the tenant's isolated SQLite queries.
     - **3-Dot Menu (`⋮`)**: Opens modal tools for the **On-the-Fly SDUI Schema Builder**, **Workspace SQLite & Sync Inspector**, and **Zero-Trust Security & KMP Lab**.
3. **System Theme Default (Light & Dark Mode)**:
   - Both the host engine (`MyApplicationTheme(darkTheme = isSystemInDarkTheme())`) and the dynamic DivKit SDUI renderer (`DivView`) automatically adapt to the device's active **System Theme** (Light or Dark).

---

## 📦 Architecture & Project Structure

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
    db/WorkspaceDatabase.sq                 # SQLDelight schema (op_log, pending_sync)
    db/Migrations.sq                        # Tenant schema migrations
    router/EngineRouter.kt                  # Intercepts local-db://, hardware://, workspace://clear_all
    state/EngineStateBridge.kt              # StateFlow snapshot for user.* reactive variables
    sync/SyncEngine.kt                      # Idempotent batch outbox sync with backoff & dead-letter
    sync/BackgroundSyncScheduler.kt         # expect class for WorkManager (Android) / BGTaskScheduler (iOS)
    ui/EngineScreen.kt                      # Shared Compose entry point wrapping DivKit & DivView
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
  EngineCustomBlockFactory.swift            # Native UITableView DivCustomBlockFactory for json_list
  AppDelegate.swift                         # Registers KMP sync handler on launch

app/
  src/main/java/
    engine/...                              # Android runtime compilation of the KMP engine
    com/yandex/divkit/multiplatform/...     # Compose DivKit SDUI renderer & json_list custom block
    com/example/
      MainActivity.kt                       # Root navigation (2-column Launcher <-> Dedicated Workspace)
      enginehost/WorkspaceProvisioner.kt    # Connected tenant specs, schemas & seed data
      enginehost/SduiOnFlyBuilder.kt        # On-the-fly DivKit AST component injector & blueprints
      enginehost/SduiEngineViewModel.kt     # Multi-tenant orchestration, Ed25519 signing & threat lab
      ui/TenantLauncherScreen.kt            # 2-column grid launcher of connected tenants
      ui/PosTerminalTab.kt                  # Dedicated tenant workspace running on-the-fly SDUI
      ui/SduiBuilderTab.kt                  # Interactive DivKit component builder & JSON editor
      ui/DatabaseAndSyncTab.kt              # SQLite PRAGMA, op_log & pending_sync inspector
      ui/SecurityAndKmpTab.kt               # Zero-trust threat simulator & KMP/Swift code browser
```

---

## 🔒 Security & Multi-Tenancy Conventions

| Rule | Enforced In |
| :--- | :--- |
| **Every output variable is `user.*`** | `EngineStateBridge.pushUpdate`, `EngineRouter.requireVar` |
| **No raw SQL from JSON** | `EngineRegistry.buildWhere` — structured filter DSL only |
| **Table/column names validated against schema** | `EngineRegistry.requireKnownColumns` (`PRAGMA table_info`) |
| **`LIMIT` hard-capped at 500** | `EngineRegistry.HARD_MAX_LIMIT` |
| **Ed25519 verify before render** | `PayloadVerifier.verify` — nothing unverified reaches `DivView` |
| **Replay window 5 min + `jti` LRU** | `PayloadVerifier` (`maxSkewMs = 300_000L`, `maxSeenJti = 10_000`) |
| **No lost background updates** | `EngineStateBridge` uses `StateFlow` snapshot |
| **iOS task IDs registered before use** | `iosApp/Info.plist` + `registerIosSyncHandler` |
