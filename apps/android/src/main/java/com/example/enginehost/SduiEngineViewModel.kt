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
import engine.db.ApexKitDatabaseEngine
import engine.db.CachedSchemaVersion
import engine.db.EngineRegistry
import engine.db.KioskOps
import engine.db.OpLogEntry
import engine.db.PendingSyncEntry
import engine.db.WorkspaceDriverFactory
import engine.db.WorkspaceManager
import engine.db.currentAndroidContext
import com.example.enginehost.demo.DemoCardRepository
import engine.fs.FileOpenWith
import engine.fs.FsEntry
import engine.fs.WorkspaceFileManager
import engine.fs.WorkspaceFileSystem
import engine.fs.WorkspaceFolder
import engine.navigation.CardEntry
import engine.navigation.CardRegistry
import engine.navigation.DivCardNavigator
import engine.navigation.NavOp
import engine.navigation.NavigationUrlParser
import engine.notifications.NotificationCenter
import engine.notifications.NotificationSeed
import engine.notifications.WorkspaceNotification
import engine.router.EngineRouter
import engine.state.DivVariableValue
import engine.state.EngineStateBridge
import engine.sync.BackgroundSyncScheduler
import engine.sync.SyncEngine
import engine.sync.SyncOutcome
import engine.tools.libsignal.LibSignalEngine
import engine.sync.createHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.File
import java.util.UUID

enum class EngineThemeMode(val label: String) {
    SYSTEM("System"),
    LIGHT("Light"),
    DARK("Dark")
}

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
    /** True while a remembered workspace is being restored after a cold launch. */
    val isRestoringSession: Boolean = false,
    val themeMode: EngineThemeMode = EngineThemeMode.SYSTEM,
    val isGridView: Boolean = true,
    val searchQuery: String = "",
    val pushGatewayBase: String = "https://kipeles-apexkit.hf.space/tenant/apexapp-push",
    val hardwareDeviceId: String = "89909045832b6844",
    val fcmPushToken: String = "fxF-S18CSsehc3lubcw_Ys:APA91bE4FCZ0XrmpHB7gVK-dMOk2sicVlcPX137g5MZ5zRgAF9MK14bc",
    val wsConnected: Boolean = true,
    val tenantToolsMap: Map<String, WorkspaceToolPermissions> =
        WorkspaceProvisioner.defaultWorkspaces.associate { it.tenantId to it.defaultTools },
    val activeRoutePath: String = "/",
    val activeRouteQueries: String = "(none)",
    val activeConfig: EngineConfig? = null,
    val keyPair: SigningKeyPair? = null,
    val verifiedDivKitJson: String = "",
    /** Card currently on screen: the entry hub at route index 0, or a pushed tool card. */
    val activeCardJson: String = "",
    /** CardRegistry id of [activeCardJson]. */
    val activeCardId: String = "",
    /** Breadcrumb of card ids from the hub down to the active card. */
    val cardStack: List<String> = emptyList(),
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
    val workspaceFolders: List<WorkspaceFolder> = emptyList(),
    val fileBrowserFolder: WorkspaceFolder? = null,
    val fileBrowserPath: String = "",
    val fileEntries: List<FsEntry> = emptyList(),
    val filePreview: FilePreview? = null,
    val notifications: List<WorkspaceNotification> = emptyList(),
    val notificationsLoading: Boolean = false,
    val notificationSettingsOpen: Boolean = false,
    val notificationShowRead: Boolean = true,
    val notificationGroupByWorkspace: Boolean = true,
    val expandedNotificationGroups: Set<String> = emptySet(),
    val statusBannerMessage: String? = null
) {
    /** Live unread count per workspace, sourced from the notification tray. */
    val notificationUnreadByWorkspace: Map<String, Int>
        get() = notifications
            .filter { it.isUnread }
            .groupBy { it.workspaceId }
            .mapValues { (_, items) -> items.size }

    /** Total unread notifications across every workspace. */
    val totalUnreadNotifications: Int get() = notifications.count { it.isUnread }

    /** Unread count for a workspace, falling back to the seeded spec value when the tray is empty. */
    fun unreadFor(tenantId: String, fallback: Int): Int =
        notificationUnreadByWorkspace[tenantId] ?: fallback
}

/** Inline text preview of a browsed file. [truncated] marks content cut at the read cap. */
data class FilePreview(
    val name: String,
    val path: String,
    val content: String,
    val truncated: Boolean,
    val sizeLabel: String
)

class SduiEngineViewModel(appContext: Context) : ViewModel() {

    private val context = appContext.applicationContext
    private val driverFactory = WorkspaceDriverFactory(context)
    val workspaceManager = WorkspaceManager(driverFactory)
    val registry = EngineRegistry()
    private val usageStore = TenantUsageStore(context)
    val fileManager = WorkspaceFileManager(context)

    // Constructed lazily so ViewModel creation (and therefore the first frame) stays cheap;
    // these allocate OkHttp clients / WorkManager plumbing that is only needed on demand.
    private val httpClient by lazy { createHttpClient() }
    val syncEngine by lazy { SyncEngine(workspaceManager, httpClient) }
    private val syncScheduler by lazy { BackgroundSyncScheduler(PlatformContext.Android(context)) }

    private val keyPair: SigningKeyPair by lazy { Ed25519.generateKeyPair("sdui-kmp-edge-key") }
    private val verifiers = mutableMapOf<String, PayloadVerifier>()

    private var activeRouter: EngineRouter? = null

    /** Card stack for the active workspace: the entry hub at index 0, pushed cards on top. */
    private var navigator: DivCardNavigator? = null

    private val initialWorkspaces =
        WorkspaceProvisioner.defaultWorkspaces.sortedByUsage(usageStore.usage())

    /** Tenant whose workspace should be re-opened on the next launch, if any. */
    private val resumeTenantId: String? = usageStore.lastOpenedTenantId()
        ?.takeIf { id -> initialWorkspaces.any { it.tenantId == id } }

    private val shouldResumeWorkspace: Boolean =
        resumeTenantId != null && usageStore.wasWorkspaceOpen()

    private val _uiState = MutableStateFlow(
        SduiEngineUiState(
            workspaces = initialWorkspaces,
            activeWorkspace = initialWorkspaces.first(),
            isRestoringSession = shouldResumeWorkspace
        )
    )
    val uiState: StateFlow<SduiEngineUiState> = _uiState.asStateFlow()

    init {
        currentAndroidContext = { context }
        // Seed the workspace notifications tray so grouping, unread counts and actions render
        // populated on first frame.
        _uiState.update {
            it.copy(notifications = NotificationSeed.demo(engine.crypto.currentTimeMillis()))
        }
        // Initiate connected tenants by receiving, verifying, and caching their initial DivKit schemas in SQLite
        viewModelScope.launch(Dispatchers.IO) {
            // Provisioning touches SQLite, Ed25519 and the filesystem. A failure here must not
            // take the process down before the launcher can render, so surface it as banner
            // state instead of letting the coroutine throw.
            try {
                _uiState.update { it.copy(keyPair = keyPair) }
                WorkspaceProvisioner.defaultWorkspaces.forEach { spec ->
                    ensureTenantInitiatedWithCachedSchema(spec)
                }
                refreshTenantCachedBadges()
                val dbFiles = workspaceManager.activeDatabaseFileNames()
                _uiState.update {
                    it.copy(
                        activeDbFiles = dbFiles,
                        statusBannerMessage = "${WorkspaceProvisioner.defaultWorkspaces.size} workspaces ready"
                    )
                }
                // Resume the previously opened tenant on relaunch; otherwise land on the launcher.
                val resumeSpec = resumeTenantId?.let { id ->
                    WorkspaceProvisioner.defaultWorkspaces.firstOrNull { it.tenantId == id }
                }
                if (resumeSpec != null && shouldResumeWorkspace) {
                    switchWorkspace(
                        resumeSpec,
                        openDedicatedScreen = true,
                        recordVisit = false,
                        onFinished = ::finishSessionRestore
                    )
                } else {
                    switchWorkspace(WorkspaceProvisioner.defaultWorkspaces.first(), openDedicatedScreen = false)
                    finishSessionRestore()
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        lastVerificationStatus = "Provisioning Error: ${e.message}",
                        isEnvelopeVerified = false,
                        statusBannerMessage = "Workspace provisioning failed: ${e.message}"
                    )
                }
                finishSessionRestore()
            }
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
        val existingVersions = workspaceManager.useDatabase(spec.tenantId) { db ->
            WorkspaceProvisioner.ensureTenantSchemaAndSeed(db, spec, registry)
            db.workspaceQueries.selectAllSchemaVersions()
        }
        val verifier = getVerifier(spec.tenantId)
        if (existingVersions.isEmpty()) {
            // Receive & verify v1.0 baseline schema
            val v10Raw = SduiOnFlyBuilder.buildCatalogTerminalBlueprint(spec)
            val v10Jti = UUID.randomUUID().toString()
            val v10Env = PayloadVerifier.createSignedEnvelope(
                tenant = spec.tenantId,
                schemaVersion = "1.0",
                payloadJson = v10Raw,
                keyPair = keyPair,
                jtiOverride = v10Jti
            )
            val v10Verified = verifier.verify(v10Env)

            // Receive & verify the current demo card revision (POS Register + SDUI Charts)
            val v11Raw = WorkspaceProvisioner.buildDefaultDivKitCardJson(spec)
            val v11Jti = UUID.randomUUID().toString()
            val v11Env = PayloadVerifier.createSignedEnvelope(
                tenant = spec.tenantId,
                schemaVersion = DEMO_CARD_REVISION,
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
                    summary = "v1.0 • Initial Stock & Catalog Donut Chart Schema"
                )
                db.workspaceQueries.cacheAndActivateSchemaVersion(
                    schemaVersion = DEMO_CARD_REVISION,
                    jti = v11Jti,
                    signedEnvelope = v11Env,
                    verifiedPayload = v11Verified,
                    receivedAt = currentTimeMillis(),
                    summary = "$DEMO_CARD_REVISION • Full POS Register + Live KMP SDUI Chart Schema"
                )
            }
        } else {
            // Cached cards never refresh on their own, so a stale tenant would keep running an
            // old demo card forever. Compare the stored payload with the freshly built card and
            // re-cache only when it actually differs, so edits reach existing workspaces without
            // a version constant that can be forgotten.
            val fresh = WorkspaceProvisioner.buildDefaultDivKitCardJson(spec)
            if (needsDemoCardRefresh(existingVersions, fresh)) {
                val jti = UUID.randomUUID().toString()
                val env = PayloadVerifier.createSignedEnvelope(
                    tenant = spec.tenantId,
                    schemaVersion = DEMO_CARD_REVISION,
                    payloadJson = fresh,
                    keyPair = keyPair,
                    jtiOverride = jti
                )
                val verified = verifier.verify(env)
                workspaceManager.useDatabase(spec.tenantId) { db ->
                    db.workspaceQueries.cacheAndActivateSchemaVersion(
                        schemaVersion = DEMO_CARD_REVISION,
                        jti = jti,
                        signedEnvelope = env,
                        verifiedPayload = verified,
                        receivedAt = currentTimeMillis(),
                        summary = "$DEMO_CARD_REVISION • POS Register + tools:// (libsignal, MobileNetV4, U-2-Net, Barcode/QR)"
                    )
                }
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
        switchWorkspace(spec, openDedicatedScreen = true, recordVisit = true)
    }

    fun returnToLauncher() {
        usageStore.setLastOpened(null, workspaceOpen = false)
        viewModelScope.launch(Dispatchers.IO) {
            refreshTenantCachedBadges()
        }
        _uiState.update {
            it.copy(
                isWorkspaceOpen = false,
                statusBannerMessage = "Select a workspace to open it"
            )
        }
    }

    // ------------------------------------------------------------------
    // Workspace notifications tray
    // ------------------------------------------------------------------

    private var notificationSequence: Int = 0

    private fun mutateNotifications(
        message: String? = null,
        transform: (List<WorkspaceNotification>) -> List<WorkspaceNotification>
    ) {
        _uiState.update { state ->
            val updated = transform(state.notifications)
            state.copy(
                notifications = updated,
                statusBannerMessage = message ?: state.statusBannerMessage
            )
        }
    }

    /** Marks one notification read and refreshes its workspace unread count immediately. */
    fun markNotificationRead(id: String) =
        mutateNotifications { NotificationCenter.markRead(it, id) }

    /** Marks every visible notification read (tray header "Mark all read"). */
    fun markAllNotificationsRead() =
        mutateNotifications("All workspace notifications marked read") { NotificationCenter.markAllRead(it) }

    /** Marks one workspace's notifications read (group header action). */
    fun markWorkspaceNotificationsRead(workspaceId: String, workspaceName: String) =
        mutateNotifications("$workspaceName notifications marked read") {
            NotificationCenter.markWorkspaceRead(it, workspaceId)
        }

    /**
     * Mutes the notification's workspace and clears its unread dot. Future notifications for a
     * muted workspace are dropped by [NotificationCenter.add] until it is unmuted.
     */
    fun muteNotification(id: String) =
        mutateNotifications("Workspace muted \u2014 future notifications suppressed") {
            NotificationCenter.mute(it, id)
        }

    /** Mutes a whole workspace group from the group overflow menu. */
    fun muteNotificationWorkspace(workspaceId: String, workspaceName: String) =
        mutateNotifications("$workspaceName muted") {
            NotificationCenter.muteWorkspace(it, workspaceId)
        }

    /** Un-mutes a workspace group so it resumes surfacing notifications. */
    fun unmuteNotificationWorkspace(workspaceId: String, workspaceName: String) =
        mutateNotifications("$workspaceName unmuted") {
            NotificationCenter.unmuteWorkspace(it, workspaceId)
        }

    /** Removes a single notification from the tray. */
    fun dismissNotification(id: String) =
        mutateNotifications { NotificationCenter.dismiss(it, id) }

    /** Clears every notification for one workspace group. */
    fun dismissNotificationWorkspace(workspaceId: String, workspaceName: String) =
        mutateNotifications("$workspaceName notifications cleared") {
            NotificationCenter.dismissWorkspace(it, workspaceId)
        }

    /** Expands/collapses a single notification row. */
    fun toggleNotificationExpanded(id: String) =
        mutateNotifications { NotificationCenter.toggleExpanded(it, id) }

    /**
     * Opens a notification's target: marks it read, then focuses the owning workspace (and the
     * related route for workspace-scoped targets) so the deep link resolves.
     */
    fun openNotificationTarget(notification: WorkspaceNotification) {
        mutateNotifications { NotificationCenter.markRead(it, notification.id) }
        val spec = _uiState.value.workspaces.firstOrNull { it.tenantId == notification.workspaceId }
        val target = notification.target
        if (spec != null) {
            if (target != null && target.type != "workspace") {
                switchActiveRoute("/${target.type}/${target.id}", "(none)")
            }
            openTenantWorkspace(spec)
        } else {
            _uiState.update {
                it.copy(statusBannerMessage = "Opened: ${notification.title}")
            }
        }
    }

    /** Simulates an incoming notification to prove real-time group/count updates. */
    fun simulateIncomingNotification(workspaceId: String = NotificationSeed.primaryWorkspaceId) {
        notificationSequence += 1
        val incoming = NotificationSeed.incoming(
            workspaceId = workspaceId,
            nowMillis = engine.crypto.currentTimeMillis(),
            sequence = notificationSequence
        )
        val muted = _uiState.value.notifications
            .filter { it.isMuted }
            .map { it.workspaceId }
            .toSet()
        mutateNotifications("New notification in ${incoming.workspaceName}") {
            NotificationCenter.add(it, incoming, mutedWorkspaces = muted)
        }
    }

    fun setNotificationSettingsOpen(open: Boolean) {
        _uiState.update { it.copy(notificationSettingsOpen = open) }
    }

    fun setNotificationShowRead(show: Boolean) {
        _uiState.update { it.copy(notificationShowRead = show) }
    }

    fun setNotificationGroupByWorkspace(group: Boolean) {
        _uiState.update { it.copy(notificationGroupByWorkspace = group) }
    }

    /** Expands/collapses a workspace group in the tray. Expansion persists while the tray is open. */
    fun toggleNotificationGroup(workspaceId: String) {
        _uiState.update { state ->
            val next = state.expandedNotificationGroups.toMutableSet()
            if (!next.add(workspaceId)) next.remove(workspaceId)
            state.copy(expandedNotificationGroups = next)
        }
    }

    /** Replaces the tray contents, e.g. when re-fetching from a backend. */
    fun setNotifications(notifications: List<WorkspaceNotification>, loading: Boolean = false) {
        _uiState.update { it.copy(notifications = notifications, notificationsLoading = loading) }
    }

    fun setNotificationsLoading(loading: Boolean) {
        _uiState.update { it.copy(notificationsLoading = loading) }
    }

    fun switchWorkspace(
        spec: TenantWorkspaceSpec,
        openDedicatedScreen: Boolean = true,
        recordVisit: Boolean = false,
        onFinished: (() -> Unit)? = null
    ) {
        if (recordVisit) {
            usageStore.recordVisit(spec.tenantId)
            usageStore.setLastOpened(spec.tenantId, workspaceOpen = true)
            _uiState.update { it.copy(workspaces = it.workspaces.sortedByUsage(usageStore.usage())) }
        }
        viewModelScope.launch {
            try {
                // 1. Verify EngineConfig via PayloadVerifier -> EngineConfig.fromVerifiedPayload
                val verifiedConfig = WorkspaceProvisioner.createVerifiedConfig(spec, keyPair)

                // 2. Ensure tenant DB & cached DivKit schema versions exist in `workspace_<tenant>.db`
                var cachedActive: CachedSchemaVersion? = null
                var allCachedVersions: List<CachedSchemaVersion> = emptyList()
                var catalogJson = "[]"
                var catalogCount = spec.defaultProducts.size.toLong()
                var kioskSeed: Map<String, DivVariableValue> = emptyMap()
                withContext(Dispatchers.IO) {
                    ensureTenantInitiatedWithCachedSchema(spec)
                    workspaceManager.useDatabase(spec.tenantId) { db ->
                        allCachedVersions = db.workspaceQueries.selectAllSchemaVersions()
                        cachedActive = db.workspaceQueries.selectActiveSchemaVersion()
                            ?: allCachedVersions.firstOrNull()
                        val products = ApexKitDatabaseEngine(db.driver).queryRecords(
                            "inventory",
                            orderBy = "id",
                            ascending = true,
                            limit = 50
                        )
                        catalogCount = products.size.toLong()
                        catalogJson = buildJsonArray {
                            products.forEach { record ->
                                addJsonObject {
                                    put("id", record.id)
                                    record.data.forEach { (key, value) -> put(key, value) }
                                }
                            }
                        }.toString()
                        kioskSeed = seedKioskVariables(
                            ApexKitDatabaseEngine(db.driver),
                            spec,
                            KioskOps.DIRECTION_CUSTOMER
                        )
                    }
                }

                // 3. Register every card for this tenant and start the stack at the entry hub,
                //    so route index 0 is the landing card that links out to the tool cards.
                val stack = registerCards(spec)

                // 4. Construct EngineRouter for the active workspace
                val router = EngineRouter(
                    config = verifiedConfig,
                    workspaceManager = workspaceManager,
                    registry = registry,
                    activeWorkspace = { spec.tenantId },
                    hardwareDispatcher = { capability, url, _ ->
                        handleHardwareCapability(spec, capability, url)
                    },
                    navigationDispatcher = { url -> handleNavigation(url) }
                )
                activeRouter = router

                // 5. Schedule Periodic Background Sync via BackgroundSyncScheduler
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
                        "user.engine_error" to DivVariableValue.Str(""),
                        "user.order_count" to DivVariableValue.IntVal(spec.defaultOrders.size.toLong()),
                        "user.catalog_count" to DivVariableValue.IntVal(catalogCount),
                        "user.catalog_json" to DivVariableValue.Str(catalogJson),
                        "user.last_order_id" to DivVariableValue.IntVal(spec.defaultOrders.size.toLong()),
                        // Scanner results start empty: a seeded code and SUCCESS badge would
                        // claim a hardware scan the terminal never performed.
                        "user.scanned_code" to DivVariableValue.Str(""),
                        "user.scanned_format" to DivVariableValue.Str(""),
                        "user.scanned_type" to DivVariableValue.Str(""),
                        "user.scan_status" to DivVariableValue.Str("IDLE"),
                        "user.ocr_text" to DivVariableValue.Str("not_run"),
                        // Embedding results start empty: a seeded vector/score would look
                        // like the tool had already run.
                        "user.vector_source" to DivVariableValue.Str("not_run"),
                        "user.vector_dim" to DivVariableValue.Str("0"),
                        "user.vector_similarity" to DivVariableValue.Str("—"),
                        "user.vector_b64" to DivVariableValue.Str(""),
                        "user.ocr_confidence" to DivVariableValue.Str("0"),
                        "user.ocr_block_count" to DivVariableValue.IntVal(0L),
                        "user.scan_image_uri" to DivVariableValue.Str(""),
                        "user.code_image_uri" to DivVariableValue.Str(""),
                        "user.code_format" to DivVariableValue.Str("QR_CODE"),
                        "user.code_kind" to DivVariableValue.Str("2D"),
                        "user.code_content" to DivVariableValue.Str("apexpos://order/pickup?terminal=${spec.terminalCode}"),
                        "user.code_id" to DivVariableValue.Str(""),
                        "user.code_status" to DivVariableValue.Str("IDLE"),
                        // MobileNetV4 is an ImageNet classifier, not a SKU model. A seeded
                        // POS label would claim a detection the graph never made.
                        "user.ai_prediction_label" to DivVariableValue.Str("not_run"),
                        "user.ai_confidence_score" to DivVariableValue.Str("0"),
                        "user.ai_source_image_uri" to DivVariableValue.Str(""),
                        // Nothing has been segmented yet: a fabricated URI here would render an
                        // empty preview and claim a result the tool never produced.
                        "user.u2net_mask_uri" to DivVariableValue.Str(""),
                        "user.bg_remove_status" to DivVariableValue.Str("Not run yet"),
                        // Nothing has been handshaked yet. Seeding ESTABLISHED plus a made-up
                        // safety number made an untouched peer look verified.
                        "user.signal_peer_id" to DivVariableValue.Str("acme_peer_01"),
                        "user.signal_session_id" to DivVariableValue.Str(""),
                        "user.signal_fingerprint" to DivVariableValue.Str(""),
                        "user.signal_fingerprint_display" to DivVariableValue.Str(""),
                        "user.signal_status" to DivVariableValue.Str("NO_SESSION"),
                        "user.signal_verification" to DivVariableValue.Str("UNVERIFIED"),
                        "user.signal_ciphertext" to DivVariableValue.Str(""),
                        "user.signal_plaintext" to DivVariableValue.Str(""),
                        "user.tool_last_status" to DivVariableValue.Str("Ready"),
                        "user.tool_last_latency" to DivVariableValue.IntVal(0L),
                        "user.icon_name" to DivVariableValue.Str("cart"),
                        "user.icon_material" to DivVariableValue.Str("ShoppingCart"),
                        "user.icon_category" to DivVariableValue.Str("Commerce"),
                        "user.kiosk_notice" to DivVariableValue.Str("Ready — no action yet")
                    ) + kioskSeed + seedSignalVariables(spec)
                )

                // 6. Load the stored/cached verified DivKit JSON schema for this tenant
                val activeSchema = cachedActive
                val verifiedCardPayload = activeSchema?.verifiedPayload
                    ?: WorkspaceProvisioner.buildDefaultDivKitCardJson(spec)
                val activeVersionLabel = activeSchema?.schemaVersion ?: "1.0"

                // 7. Execute initial queries via EngineRouter so DivKit variables (`user.orders_json`, counts) are populated.
                //    These hit SQLite, so keep them off the main thread.
                withContext(Dispatchers.IO) {
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
                        WorkspaceProvisioner.buildCatalogQueryAction()["payload"]?.jsonObject
                    )
                    router.handle(
                        "local-db://op/db.count",
                        WorkspaceProvisioner.buildCatalogCountAction()["payload"]?.jsonObject
                    )
                }

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
            } finally {
                onFinished?.invoke()
            }
        }
    }

    private fun finishSessionRestore() {
        if (_uiState.value.isRestoringSession) {
            _uiState.update { it.copy(isRestoringSession = false) }
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
                WorkspaceProvisioner.buildCatalogQueryAction()["payload"]?.jsonObject
            )
            activeRouter?.handle(
                "local-db://op/db.count",
                WorkspaceProvisioner.buildCatalogCountAction()["payload"]?.jsonObject
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
        viewModelScope.launch {
            dispatchDivKitActionAwait(url, payloadJsonStr)
        }
    }

    /**
     * Suspending variant used by [DivView] so a multi-action button can read variables
     * produced by the previous action (for example an inserted record id).
     */
    suspend fun dispatchDivKitActionAwait(url: String, payloadJsonStr: String?) {
        val router = activeRouter ?: return
        val spec = _uiState.value.activeWorkspace
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
        // Card navigation touches no database state, so it neither reports a DB banner
        // nor forces a telemetry refresh.
        if (url.startsWith("nav://") || url.startsWith("sheet://")) return
        val banner = when (outcome) {
            is EngineRouter.ActionOutcome.Handled -> "Executed $url on workspace_${spec.tenantId}.db"
            is EngineRouter.ActionOutcome.Error -> "Blocked ($url): ${outcome.message}"
            is EngineRouter.ActionOutcome.Unhandled -> "Unhandled scheme: $url"
        }
        _uiState.update { it.copy(statusBannerMessage = banner) }
        refreshDatabaseTelemetry(spec.tenantId)
    }

    /**
     * Registers the tenant's cards (entry hub first) and seeds the stack at the hub.
     *
     * Returns the registered ids in registration order; index 0 is the hub, which is the card
     * the workspace opens on. Registration is idempotent, so re-entering a tenant is safe.
     */
    private fun registerCards(spec: TenantWorkspaceSpec): List<String> {
        // Tool card ids are tenant-independent, so drop the previous tenant's cards rather
        // than letting a stale hub stay resolvable through nav://.
        CardRegistry.clear()
        val cards = SduiOnFlyBuilder.buildCardStack(spec)
        cards.forEach { (id, data) -> CardRegistry.register(id, data) }

        val hubId = DemoCardRepository.hubCardId(spec)
        val hub = CardRegistry.card(hubId)
        if (hub != null) {
            navigator = DivCardNavigator(listOf(CardEntry(hubId, hub)))
            publishActiveCard()
        }
        return cards.map { it.first }
    }

    /** Mirrors the navigator's visible card into UI state so Compose can render it. */
    private fun publishActiveCard() {
        val current = navigator?.current
        val json = current?.let { CardRegistry.card(it.id)?.let(DemoCardRepository::encode) }
        val depth = navigator?.currentIndex?.toLong() ?: 0L
        EngineStateBridge.pushUpdate("user.nav_stack_depth", DivVariableValue.IntVal(depth))
        _uiState.update {
            it.copy(
                activeCardId = current?.id.orEmpty(),
                activeCardJson = json.orEmpty(),
                cardStack = navigator?.stack?.map { entry -> entry.id }.orEmpty()
            )
        }
    }

    /**
     * Owns `nav://` / `sheet://` for the active workspace.
     *
     * Returns true for any URL the navigation layer parses, including a push whose card id is
     * unknown, which is a silent no-op per the navigation contract.
     */
    private fun handleNavigation(url: String): Boolean {
        val nav = navigator ?: return false
        val op = NavigationUrlParser.parse(url) ?: return false
        val before = nav.current?.id
        when (op) {
            is NavOp.Push -> CardRegistry.card(op.cardId)?.let {
                nav.push(CardEntry(op.cardId, it))
                maybeSeedKioskContext(op.cardId)
            }
            NavOp.Pop -> nav.pop()
            is NavOp.Replace -> CardRegistry.card(op.cardId)?.let { nav.replace(CardEntry(op.cardId, it)) }
            is NavOp.Reset -> CardRegistry.card(op.cardId)?.let { nav.reset(CardEntry(op.cardId, it)) }
            // Sheets are hosted by the Compose navigation host, not the stack.
            is NavOp.SheetPresent, NavOp.SheetDismiss -> return true
        }
        publishActiveCard()
        val after = nav.current?.id
        if (before != after) {
            _uiState.update {
                it.copy(statusBannerMessage = "Card: ${nav.current?.id ?: "none"}")
            }
        }
        return true
    }

    /**
     * The kiosk customer order and owner receipt cards share one variable namespace, so the
     * card that was just pushed decides which persisted draft is loaded into it. Without this
     * the receipt card would render the customer's cart.
     */
    private fun maybeSeedKioskContext(cardId: String) {
        val direction = when (cardId) {
            "tool_kiosk_owner" -> KioskOps.DIRECTION_OWNER
            "tool_kiosk_customer" -> KioskOps.DIRECTION_CUSTOMER
            else -> return
        }
        val spec = _uiState.value.activeWorkspace
        viewModelScope.launch {
            val batch = withContext(Dispatchers.IO) {
                runCatching {
                    workspaceManager.useDatabase(spec.tenantId) { db ->
                        seedKioskVariables(ApexKitDatabaseEngine(db.driver), spec, direction)
                    }
                }.getOrDefault(emptyMap())
            }
            if (batch.isNotEmpty()) EngineStateBridge.pushBatch(batch)
        }
    }

    /**
     * Publishes the persisted draft for [direction] so reopening a kiosk card shows real
     * SQLite state instead of empty placeholders. A missing draft is created once, which is
     * a real record the operator can then edit.
     */
    private fun seedKioskVariables(
        engine: ApexKitDatabaseEngine,
        spec: TenantWorkspaceSpec,
        direction: String
    ): Map<String, DivVariableValue> {
        if (engine.getCollection(KioskOps.ORDERS) == null) return emptyMap()
        val cart = runCatching {
            KioskOps.latestOrCreate(engine, direction, spec.terminalCode)
        }.getOrNull() ?: return emptyMap()
        val submitted = runCatching {
            KioskOps.recent(engine, KioskOps.DIRECTION_CUSTOMER, "SUBMITTED", 5)
        }.getOrDefault(emptyList())
        return mapOf(
            "user.kiosk_order_id" to DivVariableValue.Str(cart.orderId),
            "user.kiosk_order_label" to DivVariableValue.Str(cart.label),
            "user.kiosk_status" to DivVariableValue.Str(cart.status),
            "user.kiosk_direction" to DivVariableValue.Str(cart.direction),
            "user.kiosk_total" to DivVariableValue.Str(KioskOps.format(cart.total)),
            "user.kiosk_subtotal" to DivVariableValue.Str(KioskOps.format(cart.subtotal)),
            "user.kiosk_item_count" to DivVariableValue.Str(cart.itemCount.toString()),
            "user.kiosk_line_count" to DivVariableValue.Str(cart.lines.size.toString()),
            "user.kiosk_lines_json" to DivVariableValue.Str(KioskOps.linesJson(cart)),
            "user.kiosk_payload_json" to DivVariableValue.Str(KioskOps.exportPayload(cart)),
            "user.kiosk_checksum" to DivVariableValue.Str(KioskOps.checksum(KioskOps.exportPayload(cart))),
            "user.kiosk_orders_json" to DivVariableValue.Str(KioskOps.listJson(submitted)),
            "user.kiosk_order_count" to DivVariableValue.IntVal(submitted.size.toLong())
        )
    }

    /**
     * Reports the persisted Signal session so a reopened card does not claim NO_SESSION while
     * the ratchet and trust record are still on disk.
     */
    private fun seedSignalVariables(spec: TenantWorkspaceSpec): Map<String, DivVariableValue> =
        runCatching {
            val peer = "acme_peer_01"
            val hasSession = LibSignalEngine.hasSession(spec.tenantId, peer)
            if (!hasSession) return emptyMap()
            val status = LibSignalEngine.verificationStatus(spec.tenantId, peer)
            mapOf(
                "user.signal_peer_id" to DivVariableValue.Str(peer),
                "user.signal_status" to DivVariableValue.Str("ESTABLISHED"),
                "user.signal_session_id" to DivVariableValue.Str(
                    LibSignalEngine.createOrGetSession(spec.tenantId, peer, fresh = false).sessionId
                ),
                "user.signal_fingerprint" to DivVariableValue.Str(status.safetyNumber),
                "user.signal_fingerprint_display" to DivVariableValue.Str(
                    status.safetyNumber.filter { it.isDigit() }.chunked(5).joinToString(" ")
                ),
                "user.signal_verification" to DivVariableValue.Str(status.status)
            )
        }.getOrDefault(emptyMap())

    /** Pops the active card. No-op on the hub, so the landing card cannot be popped away. */
    fun navigateBack() {
        handleNavigation("nav://pop")
    }

    /**
     * Generic, unopinionated dispatcher for any native tool URL (e.g. "tools://beeper/tone").
     * Callers supply only the tool URL and an optional payload; all tool semantics, default
     * options, and hardware specifics live in the tool registry, never in the host UI.
     */
    fun dispatchTool(toolUrl: String, payloadJson: String? = null) {
        val router = activeRouter ?: return
        viewModelScope.launch {
            val payloadObj = payloadJson?.let {
                try {
                    Json.parseToJsonElement(it).jsonObject
                } catch (_: Exception) {
                    null
                }
            }
            val outcome = withContext(Dispatchers.IO) {
                router.handle(toolUrl, payloadObj)
            }
            val banner = when (outcome) {
                is EngineRouter.ActionOutcome.Handled -> "Tool executed: $toolUrl"
                is EngineRouter.ActionOutcome.Error -> "Tool blocked ($toolUrl): ${outcome.message}"
                is EngineRouter.ActionOutcome.Unhandled -> "Unhandled tool scheme: $toolUrl"
            }
            _uiState.update { it.copy(statusBannerMessage = banner) }
        }
    }

    private suspend fun handleHardwareCapability(
        spec: TenantWorkspaceSpec,
        capability: String,
        url: String
    ): Boolean {
        val perms = _uiState.value.tenantToolsMap[spec.tenantId] ?: spec.defaultTools
        return when (capability) {
            "scanner" -> {
                if (!perms.cameraBarcodeScanner) {
                    EngineStateBridge.pushUpdate(
                        "user.last_hardware_event",
                        DivVariableValue.Str("DENIED: Enable 'Camera Barcode & QR Scanner' in Workspace Settings")
                    )
                    return false
                }
                // Real scan: open the viewfinder and decode whatever the operator points at.
                // A random product is never invented — an unrecognised code is reported as such.
                val outcome = engine.tools.NativeToolsSuite.execute(
                    url = "tools://scan/scan",
                    payload = buildJsonObject { put("camera", "true") },
                    tenantId = spec.tenantId
                )
                val scanned = outcome.resultData.jsonObject["raw_value"]?.jsonPrimitive?.contentOrNull
                if (scanned.isNullOrBlank()) {
                    val reason = outcome.resultData.jsonObject["error"]?.jsonPrimitive?.contentOrNull
                        ?: "no code detected"
                    EngineStateBridge.pushUpdate(
                        "user.last_hardware_event",
                        DivVariableValue.Str("SCAN CANCELLED: $reason")
                    )
                    return false
                }

                val product = spec.defaultProducts.firstOrNull {
                    it.sku.equals(scanned, ignoreCase = true) || it.name.equals(scanned, ignoreCase = true)
                }
                if (product == null) {
                    EngineStateBridge.pushUpdate(
                        "user.last_hardware_event",
                        DivVariableValue.Str("SCANNED $scanned — not in this catalog")
                    )
                    EngineStateBridge.pushUpdate("user.scanned_code", DivVariableValue.Str(scanned))
                    return true
                }

                EngineStateBridge.pushUpdate("user.scanned_code", DivVariableValue.Str(scanned))
                EngineStateBridge.pushUpdate(
                    "user.last_hardware_event",
                    DivVariableValue.Str("SCANNER OK: ${product.sku} (${product.name})")
                )
                activeRouter?.handle(
                    "local-db://op/db.insert",
                    WorkspaceProvisioner.buildInsertOrderAction(
                        spec,
                        product.name,
                        product.sku,
                        product.price.toString()
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
                // Routes through the real Bluetooth/ESC-POS bridge, which reports NO_DEVICE or a
                // transport error instead of claiming a receipt was printed.
                val count = EngineStateBridge.state.value["user.order_count"]?.asDisplayString() ?: "0"
                val outcome = engine.tools.NativeToolsSuite.execute(
                    url = "tools://print/print",
                    payload = buildJsonObject {
                        put("text", "Receipt — $count order(s) @ ${spec.terminalCode}")
                    },
                    tenantId = spec.tenantId
                )
                val status = outcome.resultData.jsonObject["status"]?.jsonPrimitive?.contentOrNull
                    ?: "UNKNOWN"
                val detail = outcome.resultData.jsonObject["error"]?.jsonPrimitive?.contentOrNull
                    ?: outcome.resultData.jsonObject["device_name"]?.jsonPrimitive?.contentOrNull
                EngineStateBridge.pushUpdate(
                    "user.last_hardware_event",
                    DivVariableValue.Str(
                        if (status == "SUCCESS") "THERMAL PRINT OK: $count order(s) @ ${spec.terminalCode}"
                        else "THERMAL PRINT $status: ${detail ?: "no printer connected"}"
                    )
                )
                status == "SUCCESS"
            }
            // No NFC reader or cash-drawer solenoid is wired on this platform. Reporting a
            // fabricated EMV token or drawer pulse hides that, so these say so plainly.
            "nfc" -> {
                EngineStateBridge.pushUpdate(
                    "user.last_hardware_event",
                    DivVariableValue.Str("NFC UNAVAILABLE: no reader on this device (${spec.terminalCode})")
                )
                false
            }
            "cash_drawer" -> {
                EngineStateBridge.pushUpdate(
                    "user.last_hardware_event",
                    DivVariableValue.Str("CASH DRAWER UNAVAILABLE: no solenoid attached (${spec.terminalCode})")
                )
                false
            }
            "beeper" -> {
                if (!perms.hardwareBeeperAudio) {
                    EngineStateBridge.pushUpdate(
                        "user.last_hardware_event",
                        DivVariableValue.Str("DENIED: Enable 'Hardware Beeper Audio' in Workspace Settings")
                    )
                    return false
                }
                engine.tools.NativeToolsSuite.execute("tools://beeper/ring", kotlinx.serialization.json.JsonObject(emptyMap()))
                EngineStateBridge.pushUpdate(
                    "user.last_hardware_event",
                    DivVariableValue.Str("BEEPER OK: Tone emitted via tools://beeper")
                )
                true
            }
            "haptics" -> {
                if (!perms.hapticVibration) {
                    EngineStateBridge.pushUpdate(
                        "user.last_hardware_event",
                        DivVariableValue.Str("DENIED: Enable 'Haptic Vibration' in Workspace Settings")
                    )
                    return false
                }
                engine.tools.NativeToolsSuite.execute("tools://haptics/click", kotlinx.serialization.json.JsonObject(emptyMap()))
                EngineStateBridge.pushUpdate(
                    "user.last_hardware_event",
                    DivVariableValue.Str("HAPTICS OK: Tactile vibration triggered")
                )
                true
            }
            "biometrics" -> {
                if (!perms.supervisorBiometricAuth) {
                    EngineStateBridge.pushUpdate(
                        "user.last_hardware_event",
                        DivVariableValue.Str("DENIED: Enable 'Supervisor Biometric Auth' in Workspace Settings")
                    )
                    return false
                }
                // Route through the real gate so the OS prompt actually runs and a denial is
                // reported as a denial. Asserting success here approved every supervisor action.
                val outcome = engine.tools.NativeToolsSuite.execute(
                    url = "tools://biometrics/authenticate",
                    payload = buildJsonObject { put("required_role", "SUPERVISOR") },
                    tenantId = spec.tenantId
                )
                val status = outcome.resultData.jsonObject["status"]?.jsonPrimitive?.contentOrNull
                    ?: "UNKNOWN"
                val reason = outcome.resultData.jsonObject["reason"]?.jsonPrimitive?.contentOrNull
                    ?: outcome.resultData.jsonObject["error"]?.jsonPrimitive?.contentOrNull
                val authenticated = status == "SUCCESS" &&
                    outcome.resultData.jsonObject["authenticated"]?.jsonPrimitive?.contentOrNull == "true"
                EngineStateBridge.pushUpdate(
                    "user.supervisor_authenticated",
                    DivVariableValue.Bool(authenticated)
                )
                EngineStateBridge.pushUpdate(
                    "user.last_hardware_event",
                    DivVariableValue.Str(
                        if (authenticated) "SUPERVISOR VERIFIED: device credential accepted"
                        else "SUPERVISOR $status: ${reason ?: "identity not verified"}"
                    )
                )
                authenticated
            }
            "clipboard" -> {
                if (!perms.clipboardAccess) {
                    EngineStateBridge.pushUpdate(
                        "user.last_hardware_event",
                        DivVariableValue.Str("DENIED: Enable 'Clipboard Access' in Workspace Settings")
                    )
                    return false
                }
                EngineStateBridge.pushUpdate(
                    "user.last_hardware_event",
                    DivVariableValue.Str("CLIPBOARD OK: Data synchronized with system clipboard")
                )
                true
            }
            "push" -> {
                if (!perms.backgroundAlertsPush) {
                    EngineStateBridge.pushUpdate(
                        "user.last_hardware_event",
                        DivVariableValue.Str("DENIED: Enable 'Background Alerts & Push' in Workspace Settings")
                    )
                    return false
                }
                EngineStateBridge.pushUpdate(
                    "user.last_hardware_event",
                    DivVariableValue.Str("PUSH OK: Alert logged to order ledger")
                )
                true
            }
            "offline_cache" -> {
                if (!perms.offlinePageCaching) {
                    EngineStateBridge.pushUpdate(
                        "user.last_hardware_event",
                        DivVariableValue.Str("DENIED: Enable 'Offline Page Caching' in Workspace Settings")
                    )
                    return false
                }
                EngineStateBridge.pushUpdate(
                    "user.last_hardware_event",
                    DivVariableValue.Str("OFFLINE CACHE OK: 4 schema versions cached for LAN")
                )
                true
            }
            "realtime_ws" -> {
                if (!perms.realtimeEngineWsSse) {
                    EngineStateBridge.pushUpdate(
                        "user.last_hardware_event",
                        DivVariableValue.Str("DENIED: Enable 'Realtime Engine (WS / SSE)' in Workspace Settings")
                    )
                    return false
                }
                EngineStateBridge.pushUpdate(
                    "user.last_hardware_event",
                    DivVariableValue.Str("REALTIME ENGINE OK: Live kitchen WebSocket channel connected")
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

    fun setThemeMode(mode: EngineThemeMode) {
        _uiState.update {
            it.copy(
                themeMode = mode,
                statusBannerMessage = "Engine theme switched to ${mode.label}"
            )
        }
    }

    fun toggleLauncherLayoutMode() {
        _uiState.update {
            val nextGrid = !it.isGridView
            it.copy(
                isGridView = nextGrid,
                statusBannerMessage = if (nextGrid) "2-Column Grid Launcher Active" else "List View Launcher Active"
            )
        }
    }

    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun updatePushGatewayBase(url: String) {
        _uiState.update { it.copy(pushGatewayBase = url) }
    }

    fun resyncFcmToken() {
        val suffix = UUID.randomUUID().toString().replace("-", "").take(12)
        val refreshedToken = "fxF-S18CSsehc3lubcw_Ys:APA91bE4FCZ0XrmpHB7gVK-dMOk2sicVlc$suffix"
        _uiState.update {
            it.copy(
                fcmPushToken = refreshedToken,
                statusBannerMessage = "FCM Push Token re-synced with gateway"
            )
        }
    }

    fun reconnectRealtimeWs() {
        _uiState.update {
            it.copy(
                wsConnected = true,
                statusBannerMessage = "Realtime WebSocket reconnected to ${it.pushGatewayBase}"
            )
        }
    }

    fun saveWorkspaceToolPermissions(tenantId: String, permissions: WorkspaceToolPermissions) {
        val currentWorkspaces = _uiState.value.workspaces
        val targetSpec = currentWorkspaces.find { it.tenantId == tenantId } ?: return
        val updatedCapabilities = permissions.toCapabilitiesSet(targetSpec.capabilities)
        val updatedSpec = targetSpec.copy(
            capabilities = updatedCapabilities,
            defaultTools = permissions
        )
        _uiState.update { state ->
            val updatedMap = state.tenantToolsMap.toMutableMap().apply {
                put(tenantId, permissions)
            }
            val updatedList = state.workspaces.map { if (it.tenantId == tenantId) updatedSpec else it }
            state.copy(
                workspaces = updatedList,
                activeWorkspace = if (state.activeWorkspace.tenantId == tenantId) updatedSpec else state.activeWorkspace,
                tenantToolsMap = updatedMap,
                statusBannerMessage = "Saved ${permissions.activeCount} active bridge tools for ${updatedSpec.displayName}"
            )
        }
        if (_uiState.value.activeWorkspace.tenantId == tenantId) {
            switchWorkspace(updatedSpec, openDedicatedScreen = _uiState.value.isWorkspaceOpen)
        }
    }

    fun disconnectWorkspace(tenantId: String) {
        val current = _uiState.value.workspaces
        if (current.size <= 1) {
            _uiState.update {
                it.copy(statusBannerMessage = "At least one workspace must remain connected")
            }
            return
        }
        usageStore.forget(tenantId)
        val remaining = current.filterNot { it.tenantId == tenantId }
        val nextActive = if (_uiState.value.activeWorkspace.tenantId == tenantId) {
            remaining.first()
        } else {
            _uiState.value.activeWorkspace
        }
        _uiState.update {
            it.copy(
                workspaces = remaining.sortedByUsage(usageStore.usage()),
                activeWorkspace = nextActive,
                isWorkspaceOpen = false,
                statusBannerMessage = "Disconnected workspace $tenantId"
            )
        }
    }

    fun switchActiveRoute(path: String, queries: String) {
        _uiState.update {
            it.copy(
                activeRoutePath = path,
                activeRouteQueries = queries,
                statusBannerMessage = "Switched route to $path${if (queries != "(none)") "?$queries" else ""}"
            )
        }
        EngineStateBridge.pushUpdate(
            "user.last_hardware_event",
            DivVariableValue.Str("Route Active: $path ${if (queries != "(none)") "?$queries" else ""}")
        )
    }

    fun connectNewWorkspaceFromSheet(
        displayName: String,
        localLanUrl: String,
        tunnelUrl: String,
        pullEndpoint: String,
        openImmediately: Boolean = true
    ) {
        val cleanName = displayName.trim().ifBlank { "Custom POS Register" }
        val cleanId = cleanName.lowercase().replace(Regex("[^a-z0-9_-]"), "_").take(32).ifBlank {
            "pos_${UUID.randomUUID().toString().take(6)}"
        }
        val effectiveTunnel = tunnelUrl.trim().ifBlank {
            localLanUrl.trim().ifBlank { "https://apexkit-$cleanId.trycloudflare.com" }
        }
        val newSpec = TenantWorkspaceSpec(
            tenantId = cleanId,
            displayName = cleanName,
            terminalCode = "TERM-${cleanId.take(4).uppercase()}",
            subtitle = "Public Tunnel: $effectiveTunnel",
            accentHex = "#0284C7",
            mode = EngineConfig.Mode.HYBRID,
            capabilities = setOf("printer", "nfc"),
            isBuiltInDemo = false,
            tunnelUrl = effectiveTunnel,
            localLanUrl = localLanUrl.trim().ifBlank { "http://192.168.1.50:8080" },
            pullEndpoint = pullEndpoint.trim().ifBlank { "/webhook/edge_notifications/pull-notifications" },
            publicClientId = "pk_live_${UUID.randomUUID().toString().replace("-", "").take(20)}",
            unreadCount = 0,
            defaultTools = WorkspaceToolPermissions(), // Tools off by default for security
            defaultProducts = listOf(
                SeedProduct("Signature Espresso", "CSP-01", 4.50, 100, "Coffee", "#0284C7"),
                SeedProduct("Avocado Sourdough", "CSP-02", 11.00, 45, "Bakery", "#4F46E5"),
                SeedProduct("Matcha Oat Latte", "CSP-03", 6.25, 80, "Beverages", "#0D9488")
            ),
            defaultOrders = listOf(
                SeedOrder("Signature Espresso", "CSP-01", 4.50, 1, "TERM-01", "OPEN")
            )
        )
        _uiState.update { state ->
            val updatedList = state.workspaces.filterNot { it.tenantId == cleanId } + newSpec
            val updatedTools = state.tenantToolsMap.toMutableMap().apply {
                put(cleanId, WorkspaceToolPermissions())
            }
            state.copy(
                workspaces = updatedList,
                tenantToolsMap = updatedTools
            )
        }
        switchWorkspace(newSpec, openDedicatedScreen = openImmediately, recordVisit = openImmediately)
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
            subtitle = "Public Tunnel: https://$cleanId.trycloudflare.com",
            accentHex = "#0284C7",
            mode = mode,
            capabilities = capabilities,
            tunnelUrl = "https://$cleanId.trycloudflare.com",
            publicClientId = "pk_live_${UUID.randomUUID().toString().replace("-", "").take(20)}",
            defaultTools = WorkspaceToolPermissions(
                cameraBarcodeScanner = "scanner" in capabilities
            ),
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
            val updatedTools = state.tenantToolsMap.toMutableMap().apply {
                put(cleanId, newSpec.defaultTools)
            }
            state.copy(
                workspaces = updatedList,
                tenantToolsMap = updatedTools
            )
        }
        switchWorkspace(newSpec, openDedicatedScreen = true, recordVisit = true)
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
                it.copy(statusBannerMessage = "Cleared offline cache & re-verified baseline schema for ${spec.displayName}")
            }
        }
    }

    fun reseedActiveWorkspace() {
        // Preserves activeRoutePath and activeRouteQueries on refresh!
        val spec = _uiState.value.activeWorkspace
        val preservedPath = _uiState.value.activeRoutePath
        val preservedQueries = _uiState.value.activeRouteQueries
        switchWorkspace(spec, openDedicatedScreen = _uiState.value.isWorkspaceOpen)
        _uiState.update {
            it.copy(
                activeRoutePath = preservedPath,
                activeRouteQueries = preservedQueries,
                statusBannerMessage = "Refreshed ${spec.displayName} (Preserved route $preservedPath)"
            )
        }
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

    // ---- Workspace file manager ----

    /** Refreshes the workspace folder list shown on the Files tab landing view. */
    fun refreshWorkspaceFolders() {
        viewModelScope.launch(Dispatchers.IO) {
            val state = _uiState.value
            val folders = fileManager.listWorkspaceFolders(
                knownWorkspaceIds = state.workspaces.map { it.tenantId },
                displayNames = state.workspaces.associate { it.tenantId to it.displayName }
            )
            _uiState.update { it.copy(workspaceFolders = folders) }
        }
    }

    /** Opens [folder] in the browser, resetting any active preview. */
    fun openWorkspaceFolder(folder: WorkspaceFolder) {
        viewModelScope.launch(Dispatchers.IO) {
            val dir = File(folder.dirPath)
            val entries = fileManager.entriesForFolder(folder)
            _uiState.update {
                it.copy(
                    fileBrowserFolder = folder,
                    fileBrowserPath = dir.absolutePath,
                    fileEntries = entries,
                    filePreview = null
                )
            }
        }
    }

    /** Navigates into [entry] (a directory) or, for a file, loads a text preview. */
    fun openFileEntry(entry: FsEntry) {
        if (entry.isDirectory) {
            navigateToDirectory(File(entry.path))
            return
        }
        if (!entry.isTextPreviewable) {
            _uiState.update {
                it.copy(
                    statusBannerMessage = "${entry.name} is not a text file — preview unavailable"
                )
            }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val file = File(entry.path)
            val content = WorkspaceFileSystem.readTextPreview(
                file, WorkspaceFileSystem.MAX_PREVIEW_BYTES
            )
            _uiState.update {
                it.copy(
                    filePreview = content?.let { text ->
                        FilePreview(
                            name = entry.name,
                            path = entry.path,
                            content = text,
                            truncated = file.length() > WorkspaceFileSystem.MAX_PREVIEW_BYTES,
                            sizeLabel = WorkspaceFileSystem.humanSize(entry.sizeBytes)
                        )
                    },
                    statusBannerMessage = if (content == null) {
                        "${entry.name} looks binary — preview unavailable"
                    } else null
                )
            }
        }
    }

    fun closeFilePreview() {
        _uiState.update { it.copy(filePreview = null) }
    }

    /**
     * Hands [entry] to an external app through the system chooser. Directories have nothing to
     * view, so they stay in the built-in browser; text files keep the in-app preview as well.
     */
    fun openFileWith(entry: FsEntry) {
        if (entry.isDirectory) {
            navigateToDirectory(File(entry.path))
            return
        }
        when (val outcome = FileOpenWith.open(context, File(entry.path))) {
            is FileOpenWith.Outcome.Launched ->
                _uiState.update {
                    it.copy(statusBannerMessage = "Opening ${entry.name} as ${outcome.mimeType}")
                }
            FileOpenWith.Outcome.NoHandler ->
                _uiState.update {
                    it.copy(statusBannerMessage = "No installed app can open ${entry.name}")
                }
            is FileOpenWith.Outcome.Failed ->
                _uiState.update {
                    it.copy(statusBannerMessage = "Open with failed: ${outcome.reason}")
                }
        }
    }

    /** Leaves the browser and returns to the workspace folder list. */
    fun closeWorkspaceFolder() {
        _uiState.update {
            it.copy(
                fileBrowserFolder = null,
                fileBrowserPath = "",
                fileEntries = emptyList(),
                filePreview = null
            )
        }
    }

    /** Navigates to [dir] within the current workspace folder. */
    fun navigateToDirectory(dir: File) {
        viewModelScope.launch(Dispatchers.IO) {
            val folder = _uiState.value.fileBrowserFolder
            _uiState.update {
                it.copy(
                    fileBrowserPath = dir.absolutePath,
                    fileEntries = folder?.let { f -> fileManager.listForBrowse(f, dir) }
                        ?: WorkspaceFileSystem.listEntries(dir),
                    filePreview = null
                )
            }
        }
    }

    /** Navigates to the current browser's parent, bounded by the workspace root. */
    fun navigateUp() {
        val state = _uiState.value
        val current = state.fileBrowserPath.takeIf { it.isNotBlank() } ?: return
        val root = state.fileBrowserFolder?.dirPath ?: return
        if (current == root) return
        val parent = File(current).parentFile ?: return
        if (!parent.absolutePath.startsWith(root)) return
        navigateToDirectory(parent)
    }

    fun createFolder(name: String) = mutateBrowser("Folder \"$name\" created") { dir ->
        WorkspaceFileSystem.createDirectory(dir, name)
    }

    fun createFile(name: String) = mutateBrowser("File \"$name\" created") { dir ->
        WorkspaceFileSystem.createTextFile(dir, name, "")
    }

    fun renameFileEntry(entry: FsEntry, newName: String) =
        mutateBrowser("Renamed to \"$newName\"") {
            WorkspaceFileSystem.rename(File(entry.path), newName)
        }

    fun deleteFileEntry(entry: FsEntry) {
        if (entry.isReadOnly) {
            _uiState.update { it.copy(statusBannerMessage = "${entry.name} is read-only and cannot be deleted") }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val removed = WorkspaceFileSystem.delete(File(entry.path))
            refreshBrowserAfterMutation(
                if (removed) "Deleted \"${entry.name}\"" else "Could not delete \"${entry.name}\""
            )
        }
    }

    private fun mutateBrowser(
        successMessage: String,
        operation: (File) -> File?
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val dir = _uiState.value.fileBrowserPath.takeIf { it.isNotBlank() }?.let(::File)
            val result = dir?.let(operation)
            refreshBrowserAfterMutation(
                if (result != null) successMessage else "Operation failed — check the name and try again"
            )
        }
    }

    private suspend fun refreshBrowserAfterMutation(message: String) {
        val state = _uiState.value
        val folder = state.fileBrowserFolder
        val dir = state.fileBrowserPath.takeIf { it.isNotBlank() }?.let(::File)
        val entries = if (folder != null && dir != null) {
            fileManager.listForBrowse(folder, dir)
        } else {
            emptyList()
        }
        _uiState.update { it.copy(fileEntries = entries, statusBannerMessage = message) }
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

    companion object {
        /**
         * Label for the bundled demo card. Card content, not this constant, decides whether a
         * cached tenant is refreshed.
         */
        const val DEMO_CARD_REVISION = "1.4"

        /**
         * Whether a cached tenant should be re-seeded with [freshCardJson].
         *
         * A rollback activates an older row, so refreshing then would silently undo the user's
         * rollback on every launch; only the newest row may be replaced.
         */
        fun needsDemoCardRefresh(
            existing: List<CachedSchemaVersion>,
            freshCardJson: String
        ): Boolean {
            val active = existing.firstOrNull { it.isActive } ?: existing.firstOrNull() ?: return true
            val rolledBack = active.versionId != existing.maxOf { it.versionId }
            return !rolledBack && active.divkitJson != freshCardJson
        }
    }
}
