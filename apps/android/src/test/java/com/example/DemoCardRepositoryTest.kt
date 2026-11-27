package com.example

import com.example.enginehost.TenantWorkspaceSpec
import com.example.enginehost.WorkspaceProvisioner
import com.example.enginehost.demo.DemoCardRepository
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The host embeds a workspace card body as the second item of a container (after the
 * Back button). A card authored as a full `{card:{...}}` document has no `type`, so the
 * Android renderer treated it as an empty container and the tool card was blank.
 */
class DemoCardRepositoryTest {

    private fun spec(id: String): TenantWorkspaceSpec =
        WorkspaceProvisioner.defaultWorkspaces.first { it.tenantId == id }

    private fun bodyItems(spec: TenantWorkspaceSpec, name: String): JsonArray {
        val document = DemoCardRepository.toolCardDocument(spec, name)
        assertNotNull("$name must produce a card document", document)
        return document!!["card"]!!.jsonObject["states"]!!.jsonArray[0]
            .jsonObject["div"]!!.jsonObject["items"]!!.jsonArray
    }

    @Test
    fun everyApexToolsTestCardEmbedsARenderableBody() {
        val spec = spec("apex_tools_test")
        val names = spec.toolCardOrder ?: DemoCardRepository.toolCardOrder

        for (name in names) {
            val items = bodyItems(spec, name)
            assertTrue("$name must have a Back button and a body", items.size >= 2)
            val body = items[1].jsonObject
            assertNotNull("$name body must declare a node type", body["type"])
            val childItems = body["items"] as? JsonArray
            assertTrue("$name body must render children", childItems != null && childItems.isNotEmpty())
        }
    }

    @Test
    fun apexToolsTestHapticsCardIsNotBlank() {
        val items = bodyItems(spec("apex_tools_test"), "haptics")
        val body = items[1].jsonObject
        assertNotNull(body["type"])
        assertTrue((body["items"] as? JsonArray)?.isNotEmpty() == true)
    }
}
