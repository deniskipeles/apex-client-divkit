package com.example.contract

import engine.crypto.CanonicalJson
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Pins the canonical-JSON contract against the shared fixtures in
 * packages/contract/fixtures/canonical/v1. Byte-exact, so a change in either the
 * implementation or the fixtures fails the build.
 */
class CanonicalJsonContractTest {

    private val fixtureDir: File = locateContractDir().resolve("fixtures/canonical/v1")

    private fun locateContractDir(): File {
        System.getProperty("contract.dir")?.let { return File(it) }
        val candidates = listOf(File("../packages/contract"), File("../../packages/contract"), File("packages/contract"))
        return candidates.firstOrNull { it.isDirectory }
            ?: error("contract dir not found from ${File(".").absolutePath}")
    }

    @Test
    fun canonicalFixturesMatchExpectedBytes() {
        val inputs = fixtureDir.listFiles { f -> f.name.endsWith(".json") }!!.sortedBy { it.name }
        assertEquals("expected 3 canonical fixtures", 3, inputs.size)
        inputs.forEach { input ->
            val expectedFile = File(input.parentFile, input.name.removeSuffix(".json") + ".expected.bin")
            val parsed = kotlinx.serialization.json.Json.parseToJsonElement(input.readText())
            val plain = engine.crypto.PayloadVerifier.jsonToPlain(parsed)
            val actual = CanonicalJson.canonicalize(plain)
            assertEquals(input.name, expectedFile.readText(), actual)
            assertEquals(input.name + " byte length", expectedFile.readBytes().size, actual.toByteArray().size)
        }
    }

    @Test
    fun objectKeysAreSortedAndWhitespaceStripped() {
        val out = CanonicalJson.canonicalize(mapOf("z" to 1L, "a" to 2L, "m" to 3L))
        assertEquals("{\"a\":2,\"m\":3,\"z\":1}", out)
    }

    @Test
    fun wholeDoublesCollapseToIntegers() {
        assertEquals("2", CanonicalJson.canonicalize(2.0))
        assertEquals("2.5", CanonicalJson.canonicalize(2.5))
    }
}
