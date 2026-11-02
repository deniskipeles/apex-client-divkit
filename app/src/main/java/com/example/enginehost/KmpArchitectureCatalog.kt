package com.example.enginehost

data class KmpFileEntry(
    val path: String,
    val moduleLayer: String,
    val summary: String,
    val snippet: String
)

object KmpArchitectureCatalog {

    val conventions = listOf(
        "Every output variable is user.*" to "EngineStateBridge.pushUpdate, EngineRouter.requireVar",
        "No raw SQL from JSON" to "EngineRegistry.buildWhere — structured filter DSL only",
        "Table/column names validated against schema" to "requireKnownColumns (PRAGMA table_info)",
        "LIMIT hard-capped at 500" to "EngineRegistry.HARD_MAX_LIMIT",
        "Ed25519 verify before render" to "PayloadVerifier.verify — nothing unverified reaches DivView",
        "Replay window 5 min + jti LRU" to "PayloadVerifier (maxSkewMs = 300_000L, maxSeenJti = 10_000)",
        "No lost background updates" to "EngineStateBridge uses StateFlow snapshot",
        "iOS task IDs registered before use" to "Info.plist + registerIosSyncHandler"
    )

    val files: List<KmpFileEntry> = listOf(
        KmpFileEntry(
            path = "shared/src/commonMain/kotlin/engine/config/EngineConfig.kt",
            moduleLayer = "commonMain",
            summary = "Signed config.json root model with tenantId regex & HTTPS endpoint invariant.",
            snippet = """
@Serializable
data class EngineConfig(
    @SerialName("tenant_id") val tenantId: String,
    @SerialName("schema_version") val schemaVersion: String,
    val mode: Mode,
    @SerialName("edge_endpoint") val edgeEndpoint: String,
    @SerialName("verify_public_key") val verifyPublicKey: String,
    val capabilities: Set<String> = emptySet(),
    @SerialName("sync_interval_ms") val syncIntervalMs: Long = 900_000L,
    @SerialName("db_schema_version") val dbSchemaVersion: Int = 1
)
            """.trimIndent()
        ),
        KmpFileEntry(
            path = "shared/src/commonMain/kotlin/engine/crypto/PayloadVerifier.kt",
            moduleLayer = "commonMain",
            summary = "Verifies Ed25519 signature, CanonicalJson bytes, tenant binding, 5m iat window, and jti LRU cache.",
            snippet = """
suspend fun verify(rawEnvelope: String): String {
    val env = json.decodeFromString<Envelope>(rawEnvelope)
    if (env.tenant != expectedTenant) throw SecurityException("Tenant mismatch")
    val canonicalBytes = CanonicalJson.canonicalize(canonicalMap).encodeToByteArray()
    if (!Ed25519.verify(publicKeyDer, canonicalBytes, env.signature)) {
        throw SecurityException("Signature verification failed")
    }
    if (abs(now - env.iat) > maxSkewMs) throw SecurityException("Envelope expired")
    if (!seenJti.add(env.jti)) throw SecurityException("Replay detected")
    return env.payload.toString()
}
            """.trimIndent()
        ),
        KmpFileEntry(
            path = "shared/src/commonMain/kotlin/engine/db/EngineRegistry.kt",
            moduleLayer = "commonMain",
            summary = "Whitelisted SQL operations (db.insert, db.update, db.delete, db.query, db.query_one, db.count) with PRAGMA column verification.",
            snippet = """
private val operations = buildMap {
    put("db.insert", ::insert)
    put("db.update", ::update)
    put("db.delete", ::delete)
    put("db.query", ::query)
    put("db.query_one", ::queryOne)
    put("db.count", ::count)
    putAll(extensionOps)
}
            """.trimIndent()
        ),
        KmpFileEntry(
            path = "shared/src/commonMain/kotlin/engine/router/EngineRouter.kt",
            moduleLayer = "commonMain",
            summary = "Intercepts local-db://op/<name>, hardware://<capability>, and workspace://clear_all; emits user.* variables to EngineStateBridge.",
            snippet = """
suspend fun handle(url: String, payload: JsonObject?): ActionOutcome = when {
    url.startsWith("local-db://op/") -> handleLocalDb(url, payload ?: JsonObject(emptyMap()))
    url.startsWith("hardware://") -> handleHardware(url, payload)
    url == "workspace://clear_all" -> handleClearAll()
    else -> ActionOutcome.Unhandled
}
            """.trimIndent()
        ),
        KmpFileEntry(
            path = "shared/src/iosMain/kotlin/engine/crypto/Ed25519.ios.kt",
            moduleLayer = "iosMain",
            summary = "iOS Ed25519 signature verification via Security.framework SecKeyVerifySignature (kSecKeyAlgorithmEdDSA).",
            snippet = """
SecKeyVerifySignature(
    key = key,
    algorithm = kSecKeyAlgorithmEdDSA,
    signedData = msgPin.addressOf(0).toNSData(message.size),
    signature = sigPin.addressOf(0).toNSData(sigBytes.size),
    error = null
)
            """.trimIndent()
        ),
        KmpFileEntry(
            path = "iosApp/EngineCustomBlockFactory.swift",
            moduleLayer = "iosApp (Swift)",
            summary = "Only unavoidable Swift UI file: implements DivCustomBlockFactory + UITableViewDataSource for json_list custom blocks.",
            snippet = """
final class EngineJsonListBlockFactory: DivCustomBlockFactory {
    func makeBlock(data: DivCustomBlockData) -> UIView {
        let dataVar = (data.customProps["data_var"] as? String) ?? "user.items"
        let rowCard = data.customProps["row_card"] as? [String: Any] ?? [:]
        let emptyText = (data.customProps["empty_text"] as? String) ?? ""
        return JsonListBlockView(...)
    }
}
            """.trimIndent()
        ),
        KmpFileEntry(
            path = "iosApp/Info.plist & AppDelegate.swift",
            moduleLayer = "iosApp (Swift)",
            summary = "Registers BGTaskSchedulerPermittedIdentifiers (com.example.engine.sync) and invokes SharedSyncSchedulerKt.registerIosSyncHandler.",
            snippet = """
<key>BGTaskSchedulerPermittedIdentifiers</key>
<array>
    <string>com.example.engine.sync</string>
</array>
            """.trimIndent()
        )
    )
}
