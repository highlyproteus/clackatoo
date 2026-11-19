// SPDX-License-Identifier: GPL-3.0-only

package io.github.highlyproteus.clackatoo.models

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertTrue

/** Every text of this app's screens reads at 4.5:1 (WCAG AA) on its background in the fallback palette, dark and light. */
class PaletteTest {
    @Test fun `text on surface, panel and primary reads at WCAG AA`() {
        for (dark in listOf(true, false)) {
            val p = Palette.fallback(dark)
            for ((name, text, background) in listOf(
                Triple("onSurface on panel", p.onSurface, p.panel),
                Triple("onSurface on surface", p.onSurface, p.surface),
                Triple("onSurfaceVariant on panel", p.onSurfaceVariant, p.panel),
                Triple("onSurfaceVariant on surface", p.onSurfaceVariant, p.surface),
                Triple("primary on panel", p.primary, p.panel),
                Triple("onPrimary on primary", p.onPrimary, p.primary),
            )) {
                val ratio = contrast(text, background)
                assertTrue(ratio >= 4.5, "${if (dark) "dark" else "light"}: $name is $ratio:1")
            }
        }
    }

    private fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /** WCAG 2 relative luminance of an opaque ARGB colour. */
    private fun luminance(color: Int): Double {
        fun channel(shift: Int): Double {
            val c = (color shr shift and 0xFF) / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }
}
