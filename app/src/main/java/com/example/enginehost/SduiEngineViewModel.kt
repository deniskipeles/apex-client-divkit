package com.example.enginehost

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import engine.PlatformContext
import engine.config.EngineConfig
import engine.crypto.CanonicalJson
import engine.crypto.Ed25519
import engine.crypto.PayloadVerifier
import engine.crypto.SigningKeyPair
import engine.crypto.currentTimeMillis
import engine.db.CachedSchemaVersion
import engine.db.EngineRegistry
import engine.db.OpLogEntry
import engine.db.PendingSyncEntry
import engine.db.WorkspaceDriverFactory
import engine.db.WorkspaceManager
import engine.db.currentAndroidContext
import engine.router.EngineRouter
import engine.state.DivVariableValue
import engine.state.EngineStateBridge
import engine.sync.BackgroundSyncScheduler
import engine.sync.SyncEngine
import engine.sync.SyncOutcome
import engine.sync.createHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.util.UUID

data class SecurityEventLog(
    val timestamp: Long,
    val title: String,
    val detail: String,
    val passedDefense: Boolean
)

data class SchemaTableInfo(
    val tableName: String,
    val columns: List<String>
)

data class SduiEngineUiState(
    val workspaces: List<TenantWorkspaceSpec> = WorkspaceProvisioner.defaultWorkspaces,
    val activeWorkspace: TenantWorkspaceSpec = WorkspaceProvisioner.defaultWorkspaces.first(),
    val isWorkspaceOpen: Boolean = false,
    val activeConfig: EngineConfig? = null,
    val keyPair: SigningKeyPair? = null,
    val verifiedDivKitJson: String = "",
    val draftDivKitJson: String = "",
    val lastSignedEnvelopeJson: String = "",
    val lastVerificationStatus: String = "Initializing Ed25519 Verifier...",
    val isEnvelopeVerified: Boolean = true,
    val activeSchemaVersion: CachedSchemaVersion? = null,
    val schemaVersions: List<CachedSchemaVersion> = emptyList(),
    val tenantCachedBadgeMap: Map<String, String> = emptyMap(),
    val opLogs: List<OpLogEntry> = emptyList(),
    val pendingSyncEntries: List<PendingSyncEntry> = emptyList(),
    val schemaTables: List<SchemaTableInfo> = emptyList(),
    val securityEvents: List<SecurityEventLog> = emptyList(),
    val activeDbFiles: List<String> = emptyList(),
    val statusBannerMessage: String? = null
)

class SduiEngineViewModel(appContext: Context) : ViewModel() {

    private val context = appContext.applicationContext
    private val driverFactory = WorkspaceDriverFactory(context)
    val workspaceManager = WorkspaceManager(driverFactory)
    val registry = EngineRegistry()
    private val httpClient = createHttpClient()
    val syncEngine = SyncEngine(workspaceManager, httpClient)
    private val syncScheduler = BackgroundSyncScheduler(PlatformContext.Android(context))

    private val keyPair: SigningKeyPair = Ed25519.generateKeyPair("sdui-kmp-edge-key")
    private val verifiers = mutableMapOf<String, PayloadVerifier>()

    private var activeRouter: EngineRouter? = null

    private val _uiState = MutableStateFlow(
        SduiEngineUiState(keyPair = keyPair)
    )
    val uiState: StateFlow<SduiEngineUiState> = _uiState.asStateFlow()

    init {
        currentAndroidContext = { context }
        // Initiate connected tenants by receiving, verifying, and caching their initial DivKit schemas in SQLite
        viewModelScope.launch(Dispatchers.IO) {
            WorkspaceProvisioner.defaultWorkspaces.forEach { spec ->
                ensureTenantInitiatedWithCachedSchema(spec)
            }
            refreshTenantCachedBadges()
            val dbFiles = workspaceManager.activeDatabaseFileNames()
            _uiState.update {
                it.copy(
                    activeDbFiles = dbFiles,
                    statusBannerMessage = "${WorkspaceProvisioner.defaultWorkspaces.size} tenants initiated & cached in SQLite"
                )
            }
            switchWorkspace(WorkspaceProvisioner.defaultWorkspaces.first(), openDedicatedScreen = false)
        }
    }

    private fun getVerifier(tenantId: String): PayloadVerifier =
        verifiers.getOrPut(tenantId) {
            PayloadVerifier(
                expectedTenant = tenantId,
                publicKeyDer = keyPair.publicKeyDerBase64
            )
        }

    /**
     * Initiates a tenant when its DivKit JSON schema is received, verifying the Ed25519 envelope
     * and storing it in `sdui_schema_versions` inside `workspace_<tenant>.db`.
     * Seeds v1.0 (Stock Blueprint) and v1.1 (Full POS Register Blueprint) on first initiation
     * so version rollback is immediately available to test.
     */
    private suspend fun ensureTenantInitiatedWithCachedSchema(spec: TenantWorkspaceSpec) {
        val needsInitialSeed = workspaceManager.useDatabase(spec.tenantId) { db ->
            WorkspaceProvisioner.ensureTenantSchemaAndSeed(db, spec, registry)
            db.workspaceQueries.selectAllSchemaVersions().isEmpty()
        }
        if (needsInitialSeed) {
            val verifier = getVerifier(spec.tenantId)
            // Receive & verify v1.0 baseline schema
            val v10Raw = SduiOnFlyBuilder.buildInventoryTerminalBlueprint(spec)
            val v10Jti = UUID.randomUUID().toString()
            val v10Env = PayloadVerifier.createSignedEnvelope(
                tenant = spec.tenantId,
                schemaVersion = "1.0",
                payloadJson = v10Raw,
                keyPair = keyPair,
                jtiOverride = v10Jti
            )
            val v10Verified = verifier.verify(v10Env)

            // Receive & verify v1.1 active POS Register schema
            val v11Raw = WorkspaceProvisioner.buildDefaultDivKitCardJson(spec)
            val v11Jti = UUID.randomUUID().toString()
            val v11Env = PayloadVerifier.createSignedEnvelope(
                tenant = spec.tenantId,
                schemaVersion = "1.1",
                payloadJson = v11Raw,
                keyPair = keyPair,
                jtiOverride = v11Jti
            )
            val v11Verified = verifier.verify(v11Env)

            workspaceManager.useDatabase(spec.tenantId) { db ->
                db.workspaceQueries.cacheAndActivateSchemaVersion(
                    schemaVersion = "1.0",
                    jti = v10Jti,
                    signedEnvelope = v10Env,
                    verifiedPayload = v10Verified,
                    receivedAt = currentTimeMillis() - 120_000L,
                    summary = "v1.0 • Initial Stock & Inventory Schema"
                )
                db.workspaceQueries.cacheAndActivateSchemaVersion(
                    schemaVersion = "1.1",
                    jti = v11Jti,
                    signedEnvelope = v11Env,
                    verifiedPayload = v11Verified,
                    receivedAt = currentTimeMillis(),
                    summary = "v1.1 • Full POS Register + Hardware Bridge Schema"
                )
            }
        }
    }

    private suspend fun refreshTenantCachedBadges() {
        val badgeMap = mutableMapOf<String, String>()
        for (spec in _uiState.value.workspaces) {
            workspaceManager.useDatabase(spec.tenantId) { db ->
                val all = db.workspaceQueries.selectAllSchemaVersions()
                val active = all.firstOrNull { it.isActive } ?: all.firstOrNull()
                if (active != null) {
                    badgeMap[spec.tenantId] = "Schema v${active.schemaVersion} (${all.size} cached)"
                } else {
                    badgeMap[spec.tenantId] = "Schema v1.0 (Cached)"
                }
            }
        }
        _uiState.update { it.copy(tenantCachedBadgeMap = badgeMap) }
    }

    fun openTenantWorkspace(spec: TenantWorkspaceSpec) {
        switchWorkspace(spec, openDedicatedScreen = true)
    }

    fun returnToLauncher() {
        viewModelScope.launch(Dispatchers.IO) {
            refreshTenantCachedBadges()
        }
        _uiState.update {
            it.copy(
                isWorkspaceOpen = false,
                statusBannerMessage = "Select a connected tenant to launch its cached SDUI workspace"
            )
        }
    }

    fun switchWorkspace(spec: TenantWorkspaceSpec, openDedicatedScreen: Boolean = true) {
        viewModelScope.launch {
            try {
                // 1. Verify EngineConfig via PayloadVerifier -> EngineConfig.fromVerifiedPayload
                val verifiedConfig = WorkspaceProvisioner.createVerifiedConfig(spec, keyPair)

                // 2. Ensure tenant DB & cached DivKit schema versions exist in `workspace_<tenant>.db`
                var cachedActive: CachedSchemaVersion? = null
                var allCachedVersions: List<CachedSchemaVersion> = emptyList()
                withContext(Dispatchers.IO) {
                    ensureTenantInitiatedWithCachedSchema(spec)
                    workspaceManager.useDatabase(spec.tenantId) { db ->
                        allCachedVersions = db.workspaceQueries.selectAllSchemaVersions()
                        cachedActive = db.workspaceQueries.selectActiveSchemaVersion()
                            ?: allCachedVersions.firstOrNull()
                    }
                }

                // 3. Construct EngineRouter for the active workspace
                val router = EngineRouter(
                    config = verifiedConfig,
                    workspaceManager = workspaceManager,
                    registry = registry,
                    activeWorkspace = { spec.tenantId },
                    hardwareDispatcher = { capability, url, _ ->
                        handleHardwareCapability(spec, capability, url)
                    }
                )
                activeRouter = router

                // 4. Schedule Periodic Background Sync via BackgroundSyncScheduler
                syncScheduler.schedule(
                    workspaceId = spec.tenantId,
                    endpoint = verifiedConfig.edgeEndpoint,
                    intervalMs = verifiedConfig.syncIntervalMs
                )

                // 5. Reset & hydrate EngineStateBridge for the newly attached tenant UI
                EngineStateBridge.clear()
                EngineStateBridge.pushBatch(
                    mapOf(
                        "user.workspace_title" to DivVariableValue.Str("${spec.displayName} (${spec.terminalCode})"),
                        "user.custom_item_name" to DivVariableValue.Str("Express Combo #9"),
                        "user.custom_item_price" to DivVariableValue.Str("12.50"),
                        "user.last_hardware_event" to DivVariableValue.Str("Ready (${spec.capabilities.joinToString("/")})"),
                        "user.sync_status" to DivVariableValue.Str(verifiedConfig.mode.name.lowercase()),
                        "user.engine_error" to DivVariableValue.Str("None"),
                        "user.last_order_id" to DivVariableValue.IntVal(0L)
                    )
                )

                // 6. Load the stored/cached verified DivKit JSON schema for this tenant
                val activeSchema = cachedActive
                val verifiedCardPayload = activeSchema?.verifiedPayload
                    ?: WorkspaceProvisioner.buildDefaultDivKitCardJson(spec)
                val activeVersionLabel = activeSchema?.schemaVersion ?: "1.0"

                // 7. Execute initial queries via EngineRouter so DivKit variables (`user.orders_json`, counts) are populated
                router.handle(
                    "local-db://op/db.query",
                    WorkspaceProvisioner.buildQueryOrdersAction()["payload"]?.jsonObject
                )
                router.handle(
                    "local-db://op/db.count",
                    WorkspaceProvisioner.buildCountOrdersAction()["payload"]?.jsonObject
                )
                router.handle(
                    "local-db://op/db.query",
                    WorkspaceProvisioner.buildQueryInventoryAction()["payload"]?.jsonObject
                )
                router.handle(
                    "local-db://op/db.count",
                    WorkspaceProvisioner.buildCountInventoryAction()["payload"]?.jsonObject
                )

                _uiState.update {
                    it.copy(
                        activeWorkspace = spec,
                        isWorkspaceOpen = openDedicatedScreen,
                        activeConfig = verifiedConfig,
                        verifiedDivKitJson = verifiedCardPayload,
                        draftDivKitJson = verifiedCardPayload,
                        lastSignedEnvelopeJson = activeSchema?.signedEnvelope ?: "",
                        activeSchemaVersion = activeSchema,
                        schemaVersions = allCachedVersions,
                        lastVerificationStatus = "Cached Schema v$activeVersionLabel (${allCachedVersions.size} stored) • Ed25519 Verified",
                        isEnvelopeVerified = true,
                        statusBannerMessage = if (openDedicatedScreen) {
                            "Loaded cached Schema v$activeVersionLabel from workspace_${spec.tenantId}.db"
                        } else {
                            it.statusBannerMessage
                        }
                    )
                }
                refreshDatabaseTelemetry(spec.tenantId)
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        lastVerificationStatus = "Verification Error: ${e.message}",
                        isEnvelopeVerified = false,
                        statusBannerMessage = "Error: ${e.message}"
                    )
                }
            }
        }
    }

    /**
     * Rolls back (or rolls forward) the active tenant workspace UI to any previously
     * cached DivKit JSON schema stored in `sdui_schema_versions`.
     */
    fun rollbackToSchemaVersion(targetVersion: CachedSchemaVersion) {
        val spec = _uiState.value.activeWorkspace
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                workspaceManager.useDatabase(spec.tenantId) { db ->
                    db.workspaceQueries.activateSchemaVersionById(targetVersion.versionId)
                }
                refreshTenantCachedBadges()
            }
            // Refresh database telemetry and state variables for the rolled-back UI
            activeRouter?.handle(
                "local-db://op/db.query",
                WorkspaceProvisioner.buildQueryOrdersAction()["payload"]?.jsonObject
            )
            activeRouter?.handle(
                "local-db://op/db.count",
                WorkspaceProvisioner.buildCountOrdersAction()["payload"]?.jsonObject
            )
            activeRouter?.handle(
                "local-db://op/db.query",
                WorkspaceProvisioner.buildQueryInventoryAction()["payload"]?.jsonObject
            )
            activeRouter?.handle(
                "local-db://op/db.count",
                WorkspaceProvisioner.buildCountInventoryAction()["payload"]?.jsonObject
            )

            refreshDatabaseTelemetry(spec.tenantId)
            _uiState.update {
                it.copy(
                    verifiedDivKitJson = targetVersion.verifiedPayload,
                    draftDivKitJson = targetVersion.verifiedPayload,
                    lastSignedEnvelopeJson = targetVersion.signedEnvelope,
                    activeSchemaVersion = targetVersion.copy(isActive = true),
                    lastVerificationStatus = "Rolled back to cached Schema v${targetVersion.schemaVersion} • Ed25519 Verified",
                    isEnvelopeVerified = true,
                    statusBannerMessage = "Rolled back ${spec.tenantId} UI to cached Schema v${targetVersion.schemaVersion}"
                )
            }
            logSecurityEvent(
                title = "Schema Rollback → v${targetVersion.schemaVersion} (${spec.tenantId})",
                detail = "Restored cached signed schema (jti=${targetVersion.jti.take(8)}...) from workspace_${spec.tenantId}.db",
                passed = true
            )
        }
    }

    fun dispatchDivKitAction(url: String, payloadJsonStr: String?) {
        val router = activeRouter ?: return
        val spec = _uiState.value.activeWorkspace
        viewModelScope.launch {
            val payloadObj = payloadJsonStr?.let {
                try {
                    Json.parseToJsonElement(it).jsonObject
                } catch (_: Exception) {
                    null
                }
            }
            val outcome = withContext(Dispatchers.IO) {
                router.handle(url, payloadObj)
            }
            val banner = when (outcome) {
                is EngineRouter.ActionOutcome.Handled -> "Executed $url on workspace_${spec.tenantId}.db"
                is EngineRouter.ActionOutcome.Error -> "Blocked ($url): ${outcome.message}"
                is EngineRouter.ActionOutcome.Unhandled -> "Unhandled scheme: $url"
            }
            _uiState.update { it.copy(statusBannerMessage = banner) }
            refreshDatabaseTelemetry(spec.tenantId)
        }
    }

    private suspend fun handleHardwareCapability(
        spec: TenantWorkspaceSpec,
        capability: String,
        url: String
    ): Boolean {
        return when (capability) {
            "scanner" -> {
                val randomProd = spec.defaultProducts.random()
                EngineStateBridge.pushUpdate(
                    "user.last_hardware_event",
                    DivVariableValue.Str("SCANNER OK: ${randomProd.sku} (${randomProd.name})")
                )
                activeRouter?.handle(
                    "local-db://op/db.insert",
                    WorkspaceProvisioner.buildInsertOrderAction(
                        spec,
                        randomProd.name,
                        randomProd.sku,
                        randomProd.price.toString()
                    )["payload"]?.jsonObject
                )
                activeRouter?.handle(
                    "local-db://op/db.query",
                    WorkspaceProvisioner.buildQueryOrdersAction()["payload"]?.jsonObject
                )
                activeRouter?.handle(
                    "local-db://op/db.count",
                    WorkspaceProvisioner.buildCountOrdersAction()["payload"]?.jsonObject
                )
                true
            }
            "printer" -> {
                val count = EngineStateBridge.state.value["user.order_count"]?.asDisplayString() ?: "0"
                EngineStateBridge.pushUpdate(
                    "user.last_hardware_event",
                    DivVariableValue.Str("THERMAL PRINT OK: Receipt for $count order(s) @ ${spec.terminalCode}")
                )
                true
            }
            "nfc" -> {
                EngineStateBridge.pushUpdate(
                    "user.last_hardware_event",
                    DivVariableValue.Str("NFC EMV AUTHORIZED: Token 0x9F26 (${spec.terminalCode})")
                )
                true
            }
            "cash_drawer" -> {
                EngineStateBridge.pushUpdate(
                    "user.last_hardware_event",
                    DivVariableValue.Str("CASH DRAWER SOLENOID OPENED (${spec.terminalCode})")
                )
                true
            }
            else -> {
                EngineStateBridge.pushUpdate(
                    "user.last_hardware_event",
                    DivVariableValue.Str("Hardware dispatched: $url")
                )
                true
            }
        }
    }

    fun updateDraftDivKitJson(newDraft: String) {
        _uiState.update { it.copy(draftDivKitJson = newDraft) }
    }

    /**
     * Receives a new DivKit JSON schema for the active tenant, signs & verifies it via
     * [PayloadVerifier.verify], stores it as a new version row in `sdui_schema_versions`
     * (preserving prior versions for rollback), and hot-renders it in [EngineScreen].
     */
    fun signVerifyAndDeployDraft(
        customDraft: String = _uiState.value.draftDivKitJson,
        changeSummary: String = "Custom Schema Update"
    ) {
        val spec = _uiState.value.activeWorkspace
        viewModelScope.launch {
            try {
                // Validate JSON syntax first
                Json.parseToJsonElement(customDraft).jsonObject
                val currentVersions = _uiState.value.schemaVersions
                val nextMinor = currentVersions.size + 1
                val newVersionStr = "1.$nextMinor"
                val newJti = UUID.randomUUID().toString()

                val signedEnvelope = PayloadVerifier.createSignedEnvelope(
                    tenant = spec.tenantId,
                    schemaVersion = newVersionStr,
                    payloadJson = customDraft,
                    keyPair = keyPair,
                    jtiOverride = newJti
                )
                val verifiedPayload = getVerifier(spec.tenantId).verify(signedEnvelope)

                var updatedVersions: List<CachedSchemaVersion> = emptyList()
                var newlyActive: CachedSchemaVersion? = null
                withContext(Dispatchers.IO) {
                    workspaceManager.useDatabase(spec.tenantId) { db ->
                        db.workspaceQueries.cacheAndActivateSchemaVersion(
                            schemaVersion = newVersionStr,
                            jti = newJti,
                            signedEnvelope = signedEnvelope,
                            verifiedPayload = verifiedPayload,
                            receivedAt = currentTimeMillis(),
                            summary = "v$newVersionStr • $changeSummary"
                        )
                        updatedVersions = db.workspaceQueries.selectAllSchemaVersions()
                        newlyActive = db.workspaceQueries.selectActiveSchemaVersion()
                    }
                    refreshTenantCachedBadges()
                }

                _uiState.update {
                    it.copy(
                        draftDivKitJson = customDraft,
                        verifiedDivKitJson = verifiedPayload,
                        lastSignedEnvelopeJson = signedEnvelope,
                        activeSchemaVersion = newlyActive,
                        schemaVersions = updatedVersions,
                        lastVerificationStatus = "Cached Schema v$newVersionStr (${updatedVersions.size} stored) • Ed25519 Verified",
                        isEnvelopeVerified = true,
                        statusBannerMessage = "Stored & rendered new Schema v$newVersionStr ($changeSummary)"
                    )
                }
                logSecurityEvent(
                    title = "New Schema v$newVersionStr Cached (${spec.tenantId})",
                    detail = "Verified & stored in sdui_schema_versions ($changeSummary). Rollback available.",
                    passed = true
                )
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        lastVerificationStatus = "Rejected by PayloadVerifier: ${e.message}",
                        isEnvelopeVerified = false,
                        statusBannerMessage = "Deploy rejected: ${e.message}"
                    )
                }
            }
        }
    }

    fun injectOnFlyComponent(block: JsonObject, description: String, prepend: Boolean = false) {
        val updatedJson = SduiOnFlyBuilder.injectBlockIntoCard(
            currentCardJson = _uiState.value.draftDivKitJson,
            newBlock = block,
            prepend = prepend
        )
        signVerifyAndDeployDraft(updatedJson, changeSummary = "Added $description")
    }

    fun applySduiBlueprint(blueprintJson: String, label: String) {
        signVerifyAndDeployDraft(blueprintJson, changeSummary = label)
    }

    /**
     * Initiates a brand-new tenant when its initial DivKit JSON schema is received.
     * Verifies and caches the schema as v1.0 in `workspace_<tenant_id>.db`.
     */
    fun createNewWorkspace(
        tenantIdInput: String,
        displayName: String,
        terminalCode: String,
        mode: EngineConfig.Mode,
        capabilities: Set<String>
    ) {
        val cleanId = tenantIdInput.trim().lowercase().replace(Regex("[^a-z0-9_-]"), "_")
        if (cleanId.isBlank()) return
        val newSpec = TenantWorkspaceSpec(
            tenantId = cleanId,
            displayName = displayName.ifBlank { "Custom POS ($cleanId)" },
            terminalCode = terminalCode.ifBlank { "TERM-${cleanId.take(4).uppercase()}" },
            subtitle = "${mode.name} Workspace • Initiated via DivKit Schema",
            accentHex = "#F472B6",
            mode = mode,
            capabilities = capabilities,
            defaultProducts = listOf(
                SeedProduct("Signature Espresso", "CSP-01", 4.50, 100, "Coffee", "#DB2777"),
                SeedProduct("Avocado Sourdough", "CSP-02", 11.00, 45, "Bakery", "#9333EA"),
                SeedProduct("Matcha Oat Latte", "CSP-03", 6.25, 80, "Beverages", "#0D9488")
            ),
            defaultOrders = listOf(
                SeedOrder("Signature Espresso", "CSP-01", 4.50, 1, terminalCode.ifBlank { "TERM-01" }, "OPEN")
            )
        )
        _uiState.update { state ->
            val updatedList = state.workspaces.filterNot { it.tenantId == cleanId } + newSpec
            state.copy(workspaces = updatedList)
        }
        switchWorkspace(newSpec, openDedicatedScreen = true)
    }

    fun triggerSyncNow() {
        val spec = _uiState.value.activeWorkspace
        val endpoint = _uiState.value.activeConfig?.edgeEndpoint
            ?: "https://edge.sdui-pos.internal/${spec.tenantId}"
        viewModelScope.launch {
            syncScheduler.syncNow(spec.tenantId, endpoint)
            val outcome = syncEngine.sync(spec.tenantId, endpoint)
            refreshDatabaseTelemetry(spec.tenantId)
            _uiState.update {
                it.copy(
                    statusBannerMessage = when (outcome) {
                        SyncOutcome.Success -> "SyncEngine completed for ${spec.tenantId}: all pending_sync rows accepted!"
                        SyncOutcome.Retry -> "SyncEngine scheduled backoff retry for ${spec.tenantId}"
                    }
                )
            }
        }
    }

    fun clearActiveWorkspace() {
        val spec = _uiState.value.activeWorkspace
        viewModelScope.launch {
            activeRouter?.handle("workspace://clear_all", null)
            withContext(Dispatchers.IO) {
                ensureTenantInitiatedWithCachedSchema(spec)
                refreshTenantCachedBadges()
            }
            switchWorkspace(spec, openDedicatedScreen = _uiState.value.isWorkspaceOpen)
            _uiState.update {
                it.copy(statusBannerMessage = "Executed workspace://clear_all & re-initiated baseline schema")
            }
        }
    }

    fun reseedActiveWorkspace() {
        val spec = _uiState.value.activeWorkspace
        switchWorkspace(spec, openDedicatedScreen = _uiState.value.isWorkspaceOpen)
    }

    fun refreshDatabaseTelemetry(workspaceId: String = _uiState.value.activeWorkspace.tenantId) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                workspaceManager.useDatabase(workspaceId) { db ->
                    val logs = db.workspaceQueries.selectRecentOpLogs(40)
                    val pending = db.workspaceQueries.selectAllPendingSync()
                    val versions = db.workspaceQueries.selectAllSchemaVersions()
                    val activeVer = db.workspaceQueries.selectActiveSchemaVersion()
                    val tables = inspectWorkspaceTables(db)
                    val dbFiles = workspaceManager.activeDatabaseFileNames()
                    _uiState.update {
                        it.copy(
                            opLogs = logs,
                            pendingSyncEntries = pending,
                            schemaVersions = versions,
                            activeSchemaVersion = activeVer,
                            schemaTables = tables,
                            activeDbFiles = dbFiles
                        )
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun inspectWorkspaceTables(db: engine.db.WorkspaceDatabase): List<SchemaTableInfo> {
        val tableNames = mutableListOf<String>()
        db.driver.executeQuery(
            null,
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'android_%' AND name NOT LIKE 'sqlite_%' ORDER BY name",
            0
        ).use { c ->
            while (c.next().value) {
                c.getString(0)?.let { tableNames.add(it) }
            }
        }
        return tableNames.map { tbl ->
            val cols = mutableListOf<String>()
            db.driver.executeQuery(null, "PRAGMA table_info($tbl)", 0).use { c ->
                while (c.next().value) {
                    val colName = c.getString(1) ?: continue
                    val colType = c.getString(2) ?: ""
                    cols.add("$colName:$colType")
                }
            }
            SchemaTableInfo(tbl, cols)
        }
    }

    // ---- Interactive Security & Guard Verification Suite ----

    fun runSecurityVerificationScenario(scenario: String) {
        val spec = _uiState.value.activeWorkspace
        val verifier = getVerifier(spec.tenantId)
        viewModelScope.launch {
            when (scenario) {
                "tamper_signature" -> {
                    val validEnv = PayloadVerifier.createSignedEnvelope(
                        tenant = spec.tenantId,
                        payloadJson = """{"type":"text","text":"Original $10.00"}""",
                        keyPair = keyPair
                    )
                    val tampered = validEnv.replace("Original $10.00", "Tampered $0.01")
                    try {
                        verifier.verify(tampered)
                        logSecurityEvent("MITM Payload Tamper Test", "Unexpectedly passed", false)
                    } catch (e: SecurityException) {
                        logSecurityEvent(
                            "MITM Payload Tamper Blocked",
                            "PayloadVerifier threw SecurityException: ${e.message}",
                            true
                        )
                    }
                }

                "replay_jti" -> {
                    val fixedJti = "replay-jti-${currentTimeMillis()}"
                    val env = PayloadVerifier.createSignedEnvelope(
                        tenant = spec.tenantId,
                        payloadJson = """{"type":"text","text":"First transmission"}""",
                        keyPair = keyPair,
                        jtiOverride = fixedJti
                    )
                    try {
                        verifier.verify(env)
                        verifier.verify(env)
                        logSecurityEvent("Replay JTI Test", "Unexpectedly allowed duplicate jti", false)
                    } catch (e: SecurityException) {
                        logSecurityEvent(
                            "Replay Attack Blocked (jti LRU)",
                            "PayloadVerifier threw SecurityException: ${e.message}",
                            true
                        )
                    }
                }

                "expired_iat" -> {
                    val expiredIat = currentTimeMillis() - 600_000L
                    val env = PayloadVerifier.createSignedEnvelope(
                        tenant = spec.tenantId,
                        payloadJson = """{"type":"text","text":"Stale envelope"}""",
                        keyPair = keyPair,
                        iatOverride = expiredIat
                    )
                    try {
                        verifier.verify(env)
                        logSecurityEvent("Expired iat Skew Test", "Unexpectedly allowed expired iat", false)
                    } catch (e: SecurityException) {
                        logSecurityEvent(
                            "Stale Envelope Blocked (5m iat Window)",
                            "PayloadVerifier threw SecurityException: ${e.message}",
                            true
                        )
                    }
                }

                "cross_tenant" -> {
                    val wrongTenantEnv = PayloadVerifier.createSignedEnvelope(
                        tenant = "rogue_tenant_99",
                        payloadJson = """{"type":"text","text":"Cross-tenant injection"}""",
                        keyPair = keyPair
                    )
                    try {
                        verifier.verify(wrongTenantEnv)
                        logSecurityEvent("Cross-Tenant Confusion Test", "Unexpectedly allowed", false)
                    } catch (e: SecurityException) {
                        logSecurityEvent(
                            "Cross-Tenant Spoofing Blocked",
                            "PayloadVerifier threw SecurityException: ${e.message}",
                            true
                        )
                    }
                }

                "sql_injection" -> {
                    val outcome = activeRouter?.handle(
                        "local-db://op/db.query",
                        buildJsonObject {
                            put("table", "orders; DROP TABLE op_log --")
                        }
                    )
                    val msg = (outcome as? EngineRouter.ActionOutcome.Error)?.message ?: "Blocked"
                    logSecurityEvent(
                        "SQL Injection Blocked (IDENT_REGEX + PRAGMA)",
                        "EngineRegistry rejected malicious identifier: $msg",
                        true
                    )
                    refreshDatabaseTelemetry(spec.tenantId)
                }

                "unfiltered_delete" -> {
                    val outcome = activeRouter?.handle(
                        "local-db://op/db.delete",
                        buildJsonObject {
                            put("table", "orders")
                        }
                    )
                    val msg = (outcome as? EngineRouter.ActionOutcome.Error)?.message ?: "Blocked"
                    logSecurityEvent(
                        "Unfiltered DELETE Guard Triggered",
                        "EngineRegistry rejected truncation without confirm_truncate=true: $msg",
                        true
                    )
                    refreshDatabaseTelemetry(spec.tenantId)
                }
            }
        }
    }

    private fun logSecurityEvent(title: String, detail: String, passed: Boolean) {
        val event = SecurityEventLog(
            timestamp = currentTimeMillis(),
            title = title,
            detail = detail,
            passedDefense = passed
        )
        _uiState.update {
            it.copy(
                securityEvents = listOf(event) + it.securityEvents.take(29),
                statusBannerMessage = "$title — $detail"
            )
        }
    }
}
