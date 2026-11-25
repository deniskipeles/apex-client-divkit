package com.example

import androidx.compose.ui.graphics.Color
import com.yandex.divkit.multiplatform.adaptButtonBgForTheme
import com.yandex.divkit.multiplatform.adaptButtonTextForTheme
import com.yandex.divkit.multiplatform.adaptSurfaceColorForTheme
import com.yandex.divkit.multiplatform.adaptTextColorForTheme
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Every text node in every bundled demo card must stay readable once the light theme has rewritten
 * its colours.
 *
 * The adapters flip a light caption (`#F8FAFC`) to ink (`#0F172A`) for the light theme, which is
 * only correct when the surface under it was lightened too. The Kiosk catalog rows paint `#1B2740`
 * panels that keep their dark fill, so the flip painted dark-on-dark and the item names were
 * invisible on device. Pastel accents (`#C4B5FD`, `#67E8F9`) had the opposite problem: authored for
 * a dark panel, they washed out to ~1.5:1 once the panel became light.
 */
class DemoCardContrastTest {

    private data class TextPair(
        val surface: String,
        val text: String,
        val label: String,
        val isButton: Boolean
    )

    private val hex = Regex("^#[0-9A-Fa-f]{6}$")

    private fun linear(channel: Float): Double {
        val c = channel.toDouble()
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(color: Color): Double =
        0.2126 * linear(color.red) + 0.7152 * linear(color.green) + 0.0722 * linear(color.blue)

    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    private fun surfaceOf(node: JsonObject, inherited: String?): String? =
        node["background_color"]?.jsonPrimitive?.contentOrNull
            ?: node["background"]?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("color")?.jsonPrimitive?.contentOrNull
            ?: inherited

    private fun collectPairs(node: JsonObject, inherited: String?, out: MutableList<TextPair>) {
        val surface = surfaceOf(node, inherited)
        val text = node["text_color"]?.jsonPrimitive?.contentOrNull
        val isButton = node["type"]?.jsonPrimitive?.contentOrNull == "button"
        if (text != null && hex.matches(text)) {
            // A button paints its own fill and is adapted through the button pair of helpers.
            val fill = if (isButton) surface ?: "#2563EB" else surface ?: "#FFFFFF"
            out += TextPair(fill, text, node["text"]?.jsonPrimitive?.contentOrNull.orEmpty(), isButton)
        }
        node["items"]?.jsonArray?.forEach { child ->
            child.jsonObject.let { collectPairs(it, surface, out) }
        }
    }

    private fun cardFiles(): List<File> {
        val root = File("src/dev/resources/demo")
        assumeTrue("demo sources not present", root.isDirectory)
        return root.walkTopDown().filter { it.isFile && it.extension == "json" }.toList()
    }

    @Test
    fun `every demo card text pair stays readable in the light theme`() {
        val failures = mutableListOf<String>()
        var checked = 0
        for (file in cardFiles()) {
            val root = runCatching { Json.parseToJsonElement(file.readText()).jsonObject }.getOrNull()
                ?: continue
            val pairs = mutableListOf<TextPair>()
            collectPairs(root, null, pairs)
            for (pair in pairs) {
                val bg = if (pair.isButton) {
                    adaptButtonBgForTheme(pair.surface, isDark = false)
                } else {
                    adaptSurfaceColorForTheme(pair.surface, isDark = false)
                }
                val fg = if (pair.isButton) {
                    adaptButtonTextForTheme(pair.text, pair.surface, isDark = false)
                } else {
                    adaptTextColorForTheme(pair.text, isDark = false, surfaceHex = pair.surface)
                }
                val ratio = contrast(bg, fg)
                checked++
                if (ratio < 4.5) {
                    failures += "${file.name}: \"${pair.label}\" ${pair.text} on ${pair.surface} " +
                        "renders ${"%.2f".format(ratio)}:1"
                }
            }
        }
        assertTrue("no text nodes were checked", checked > 50)
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
