// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.keyboard.KeyboardTheme
import helium314.keyboard.latin.settings.Settings
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What a tap on a theme swatch of the home screen writes. */
@RunWith(RobolectricTestRunner::class)
class ThemeSelectionTest {
    private val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("theme-selection-test", Context.MODE_PRIVATE)

    private fun fresh() = prefs.edit { clear() }

    @Test fun `a tap sets the day theme, or the night theme only when day and night differ and the phone is dark`() {
        assertEquals(Settings.PREF_THEME_COLORS, ThemeSelection.prefKey(ThemeSelection.editsNight(dayNightOn = false, phoneIsNight = true)))
        assertEquals(Settings.PREF_THEME_COLORS, ThemeSelection.prefKey(ThemeSelection.editsNight(dayNightOn = true, phoneIsNight = false)))
        assertEquals(Settings.PREF_THEME_COLORS_NIGHT, ThemeSelection.prefKey(ThemeSelection.editsNight(dayNightOn = true, phoneIsNight = true)))
    }

    @Test fun `applying for the day leaves the night theme alone, and the other way round`() {
        fresh()
        ThemeSelection.apply(prefs, "omarchy_nord", night = false)
        assertEquals("omarchy_nord", prefs.getString(Settings.PREF_THEME_COLORS, null))
        assertNull(prefs.getString(Settings.PREF_THEME_COLORS_NIGHT, null))
        ThemeSelection.apply(prefs, ThemeCatalog.GLASS, night = true)
        assertEquals("omarchy_nord", prefs.getString(Settings.PREF_THEME_COLORS, null))
        assertEquals(ThemeCatalog.GLASS, prefs.getString(Settings.PREF_THEME_COLORS_NIGHT, null))
        assertEquals(ThemeCatalog.GLASS, ThemeSelection.current(prefs, night = true))
    }

    @Test fun `glass and Omarchy themes switch to the Clackatoo style, others leave the style`() {
        fresh()
        prefs.edit { putString(Settings.PREF_THEME_STYLE, KeyboardTheme.STYLE_MATERIAL) }
        ThemeSelection.apply(prefs, "amoled", night = false)
        assertEquals(KeyboardTheme.STYLE_MATERIAL, prefs.getString(Settings.PREF_THEME_STYLE, null))
        ThemeSelection.apply(prefs, "omarchy_gruvbox", night = false)
        assertEquals(KeyboardTheme.STYLE_CLACKATOO, prefs.getString(Settings.PREF_THEME_STYLE, null))
        prefs.edit { putString(Settings.PREF_THEME_STYLE, KeyboardTheme.STYLE_MATERIAL) }
        ThemeSelection.apply(prefs, ThemeCatalog.GLASS_DARK, night = false)
        assertEquals(KeyboardTheme.STYLE_CLACKATOO, prefs.getString(Settings.PREF_THEME_STYLE, null))
    }

    @Test fun `the swatches show the current theme first, then Glass and two Omarchy themes, without repeats`() {
        val swatches = ThemeSelection.swatches(KeyboardTheme.THEME_CLACKATOO)
        assertEquals(ThemeSelection.COUNT, swatches.size)
        assertEquals(KeyboardTheme.THEME_CLACKATOO, swatches[0])
        assertEquals(ThemeCatalog.GLASS, swatches[1])
        assertTrue(swatches.drop(2).all { ThemeCatalog.isOmarchy(it) })
        // the current theme is one of the usual ones: it is not offered twice and another one takes its place
        val glass = ThemeSelection.swatches(ThemeCatalog.GLASS)
        assertEquals(ThemeCatalog.GLASS, glass[0])
        assertEquals(ThemeSelection.COUNT, glass.size)
        assertEquals(glass.size, glass.distinct().size)
        val nord = ThemeSelection.swatches("omarchy_tokyo-night")
        assertEquals("omarchy_tokyo-night", nord[0])
        assertEquals(nord.size, nord.distinct().size)
        // every theme offered exists
        for (id in swatches + glass + nord) assertTrue(id == KeyboardTheme.THEME_CLACKATOO || ThemeCatalog.isGlass(id) || ThemeCatalog.isOmarchy(id), id)
    }
}
