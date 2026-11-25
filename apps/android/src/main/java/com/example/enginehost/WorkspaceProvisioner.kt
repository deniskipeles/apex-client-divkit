package com.example.enginehost

import engine.config.EngineConfig
import engine.crypto.PayloadVerifier
import engine.crypto.SigningKeyPair
import engine.db.EngineRegistry
import engine.db.OpResult
import engine.db.WorkspaceDatabase
import com.example.enginehost.demo.DemoCollectionRepository
import kotlinx.serialization.json.*

data class WorkspaceToolPermissions(
    val hapticVibration: Boolean = false,
    val hardwareBeeperAudio: Boolean = false,
    val cameraBarcodeScanner: Boolean = false,
    val supervisorBiometricAuth: Boolean = false,
    val clipboardAccess: Boolean = false,
    val backgroundAlertsPush: Boolean = false,
    val offlinePageCaching: Boolean = false,
    val realtimeEngineWsSse: Boolean = false
) {
    val activeCount: Int
        get() = listOf(
            hapticVibration,
            hardwareBeeperAudio,
            cameraBarcodeScanner,
            supervisorBiometricAuth,
            clipboardAccess,
            backgroundAlertsPush,
            offlinePageCaching,
            realtimeEngineWsSse
        ).count { it }

    fun toCapabilitiesSet(baseCapabilities: Set<String> = emptySet()): Set<String> = buildSet {
        addAll(baseCapabilities)
        if (cameraBarcodeScanner) add("scanner") else remove("scanner")
        if (hapticVibration) add("haptics")
        if (hardwareBeeperAudio) add("beeper")
        if (supervisorBiometricAuth) add("biometrics")
        if (clipboardAccess) add("clipboard")
        if (backgroundAlertsPush) add("push")
        if (offlinePageCaching) add("offline_cache")
        if (realtimeEngineWsSse) add("realtime_ws")
    }
}

data class TenantWorkspaceSpec(
    val tenantId: String,
    val displayName: String,
    val terminalCode: String,
    val subtitle: String,
    val accentHex: String,
    val mode: EngineConfig.Mode,
    val capabilities: Set<String>,
    val defaultProducts: List<SeedProduct> = emptyList(),
    val defaultOrders: List<SeedOrder> = emptyList(),
    /** Collection documents shipped by the workspace package (schemas + seed rows). */
    val collections: List<JsonObject> = emptyList(),
    val isBuiltInDemo: Boolean = false,
    val tunnelUrl: String = "https://apexkit-pos.trycloudflare.com",
    val localLanUrl: String = "http://192.168.1.42:8080",
    val pullEndpoint: String = "/webhook/edge_notifications/pull-notifications",
    val publicClientId: String = "pk_live_bdbc75560c43b6605370bf4a",
    val unreadCount: Int = 0,
    val defaultTools: WorkspaceToolPermissions = WorkspaceToolPermissions(),
    /** Resource root for this workspace's DivKit demo cards. */
    val demoRoot: String = "demo/apex",
    /** Tool card names in render order; null uses the default showcase order. */
    val toolCardOrder: List<String>? = null
)

data class SeedProduct(
    val name: String,
    val sku: String,
    val price: Double,
    val stock: Int,
    val category: String,
    val colorHex: String
)

data class SeedOrder(
    val itemName: String,
    val sku: String,
    val price: Double,
    val qty: Int,
    val terminalId: String,
    val status: String
)

object WorkspaceProvisioner {

    /**
     * The single built-in demo tenant. It exists to showcase the DivKit SDUI surface
     * (live charts, json_list, hardware testers) together with the full unopinionated
     * native tool suite. Additional tenants are connected at runtime through the
     * Connect Workspace flow rather than being shipped as static demos.
     */
    val defaultWorkspaces: List<TenantWorkspaceSpec>
        get() {
            val report = engine.workspace.WorkspaceRepository.loadAll()
            lastLoadFailure = report.failures.firstOrNull()
                ?.let { "${it.id}: ${it.reason}" }
                .orEmpty()
            return report.bundles.map { it.toSpec() }
        }

    /** Why the last [defaultWorkspaces] scan came back empty, or "" when everything loaded. */
    var lastLoadFailure: String = ""
        private set

    /**
     * Stand-in when a build can load no workspace at all — a stale encryption key, a missing or
     * corrupt bundle. `workspaces.first()` used to throw before the first frame was drawn, so the
     * app died instead of explaining itself.
     */
    val emptyWorkspace: TenantWorkspaceSpec = TenantWorkspaceSpec(
        tenantId = "",
        displayName = "No workspace",
        terminalCode = "",
        subtitle = "",
        accentHex = "#0F766E",
        mode = EngineConfig.Mode.HYBRID,
        capabilities = emptySet()
    )

    fun firstOrEmpty(workspaces: List<TenantWorkspaceSpec>): TenantWorkspaceSpec =
        workspaces.firstOrNull() ?: emptyWorkspace

    /** Maps a data-defined workspace onto the runtime spec the engine already understands. */
    fun engine.workspace.WorkspaceBundle.toSpec(): TenantWorkspaceSpec {
        val toolsJson = tools
        fun flag(name: String) = (toolsJson[name] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: false
        val urlsJson = urls
        return TenantWorkspaceSpec(
            tenantId = id,
            displayName = title,
            terminalCode = terminal,
            subtitle = subtitle,
            accentHex = accent,
            mode = runCatching { EngineConfig.Mode.valueOf(mode.uppercase()) }.getOrDefault(EngineConfig.Mode.HYBRID),
            capabilities = capabilities.toSet(),
            defaultProducts = emptyList(),
            defaultOrders = emptyList(),
            collections = collections,
            isBuiltInDemo = origin == "builtin",
            tunnelUrl = (urlsJson["tunnel"] as? JsonPrimitive)?.contentOrNull ?: "",
            localLanUrl = (urlsJson["lan"] as? JsonPrimitive)?.contentOrNull ?: "",
            publicClientId = (urlsJson["client_id"] as? JsonPrimitive)?.contentOrNull
                ?: "pk_live_bdbc75560c43b6605370bf4a",
            unreadCount = unread,
            defaultTools = WorkspaceToolPermissions(
                hapticVibration = flag("hapticVibration"),
                hardwareBeeperAudio = flag("hardwareBeeperAudio"),
                cameraBarcodeScanner = flag("cameraBarcodeScanner"),
                supervisorBiometricAuth = flag("supervisorBiometricAuth"),
                clipboardAccess = flag("clipboardAccess"),
                backgroundAlertsPush = flag("backgroundAlertsPush"),
                offlinePageCaching = flag("offlinePageCaching"),
                realtimeEngineWsSse = flag("realtimeEngineWsSse")
            ),
            demoRoot = "demo/$id",
            toolCardOrder = cardNames
        )
    }

    /**
     * Creates signed [EngineConfig] and verifies it through [PayloadVerifier]
     * so `EngineConfig.fromVerifiedPayload` is always used according to the security invariant.
     */
    suspend fun createVerifiedConfig(
        spec: TenantWorkspaceSpec,
        keyPair: SigningKeyPair
    ): EngineConfig {
        val rawConfigJson = buildJsonObject {
            put("tenant_id", spec.tenantId)
            put("schema_version", "1.0")
            put("mode", spec.mode.name)
            put("edge_endpoint", "https://edge.sdui-pos.internal/${spec.tenantId}")
            put("verify_public_key", keyPair.publicKeyDerBase64)
            putJsonArray("capabilities") {
                spec.capabilities.forEach { add(it) }
            }
            put("sync_interval_ms", 900_000L)
            put("db_schema_version", 1)
        }.toString()

        val signedEnvelope = PayloadVerifier.createSignedEnvelope(
            tenant = spec.tenantId,
            schemaVersion = "1.0",
            payloadJson = rawConfigJson,
            keyPair = keyPair
        )
        val verifier = PayloadVerifier(
            expectedTenant = spec.tenantId,
            publicKeyDer = keyPair.publicKeyDerBase64
        )
        val verifiedPayload = verifier.verify(signedEnvelope)
        return EngineConfig.fromVerifiedPayload(verifiedPayload)
    }

    /**
     * Registers the tenant's `orders` and `inventory` collections in the ApexKit
     * schema and seeds them through `EngineRegistry`, so `db.count`/`db.insert`/
     * `db.query` resolve against the generic `collections`/`records` store.
     */
    fun ensureTenantSchemaAndSeed(
        db: WorkspaceDatabase,
        spec: TenantWorkspaceSpec,
        registry: EngineRegistry
    ) {
        val ws = spec.tenantId
        val apexEngine = engine.db.ApexKitDatabaseEngine(db.driver)

        // Collections are INSERT OR REPLACE'd by import, which would cascade-delete
        // existing records, so only import when the tenant schema is absent.
        val demoCollections = spec.collections.takeIf { it.isNotEmpty() }
            ?: DemoCollectionRepository.load(spec.tenantId)?.let { loaded ->
                loaded.schemaPayload["schema"]!!.jsonObject["collections"]!!.jsonArray
                    .mapNotNull { it as? JsonObject }
            }
        val collectionNames = demoCollections?.mapNotNull { (it["name"] as? JsonPrimitive)?.contentOrNull }.orEmpty()
        val missingCollection = demoCollections != null &&
            collectionNames.any { apexEngine.getCollection(it) == null }
        if (demoCollections != null && missingCollection) {
            registry.execute(
                "db.import_schema",
                db,
                ws,
                buildJsonObject {
                    putJsonObject("schema") {
                        putJsonArray("collections") { demoCollections.forEach { add(it) } }
                    }
                }
            )
        }

        // Workspaces that declare collection seeds need no Kotlin-side demo data.
        if (demoCollections != null) return

        val invCount = (registry.execute(
            "db.count",
            db,
            ws,
            buildJsonObject { put("table", "inventory") }
        ) as? OpResult.Count)?.n ?: 0L

        if (invCount == 0L) {
            spec.defaultProducts.forEach { prod ->
                registry.execute(
                    "db.insert",
                    db,
                    ws,
                    buildJsonObject {
                        put("table", "inventory")
                        put("idempotent", true)
                        put("enqueue_sync", false)
                        putJsonObject("values") {
                            put("item_name", prod.name)
                            put("sku", prod.sku)
                            put("price", prod.price)
                            put("price_display", String.format(java.util.Locale.US, "%.2f", prod.price))
                            put("stock", prod.stock)
                            put("category", prod.category)
                        }
                    }
                )
            }
        }

        val ordCount = (registry.execute(
            "db.count",
            db,
            ws,
            buildJsonObject { put("table", "orders") }
        ) as? OpResult.Count)?.n ?: 0L

        if (ordCount == 0L) {
            spec.defaultOrders.forEach { ord ->
                registry.execute(
                    "db.insert",
                    db,
                    ws,
                    buildJsonObject {
                        put("table", "orders")
                        put("enqueue_sync", true)
                        putJsonObject("values") {
                            put("item_name", ord.itemName)
                            put("sku", ord.sku)
                            put("price", ord.price)
                            put("qty", ord.qty)
                            put("terminal_id", ord.terminalId)
                            put("status", ord.status)
                        }
                    }
                )
            }
        }
    }

    /**
     * Generates the route-index-0 DivKit card for a tenant POS workspace.
     *
     * Layout comes from `resources/demo/apex/card.json`; the tool catalog spliced into it links to
     * each tool card through `nav://push`. Tool markup lives in `resources/demo/apex/tools` and is
     * registered separately, so the landing card stays small.
     */
    fun buildDefaultDivKitCardJson(spec: TenantWorkspaceSpec): String =
        SduiOnFlyBuilder.buildShowcaseCard(spec)

    /** Every card for the tenant, entry hub first — the tool cards the hub links to. */
    fun buildDivKitCardStack(spec: TenantWorkspaceSpec): List<Pair<String, JsonObject>> =
        SduiOnFlyBuilder.buildCardStack(spec)

    fun buildInsertOrderAction(
        spec: TenantWorkspaceSpec,
        itemNameExpr: String,
        sku: String,
        priceExpr: String
    ): JsonObject = buildJsonObject {
        put("log_id", "insert_order")
        put("url", "local-db://op/db.insert")
        putJsonObject("payload") {
            put("table", "orders")
            putJsonObject("values") {
                put("item_name", itemNameExpr)
                put("sku", sku)
                put("price", priceExpr.toDoubleOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(priceExpr))
                put("qty", 1)
                put("terminal_id", spec.terminalCode)
                put("status", if (spec.mode == EngineConfig.Mode.OFFLINE) "QUEUED_OFFLINE" else "OPEN")
            }
            putJsonObject("output") {
                put("id", "user.last_order_id")
            }
        }
    }

    fun buildQueryOrdersAction(): JsonObject = buildJsonObject {
        put("log_id", "refresh_orders_list")
        put("url", "local-db://op/db.query")
        putJsonObject("payload") {
            put("table", "orders")
            put("order_by", "id")
            put("limit", 50)
            put("json_list_var", "user.orders_json")
            putJsonObject("output") {
                put("_json", "user.orders_json")
            }
        }
    }

    fun buildCountOrdersAction(): JsonObject = buildJsonObject {
        put("log_id", "count_orders")
        put("url", "local-db://op/db.count")
        putJsonObject("payload") {
            put("table", "orders")
            putJsonObject("output") {
                put("count", "user.order_count")
            }
        }
    }

    fun buildCatalogCountAction(): JsonObject = buildJsonObject {
        put("log_id", "count_catalog")
        put("url", "local-db://op/db.count")
        putJsonObject("payload") {
            put("table", "inventory")
            putJsonObject("output") {
                put("count", "user.catalog_count")
            }
        }
    }

    fun buildCatalogQueryAction(): JsonObject = buildJsonObject {
        put("log_id", "query_catalog")
        put("url", "local-db://op/db.query")
        putJsonObject("payload") {
            put("table", "inventory")
            put("order_by", "id")
            put("limit", 50)
            put("json_list_var", "user.catalog_json")
            putJsonObject("output") {
                put("_json", "user.catalog_json")
            }
        }
    }


}
