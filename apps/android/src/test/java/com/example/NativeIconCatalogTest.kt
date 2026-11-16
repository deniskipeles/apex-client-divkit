package com.example

import engine.icons.IconCategory
import engine.icons.NativeIconCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the bundled native POS icon catalog. No Android/Compose types are
 * touched by [NativeIconCatalog], so these run as plain unit tests.
 */
class NativeIconCatalogTest {

    @Test
    fun `ships a large curated set`() {
        assertTrue("expected 100+ icons, got ${NativeIconCatalog.size}", NativeIconCatalog.size >= 100)
        assertEquals(NativeIconCatalog.icons.size, NativeIconCatalog.size)
    }

    @Test
    fun `every key resolves to exactly one icon`() {
        val keys = NativeIconCatalog.icons.flatMap { listOf(it.name) + it.aliases }
        assertEquals(keys.size, keys.toSet().size)
        NativeIconCatalog.icons.forEach { icon ->
            // Canonical name and every alias resolve back to that same icon.
            (listOf(icon.name) + icon.aliases).forEach { key ->
                val resolved = NativeIconCatalog.find(key)
                assertNotNull("unresolved key: $key", resolved)
                assertEquals("key $key resolved to the wrong icon", icon.name, resolved!!.name)
                assertEquals(icon.name, NativeIconCatalog.normalize(key))
            }
        }
    }

    @Test
    fun `every icon declares a material id and a category`() {
        NativeIconCatalog.icons.forEach { icon ->
            assertTrue(icon.name.isNotBlank())
            assertTrue(icon.material.isNotBlank())
            assertTrue(icon.aliases.none { it.isBlank() })
        }
        assertEquals(IconCategory.entries.toSet(), NativeIconCatalog.icons.map { it.category }.toSet())
    }

    @Test
    fun `every category is populated`() {
        NativeIconCatalog.categories.forEach { category ->
            assertTrue(
                "category ${category.label} is empty",
                NativeIconCatalog.byCategory(category).isNotEmpty()
            )
        }
    }

    @Test
    fun `resolves the canonical native uri form`() {
        assertEquals("cart", NativeIconCatalog.find("icons://native/cart")?.name)
        assertEquals("bell", NativeIconCatalog.find("icons://native/bell")?.name)
        assertEquals("printer", NativeIconCatalog.find("icons://native/printer")?.name)
    }

    @Test
    fun `accepts shorthand uri, canonical, alias, and material casing`() {
        // Same icon reachable four ways.
        assertEquals("cart", NativeIconCatalog.find("icons://cart")?.name)
        assertEquals("cart", NativeIconCatalog.find("cart")?.name)
        assertEquals("cart", NativeIconCatalog.find("Cart")?.name)
        assertEquals("cart", NativeIconCatalog.find("shopping_cart")?.name)
        assertEquals("cart", NativeIconCatalog.find("ShoppingCart")?.name)
        // Canonical name is returned even when looked up via alias.
        assertEquals("bell", NativeIconCatalog.find("notification")?.name)
    }

    @Test
    fun `ignores query strings and fragments`() {
        assertEquals("cart", NativeIconCatalog.find("icons://native/cart?variant=filled")?.name)
        assertEquals("cart", NativeIconCatalog.find("icons://native/cart#top")?.name)
    }

    @Test
    fun `rejects foreign schemes and blank input`() {
        assertNull(NativeIconCatalog.find("https://example.com/cart.png"))
        assertNull(NativeIconCatalog.find("file:///tmp/cart.png"))
        assertNull(NativeIconCatalog.find(""))
        assertNull(NativeIconCatalog.find("   "))
        assertNull(NativeIconCatalog.normalize("icons://"))
        assertNull(NativeIconCatalog.normalize("icons://native"))
        assertNull(NativeIconCatalog.normalize("icons://native/"))
    }

    @Test
    fun `unknown name is not reported as known`() {
        assertNull(NativeIconCatalog.find("definitely_not_an_icon"))
        assertFalse(NativeIconCatalog.isKnown("definitely_not_an_icon"))
        // But the key itself is still echoed back by normalize for diagnostics.
        assertEquals("definitely_not_an_icon", NativeIconCatalog.normalize("definitely_not_an_icon"))
    }

    @Test
    fun `material lookup round trips`() {
        NativeIconCatalog.icons.forEach { icon ->
            assertEquals(icon, NativeIconCatalog.findByMaterial(icon.material))
            // The material id must not be shadowed by another icon's key.
            assertEquals(icon.name, NativeIconCatalog.find(icon.material)?.name)
            assertEquals(icon.material, NativeIconCatalog.materialFor(icon.name))
        }
    }

    @Test
    fun `every material id is unique`() {
        val materials = NativeIconCatalog.icons.map { it.material }
        assertEquals(materials.size, materials.toSet().size)
    }

    @Test
    fun `legacy resolveIcon names still resolve`() {
        // Every name the pre-catalog resolveIcon accepted must keep working.
        val legacy = listOf(
            "add", "plus", "cart_add", "scan", "barcode", "scanner", "qr_code", "qr",
            "print", "printer", "receipt", "nfc", "tap", "card", "delete", "trash",
            "clear", "sync", "cloud", "check", "checkout", "pay", "inventory", "box",
            "refresh", "search", "ocr", "text", "read_text", "camera", "photo", "image",
            "lock", "shield", "e2ee", "vibration", "haptics", "volume", "beeper", "audio",
            "fingerprint", "biometrics", "paste", "clipboard", "notification", "push", "bell"
        )
        val unresolved = legacy.filter { NativeIconCatalog.find(it) == null }
        assertTrue("legacy names no longer resolve: $unresolved", unresolved.isEmpty())
    }

    @Test
    fun `uriFor builds canonical uris`() {
        assertEquals("icons://native/cart", NativeIconCatalog.uriFor("cart"))
        assertTrue(NativeIconCatalog.isIconUri("icons://native/cart"))
        assertTrue(NativeIconCatalog.isIconUri("ICONS://NATIVE/CART"))
        assertFalse(NativeIconCatalog.isIconUri("https://example.com/cart.png"))
    }

    @Test
    fun `covers the common pos vocabulary`() {
        listOf(
            "cart", "checkout", "payment", "card", "cash", "receipt", "discount",
            "product", "stock", "warehouse", "shipping", "scan", "qr", "nfc",
            "customer", "bell", "error", "warning", "check", "clock", "calendar",
            "sync", "wifi", "bluetooth", "printer", "camera", "bar_chart", "lock",
            "fingerprint", "add", "edit", "delete", "search", "filter", "refresh",
            "home", "menu", "settings", "location", "call", "email", "chat"
        ).forEach { name ->
            assertTrue("missing common POS icon: $name", NativeIconCatalog.isKnown(name))
        }
    }
}
