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
    private class Roles(
        val name: String, val surface: Int, val container: Int, val onSurface: Int, val onSurfaceVariant: Int,
        val primary: Int, val error: Int,
    )

    private val lightnessOfTone = mapOf(0 to 100.0, 10 to 99.0, 50 to 95.0, 100 to 90.0, 200 to 80.0, 300 to 70.0,
        400 to 60.0, 500 to 50.0, 600 to 40.0, 700 to 30.0, 800 to 20.0, 900 to 10.0, 1000 to 0.0)

    private fun tones(hue: Double): (Tonal, Int) -> Int = { palette, n ->
        val chroma = when (palette) { Tonal.NEUTRAL1 -> 4.0; Tonal.NEUTRAL2 -> 8.0; Tonal.ACCENT1 -> 48.0 }
        val radians = Math.toRadians(hue)
        ColorUtils.LABToColor(lightnessOfTone.getValue(n), chroma * cos(radians), chroma * sin(radians))
    }

    private fun roles(dark: Boolean): List<Roles> {
        val s = if (dark) darkColorScheme() else lightColorScheme()
        // the phone's colours on Android 12+ (dynamic colour): the system tones for a few wallpaper hues
        val dynamic = listOf(262.0, 75.0, 135.0, 30.0, 310.0).map { hue ->
            val p = ClackatooPalette.fromTones(dark, tones(hue))
            Roles("dynamic $hue", p.surface, p.surfaceContainerHigh, p.onSurface, p.onSurfaceVariant, p.primary, s.error.toArgb())
        }
        // Material 3's baseline colours, used below Android 12
        val baseline = Roles("baseline", s.surface.toArgb(), s.surfaceContainerHigh.toArgb(), s.onSurface.toArgb(),
            s.onSurfaceVariant.toArgb(), s.primary.toArgb(), s.error.toArgb())
        val f = ClackatooPalette.fallback(dark)
        val fallback = Roles("fallback", f.surface, f.surfaceContainerHigh, f.onSurface, f.onSurfaceVariant, f.primary, s.error.toArgb())
        return dynamic + baseline + fallback
    }

    /** Wallpapers: the extremes, greys and saturated colours; the window blends them with the scrim. */
    private val wallpapers = listOf(
        Color.Black, Color.White, Color.Gray, Color.Red, Color.Green, Color.Blue, Color.Yellow, Color.Cyan, Color.Magenta,
        Color(0xFF202020), Color(0xFFE0E0E0), Color(0xFFFFA500), Color(0xFF808080), Color(0xFF00FF80), Color(0xFF8000FF),
    ).map { it.toArgb() } + java.util.Random(12).let { random -> List(60) { random.nextInt() or (0xFF shl 24) } }

    /**
     * Every text surface of the settings, each against its own backing over [wallpaper]: what is on the clear window
     * (page title, section headers, hints, toolbar icons) sits on a frosted backing; the rest on a panel or a dialog.
     */
    private fun surfaces(r: Roles, wallpaper: Int, blur: Boolean): List<Pair<String, Double>> {
        // the settings use these three through `glassColorScheme`
        fun readable(color: Int) = GlassStyle.readable(color, r.onSurface, r.container, r.surface)
        val onSurfaceVariant = readable(r.onSurfaceVariant)
        val primary = readable(r.primary)
        val error = readable(r.error)
        val backing = GlassStyle.backingOver(r.container, r.surface, wallpaper, blur)
        val panel = GlassStyle.panelOver(r.container, r.surface, wallpaper, blur)
        val dialog = GlassStyle.dialogOver(r.container, r.surface, wallpaper, blur)
        fun c(text: Int, background: Int) = ClackatooPalette.contrast(text, background)
        return listOf(
            "page title on its backing" to c(r.onSurface, backing),
            "toolbar icons on their backing" to c(r.onSurface, backing),
            "section header on its backing" to c(r.onSurface, backing),
            "hint on its backing" to c(r.onSurface, backing),
            "row title on a panel" to c(r.onSurface, panel),
            "row subtitle on a panel" to c(onSurfaceVariant, panel),
            "trailing value on a panel" to c(onSurfaceVariant, panel),
            "search field text on a panel" to c(r.onSurface, panel),
            "search placeholder on a panel" to c(onSurfaceVariant, panel),
            "model card state on a panel" to c(onSurfaceVariant, panel),
            "model card failure on a panel" to c(error, panel),
            "selected theme name and buttons on a panel" to c(primary, panel),
            // the home screen: header words on their backing, tiles, checklist, quick buttons, swatches and the try field on panels
            "home header title on its backing" to c(r.onSurface, backing),
            "home header subtitle on its backing" to c(onSurfaceVariant, backing),
            "home tile title on a panel" to c(r.onSurface, panel),
            "home tile state on a panel" to c(onSurfaceVariant, panel),
            "home checklist title on a panel" to c(r.onSurface, panel),
            "home checklist suggestion and progress on a panel" to c(onSurfaceVariant, panel),
            "home checklist failure on a panel" to c(error, panel),
            "home quick button label on a panel" to c(r.onSurface, panel),
            "theme swatch name on a panel" to c(r.onSurface, panel),
            "selected theme swatch name on a panel" to c(primary, panel),
            "try field text on a panel" to c(r.onSurface, panel),
            "try field placeholder on a panel" to c(onSurfaceVariant, panel),
            "dialog title" to c(r.onSurface, dialog),
            "dialog text" to c(onSurfaceVariant, dialog),
            "dialog buttons" to c(primary, dialog),
        )
    }

    private fun check(dark: Boolean) {
        // the worst ratio of each surface, so that a failure lists everything to fix at once
        val worst = linkedMapOf<String, Pair<Double, String>>()
        for (r in roles(dark)) for (wallpaper in wallpapers) for (blur in listOf(true, false))
            for ((what, ratio) in surfaces(r, wallpaper, blur))
                if (ratio < (worst[what]?.first ?: Double.MAX_VALUE))
                    worst[what] = ratio to "${r.name}, ${if (blur) "blur" else "no blur"}, wallpaper ${Integer.toHexString(wallpaper)}"
        val failing = worst.filterValues { it.first < MIN_TEXT_CONTRAST }
        assertTrue(failing.isEmpty(), (if (dark) "dark" else "light") + ": " + failing.entries.joinToString("; ") { "${it.key} is ${it.value.first}:1 (${it.value.second})" })
    }

    @Test fun `every text surface keeps 4_5 to 1 over any wallpaper in light mode`() = check(dark = false)

    @Test fun `every text surface keeps 4_5 to 1 over any wallpaper in dark mode`() = check(dark = true)

    @Test fun `without glass the surfaces are solid and keep 4_5 to 1`() {
        for (dark in listOf(false, true)) for (r in roles(dark)) {
            val solid = listOf(r.surface or (0xFF shl 24), r.container or (0xFF shl 24))
            // home screen: tile and checklist titles, swatch names and the try field's text, and tile states, suggestions and the placeholder
            for (background in solid) for ((what, text) in listOf(
                "title" to r.onSurface, "subtitle" to r.onSurfaceVariant, "home tile, checklist, swatch and try field text" to r.onSurface,
                "home tile state, suggestion and try field placeholder" to r.onSurfaceVariant,
            ))
                assertTrue(ClackatooPalette.contrast(text, background) >= MIN_TEXT_CONTRAST, "${if (dark) "dark" else "light"} ${r.name}: solid $what")
        }
    }

    @Test fun `dialogs are solid without blur`() {
        val r = roles(false).first()
        assertTrue(GlassStyle.dialogOver(r.container, r.surface, Color.Black.toArgb(), blur = false) == (r.container or (0xFF shl 24)))
    }

    @Test fun `the clear window is lightly tinted with blur, heavier without, and frosted parts are stronger than clear ones`() {
        assertTrue(GlassStyle.SCRIM_ALPHA_BLUR in 0.15f..0.35f)
        assertTrue(GlassStyle.SCRIM_ALPHA_FLAT > GlassStyle.SCRIM_ALPHA_BLUR)
        assertTrue(GlassStyle.PANEL_ALPHA_LIGHT in 0.6f..0.85f && GlassStyle.PANEL_ALPHA_DARK in 0.6f..0.85f)
        assertTrue(GlassStyle.BACKING_ALPHA_LIGHT >= GlassStyle.PANEL_ALPHA_LIGHT && GlassStyle.BACKING_ALPHA_DARK >= GlassStyle.PANEL_ALPHA_DARK)
        assertTrue(GlassStyle.DIALOG_ALPHA_LIGHT >= GlassStyle.BACKING_ALPHA_LIGHT && GlassStyle.DIALOG_ALPHA_DARK >= GlassStyle.BACKING_ALPHA_DARK)
    }

    @Test fun `the check of the ready card is readable on its green`() {
        assertTrue(ClackatooPalette.contrast(Color.White.toArgb(), READY_GREEN.toArgb()) >= MIN_TEXT_CONTRAST)
    }

    @Test fun `panels, backings and dialogs are translucent`() {
        for (a in listOf(GlassStyle.PANEL_ALPHA_LIGHT, GlassStyle.PANEL_ALPHA_DARK, GlassStyle.BACKING_ALPHA_LIGHT,
            GlassStyle.BACKING_ALPHA_DARK, GlassStyle.DIALOG_ALPHA_LIGHT, GlassStyle.DIALOG_ALPHA_DARK))
            assertTrue(a < 1f)
    }
}
