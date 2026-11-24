package com.example

import engine.tools.NativeToolsSuite
import engine.tools.ToolArgs
import engine.tools.ToolContext
import engine.tools.ToolResult
import engine.tools.impl.LibSignalTool
import engine.tools.impl.NetTool
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Card-facing contracts for the Signal session and the LAN hub, kept as they were. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SignalAndNetworkContractTest {

    @Test
    fun `libsignal establish clears the previous ciphertext and plaintext`(): Unit = runBlocking {
        val tool = LibSignalTool()
        val ctx = ToolContext(workspaceId = "test_libsignal_reset")
        val established = tool.invoke(
            ToolArgs(mapOf("op" to "create_session", "peer_id" to "peer_a")), ctx
        ) as ToolResult.Success

        assertEquals("ESTABLISHED", established.data["status"])
        assertEquals("", established.data["ciphertext"])
        assertEquals("", established.data["plaintext"])
        assertEquals("UNVERIFIED", established.data["verification_status"])
        val safety = established.data["safety_number"] as String
        assertEquals(60, safety.length)
        assertEquals(
            safety.chunked(5).joinToString(" "),
            established.data["safety_number_display"]
        )
    }

    @Test
    fun `libsignal verify without a session fails closed`(): Unit = runBlocking {
        val tool = LibSignalTool()
        val result = tool.invoke(
            ToolArgs(mapOf("op" to "verify_identity", "peer_id" to "never_paired")),
            ToolContext(workspaceId = "test_libsignal_no_session")
        )
        assertTrue(result is ToolResult.Failed)
        assertFalse((result as ToolResult.Failed).message.contains("VERIFIED"))
    }

    @Test
    fun `libsignal decrypt without a session or ciphertext fails`(): Unit = runBlocking {
        val tool = LibSignalTool()
        val ctx = ToolContext(workspaceId = "test_libsignal_decrypt")
        val missing = tool.invoke(ToolArgs(mapOf("op" to "decrypt", "peer_id" to "peer_a")), ctx)
        assertTrue(missing is ToolResult.Failed)

        tool.invoke(ToolArgs(mapOf("op" to "create_session", "peer_id" to "peer_a")), ctx)
        val tampered = tool.invoke(
            ToolArgs(mapOf("op" to "decrypt", "peer_id" to "peer_a", "ciphertext" to "AAAA")),
            ctx
        )
        assertTrue(tampered is ToolResult.Failed)
    }

    @Test
    fun `libsignal decrypt with a blank ciphertext fails with an actionable reason`(): Unit = runBlocking {
        val tool = LibSignalTool()
        val ctx = ToolContext(workspaceId = "test_libsignal_blank_cipher")
        tool.invoke(ToolArgs(mapOf("op" to "create_session", "peer_id" to "peer_a")), ctx)

        // The card binds user.signal_ciphertext, which is "" until Encrypt runs.
        val blank = tool.invoke(
            ToolArgs(mapOf("op" to "decrypt", "peer_id" to "peer_a", "ciphertext" to "")),
            ctx
        ) as ToolResult.Failed
        assertTrue(blank.message.contains("Encrypt"))
        assertFalse(blank.message.contains("Exception"))
    }

    @Test
    fun `libsignal encrypt with a blank payload fails instead of sealing nothing`(): Unit = runBlocking {
        val tool = LibSignalTool()
        val result = tool.invoke(
            ToolArgs(mapOf("op" to "encrypt", "peer_id" to "peer_a", "data" to "  ")),
            ToolContext(workspaceId = "test_libsignal_blank_payload")
        ) as ToolResult.Failed
        assertFalse(result.message.contains("Exception"))
        assertTrue(result.message.contains("Nothing to encrypt"))
    }

    @Test
    fun `libsignal tampered ciphertext reports a user facing reason`(): Unit = runBlocking {
        val tool = LibSignalTool()
        val ctx = ToolContext(workspaceId = "test_libsignal_tampered")
        tool.invoke(ToolArgs(mapOf("op" to "create_session", "peer_id" to "peer_a")), ctx)
        val tampered = tool.invoke(
            ToolArgs(mapOf("op" to "decrypt", "peer_id" to "peer_a", "ciphertext" to "AAAA")),
            ctx
        ) as ToolResult.Failed
        assertFalse(tampered.message.contains("Exception"))
        assertFalse(tampered.message.contains("java."))
        assertFalse(tampered.message.contains("ParseException"))
    }

    @Test
    fun `lan share without a running hub is staged not silently shared`(): Unit = runBlocking {
        val tool = NetTool()
        val result = tool.invoke(
            ToolArgs(mapOf("op" to "share", "name" to "order.json", "content" to "{}")),
            ToolContext()
        ) as ToolResult.Success
        assertEquals("STAGED", result.data["status"])
        assertEquals(false, result.data["hub_running"])
        assertTrue((result.data["warning"] as String).isNotBlank())

        tool.invoke(ToolArgs(mapOf("op" to "serve", "port" to "0")), ToolContext())
        val running = tool.invoke(
            ToolArgs(mapOf("op" to "share", "name" to "order2.json", "content" to "{}")),
            ToolContext()
        ) as ToolResult.Success
        assertEquals("SHARED", running.data["status"])
        assertEquals(true, running.data["hub_running"])
        tool.invoke(ToolArgs(mapOf("op" to "stop")), ToolContext())
    }

    @Test
    fun `libsignal is registered in the native tool suite with a demo card`() {
        val schema = NativeToolsSuite.registeredSchemas.first { it.toolId == "libsignal" }
        assertTrue(schema.supportedOperations.contains("create_session"))
        assertTrue(schema.supportedOperations.contains("verify_identity"))
    }
}
