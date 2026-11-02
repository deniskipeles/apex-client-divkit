package com.example.enginehost

import engine.config.EngineConfig
import engine.crypto.PayloadVerifier
import engine.crypto.SigningKeyPair
import engine.db.EngineRegistry
import engine.db.OpResult
import engine.db.WorkspaceDatabase
import kotlinx.serialization.json.*

data class TenantWorkspaceSpec(
    val tenantId: String,
    val displayName: String,
    val terminalCode: String,
    val subtitle: String,
    val accentHex: String,
    val mode: EngineConfig.Mode,
    val capabilities: Set<String>,
    val defaultProducts: List<SeedProduct>,
    val defaultOrders: List<SeedOrder>
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

    val defaultWorkspaces: List<TenantWorkspaceSpec> = listOf(
        TenantWorkspaceSpec(
            tenantId = "acme_pos",
            displayName = "Acme Bistro & Grill",
            terminalCode = "POS-TERM-01",
            subtitle = "Hybrid Hospitality POS • Full Hardware Grants",
            accentHex = "#38BDF8",
            mode = EngineConfig.Mode.HYBRID,
            capabilities = setOf("scanner", "printer", "nfc", "cash_drawer"),
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
            tenantId = "nova_retail",
            displayName = "Nova Electronics Express",
            terminalCode = "REG-04-WEST",
            subtitle = "Online Flagship Retail • High-Throughput SKU Checkout",
            accentHex = "#818CF8",
            mode = EngineConfig.Mode.ONLINE,
            capabilities = setOf("scanner", "printer", "nfc"),
            defaultProducts = listOf(
                SeedProduct("USB-C GaN 100W Charger", "NVA-881", 49.00, 120, "Power", "#4F46E5"),
                SeedProduct("ANC Studio Earbuds Pro", "NVA-410", 129.00, 34, "Audio", "#7C3AED"),
                SeedProduct("MagSafe Alloy Stand", "NVA-209", 35.00, 56, "Accessories", "#2563EB"),
                SeedProduct("Mechanical Keycap Kit", "NVA-512", 59.00, 19, "Peripherals", "#0891B2")
            ),
            defaultOrders = listOf(
                SeedOrder("USB-C GaN 100W Charger", "NVA-881", 49.00, 1, "REG-04-WEST", "OPEN"),
                SeedOrder("MagSafe Alloy Stand", "NVA-209", 35.00, 1, "REG-04-WEST", "SYNCED")
            )
        ),
        TenantWorkspaceSpec(
            tenantId = "zen_pharmacy",
            displayName = "Zenith Rx Clinic Kiosk",
            terminalCode = "KIOSK-RX-02",
            subtitle = "Offline-First Sandbox • Scanner-Only Capability",
            accentHex = "#34D399",
            mode = EngineConfig.Mode.OFFLINE,
            capabilities = setOf("scanner"), // Printer & NFC intentionally restricted to showcase capability sandboxing
            defaultProducts = listOf(
                SeedProduct("Electrolyte Recovery Pack", "ZEN-011", 14.00, 90, "Wellness", "#059669"),
                SeedProduct("Digital Thermometer Pro", "ZEN-045", 24.50, 31, "Devices", "#0D9488"),
                SeedProduct("Vitamin D3 + K2 Drops", "ZEN-078", 19.00, 74, "Supplements", "#0284C7"),
                SeedProduct("First Aid Travel Kit", "ZEN-092", 29.00, 40, "Emergency", "#4F46E5")
            ),
            defaultOrders = listOf(
                SeedOrder("Electrolyte Recovery Pack", "ZEN-011", 14.00, 1, "KIOSK-RX-02", "QUEUED_OFFLINE")
            )
        ),
        TenantWorkspaceSpec(
            tenantId = "metro_coffee",
            displayName = "Metro Roasters Cafe",
            terminalCode = "BAR-POS-03",
            subtitle = "Express Barista Terminal • Contactless & Receipt",
            accentHex = "#F59E0B",
            mode = EngineConfig.Mode.HYBRID,
            capabilities = setOf("scanner", "printer", "nfc"),
            defaultProducts = listOf(
                SeedProduct("Single Origin Cortado", "MTR-110", 5.25, 140, "Espresso", "#D97706"),
                SeedProduct("Pistachio Croissant", "MTR-205", 6.75, 38, "Pastry", "#059669"),
                SeedProduct("Kyoto Cold Tower", "MTR-118", 6.50, 62, "Cold Brew", "#0284C7"),
                SeedProduct("Matcha Whisk Tin", "MTR-309", 28.00, 22, "Retail", "#7C3AED")
            ),
            defaultOrders = listOf(
                SeedOrder("Single Origin Cortado", "MTR-110", 5.25, 2, "BAR-POS-03", "PAID"),
                SeedOrder("Pistachio Croissant", "MTR-205", 6.75, 1, "BAR-POS-03", "OPEN")
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
     * Ensures tenant-specific SQLite tables (`<ws>_orders` and `<ws>_inventory`) exist
     * inside `workspace_<ws>.db` so `EngineRegistry`'s `PRAGMA table_info` check succeeds.
     */
    fun ensureTenantSchemaAndSeed(
        db: WorkspaceDatabase,
        spec: TenantWorkspaceSpec,
        registry: EngineRegistry
    ) {
        val ws = spec.tenantId
        val ordersTable = "${ws}_orders"
        val inventoryTable = "${ws}_inventory"

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

                                // 5. Native Custom Block `json_list` bound to `user.orders_json`
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
}
