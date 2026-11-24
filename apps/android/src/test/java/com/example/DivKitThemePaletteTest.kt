package com.example

import androidx.compose.ui.graphics.Color
import com.yandex.divkit.multiplatform.adaptButtonBgForTheme
import com.yandex.divkit.multiplatform.adaptButtonTextForTheme
import com.yandex.divkit.multiplatform.adaptSurfaceColorForTheme
import com.yandex.divkit.multiplatform.adaptTextColorForTheme
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Demo cards are authored against the dark slate palette (#1E293B surfaces, #F8FAFC text).
 * The light theme rewrites those hexes, so every authored pair has to keep a readable
 * contrast ratio after adaptation.
 */
class DivKitThemePaletteTest {

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

    private fun assertReadable(surface: String, text: String, isDark: Boolean = false) {
        val bg = adaptSurfaceColorForTheme(surface, isDark)
        val fg = adaptTextColorForTheme(text, isDark)
        val ratio = contrast(bg, fg)
        assertTrue(
            "theme(dark=$isDark): $text on $surface renders ${"%.2f".format(ratio)}:1",
            ratio >= 4.5
        )
    }

    @Test
    fun `tool card panels stay readable in the light theme`() {
        assertReadable("#334155", "#CBD5E1")
        assertReadable("#334155", "#94A3B8")
        assertReadable("#334155", "#64748B")
        assertReadable("#334155", "#F8FAFC")
        assertReadable("#334155", "#F87171")
        assertReadable("#334155", "#34D399")
    }

    @Test
    fun `tool card panels stay readable in the dark theme`() {
        assertReadable("#334155", "#CBD5E1", isDark = true)
        assertReadable("#334155", "#94A3B8", isDark = true)
        assertReadable("#334155", "#64748B", isDark = true)
        assertReadable("#334155", "#F8FAFC", isDark = true)
        assertReadable("#1E293B", "#CBD5E1", isDark = true)
        assertReadable("#1E293B", "#64748B", isDark = true)
    }

    @Test
    fun `card surfaces stay readable in the light theme`() {
        assertReadable("#1E293B", "#F8FAFC")
        assertReadable("#1E293B", "#CBD5E1")
        assertReadable("#1E293B", "#94A3B8")
        assertReadable("#0F172A", "#F8FAFC")
        assertReadable("#0F172A", "#94A3B8")
        assertReadable("#FFFFFF", "#0F172A")
    }

    @Test
    fun `slate panel surface is rewritten for the light theme`() {
        assertTrue(adaptSurfaceColorForTheme("#334155", isDark = false) != Color(0xFF334155))
        // Dark theme keeps the authored palette untouched.
        assertTrue(adaptSurfaceColorForTheme("#334155", isDark = true) == Color(0xFF334155))
    }

    @Test
    fun `secondary buttons keep readable labels in the light theme`() {
        val bg = adaptButtonBgForTheme("#334155", isDark = false)
        val fg = adaptButtonTextForTheme("#F8FAFC", "#334155", isDark = false)
        assertTrue("secondary button label contrast", contrast(bg, fg) >= 4.5)
    }
}
