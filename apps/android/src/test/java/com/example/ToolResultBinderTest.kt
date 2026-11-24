package com.example

import engine.tools.ResultBinder
import engine.tools.ToolContext
import engine.tools.ToolResult
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Cards bind `result_var` to a status word. A status alone ("failed") leaves the operator with no
 * cause, so the binder must publish the reason alongside it.
 */
class ToolResultBinderTest {

    private fun bind(result: ToolResult, resultVar: String = "user.codec"): Map<String, String> {
        val vars = mutableMapOf<String, String>()
        ResultBinder.bind(
            result,
            mapOf("result_var" to resultVar),
            ToolContext(variables = { k, v -> vars[k] = v })
        )
        return vars
    }

    @Test
    fun `failed tool publishes its message next to the status`() {
        val vars = bind(ToolResult.Failed("codec", "Checksum mismatch"))
        assertEquals("failed", vars["user.codec"])
        assertEquals("Checksum mismatch", vars["user.codec_error"])
    }

    @Test
    fun `unsupported tool publishes the reason it could not run`() {
        val vars = bind(ToolResult.Unsupported("camera", "No camera host attached"), "user.camera")
        assertEquals("unsupported", vars["user.camera"])
        assertEquals("No camera host attached", vars["user.camera_error"])
    }

    @Test
    fun `denied tool publishes the missing permission`() {
        val vars = bind(ToolResult.PermissionDenied("camera", "android.permission.CAMERA"), "user.camera")
        assertEquals("denied", vars["user.camera"])
        assertEquals("android.permission.CAMERA", vars["user.camera_error"])
    }
}
