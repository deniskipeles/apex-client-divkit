import Foundation
import Contract

/// Per-workspace tool permissions. Mirrors `WorkspaceToolPermissions`.
public struct WorkspaceToolPermissions: Sendable {
    public var hapticVibration: Bool
    public var hardwareBeeperAudio: Bool
    public var cameraBarcodeScanner: Bool
    public var supervisorBiometricAuth: Bool
    public var clipboardAccess: Bool
    public var backgroundAlertsPush: Bool
    public var offlinePageCaching: Bool
    public var realtimeEngineWsSse: Bool

    public init(
        hapticVibration: Bool = false,
        hardwareBeeperAudio: Bool = false,
        cameraBarcodeScanner: Bool = false,
        supervisorBiometricAuth: Bool = false,
        clipboardAccess: Bool = false,
        backgroundAlertsPush: Bool = false,
        offlinePageCaching: Bool = false,
        realtimeEngineWsSse: Bool = false
    ) {
        self.hapticVibration = hapticVibration
        self.hardwareBeeperAudio = hardwareBeeperAudio
        self.cameraBarcodeScanner = cameraBarcodeScanner
        self.supervisorBiometricAuth = supervisorBiometricAuth
        self.clipboardAccess = clipboardAccess
        self.backgroundAlertsPush = backgroundAlertsPush
        self.offlinePageCaching = offlinePageCaching
        self.realtimeEngineWsSse = realtimeEngineWsSse
    }

    public var activeCount: Int {
        [hapticVibration, hardwareBeeperAudio, cameraBarcodeScanner, supervisorBiometricAuth,
         clipboardAccess, backgroundAlertsPush, offlinePageCaching, realtimeEngineWsSse].filter { $0 }.count
    }

    public func toCapabilitiesSet(base: Set<String> = []) -> Set<String> {
        var out = base
        if cameraBarcodeScanner { out.insert("scanner") } else { out.remove("scanner") }
        if hapticVibration { out.insert("haptics") }
        if hardwareBeeperAudio { out.insert("beeper") }
        if supervisorBiometricAuth { out.insert("biometrics") }
        if clipboardAccess { out.insert("clipboard") }
        if backgroundAlertsPush { out.insert("push") }
        if offlinePageCaching { out.insert("offline_cache") }
        if realtimeEngineWsSse { out.insert("realtime_ws") }
        return out
    }
}

/// Seed product row. Mirrors `SeedProduct`.
public struct SeedProduct: Sendable {
    public let name: String
    public let sku: String
    public let price: Double
    public let stock: Int
    public let category: String
    public let colorHex: String

    public init(name: String, sku: String, price: Double, stock: Int, category: String, colorHex: String) {
        self.name = name; self.sku = sku; self.price = price
        self.stock = stock; self.category = category; self.colorHex = colorHex
    }
}

/// Seed order row. Mirrors `SeedOrder`.
public struct SeedOrder: Sendable {
    public let itemName: String
    public let sku: String
    public let price: Double
    public let qty: Int
    public let terminalId: String
    public let status: String

    public init(itemName: String, sku: String, price: Double, qty: Int, terminalId: String, status: String) {
        self.itemName = itemName; self.sku = sku; self.price = price
        self.qty = qty; self.terminalId = terminalId; self.status = status
    }
}

/// Tenant workspace description. Mirrors `TenantWorkspaceSpec`.
public struct TenantWorkspaceSpec: Sendable {
    public let tenantId: String
    public let displayName: String
    public let terminalCode: String
    public let subtitle: String
    public let accentHex: String
    public let mode: EngineConfig.Mode
    public let capabilities: Set<String>
    public let defaultProducts: [SeedProduct]
    public let defaultOrders: [SeedOrder]
    public let isBuiltInDemo: Bool
    public let tunnelUrl: String
    public let localLanUrl: String
    public let pullEndpoint: String
    public let publicClientId: String
    public let unreadCount: Int
    public let defaultTools: WorkspaceToolPermissions

    public init(
        tenantId: String,
        displayName: String,
        terminalCode: String,
        subtitle: String,
        accentHex: String,
        mode: EngineConfig.Mode,
        capabilities: Set<String>,
        defaultProducts: [SeedProduct],
        defaultOrders: [SeedOrder],
        isBuiltInDemo: Bool = false,
        tunnelUrl: String = "https://apexkit-pos.trycloudflare.com",
        localLanUrl: String = "http://192.168.1.42:8080",
        pullEndpoint: String = "/webhook/edge_notifications/pull-notifications",
        publicClientId: String = "pk_live_bdbc75560c43b6605370bf4a",
        unreadCount: Int = 0,
        defaultTools: WorkspaceToolPermissions = WorkspaceToolPermissions()
    ) {
        self.tenantId = tenantId
        self.displayName = displayName
        self.terminalCode = terminalCode
        self.subtitle = subtitle
        self.accentHex = accentHex
        self.mode = mode
        self.capabilities = capabilities
        self.defaultProducts = defaultProducts
        self.defaultOrders = defaultOrders
        self.isBuiltInDemo = isBuiltInDemo
        self.tunnelUrl = tunnelUrl
        self.localLanUrl = localLanUrl
        self.pullEndpoint = pullEndpoint
        self.publicClientId = publicClientId
        self.unreadCount = unreadCount
        self.defaultTools = defaultTools
    }
}

/// Mirrors `WorkspaceProvisioner`.
public enum WorkspaceProvisioner {

    private static let ordersFields = ["item_name", "sku", "price", "qty", "terminal_id", "status"]
    private static let inventoryFields = ["item_name", "sku", "price", "stock", "category"]

    /// The single built-in demo tenant.
    public static let defaultWorkspaces: [TenantWorkspaceSpec] = [
        TenantWorkspaceSpec(
            tenantId: "acme_pos",
            displayName: "Apex POS Terminal",
            terminalCode: "POS-TERM-01",
            subtitle: "DivKit SDUI Showcase • Full Native Tool Suite",
            accentHex: "#0284C7",
            mode: .hybrid,
            capabilities: ["scanner", "printer", "nfc", "cash_drawer", "haptics", "beeper"],
            defaultProducts: [
                SeedProduct(name: "Wagyu Smash Burger", sku: "ACM-101", price: 16.50, stock: 42, category: "Entrees", colorHex: "#0284C7"),
                SeedProduct(name: "Truffle Parmesan Fries", sku: "ACM-102", price: 8.00, stock: 65, category: "Sides", colorHex: "#0D9488"),
                SeedProduct(name: "Artisan Cold Brew", sku: "ACM-204", price: 5.50, stock: 88, category: "Beverages", colorHex: "#4F46E5"),
                SeedProduct(name: "Wood-Fired Margherita", sku: "ACM-109", price: 18.00, stock: 29, category: "Pizza", colorHex: "#D97706")
            ],
            defaultOrders: [
                SeedOrder(itemName: "Wagyu Smash Burger", sku: "ACM-101", price: 16.50, qty: 1, terminalId: "POS-TERM-01", status: "PAID"),
                SeedOrder(itemName: "Truffle Parmesan Fries", sku: "ACM-102", price: 8.00, qty: 2, terminalId: "POS-TERM-01", status: "OPEN"),
                SeedOrder(itemName: "Artisan Cold Brew", sku: "ACM-204", price: 5.50, qty: 1, terminalId: "POS-TERM-01", status: "OPEN")
            ],
            isBuiltInDemo: true,
            tunnelUrl: "https://apex-terminal.local/",
            localLanUrl: "http://192.168.1.10:8080",
            publicClientId: "pk_live_bdbc75560c43b6605370bf4a",
            unreadCount: 4,
            defaultTools: WorkspaceToolPermissions(
                hapticVibration: true,
                hardwareBeeperAudio: true,
                cameraBarcodeScanner: true,
                supervisorBiometricAuth: true,
                clipboardAccess: true,
                backgroundAlertsPush: true,
                offlinePageCaching: true,
                realtimeEngineWsSse: true
            )
        )
    ]

    /// Creates a signed `EngineConfig` and verifies it so `fromVerifiedPayload` is always used
    /// according to the security invariant.
    public static func createVerifiedConfig(
        spec: TenantWorkspaceSpec,
        keyPair: SigningKeyPair
    ) throws -> EngineConfig {
        let rawConfig: JsonValue = .object([
            ("tenant_id", .string(spec.tenantId)),
            ("schema_version", .string("1.0")),
            ("mode", .string(spec.mode.rawValue)),
            ("edge_endpoint", .string("https://edge.sdui-pos.internal/\(spec.tenantId)")),
            ("verify_public_key", .string(keyPair.publicKeyDerBase64)),
            ("capabilities", .array(spec.capabilities.sorted().map { .string($0) })),
            ("sync_interval_ms", .int(900_000)),
            ("db_schema_version", .int(1))
        ])

        let envelope = try PayloadVerifier.createSignedEnvelope(
            tenant: spec.tenantId,
            schemaVersion: "1.0",
            payloadJson: rawConfig.serialized,
            privateKeyBase64: keyPair.privateKeyDerBase64
        )
        let verifier = try PayloadVerifier(tenant: spec.tenantId, publicKeyBase64: keyPair.publicKeyDerBase64)
        let verified = try verifier.verify(rawEnvelope: envelope)
        guard let config = EngineConfig.fromVerifiedPayload(verified) else {
            throw EngineError.validation("Verified config could not be parsed")
        }
        return config
    }

    /// Registers the tenant's `orders` and `inventory` collections and seeds them through
    /// `EngineRegistry`.
    public static func ensureTenantSchemaAndSeed(
        db: WorkspaceDatabase,
        spec: TenantWorkspaceSpec,
        registry: EngineRegistry
    ) throws {
        let ws = spec.tenantId
        let apexEngine = ApexKitDatabaseEngine(driver: db.driver)

        // Collections are INSERT OR REPLACE'd by import, which would cascade-delete existing
        // records, so only import when the tenant schema is absent.
        if apexEngine.getCollection("orders") == nil {
            let schema: JsonValue = .object([
                ("schema", .object([
                    ("collections", .array([
                        collectionJson(id: 1, name: "orders", fields: ordersFields),
                        collectionJson(id: 2, name: "inventory", fields: inventoryFields)
                    ]))
                ]))
            ])
            _ = try registry.execute(opName: "db.import_schema", db: db, workspaceId: ws, payload: schema)
        }

        let invCount: Int64
        if case let .count(n) = try registry.execute(opName: "db.count", db: db, workspaceId: ws, payload: .object([("table", .string("inventory"))])) {
            invCount = n
        } else { invCount = 0 }

        if invCount == 0 {
            for prod in spec.defaultProducts {
                let payload: JsonValue = .object([
                    ("table", .string("inventory")),
                    ("idempotent", .bool(true)),
                    ("enqueue_sync", .bool(false)),
                    ("values", .object([
                        ("item_name", .string(prod.name)),
                        ("sku", .string(prod.sku)),
                        ("price", .double(prod.price)),
                        ("stock", .int(Int64(prod.stock))),
                        ("category", .string(prod.category))
                    ]))
                ])
                _ = try registry.execute(opName: "db.insert", db: db, workspaceId: ws, payload: payload)
            }
        }

        let ordCount: Int64
        if case let .count(n) = try registry.execute(opName: "db.count", db: db, workspaceId: ws, payload: .object([("table", .string("orders"))])) {
            ordCount = n
        } else { ordCount = 0 }

        if ordCount == 0 {
            for ord in spec.defaultOrders {
                let payload: JsonValue = .object([
                    ("table", .string("orders")),
                    ("enqueue_sync", .bool(true)),
                    ("values", .object([
                        ("item_name", .string(ord.itemName)),
                        ("sku", .string(ord.sku)),
                        ("price", .double(ord.price)),
                        ("qty", .int(Int64(ord.qty))),
                        ("terminal_id", .string(ord.terminalId)),
                        ("status", .string(ord.status))
                    ]))
                ])
                _ = try registry.execute(opName: "db.insert", db: db, workspaceId: ws, payload: payload)
            }
        }
    }

    static func collectionJson(id: Int64, name: String, fields: [String]) -> JsonValue {
        var fieldPairs: [(String, JsonValue)] = []
        for field in fields {
            fieldPairs.append((field, .object([
                ("type", .string(field == "price" ? "number" : "string")),
                ("required", .bool(false)),
                ("uid", .string("\(name)_\(field)"))
            ])))
        }
        return .object([
            ("id", .int(id)),
            ("name", .string(name)),
            ("schema", .object([
                ("fields", .object(fieldPairs)),
                ("policies", .object([
                    ("read", .string("public")),
                    ("create", .string("public")),
                    ("update", .string("admin")),
                    ("delete", .string("admin"))
                ]))
            ]))
        ])
    }
}
