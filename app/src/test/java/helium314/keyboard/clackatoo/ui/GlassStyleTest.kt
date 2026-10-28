// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo.ui

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import helium314.keyboard.clackatoo.ClackatooPalette
import helium314.keyboard.clackatoo.ClackatooPalette.Companion.MIN_TEXT_CONTRAST
import helium314.keyboard.clackatoo.ClackatooPalette.Companion.Tonal
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

/** Text on the glass settings keeps 4.5:1 over any wallpaper, in light and dark mode. */
@RunWith(RobolectricTestRunner::class)
class GlassStyleTest {
    /** The Material 3 roles the settings draw with. */
    private class Roles(val name: String, val surface: Int, val container: Int, val onSurface: Int, val onSurfaceVariant: Int)

    private val lightnessOfTone = mapOf(0 to 100.0, 10 to 99.0, 50 to 95.0, 100 to 90.0, 200 to 80.0, 300 to 70.0,
        400 to 60.0, 500 to 50.0, 600 to 40.0, 700 to 30.0, 800 to 20.0, 900 to 10.0, 1000 to 0.0)

    private fun tones(hue: Double): (Tonal, Int) -> Int = { palette, n ->
        val chroma = when (palette) { Tonal.NEUTRAL1 -> 4.0; Tonal.NEUTRAL2 -> 8.0; Tonal.ACCENT1 -> 48.0 }
        val radians = Math.toRadians(hue)
        ColorUtils.LABToColor(lightnessOfTone.getValue(n), chroma * cos(radians), chroma * sin(radians))
    }

    private fun roles(dark: Boolean): List<Roles> {
        // the phone's colours on Android 12+ (dynamic colour): the system tones for a few wallpaper hues
        val dynamic = listOf(262.0, 75.0, 135.0, 30.0, 310.0).map { hue ->
            val p = ClackatooPalette.fromTones(dark, tones(hue))
            Roles("dynamic $hue", p.surface, p.surfaceContainerHigh, p.onSurface, p.onSurfaceVariant)
        }
        // Material 3's baseline colours, used below Android 12
        val s = if (dark) darkColorScheme() else lightColorScheme()
        val baseline = Roles("baseline", s.surface.toArgb(), s.surfaceContainerHigh.toArgb(), s.onSurface.toArgb(), s.onSurfaceVariant.toArgb())
        val f = ClackatooPalette.fallback(dark)
        val fallback = Roles("fallback", f.surface, f.surfaceContainerHigh, f.onSurface, f.onSurfaceVariant)
        return dynamic + baseline + fallback
    }

    /** Wallpapers: the extremes, greys and saturated colours; the window blends them with the scrim. */
    private val wallpapers = listOf(
        Color.Black, Color.White, Color.Gray, Color.Red, Color.Green, Color.Blue, Color.Yellow, Color.Cyan, Color.Magenta,
        Color(0xFF202020), Color(0xFFE0E0E0), Color(0xFFFFA500),
    ).map { it.toArgb() }

    private fun check(dark: Boolean) {
        for (r in roles(dark)) for (wallpaper in wallpapers) {
            val onWindow = GlassStyle.scrimOver(r.surface, wallpaper)
            val onPanel = GlassStyle.panelOver(r.container, r.surface, wallpaper)
            val cases = listOf(
                "title on the window" to ClackatooPalette.contrast(r.onSurface, onWindow),
                "text on a panel" to ClackatooPalette.contrast(r.onSurface, onPanel),
                "subtitle on a panel" to ClackatooPalette.contrast(r.onSurfaceVariant, onPanel),
            )
            for ((what, ratio) in cases)
                assertTrue(ratio >= MIN_TEXT_CONTRAST, "${if (dark) "dark" else "light"} ${r.name}: $what is $ratio:1 over wallpaper ${Integer.toHexString(wallpaper)}")
        }
    }

    @Test fun `text keeps 4_5 to 1 over any wallpaper in light mode`() = check(dark = false)

    @Test fun `text keeps 4_5 to 1 over any wallpaper in dark mode`() = check(dark = true)

    @Test fun `the check of the ready card is readable on its green`() {
        assertTrue(ClackatooPalette.contrast(Color.White.toArgb(), READY_GREEN.toArgb()) >= MIN_TEXT_CONTRAST)
    }

    @Test fun `the scrim and the panels are translucent`() {
        assertTrue(GlassStyle.SCRIM_ALPHA in 0.3f..0.95f)
        assertTrue(GlassStyle.PANEL_ALPHA < 1f)
    }
}
