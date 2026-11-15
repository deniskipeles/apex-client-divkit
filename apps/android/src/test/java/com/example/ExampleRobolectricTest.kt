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
import engine.router.EngineRouter
import engine.state.EngineStateBridge
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
        assertEquals("ApexClient Terminal", appName)
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

    @Test
    fun `icons scheme resolves a known icon into state`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = WorkspaceManager(WorkspaceDriverFactory(context))
        val registry = EngineRegistry()
        val spec = WorkspaceProvisioner.defaultWorkspaces.first()
        val keyPair = Ed25519.generateKeyPair("test-seed")
        val config = WorkspaceProvisioner.createVerifiedConfig(spec, keyPair)
        EngineStateBridge.clear()

        val router = EngineRouter(config, manager, registry, activeWorkspace = { spec.tenantId })

        val outcome = router.handle("icons://native/cart", null)
        assertTrue(outcome is EngineRouter.ActionOutcome.Handled)
        assertEquals("cart", EngineStateBridge.state.value["user.icon_name"]?.asDisplayString())
        assertEquals("ShoppingCart", EngineStateBridge.state.value["user.icon_material"]?.asDisplayString())
        assertEquals("Commerce", EngineStateBridge.state.value["user.icon_category"]?.asDisplayString())
    }

    @Test
    fun `icons scheme honours an explicit output variable`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = WorkspaceManager(WorkspaceDriverFactory(context))
        val registry = EngineRegistry()
        val spec = WorkspaceProvisioner.defaultWorkspaces.first()
        val keyPair = Ed25519.generateKeyPair("test-seed")
        val config = WorkspaceProvisioner.createVerifiedConfig(spec, keyPair)
        EngineStateBridge.clear()

        val router = EngineRouter(config, manager, registry, activeWorkspace = { spec.tenantId })

        val payload = buildJsonObject { put("var", "user.chosen_icon") }
        val outcome = router.handle("icons://native/bell", payload)
        assertTrue(outcome is EngineRouter.ActionOutcome.Handled)
        assertEquals("bell", EngineStateBridge.state.value["user.chosen_icon"]?.asDisplayString())
    }

    @Test
    fun `icons scheme reports an unknown icon as an error`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = WorkspaceManager(WorkspaceDriverFactory(context))
        val registry = EngineRegistry()
        val spec = WorkspaceProvisioner.defaultWorkspaces.first()
        val keyPair = Ed25519.generateKeyPair("test-seed")
        val config = WorkspaceProvisioner.createVerifiedConfig(spec, keyPair)

        val router = EngineRouter(config, manager, registry, activeWorkspace = { spec.tenantId })
        val outcome = router.handle("icons://native/not_a_real_icon", null)
        assertTrue(outcome is EngineRouter.ActionOutcome.Error)
    }

    @Test
    fun `every catalog icon maps to a compose vector`() {
        // Guards against a catalog entry being added without a NativeIconVectors branch.
        val unresolved = engine.icons.NativeIconCatalog.icons
            .filter { engine.icons.NativeIconVectors.resolve(it.material) == null }
            .map { "${it.name}(${it.material})" }
        assertTrue("catalog icons without a vector: $unresolved", unresolved.isEmpty())
    }
}
