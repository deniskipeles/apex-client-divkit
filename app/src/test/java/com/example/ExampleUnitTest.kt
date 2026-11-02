package com.example

import engine.config.EngineConfig
import engine.crypto.CanonicalJson
import engine.state.DivVariableValue
import engine.state.EngineStateBridge
import org.junit.Assert.*
import org.junit.Test

class ExampleUnitTest {
    @Test
    fun canonicalJson_sortsKeysAndFormatsNumbers() {
        val input = mapOf(
            "z" to 10.0,
            "a" to "hello",
            "b" to listOf(true, false, null)
        )
        val canonical = CanonicalJson.canonicalize(input)
        assertEquals("""{"a":"hello","b":[true,false,null],"z":10}""", canonical)
    }

    @Test
    fun engineConfig_validatesTenantAndHttps() {
        val raw = """
            {
              "tenant_id": "acme_pos",
              "schema_version": "1.0",
              "mode": "HYBRID",
              "edge_endpoint": "https://edge.sdui-pos.internal/acme_pos",
              "verify_public_key": "dGVzdGtleQ==",
              "capabilities": ["scanner", "printer"]
            }
        """.trimIndent()
        val cfg = EngineConfig.fromVerifiedPayload(raw)
        assertEquals("acme_pos", cfg.tenantId)
        assertEquals(EngineConfig.Mode.HYBRID, cfg.mode)
        assertTrue("scanner" in cfg.capabilities)
    }

    @Test(expected = IllegalArgumentException::class)
    fun engineStateBridge_rejectsNonUserNamespace() {
        EngineStateBridge.pushUpdate("engine.error", DivVariableValue.Str("compromised"))
    }
}
