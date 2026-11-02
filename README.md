# DivKit SDUI POS — Multi-Tenancy KMP App Engine

A Kotlin Multiplatform (KMP) Server-Driven UI (SDUI) application engine that uses **DivKit** for dynamic, on-the-fly component rendering across **Android** and **iOS**. Designed for multi-tenant Point-of-Sale (POS) terminals and isolated workspaces, every tenant is initiated upon receiving its signed DivKit JSON schema, which is cryptographically verified, cached persistently in SQLite, and versioned for instant rollback.

---

## 🚀 Home Launcher, Schema Caching & Dedicated Workspace Flow

1. **2-Column Tenant Grid Launcher (Home Screen)**:
   - On launch, the app presents a **two-column grid launcher** (`TenantLauncherScreen`) listing all connected tenants and their cached schema status:
     - **Acme Bistro & Grill** (`acme_pos` • `POS-TERM-01` • `HYBRID` • `Schema v1.1 (2 cached)`)
     - **Nova Electronics Express** (`nova_retail` • `REG-04-WEST` • `ONLINE` • `Schema v1.1 (2 cached)`)
     - **Zenith Rx Clinic Kiosk** (`zen_pharmacy` • `KIOSK-RX-02` • `OFFLINE` • `Schema v1.1 (2 cached)`)
     - **Metro Roasters Cafe** (`metro_coffee` • `BAR-POS-03` • `HYBRID` • `Schema v1.1 (2 cached)`)
   - Users can also initiate new tenants on the fly via the **+ New Tenant** button.
2. **Schema-Driven Tenant Initiation, Persistent SQLite Caching & Version Rollback**:
   - **Initiation on Schema Receipt**: A tenant is initiated when its signed DivKit JSON schema is received and verified via Ed25519 (`PayloadVerifier`).
   - **Persistent SQLite Storage (`sdui_schema_versions`)**: Every verified DivKit JSON schema is cached inside the tenant's isolated SQLite database (`workspace_<tenant_id>.db` -> `sdui_schema_versions`) along with its semantic version (`v1.0`, `v1.1`, `v1.2`, ...), `envelope_jti`, `envelope_iat`, and change summary.
   - **Version Rollback**: Whenever a new schema is received or built on the fly, it is stored as a new version in `sdui_schema_versions` while retaining all prior cached schemas. Operators can open **Rollback (`SchemaVersionRollbackSheet`)** from the workspace footer or the AppBar `⋮` menu to roll back to any previously cached schema version in one tap.
3. **Full-Screen Self-Contained Tenant Workspace & Standout Pill**:
   - Clicking any tenant card in the 2-column grid opens a **dedicated full-screen workspace** for that tenant with all screen space available to its dynamically rendered DivKit UI (`EngineScreen` -> `DivView`).
   - **Standout Pill (Top-Center)**: A high-contrast, zero-shadow pill (`workspace_standout_pill`) sits unobtrusively at the top-center of the workspace, visible across both Light and Dark system themes without shadowing any workspace content.
   - **Persistent `< Hub` AppBar on Pill Click**: Clicking the standout pill opens the top AppBar overlay, which **remains open unless the active workspace screen is clicked or the `< Hub` exit button is clicked**:
     - **`< Hub` Exit Button**: Closes the workspace and returns immediately to the 2-column Tenant Hub launcher.
     - **Tenant Identity, Tunnel & Cached Version Status**: Displays the active tenant name, mode (`HYBRID`, `ONLINE`, `OFFLINE`), and active cached schema version (`Schema v1.1 (2 cached)`).
     - **Refresh (`↻`) Button**: Re-reads the active cached DivKit schema from SQLite and refreshes the tenant's isolated SQLite queries.
     - **3-Dot Menu (`⋮`)**: Opens modal tools for **Schema Cache & Rollback**, **Receive / Build New SDUI Schema**, **Workspace SQLite & Sync Inspector**, and **Zero-Trust Security & KMP Lab**.
4. **System Theme Default (Light & Dark Mode)**:
   - Both the host engine (`MyApplicationTheme(darkTheme = isSystemInDarkTheme())`) and the dynamic DivKit SDUI renderer (`DivView`) automatically default to the device's active **System Theme** (Light or Dark).

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
    db/WorkspaceDatabase.sq                 # SQLDelight schema (sdui_schema_versions, op_log, pending_sync)
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
      enginehost/SduiEngineViewModel.kt     # Schema initiation, SQLite schema cache, rollback & Ed25519 lab
      ui/TenantLauncherScreen.kt            # 2-column grid launcher of connected tenants
      ui/PosTerminalTab.kt                  # Dedicated tenant workspace, standout pill, AppBar & rollback sheet
      ui/SduiBuilderTab.kt                  # Interactive DivKit component builder, JSON editor & version rollback
      ui/DatabaseAndSyncTab.kt              # SQLite PRAGMA, op_log & pending_sync inspector
      ui/SecurityAndKmpTab.kt               # Zero-trust threat simulator & KMP/Swift code browser
```

---

## 🔒 Security & Multi-Tenancy Conventions

| Rule | Enforced In |
| :--- | :--- |
| **Schema Cache & Version Rollback** | `WorkspaceDatabase.sdui_schema_versions` + `SduiEngineViewModel.rollbackToSchemaVersion` |
| **Every output variable is `user.*`** | `EngineStateBridge.pushUpdate`, `EngineRouter.requireVar` |
| **No raw SQL from JSON** | `EngineRegistry.buildWhere` — structured filter DSL only |
| **Table/column names validated against schema** | `EngineRegistry.requireKnownColumns` (`PRAGMA table_info`) |
| **`LIMIT` hard-capped at 500** | `EngineRegistry.HARD_MAX_LIMIT` |
| **Ed25519 verify before render & cache** | `PayloadVerifier.verify` — nothing unverified reaches `DivView` or SQLite cache |
| **Replay window 5 min + `jti` LRU** | `PayloadVerifier` (`maxSkewMs = 300_000L`, `maxSeenJti = 10_000`) |
| **No lost background updates** | `EngineStateBridge` uses `StateFlow` snapshot |
| **iOS task IDs registered before use** | `iosApp/Info.plist` + `registerIosSyncHandler` |
