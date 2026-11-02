package com.example

import com.example.enginehost.SduiOnFlyBuilder
import com.example.enginehost.WorkspaceProvisioner
import engine.config.EngineConfig
import engine.crypto.CanonicalJson
import engine.state.DivVariableValue
import engine.state.EngineStateBridge
import engine.ui.DivChartParser
import engine.ui.DivChartType
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

    @Test
    fun divChartParser_parsesReactiveOrderJsonAndAggregatesSkus() {
        val spec = WorkspaceProvisioner.defaultWorkspaces.first()
        val chartNode = SduiOnFlyBuilder.buildSduiChartBlock(spec, chartType = "bar", dataSource = "orders")
        val mockOrdersJson = """
            [
              {"sku":"ACM-101","item_name":"Wagyu Smash Burger","price":16.5,"qty":2},
              {"sku":"ACM-102","item_name":"Truffle Parmesan Fries","price":8.0,"qty":1},
              {"sku":"ACM-101","item_name":"Wagyu Smash Burger","price":16.5,"qty":1}
            ]
        """.trimIndent()
        val parsed = DivChartParser.parse(
            node = chartNode,
            variables = mapOf("user.orders_json" to DivVariableValue.Str(mockOrdersJson))
        )
        assertEquals(DivChartType.BAR, parsed.chartType)
        assertEquals(2, parsed.points.size)
        val acm101 = parsed.points.first { it.label == "ACM-101" }
        assertEquals(49.5, acm101.value, 0.01)
    }
}
