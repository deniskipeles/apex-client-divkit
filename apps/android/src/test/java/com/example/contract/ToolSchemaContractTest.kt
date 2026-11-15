package com.example.contract

import engine.tools.NativeToolsSuite
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the tool schema surface and the DivKit subset schema.
 */
class ToolSchemaContractTest {

    private fun repoRoot(): File =
        System.getProperty("contract.dir")?.let { File(it).parentFile.parentFile }
            ?: listOf(File(".."), File(".")).first()

    @Test
    fun everyRegisteredSchemaHasUniqueToolId() {
        val ids = NativeToolsSuite.registeredSchemas.map { it.toolId }
        assertTrue("duplicate toolId in NativeToolsSuite", ids.size == ids.toSet().size)
    }

    @Test
    fun everyRegisteredSchemaDeclaresOperations() {
        NativeToolsSuite.registeredSchemas.forEach { schema ->
            assertTrue("schema '${schema.toolId}' has no operations", schema.supportedOperations.isNotEmpty())
        }
    }

    @Test
    fun divkitSubsetSchemaExistsAndListsEmittedTypes() {
        val schema = repoRoot().resolve("packages/divkit-subset/schema.json")
        assertTrue("missing divkit-subset schema at $schema", schema.isFile)
        val text = schema.readText()
        listOf("container", "text", "button", "grid", "input", "custom", "image").forEach {
            assertTrue("subset schema missing component type '$it'", text.contains("\"$it\""))
        }
    }
}
