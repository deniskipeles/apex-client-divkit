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
    val defaultProducts: List<SeedProduct>,
    val defaultOrders: List<SeedOrder>,
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
    val defaultWorkspaces: List<TenantWorkspaceSpec> = listOf(
        TenantWorkspaceSpec(
            tenantId = "acme_pos",
            displayName = "Apex POS Terminal",
            terminalCode = "POS-TERM-01",
            subtitle = "DivKit SDUI Showcase • Full Native Tool Suite",
            accentHex = "#0284C7",
            mode = EngineConfig.Mode.HYBRID,
            capabilities = setOf("scanner", "printer", "nfc", "cash_drawer", "haptics", "beeper"),
            isBuiltInDemo = true,
            tunnelUrl = "https://apex-terminal.local/",
            localLanUrl = "http://192.168.1.10:8080",
            publicClientId = "pk_live_bdbc75560c43b6605370bf4a",
            unreadCount = 4,
            defaultTools = WorkspaceToolPermissions(
                hapticVibration = true,
                hardwareBeeperAudio = true,
                cameraBarcodeScanner = true,
                supervisorBiometricAuth = true,
                clipboardAccess = true,
                backgroundAlertsPush = true,
                offlinePageCaching = true,
                realtimeEngineWsSse = true
            ),
            defaultProducts = listOf(
                SeedProduct("Wagyu Smash Burger", "ACM-101", 16.50, 42, "Entrees", "#0284C7"),
                SeedProduct("Truffle Parmesan Fries", "ACM-102", 8.00, 65, "Sides", "#0D9488"),
                SeedProduct("Artisan Cold Brew", "ACM-204", 5.50, 88, "Beverages", "#4F46E5"),
                SeedProduct("Wood-Fired Margherita", "ACM-109", 18.00, 29, "Pizza", "#D97706")
            ),
            defaultOrders = listOf(
                SeedOrder("Wagyu Smash Burger", "ACM-101", 16.50, 1, "POS-TERM-01", "PAID"),
                SeedOrder("Truffle Parmesan Fries", "ACM-102", 8.00, 2, "POS-TERM-01", "OPEN"),
                SeedOrder("Artisan Cold Brew", "ACM-204", 5.50, 1, "POS-TERM-01", "OPEN")
            )
        ),
        TenantWorkspaceSpec(
            tenantId = "apex_kiosk",
            displayName = "APEX KIOSK",
            terminalCode = "KSK-001",
            subtitle = "Customer & owner receipt workflows • QR / LAN",
            accentHex = "#7C3AED",
            mode = EngineConfig.Mode.HYBRID,
            capabilities = setOf("scanner", "printer", "haptics", "beeper", "clipboard"),
            isBuiltInDemo = true,
            tunnelUrl = "https://apex-kiosk.local/",
            localLanUrl = "http://192.168.1.20:8089",
            publicClientId = "pk_live_bdbc75560c43b6605370bf4a",
            unreadCount = 2,
            defaultTools = WorkspaceToolPermissions(
                hapticVibration = true,
                hardwareBeeperAudio = true,
                cameraBarcodeScanner = true,
                clipboardAccess = true,
                backgroundAlertsPush = true,
                offlinePageCaching = true,
                realtimeEngineWsSse = true
            ),
            defaultProducts = listOf(
                SeedProduct("Mineral Water", "P1001", 1.50, 120, "Drinks", "#0EA5E9"),
                SeedProduct("Sandwich", "P1008", 4.00, 40, "Food", "#F59E0B"),
                SeedProduct("Coffee", "P1012", 2.50, 80, "Drinks", "#8B5CF6"),
                SeedProduct("Chocolate Bar", "P1015", 1.75, 60, "Snacks", "#EC4899"),
                SeedProduct("Fresh Juice", "P1020", 3.25, 35, "Drinks", "#10B981"),
                SeedProduct("Croissant", "P1024", 2.75, 25, "Bakery", "#F97316")
            ),
            defaultOrders = listOf(
                SeedOrder("Mineral Water", "P1001", 1.50, 2, "KSK-001", "OPEN"),
                SeedOrder("Sandwich", "P1008", 4.00, 1, "KSK-001", "PAID")
            ),
            demoRoot = "demo/apex-kiosk",
            toolCardOrder = listOf("kiosk_customer", "kiosk_owner", "kiosk_transfer", "kiosk_lan")
        )
    )

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
        val demoCollections = DemoCollectionRepository.load()
        val missingCollection = demoCollections?.names?.any { apexEngine.getCollection(it) == null } == true
        if (demoCollections != null && missingCollection) {
            registry.execute("db.import_schema", db, ws, demoCollections.schemaPayload)
        }

        // Check if inventory is empty; if so, seed via EngineRegistry
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
