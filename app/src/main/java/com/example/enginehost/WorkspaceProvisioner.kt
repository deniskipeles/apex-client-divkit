package com.example.enginehost

import engine.config.EngineConfig
import engine.crypto.PayloadVerifier
import engine.crypto.SigningKeyPair
import engine.db.EngineRegistry
import engine.db.OpResult
import engine.db.WorkspaceDatabase
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
    val defaultTools: WorkspaceToolPermissions = WorkspaceToolPermissions()
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
        if (apexEngine.getCollection("orders") == null) {
            registry.execute(
                "db.import_schema",
                db,
                ws,
                buildJsonObject {
                    putJsonObject("schema") {
                        putJsonArray("collections") {
                            add(collectionJson(id = 1L, name = "orders", fields = ORDERS_FIELDS))
                            add(collectionJson(id = 2L, name = "inventory", fields = INVENTORY_FIELDS))
                        }
                    }
                }
            )
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
     * Generates the default DivKit SDUI JSON card for a tenant POS workspace.
     */
    fun buildDefaultDivKitCardJson(spec: TenantWorkspaceSpec): String {
        val root = buildJsonObject {
            putJsonObject("card") {
                put("log_id", "pos_terminal_${spec.tenantId}")
                putJsonArray("variables") {
                    addJsonObject {
                        put("name", "user.workspace_title")
                        put("type", "string")
                        put("value", "${spec.displayName} (${spec.terminalCode})")
                    }
                    addJsonObject {
                        put("name", "user.custom_item_name")
                        put("type", "string")
                        put("value", "Express Combo #9")
                    }
                    addJsonObject {
                        put("name", "user.custom_item_price")
                        put("type", "string")
                        put("value", "12.50")
                    }
                    addJsonObject {
                        put("name", "user.last_hardware_event")
                        put("type", "string")
                        put("value", "Ready (${spec.capabilities.joinToString("/")})")
                    }
                    addJsonObject {
                        put("name", "user.sync_status")
                        put("type", "string")
                        put("value", "ready")
                    }
                    addJsonObject {
                        put("name", "user.engine_error")
                        put("type", "string")
                        put("value", "None")
                    }
                    addJsonObject {
                        put("name", "user.order_count")
                        put("type", "integer")
                        put("value", spec.defaultOrders.size)
                    }
                    addJsonObject {
                        put("name", "user.inventory_count")
                        put("type", "integer")
                        put("value", spec.defaultProducts.size)
                    }
                    addJsonObject {
                        put("name", "user.last_order_id")
                        put("type", "integer")
                        put("value", spec.defaultOrders.size)
                    }
                    addJsonObject {
                        put("name", "user.scanned_code")
                        put("type", "string")
                        put("value", "9780201633610")
                    }
                    addJsonObject {
                        put("name", "user.scanned_format")
                        put("type", "string")
                        put("value", "EAN_13")
                    }
                    addJsonObject {
                        put("name", "user.scanned_type")
                        put("type", "string")
                        put("value", "1D")
                    }
                    addJsonObject {
                        put("name", "user.scan_status")
                        put("type", "string")
                        put("value", "SUCCESS")
                    }
                    addJsonObject {
                        put("name", "user.ocr_text")
                        put("type", "string")
                        put("value", "TABLE 12 • 2 GUESTS • ORDER #4821")
                    }
                    addJsonObject {
                        put("name", "user.ocr_confidence")
                        put("type", "string")
                        put("value", "0.94")
                    }
                    addJsonObject {
                        put("name", "user.ocr_block_count")
                        put("type", "integer")
                        put("value", 3)
                    }
                    addJsonObject {
                        put("name", "user.code_image_uri")
                        put("type", "string")
                        put("value", "file:///data/user/0/com.example/files/workspaces/acme_pos/media/code_9f3c1a2b7d10.png")
                    }
                    addJsonObject {
                        put("name", "user.code_format")
                        put("type", "string")
                        put("value", "QR_CODE")
                    }
                    addJsonObject {
                        put("name", "user.code_kind")
                        put("type", "string")
                        put("value", "2D")
                    }
                    addJsonObject {
                        put("name", "user.code_content")
                        put("type", "string")
                        put("value", "apexpos://order/pickup?terminal=POS-TERM-01")
                    }
                    addJsonObject {
                        put("name", "user.code_id")
                        put("type", "string")
                        put("value", "code_9f3c1a2b7d10")
                    }
                    addJsonObject {
                        put("name", "user.code_status")
                        put("type", "string")
                        put("value", "SUCCESS")
                    }
                    addJsonObject {
                        put("name", "user.code_matrix")
                        put("type", "string")
                        put("value", "111111100101011111111\n100000101101001000001\n101110101001101011101")
                    }
                    addJsonObject {
                        put("name", "user.ai_prediction_label")
                        put("type", "string")
                        put("value", "Wagyu Smash Burger (Entree)")
                    }
                    addJsonObject {
                        put("name", "user.ai_confidence_score")
                        put("type", "string")
                        put("value", "0.93")
                    }
                    addJsonObject {
                        put("name", "user.u2net_mask_uri")
                        put("type", "string")
                        put("value", "content://com.example.provider/u2net/alpha_matte_01.png")
                    }
                    addJsonObject {
                        put("name", "user.bg_remove_status")
                        put("type", "string")
                        put("value", "Transparent PNG Ready")
                    }
                    addJsonObject {
                        put("name", "user.signal_session_id")
                        put("type", "string")
                        put("value", "sig_sess_acme_peer_01")
                    }
                    addJsonObject {
                        put("name", "user.signal_fingerprint")
                        put("type", "string")
                        put("value", "4E-91-A2-5F-C0-83")
                    }
                    addJsonObject {
                        put("name", "user.signal_status")
                        put("type", "string")
                        put("value", "ESTABLISHED")
                    }
                    addJsonObject {
                        put("name", "user.tool_last_latency")
                        put("type", "integer")
                        put("value", 3)
                    }
                    addJsonObject {
                        put("name", "user.haptic_state")
                        put("type", "string")
                        put("value", "ready")
                    }
                    addJsonObject {
                        put("name", "user.haptic_pattern")
                        put("type", "string")
                        put("value", "click")
                    }
                }
                putJsonArray("states") {
                    addJsonObject {
                        put("state_id", 0)
                        putJsonObject("div") {
                            put("type", "container")
                            put("orientation", "vertical")
                            put("item_spacing", 12)
                            putJsonArray("items") {
                                // 1. Terminal Header Card
                                addJsonObject {
                                    put("type", "container")
                                    put("orientation", "vertical")
                                    put("background_color", "#1E293B")
                                    put("corner_radius", 14)
                                    putJsonObject("paddings") {
                                        put("left", 14); put("right", 14); put("top", 12); put("bottom", 12)
                                    }
                                    putJsonObject("border") {
                                        put("corner_radius", 14)
                                        putJsonObject("stroke") {
                                            put("color", spec.accentHex)
                                            put("width", 1)
                                        }
                                    }
                                    putJsonArray("items") {
                                        addJsonObject {
                                            put("type", "container")
                                            put("orientation", "horizontal")
                                            putJsonArray("items") {
                                                addJsonObject {
                                                    put("type", "text")
                                                    put("weight", 1f)
                                                    put("text", "@{user.workspace_title}")
                                                    put("font_size", 16)
                                                    put("font_weight", "bold")
                                                    put("text_color", "#F8FAFC")
                                                }
                                                addJsonObject {
                                                    put("type", "text")
                                                    put("text", "Mode: ${spec.mode.name}")
                                                    put("font_size", 11)
                                                    put("font_weight", "bold")
                                                    put("text_color", spec.accentHex)
                                                }
                                            }
                                        }
                                        addJsonObject {
                                            put("type", "text")
                                            put("text", "SQLite: workspace_${spec.tenantId}.db • Sync: @{user.sync_status}")
                                            put("font_size", 12)
                                            put("text_color", "#94A3B8")
                                            put("font_family", "monospace")
                                        }
                                        // Live KPI Row
                                        addJsonObject {
                                            put("type", "grid")
                                            put("column_count", 3)
                                            put("item_spacing", 8)
                                            putJsonArray("items") {
                                                addJsonObject {
                                                    put("type", "container")
                                                    put("background_color", "#0F172A")
                                                    put("corner_radius", 10)
                                                    putJsonObject("paddings") {
                                                        put("left", 10); put("right", 10); put("top", 8); put("bottom", 8)
                                                    }
                                                    putJsonArray("items") {
                                                        addJsonObject {
                                                            put("type", "text")
                                                            put("text", "ORDERS")
                                                            put("font_size", 10)
                                                            put("font_weight", "bold")
                                                            put("text_color", "#64748B")
                                                        }
                                                        addJsonObject {
                                                            put("type", "text")
                                                            put("text", "@{user.order_count}")
                                                            put("font_size", 18)
                                                            put("font_weight", "bold")
                                                            put("text_color", "#38BDF8")
                                                        }
                                                    }
                                                }
                                                addJsonObject {
                                                    put("type", "container")
                                                    put("background_color", "#0F172A")
                                                    put("corner_radius", 10)
                                                    putJsonObject("paddings") {
                                                        put("left", 10); put("right", 10); put("top", 8); put("bottom", 8)
                                                    }
                                                    putJsonArray("items") {
                                                        addJsonObject {
                                                            put("type", "text")
                                                            put("text", "SKUs IN DB")
                                                            put("font_size", 10)
                                                            put("font_weight", "bold")
                                                            put("text_color", "#64748B")
                                                        }
                                                        addJsonObject {
                                                            put("type", "text")
                                                            put("text", "@{user.inventory_count}")
                                                            put("font_size", 18)
                                                            put("font_weight", "bold")
                                                            put("text_color", "#34D399")
                                                        }
                                                    }
                                                }
                                                addJsonObject {
                                                    put("type", "container")
                                                    put("background_color", "#0F172A")
                                                    put("corner_radius", 10)
                                                    putJsonObject("paddings") {
                                                        put("left", 10); put("right", 10); put("top", 8); put("bottom", 8)
                                                    }
                                                    putJsonArray("items") {
                                                        addJsonObject {
                                                            put("type", "text")
                                                            put("text", "LAST ROW ID")
                                                            put("font_size", 10)
                                                            put("font_weight", "bold")
                                                            put("text_color", "#64748B")
                                                        }
                                                        addJsonObject {
                                                            put("type", "text")
                                                            put("text", "#@{user.last_order_id}")
                                                            put("font_size", 18)
                                                            put("font_weight", "bold")
                                                            put("text_color", "#FBBF24")
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                // 2. Quick-Tap POS Catalog Grid (DivKit `grid` -> `local-db://op/db.insert`)
                                addJsonObject {
                                    put("type", "text")
                                    put("text", "QUICK-TAP POS CATALOG (local-db://op/db.insert)")
                                    put("font_size", 11)
                                    put("font_weight", "bold")
                                    put("text_color", "#94A3B8")
                                }
                                addJsonObject {
                                    put("type", "grid")
                                    put("column_count", 2)
                                    put("item_spacing", 8)
                                    putJsonArray("items") {
                                        spec.defaultProducts.forEach { prod ->
                                            addJsonObject {
                                                put("type", "button")
                                                put("id", "catalog_${prod.sku}")
                                                put("text", prod.name)
                                                put("subtitle", "$${"%.2f".format(prod.price)} • ${prod.sku}")
                                                put("icon", "cart_add")
                                                put("background_color", prod.colorHex)
                                                put("text_color", "#F8FAFC")
                                                put("corner_radius", 12)
                                                putJsonArray("actions") {
                                                    add(buildInsertOrderAction(spec, prod.name, prod.sku, prod.price.toString()))
                                                    add(buildQueryOrdersAction())
                                                    add(buildCountOrdersAction())
                                                }
                                            }
                                        }
                                    }
                                }

                                // 3. Custom Order Entry Bar (DivKit `input` + `button`)
                                addJsonObject {
                                    put("type", "container")
                                    put("orientation", "vertical")
                                    put("background_color", "#1E293B")
                                    put("corner_radius", 12)
                                    putJsonObject("paddings") {
                                        put("left", 12); put("right", 12); put("top", 10); put("bottom", 10)
                                    }
                                    putJsonArray("items") {
                                        addJsonObject {
                                            put("type", "text")
                                            put("text", "DYNAMIC SDUI LINE-ITEM ENTRY (user.* Variable Binding)")
                                            put("font_size", 11)
                                            put("font_weight", "bold")
                                            put("text_color", "#94A3B8")
                                        }
                                        addJsonObject {
                                            put("type", "container")
                                            put("orientation", "horizontal")
                                            put("item_spacing", 8)
                                            putJsonArray("items") {
                                                addJsonObject {
                                                    put("type", "input")
                                                    put("weight", 1.6f)
                                                    put("hint_text", "Item Name (user.custom_item_name)")
                                                    put("text_variable", "user.custom_item_name")
                                                }
                                                addJsonObject {
                                                    put("type", "input")
                                                    put("weight", 1f)
                                                    put("hint_text", "Price ($)")
                                                    put("text_variable", "user.custom_item_price")
                                                }
                                            }
                                        }
                                        addJsonObject {
                                            put("type", "button")
                                            put("id", "add_custom_line_item")
                                            put("text", "Insert @{user.custom_item_name} ($@{user.custom_item_price})")
                                            put("icon", "add")
                                            put("background_color", "#0EA5E9")
                                            put("text_color", "#0F172A")
                                            put("corner_radius", 10)
                                            putJsonArray("actions") {
                                                add(
                                                    buildInsertOrderAction(
                                                        spec = spec,
                                                        itemNameExpr = "@{user.custom_item_name}",
                                                        sku = "CUSTOM-SKU",
                                                        priceExpr = "@{user.custom_item_price}"
                                                    )
                                                )
                                                add(buildQueryOrdersAction())
                                                add(buildCountOrdersAction())
                                            }
                                        }
                                    }
                                }

                                // 4. Hardware Bridge Actions (`hardware://scanner`, `hardware://printer`, `hardware://nfc`)
                                addJsonObject {
                                    put("type", "container")
                                    put("orientation", "vertical")
                                    put("background_color", "#1E293B")
                                    put("corner_radius", 12)
                                    putJsonObject("paddings") {
                                        put("left", 12); put("right", 12); put("top", 10); put("bottom", 10)
                                    }
                                    putJsonArray("items") {
                                        addJsonObject {
                                            put("type", "text")
                                            put("text", "POS HARDWARE CAPABILITY BRIDGE (hardware://*)")
                                            put("font_size", 11)
                                            put("font_weight", "bold")
                                            put("text_color", "#94A3B8")
                                        }
                                        addJsonObject {
                                            put("type", "grid")
                                            put("column_count", 3)
                                            put("item_spacing", 8)
                                            putJsonArray("items") {
                                                addJsonObject {
                                                    put("type", "button")
                                                    put("id", "hw_scanner")
                                                    put("text", "Scan Barcode")
                                                    put("icon", "scanner")
                                                    put("background_color", "#334155")
                                                    put("text_color", "#38BDF8")
                                                    put("corner_radius", 10)
                                                    putJsonObject("action") {
                                                        put("log_id", "hw_scan")
                                                        put("url", "hardware://scanner/read_ean13")
                                                        putJsonObject("payload") {
                                                            put("terminal", spec.terminalCode)
                                                        }
                                                    }
                                                }
                                                addJsonObject {
                                                    put("type", "button")
                                                    put("id", "hw_printer")
                                                    put("text", "Print Receipt")
                                                    put("icon", "printer")
                                                    put("background_color", "#334155")
                                                    put("text_color", "#A78BFA")
                                                    put("corner_radius", 10)
                                                    putJsonObject("action") {
                                                        put("log_id", "hw_print")
                                                        put("url", "hardware://printer/thermal_receipt")
                                                        putJsonObject("payload") {
                                                            put("terminal", spec.terminalCode)
                                                        }
                                                    }
                                                }
                                                addJsonObject {
                                                    put("type", "button")
                                                    put("id", "hw_nfc")
                                                    put("text", "NFC Tap Pay")
                                                    put("icon", "nfc")
                                                    put("background_color", "#334155")
                                                    put("text_color", "#34D399")
                                                    put("corner_radius", 10)
                                                    putJsonObject("action") {
                                                        put("log_id", "hw_nfc")
                                                        put("url", "hardware://nfc/contactless_authorize")
                                                        putJsonObject("payload") {
                                                            put("terminal", spec.terminalCode)
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        addJsonObject {
                                            put("type", "text")
                                            put("text", "Hardware Telemetry: @{user.last_hardware_event}")
                                            put("font_size", 12)
                                            put("text_color", "#E2E8F0")
                                            put("font_family", "monospace")
                                        }
                                        addJsonObject {
                                            put("type", "text")
                                            put("text", "Last Engine Guard: @{user.engine_error}")
                                            put("font_size", 11)
                                            put("text_color", "#F87171")
                                            put("font_family", "monospace")
                                        }
                                    }
                                }

                                // 5. Native Tools Suite Bridge (tools://*)
                                add(SduiOnFlyBuilder.buildScannerToolBlock(spec))
                                add(SduiOnFlyBuilder.buildCodeGenerateToolBlock(spec))
                                add(SduiOnFlyBuilder.buildMobileNetV4ClassifyToolBlock(spec))
                                add(SduiOnFlyBuilder.buildU2NetToolBlock(spec))
                                add(SduiOnFlyBuilder.buildLibSignalToolBlock(spec))

                                // 6. Custom DivKit Chart Block (`"custom_type": "sdui_chart"`) bound to `user.orders_json`
                                add(
                                    SduiOnFlyBuilder.buildSduiChartBlock(
                                        spec = spec,
                                        chartType = "bar",
                                        dataSource = "orders"
                                    )
                                )

                                // 6. Native Custom Block `json_list` bound to `user.orders_json`
                                addJsonObject {
                                    put("type", "container")
                                    put("orientation", "horizontal")
                                    putJsonArray("items") {
                                        addJsonObject {
                                            put("type", "text")
                                            put("weight", 1f)
                                            put("text", "LIVE POS ORDER LEDGER (DivKit json_list → user.orders_json)")
                                            put("font_size", 11)
                                            put("font_weight", "bold")
                                            put("text_color", "#94A3B8")
                                        }
                                    }
                                }
                                addJsonObject {
                                    put("type", "json_list")
                                    put("data_var", "user.orders_json")
                                    put("empty_text", "No active orders in ${spec.tenantId}_orders table. Tap a catalog item above!")
                                    putJsonObject("row_card") {
                                        put("type", "container")
                                        put("orientation", "horizontal")
                                        put("background_color", "#1E293B")
                                        put("corner_radius", 10)
                                        putJsonObject("paddings") {
                                            put("left", 12); put("right", 12); put("top", 10); put("bottom", 10)
                                        }
                                        putJsonObject("border") {
                                            put("corner_radius", 10)
                                            putJsonObject("stroke") {
                                                put("color", "#334155")
                                                put("width", 1)
                                            }
                                        }
                                        putJsonArray("items") {
                                            addJsonObject {
                                                put("type", "container")
                                                put("orientation", "vertical")
                                                put("weight", 1f)
                                                put("item_spacing", 2)
                                                putJsonArray("items") {
                                                    addJsonObject {
                                                        put("type", "text")
                                                        put("text", "#@{item.id} • @{item.item_name}")
                                                        put("font_size", 14)
                                                        put("font_weight", "bold")
                                                        put("text_color", "#F8FAFC")
                                                    }
                                                    addJsonObject {
                                                        put("type", "text")
                                                        put("text", "SKU: @{item.sku} • $@{item.price} x @{item.qty} • @{item.status}")
                                                        put("font_size", 12)
                                                        put("text_color", "#94A3B8")
                                                        put("font_family", "monospace")
                                                    }
                                                }
                                            }
                                            addJsonObject {
                                                put("type", "button")
                                                put("weight", 0.42f)
                                                put("text", "Void #@{item.id}")
                                                put("icon", "delete")
                                                put("background_color", "#3F1D24")
                                                put("text_color", "#FCA5A5")
                                                put("corner_radius", 8)
                                                putJsonArray("actions") {
                                                    addJsonObject {
                                                        put("log_id", "delete_order_row")
                                                        put("url", "local-db://op/db.delete")
                                                        putJsonObject("payload") {
                                                            put("table", "orders")
                                                            putJsonArray("where") {
                                                                addJsonObject {
                                                                    put("column", "id")
                                                                    put("op", "=")
                                                                    put("value", "@{item.id}")
                                                                }
                                                            }
                                                            putJsonObject("output") {
                                                                put("count", "user.last_deleted_count")
                                                            }
                                                        }
                                                    }
                                                    add(buildQueryOrdersAction())
                                                    add(buildCountOrdersAction())
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), root)
    }

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

    fun buildCountInventoryAction(): JsonObject = buildJsonObject {
        put("log_id", "count_inventory")
        put("url", "local-db://op/db.count")
        putJsonObject("payload") {
            put("table", "inventory")
            putJsonObject("output") {
                put("count", "user.inventory_count")
            }
        }
    }

    fun buildQueryInventoryAction(): JsonObject = buildJsonObject {
        put("log_id", "query_inventory")
        put("url", "local-db://op/db.query")
        putJsonObject("payload") {
            put("table", "inventory")
            put("order_by", "id")
            put("limit", 50)
            put("json_list_var", "user.inventory_json")
            putJsonObject("output") {
                put("_json", "user.inventory_json")
            }
        }
    }

    private fun collectionJson(id: Long, name: String, fields: List<String>): JsonObject = buildJsonObject {
        put("id", id)
        put("name", name)
        putJsonObject("schema") {
            putJsonObject("fields") {
                for (field in fields) {
                    putJsonObject(field) {
                        put("type", if (field == "price") "number" else "string")
                        put("required", false)
                        put("uid", "${name}_$field")
                    }
                }
            }
            putJsonObject("policies") {
                put("read", "public")
                put("create", "public")
                put("update", "admin")
                put("delete", "admin")
            }
        }
    }

    private val ORDERS_FIELDS = listOf("item_name", "sku", "price", "qty", "terminal_id", "status")
    private val INVENTORY_FIELDS = listOf("item_name", "sku", "price", "stock", "category")
}
