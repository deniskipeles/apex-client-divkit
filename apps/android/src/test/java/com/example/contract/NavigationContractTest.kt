package com.example.contract

import engine.navigation.CardRegistry
import engine.navigation.CardEntry
import engine.navigation.DivCardNavigator
import engine.navigation.NavOp
import engine.navigation.NavigationRouter
import engine.navigation.NavigationUrlParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins the Android navigation parser against the shared fixtures in
 * packages/contract/fixtures/navigation/v1. Any drift between this parser and the other three
 * platforms fails the build.
 */
class NavigationContractTest {

    private val fixtureDir: File = locateContractDir().resolve("fixtures/navigation/v1")

    private fun locateContractDir(): File {
        System.getProperty("contract.dir")?.let { return File(it) }
        val candidates = listOf(File("../packages/contract"), File("../../packages/contract"), File("packages/contract"))
        return candidates.firstOrNull { it.isDirectory }
            ?: error("contract dir not found from ${File(".").absolutePath}")
    }

    @Test
    fun parseFixturesMatchExpected() {
        val files = fixtureDir.listFiles { f -> f.name.endsWith(".json") }!!.sortedBy { it.name }
        assertEquals("expected 6 navigation fixtures", 6, files.size)

        files.forEach { file ->
            val root = Json.parseToJsonElement(file.readText()).jsonObject
            val input = root["input"]!!.jsonPrimitive.content
            val expected = root["expected"]!!.jsonObject.mapValues { (_, v) -> v.jsonPrimitive.content }

            val parsed = NavigationUrlParser.parse(input)
            assertTrue("${file.name}: expected a parse, got null", parsed != null)
            assertEquals(file.name, expected, parsed!!.toFixtureMap())
        }
    }

    @Test
    fun parameterlessOpsCarryNoCardId() {
        assertEquals(NavOp.Pop, NavigationUrlParser.parse("nav://pop"))
        assertEquals(mapOf("op" to "pop"), NavigationUrlParser.parse("nav://pop")!!.toFixtureMap())
        assertEquals(mapOf("op" to "sheet-dismiss"), NavigationUrlParser.parse("sheet://dismiss")!!.toFixtureMap())
    }

    @Test
    fun unresolvableAndForeignUrlsReturnNull() {
        assertNull(NavigationUrlParser.parse("nav://push"))
        assertNull(NavigationUrlParser.parse("nav://push?id="))
        assertNull(NavigationUrlParser.parse("sheet://present"))
        assertNull(NavigationUrlParser.parse("tools://device.haptics"))
        assertNull(NavigationUrlParser.parse("https://example.com"))
        assertNull(NavigationUrlParser.parse("not a url"))
    }

    private fun entry(id: String) = CardEntry(id, JsonObject(mapOf("id" to JsonPrimitive(id))))

    @Test
    fun routerPushResolvesThroughRegistry() {
        CardRegistry.clear()
        CardRegistry.register("register", entry("register").data)
        val nav = DivCardNavigator(listOf(entry("launcher")))
        val router = NavigationRouter(nav, CardRegistry)

        assertTrue(router.handle("nav://push?id=register"))
        assertEquals("register", nav.current?.id)
        assertEquals(2, nav.stack.size)
    }

    @Test
    fun routerPushUnknownCardIsSilentNoOp() {
        CardRegistry.clear()
        val nav = DivCardNavigator(listOf(entry("launcher")))
        val router = NavigationRouter(nav, CardRegistry)

        assertTrue(router.handle("nav://push?id=missing"))
        assertEquals("launcher", nav.current?.id)
        assertEquals(1, nav.stack.size)
    }

    @Test
    fun routerIgnoresForeignUrls() {
        CardRegistry.clear()
        val nav = DivCardNavigator(listOf(entry("launcher")))
        val router = NavigationRouter(nav, CardRegistry)
        assertFalse(router.handle("tools://device.haptics"))
    }

    @Test
    fun navigatorViewCacheEvictsBeyondLimitAndProtectsVisibleCard() {
        val nav = DivCardNavigator(listOf(entry("a")), cacheLimit = 2)
        listOf("b", "c", "d", "e").forEach { nav.push(entry(it)) }
        // The visible card must never be evicted.
        assertTrue(nav.liveCardIds.contains("e"))
        assertTrue(nav.liveCardIds.size <= 2)
    }
}
