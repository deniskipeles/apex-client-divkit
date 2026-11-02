package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.enginehost.WorkspaceProvisioner
import engine.crypto.Ed25519
import engine.crypto.PayloadVerifier
import engine.db.EngineRegistry
import engine.db.OpResult
import engine.db.WorkspaceDriverFactory
import engine.db.WorkspaceManager
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("DivKit SDUI POS", appName)
    }

    @Test
    fun `workspace isolation and payload verifier end to end`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = WorkspaceManager(WorkspaceDriverFactory(context))
        val registry = EngineRegistry()
        val spec = WorkspaceProvisioner.defaultWorkspaces.first()
        val keyPair = Ed25519.generateKeyPair("test-seed")

        val config = WorkspaceProvisioner.createVerifiedConfig(spec, keyPair)
        assertEquals("acme_pos", config.tenantId)

        manager.useDatabase(spec.tenantId) { db ->
            WorkspaceProvisioner.ensureTenantSchemaAndSeed(db, spec, registry)
            val countResult = registry.execute(
                "db.count",
                db,
                spec.tenantId,
                buildJsonObject { put("table", "orders") }
            ) as OpResult.Count
            assertTrue(countResult.n >= 3L)
        }

        val envelope = PayloadVerifier.createSignedEnvelope(
            tenant = spec.tenantId,
            payloadJson = """{"type":"text","text":"Verified POS"}""",
            keyPair = keyPair
        )
        val verifier = PayloadVerifier(spec.tenantId, keyPair.publicKeyDerBase64)
        val verified = verifier.verify(envelope)
        assertTrue(verified.contains("Verified POS"))

        manager.useDatabase(spec.tenantId) { db ->
            db.workspaceQueries.cacheAndActivateSchemaVersion(
                schemaVersion = "1.0",
                jti = "jti-10",
                signedEnvelope = envelope,
                verifiedPayload = """{"card":{"log_id":"v10"}}""",
                receivedAt = 1000L,
                summary = "Initial Schema v1.0"
            )
            db.workspaceQueries.cacheAndActivateSchemaVersion(
                schemaVersion = "1.1",
                jti = "jti-11",
                signedEnvelope = envelope,
                verifiedPayload = """{"card":{"log_id":"v11"}}""",
                receivedAt = 2000L,
                summary = "Updated Schema v1.1"
            )
            val activeBefore = db.workspaceQueries.selectActiveSchemaVersion()
            assertEquals("1.1", activeBefore?.schemaVersion)

            val all = db.workspaceQueries.selectAllSchemaVersions()
            assertEquals(2, all.size)
            val v10 = all.first { it.schemaVersion == "1.0" }
            db.workspaceQueries.activateSchemaVersionById(v10.versionId)

            val activeAfterRollback = db.workspaceQueries.selectActiveSchemaVersion()
            assertEquals("1.0", activeAfterRollback?.schemaVersion)
            assertTrue(activeAfterRollback?.divkitJson?.contains("v10") == true)
        }
    }
}
