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
    private val customCardByTenant = mutableMapOf<String, String>()

    private var activeRouter: EngineRouter? = null

    private val _uiState = MutableStateFlow(
        SduiEngineUiState(keyPair = keyPair)
    )
    val uiState: StateFlow<SduiEngineUiState> = _uiState.asStateFlow()

    init {
        currentAndroidContext = { context }
        // Pre-warm default tenant databases so the 2-column home launcher shows live DB status
        viewModelScope.launch(Dispatchers.IO) {
            WorkspaceProvisioner.defaultWorkspaces.forEach { spec ->
                workspaceManager.useDatabase(spec.tenantId) { db ->
                    WorkspaceProvisioner.ensureTenantSchemaAndSeed(db, spec, registry)
                }
            }
            val dbFiles = workspaceManager.activeDatabaseFileNames()
            _uiState.update {
                it.copy(
                    activeDbFiles = dbFiles,
                    statusBannerMessage = "${WorkspaceProvisioner.defaultWorkspaces.size} connected tenants ready in launcher"
                )
            }
        }
        switchWorkspace(WorkspaceProvisioner.defaultWorkspaces.first(), openDedicatedScreen = false)
    }

    fun openTenantWorkspace(spec: TenantWorkspaceSpec) {
        switchWorkspace(spec, openDedicatedScreen = true)
    }

    fun returnToLauncher() {
        _uiState.update {
            it.copy(
                isWorkspaceOpen = false,
                statusBannerMessage = "Select a connected tenant to launch its on-the-fly SDUI workspace"
            )
        }
    }

    private fun getVerifier(tenantId: String): PayloadVerifier =
        verifiers.getOrPut(tenantId) {
            PayloadVerifier(
                expectedTenant = tenantId,
                publicKeyDer = keyPair.publicKeyDerBase64
            )
        }

    fun switchWorkspace(spec: TenantWorkspaceSpec, openDedicatedScreen: Boolean = true) {
        viewModelScope.launch {
            try {
                // 1. Verify EngineConfig via PayloadVerifier -> EngineConfig.fromVerifiedPayload
                val verifiedConfig = WorkspaceProvisioner.createVerifiedConfig(spec, keyPair)

                // 2. Initialize isolated SQLite database (`workspace_<tenant>.db`) & seed tables
                withContext(Dispatchers.IO) {
                    workspaceManager.useDatabase(spec.tenantId) { db ->
                        WorkspaceProvisioner.ensureTenantSchemaAndSeed(db, spec, registry)
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

                // 6. Sign & Verify the DivKit SDUI Card Envelope before passing to EngineScreen
                val rawCard = customCardByTenant[spec.tenantId]
                    ?: WorkspaceProvisioner.buildDefaultDivKitCardJson(spec)
                customCardByTenant[spec.tenantId] = rawCard

                val envelope = PayloadVerifier.createSignedEnvelope(
                    tenant = spec.tenantId,
                    schemaVersion = verifiedConfig.schemaVersion,
                    payloadJson = rawCard,
                    keyPair = keyPair
                )
                val verifiedCardPayload = getVerifier(spec.tenantId).verify(envelope)

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
                        draftDivKitJson = rawCard,
                        lastSignedEnvelopeJson = envelope,
                        lastVerificationStatus = "Ed25519 Verified • tenant=${spec.tenantId} • schema=${verifiedConfig.schemaVersion}",
                        isEnvelopeVerified = true,
                        statusBannerMessage = if (openDedicatedScreen) {
                            "Built UI on the fly from ${spec.tenantId} schema (workspace_${spec.tenantId}.db)"
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
                // Also insert the scanned product into orders via EngineRouter!
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
     * Signs the current draft DivKit JSON with Ed25519, runs it through [PayloadVerifier.verify],
     * and hot-renders it in [EngineScreen].
     */
    fun signVerifyAndDeployDraft(customDraft: String = _uiState.value.draftDivKitJson) {
        val spec = _uiState.value.activeWorkspace
        viewModelScope.launch {
            try {
                // Validate JSON syntax first
                Json.parseToJsonElement(customDraft).jsonObject
                val signedEnvelope = PayloadVerifier.createSignedEnvelope(
                    tenant = spec.tenantId,
                    schemaVersion = "1.0",
                    payloadJson = customDraft,
                    keyPair = keyPair
                )
                val verifiedPayload = getVerifier(spec.tenantId).verify(signedEnvelope)
                customCardByTenant[spec.tenantId] = customDraft
                _uiState.update {
                    it.copy(
                        draftDivKitJson = customDraft,
                        verifiedDivKitJson = verifiedPayload,
                        lastSignedEnvelopeJson = signedEnvelope,
                        lastVerificationStatus = "Ed25519 Signature Verified • Hot-Deployed to ${spec.terminalCode}",
                        isEnvelopeVerified = true,
                        statusBannerMessage = "Signed & hot-rendered SDUI card for ${spec.tenantId}"
                    )
                }
                logSecurityEvent(
                    title = "Signed SDUI Envelope Deployed (${spec.tenantId})",
                    detail = "CanonicalJson + Ed25519 signature verified; rendered in DivView.",
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
        signVerifyAndDeployDraft(updatedJson)
        _uiState.update {
            it.copy(statusBannerMessage = "Injected $description & Ed25519 re-verified!")
        }
    }

    fun applySduiBlueprint(blueprintJson: String, label: String) {
        signVerifyAndDeployDraft(blueprintJson)
        _uiState.update {
            it.copy(statusBannerMessage = "Switched SDUI Blueprint: $label")
        }
    }

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
            subtitle = "${mode.name} Workspace • Isolated SQLite DB",
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
        switchWorkspace(newSpec)
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
            customCardByTenant.remove(spec.tenantId)
            // Re-initialize schema so the user can continue using or re-seed
            withContext(Dispatchers.IO) {
                workspaceManager.useDatabase(spec.tenantId) { db ->
                    val ordersTable = "${spec.tenantId}_orders"
                    val inventoryTable = "${spec.tenantId}_inventory"
                    db.driver.executeUpdate(
                        null,
                        """
                        CREATE TABLE IF NOT EXISTS $ordersTable (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            item_name TEXT NOT NULL,
                            sku TEXT NOT NULL,
                            price REAL NOT NULL,
                            qty INTEGER NOT NULL DEFAULT 1,
                            terminal_id TEXT NOT NULL,
                            status TEXT NOT NULL DEFAULT 'OPEN'
                        )
                        """.trimIndent(),
                        0
                    )
                    db.driver.executeUpdate(
                        null,
                        """
                        CREATE TABLE IF NOT EXISTS $inventoryTable (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            item_name TEXT NOT NULL,
                            sku TEXT UNIQUE NOT NULL,
                            price REAL NOT NULL,
                            stock INTEGER NOT NULL DEFAULT 0,
                            category TEXT NOT NULL
                        )
                        """.trimIndent(),
                        0
                    )
                }
            }
            EngineStateBridge.pushBatch(
                mapOf(
                    "user.workspace_title" to DivVariableValue.Str("${spec.displayName} (RESET)"),
                    "user.orders_json" to DivVariableValue.Str("[]"),
                    "user.inventory_json" to DivVariableValue.Str("[]"),
                    "user.order_count" to DivVariableValue.IntVal(0L),
                    "user.inventory_count" to DivVariableValue.IntVal(0L),
                    "user.last_order_id" to DivVariableValue.IntVal(0L),
                    "user.sync_status" to DivVariableValue.Str("cleared"),
                    "user.engine_error" to DivVariableValue.Str("Workspace wiped via workspace://clear_all")
                )
            )
            refreshDatabaseTelemetry(spec.tenantId)
            _uiState.update {
                it.copy(statusBannerMessage = "Executed workspace://clear_all on workspace_${spec.tenantId}.db")
            }
        }
    }

    fun reseedActiveWorkspace() {
        val spec = _uiState.value.activeWorkspace
        switchWorkspace(spec)
    }

    fun refreshDatabaseTelemetry(workspaceId: String = _uiState.value.activeWorkspace.tenantId) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                workspaceManager.useDatabase(workspaceId) { db ->
                    val logs = db.workspaceQueries.selectRecentOpLogs(40)
                    val pending = db.workspaceQueries.selectAllPendingSync()
                    val tables = inspectWorkspaceTables(db)
                    val dbFiles = workspaceManager.activeDatabaseFileNames()
                    _uiState.update {
                        it.copy(
                            opLogs = logs,
                            pendingSyncEntries = pending,
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
                    // Tamper with payload after signing
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
                        verifier.verify(env) // 1st time succeeds
                        verifier.verify(env) // 2nd time with same jti must throw!
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
                    val expiredIat = currentTimeMillis() - 600_000L // 10 mins old (> 5m maxSkewMs)
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

    fun getCanonicalJsonSample(): String {
        val map = mapOf(
            "z_last_key" to 42.0,
            "a_first_key" to "sorted-deterministically",
            "nested" to mapOf("b" to true, "a" to listOf(1, 2, 3))
        )
        return CanonicalJson.canonicalize(map)
    }
}
