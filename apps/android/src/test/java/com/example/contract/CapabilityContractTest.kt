package com.example.contract

import engine.tools.TerminalApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Enforces the capability contract from packages/contract/tools.json: every canonical id
 * and alias must resolve to a registered handler. Generated ToolIds keeps this in sync.
 */
class CapabilityContractTest {

    @Test
    fun everyCanonicalIdResolves() {
        ToolIds.ALL.forEach { id ->
            assertNotNull("unregistered canonical capability: $id", TerminalApp.registry.resolve(id))
        }
    }

    @Test
    fun everyAliasResolvesToItsCanonicalHandler() {
        ToolIds.ALIASES.forEach { (id, aliases) ->
            val canonical = TerminalApp.registry.resolve(id)
            assertNotNull(canonical)
            aliases.forEach { alias ->
                val handler = TerminalApp.registry.resolve(alias)
                assertNotNull("unregistered alias '$alias' for '$id'", handler)
                assertEquals("alias '$alias' resolved to wrong handler", canonical, handler)
            }
        }
    }

    @Test
    fun manifestAdvertisesEveryContractCapability() {
        val manifest = TerminalApp.registry.manifest()
        ToolIds.ALL.forEach { id ->
            assertTrue("manifest missing $id", manifest.has(id))
        }
    }

    @Test
    fun handlerCapabilityMatchesItsContractId() {
        ToolIds.ALL.forEach { id ->
            assertEquals(id, TerminalApp.registry.resolve(id)!!.capability)
        }
    }
}
