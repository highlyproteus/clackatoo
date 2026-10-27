// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.clackatoo

import androidx.core.content.edit
import androidx.core.graphics.ColorUtils
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.keyboard.KeyboardTheme
import helium314.keyboard.clackatoo.ClackatooPalette.Companion.MIN_TEXT_CONTRAST
import helium314.keyboard.clackatoo.ClackatooPalette.Companion.Tonal
import helium314.keyboard.latin.App
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.Colors
import helium314.keyboard.latin.utils.prefs
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class ClackatooPaletteTest {
    @Before @After fun resetPrefs() {
        ApplicationProvider.getApplicationContext<App>().prefs().edit { clear() }
    }

    /** Android's system tone `system_<palette>_<n>` as lightness (L*) on the CIE scale: 0 is white, 1000 is black. */
    private val lightnessOfTone = mapOf(0 to 100.0, 10 to 99.0, 50 to 95.0, 100 to 90.0, 200 to 80.0, 300 to 70.0,
        400 to 60.0, 500 to 50.0, 600 to 40.0, 700 to 30.0, 800 to 20.0, 900 to 10.0, 1000 to 0.0)

    /** A phone's tones for a seed hue (degrees), like Android makes them: faint tint for the neutrals, strong for the accent. */
    private fun tones(hue: Double): (Tonal, Int) -> Int = { palette, n ->
        val chroma = when (palette) { Tonal.NEUTRAL1 -> 4.0; Tonal.NEUTRAL2 -> 8.0; Tonal.ACCENT1 -> 48.0 }
        val radians = Math.toRadians(hue)
        ColorUtils.LABToColor(lightnessOfTone.getValue(n), chroma * cos(radians), chroma * sin(radians))
    }

    /** Seeds: the baseline (7AA2F7, blue), E0AF68 (amber), 9ECE6A (green), and a red and a violet. */
    private val seedHues = listOf(262.0, 75.0, 135.0, 30.0, 310.0)

    private fun palettes(dark: Boolean) =
        seedHues.map { ClackatooPalette.fromTones(dark, tones(it)).withReadableText() } + ClackatooPalette.fallback(dark)

    /** Every pair of (text, what it is drawn on) in a palette that the keyboard draws. */
    private fun textOnKeys(p: ClackatooPalette): List<Triple<String, Int, Int>> {
        val backgrounds = mapOf(
            "surface" to p.surface, "letter key" to p.surfaceContainerHigh, "function key" to p.surfaceContainerHighest,
            "pressed letter key" to p.pressed(p.surfaceContainerHigh), "pressed function key" to p.pressed(p.surfaceContainerHighest),
            "Enter key" to p.actionKey,
        )
        return backgrounds.flatMap { (name, background) ->
            // hint letters are only required to be readable on keys at rest (a pressed key is a moment) and the Enter key has none
            listOfNotNull(Triple("label on $name", p.onSurface, background),
                if (name.startsWith("pressed") || name == "Enter key") null else Triple("hint on $name", p.onSurfaceVariant, background))
        } + Triple("Enter glyph on Enter key", p.actionKeyContent, p.actionKey) + Triple("selected suggestion on surface", p.selectedText, p.surface)
    }

    @Test fun labelsAndHintsAreReadableOnEveryKeyInDarkAndLight() {
        for (dark in listOf(true, false)) for ((i, p) in palettes(dark).withIndex()) for ((name, text, background) in textOnKeys(p))
            assertTrue(ClackatooPalette.contrast(text, background) >= MIN_TEXT_CONTRAST,
                "dark=$dark palette $i: $name has ${ClackatooPalette.contrast(text, background)}:1")
    }

    @Test fun theThreeSurfacesStayApart() {
        for (dark in listOf(true, false)) for ((i, p) in palettes(dark).withIndex()) {
            assertNotEquals(p.surface, p.surfaceContainerHigh, "dark=$dark palette $i")
            assertNotEquals(p.surfaceContainerHigh, p.surfaceContainerHighest, "dark=$dark palette $i")
        }
    }

    @Test fun oddSystemColoursDoNotMakeLabelsUnreadable() {
        // labels that nearly match the keys, as an unusual overlay could produce
        val odd = ClackatooPalette(true, 0xFF202020.toInt(), 0xFF282828.toInt(), 0xFF303030.toInt(), 0xFF383838.toInt(), 0xFF404040.toInt(), 0xFF606060.toInt())
        val oddLight = ClackatooPalette(false, 0xFFF0F0F0.toInt(), 0xFFE8E8E8.toInt(), 0xFFE0E0E0.toInt(), 0xFFD0D0D0.toInt(), 0xFFC8C8C8.toInt(), 0xFFA0A0A0.toInt())
        for (p in listOf(odd, oddLight).map { it.withReadableText() })
            for ((name, text, background) in textOnKeys(p))
                assertTrue(ClackatooPalette.contrast(text, background) >= MIN_TEXT_CONTRAST, "$name has ${ClackatooPalette.contrast(text, background)}:1")
        assertEquals(0xFFFFFFFF.toInt(), odd.withReadableText().onSurface)
        assertEquals(0xFF000000.toInt(), oddLight.withReadableText().onSurface)
    }

    @Test fun hairlineIsOnSurfaceAtTwelveAndSixteenPercent() {
        val dark = ClackatooPalette.fallback(true).hairline
        val light = ClackatooPalette.fallback(false).hairline
        assertEquals(0.12, (dark ushr 24) / 255.0, 0.005)
        assertEquals(0.16, (light ushr 24) / 255.0, 0.005)
        assertEquals(ClackatooPalette.fallback(true).onSurface and 0xFFFFFF, dark and 0xFFFFFF)
    }

    @Test fun fallbackIsTheDesignTable() {
        val dark = ClackatooPalette.fallback(true)
        assertEquals(0xFF0E0F11.toInt(), dark.surface)
        assertEquals(0xFF1E2024.toInt(), dark.surfaceContainerHigh)
        assertEquals(0xFF282B30.toInt(), dark.surfaceContainerHighest)
        assertEquals(0xFFECEDEF.toInt(), dark.onSurface)
        assertEquals(0xFFA3A7AE.toInt(), dark.onSurfaceVariant)
        assertEquals(0xFF9DB8F5.toInt(), dark.primary)
        val light = ClackatooPalette.fallback(false)
        assertEquals(0xFFF5F6F8.toInt(), light.surface)
        assertEquals(0xFFE6E8EC.toInt(), light.surfaceContainerHigh)
        assertEquals(0xFFDADDE2.toInt(), light.surfaceContainerHighest)
        assertEquals(0xFF15171A.toInt(), light.onSurface)
        assertEquals(0xFF5A5F67.toInt(), light.onSurfaceVariant)
        assertEquals(0xFF3A64C8.toInt(), light.primary)
    }

    /** What the keyboard really draws, from the colours the app builds, for the phone's dark and light theme. */
    private fun pairsOf(colors: Colors) = listOf(
        Triple("key label on letter key", ColorType.KEY_TEXT, ColorType.KEY_BACKGROUND),
        Triple("key label on function key", ColorType.FUNCTIONAL_KEY_TEXT, ColorType.FUNCTIONAL_KEY_BACKGROUND),
        Triple("key label on space bar", ColorType.KEY_TEXT, ColorType.SPACE_BAR_BACKGROUND),
        Triple("hint on letter key", ColorType.KEY_HINT_TEXT, ColorType.KEY_BACKGROUND),
        Triple("space bar text on space bar", ColorType.SPACE_BAR_TEXT, ColorType.SPACE_BAR_BACKGROUND),
        Triple("Enter on Enter key", ColorType.ACTION_KEY_ICON, ColorType.ACTION_KEY_BACKGROUND),
        Triple("popup key label on popup", ColorType.POPUP_KEY_TEXT, ColorType.POPUP_KEYS_BACKGROUND),
        Triple("key preview", ColorType.KEY_PREVIEW_TEXT, ColorType.KEY_PREVIEW_BACKGROUND),
        Triple("suggestion on strip", ColorType.SUGGESTED_WORD, ColorType.STRIP_BACKGROUND),
        Triple("selected suggestion on strip", ColorType.SUGGESTION_AUTO_CORRECT, ColorType.STRIP_BACKGROUND),
        Triple("toolbar icon on strip", ColorType.TOOL_BAR_KEY, ColorType.STRIP_BACKGROUND),
        Triple("emoji on keyboard", ColorType.EMOJI_KEY_TEXT, ColorType.MAIN_BACKGROUND),
    ).map { (name, text, background) -> Triple(name, colors.get(text), colors.get(background)) }

    @Test fun appColoursKeepLabelsReadableInDarkAndLight() {
        val context = ApplicationProvider.getApplicationContext<App>()
        for (dark in listOf(true, false)) {
            val colors = ClackatooStyle.colors(context, KeyboardTheme.STYLE_CLACKATOO, true, dark, null)
            for ((name, text, background) in pairsOf(colors))
                assertTrue(ClackatooPalette.contrast(text, background) >= MIN_TEXT_CONTRAST,
                    "dark=$dark: $name has ${ClackatooPalette.contrast(text, background)}:1")
        }
    }

    @Test fun coloursAreRebuiltWhenThePhonesDarkThemeChanges() {
        val context = ApplicationProvider.getApplicationContext<App>()
        RuntimeEnvironment.setQualifiers("+notnight")
        val day = KeyboardTheme.getColorsForCurrentTheme(context)
        assertFalse(day.haveColorsChanged(context))
        RuntimeEnvironment.setQualifiers("+night")
        assertTrue(day.haveColorsChanged(context)) // the keyboard sees this and rebuilds itself the next time it opens
        val night = KeyboardTheme.getColorsForCurrentTheme(context)
        assertFalse(night.haveColorsChanged(context))
        assertNotEquals(day.get(ColorType.MAIN_BACKGROUND), night.get(ColorType.MAIN_BACKGROUND))
        RuntimeEnvironment.setQualifiers("+notnight")
    }

    @Test fun darkThemeSwitchOfThePhoneIsFollowedEvenWithoutTheKeyboardsDayNightSetting() {
        val context = ApplicationProvider.getApplicationContext<App>()
        context.prefs().edit { putBoolean(helium314.keyboard.latin.settings.Settings.PREF_THEME_DAY_NIGHT, false) }
        RuntimeEnvironment.setQualifiers("+night")
        val night = KeyboardTheme.getColorsForCurrentTheme(context)
        assertEquals(ClackatooPalette.resolve(context, true).surface, night.get(ColorType.MAIN_BACKGROUND))
        RuntimeEnvironment.setQualifiers("+notnight")
    }
}
