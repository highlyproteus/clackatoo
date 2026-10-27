// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.clackatoo

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.keyboard.KeyboardTheme
import helium314.keyboard.latin.App
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // needed to read back drawn pixels
class ClackatooStyleTest {
    // the preferences are shared between tests, so every test starts from the defaults
    @Before @After fun resetPrefs() {
        ApplicationProvider.getApplicationContext<App>().prefs().edit { clear() }
    }

    private val size = 100
    private val fill = 0xFF24283B.toInt()
    private val border = 0xFF414868.toInt()
    private val accent = 0xFF7AA2F7.toInt()

    private fun drawKey(pressed: Boolean = false, stroke: Float = 2f, radius: Float = 12f): Bitmap {
        val d = ClackatooKeyDrawable(radius, stroke, border, accent)
        d.setTintList(ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_pressed), intArrayOf(-android.R.attr.state_pressed)),
            intArrayOf(0xFF3A4060.toInt(), fill)
        ))
        d.setBounds(0, 0, size, size)
        d.state = if (pressed) intArrayOf(android.R.attr.state_pressed) else intArrayOf()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        d.draw(Canvas(bitmap))
        return bitmap
    }

    @Test fun keyIsFilledBordered_andSquare() {
        val b = drawKey()
        assertEquals(fill, b.getPixel(size / 2, size / 2))
        assertEquals(border, b.getPixel(size / 2, 0)) // top edge, outermost pixel row is the border
        assertEquals(border, b.getPixel(0, size / 2))
        assertEquals(Color.TRANSPARENT, b.getPixel(0, 0)) // corner is cut by the radius
        assertNotEquals(Color.TRANSPARENT, b.getPixel(4, 4)) // but only a little: square, not a pill
        assertEquals(fill, b.getPixel(size / 2, 4)) // border is thin
    }

    @Test fun pressedKeyUsesPressedFillAndAccentBorder() {
        val b = drawKey(pressed = true)
        assertEquals(0xFF3A4060.toInt(), b.getPixel(size / 2, size / 2))
        assertEquals(accent, b.getPixel(size / 2, 0))
    }

    @Test fun noBorderMeansFillOnly() {
        val b = drawKey(stroke = 0f)
        assertEquals(fill, b.getPixel(size / 2, 0))
    }

    @Test fun clackatooIsTheDefaultStyleAndColours() {
        assertEquals(KeyboardTheme.STYLE_CLACKATOO, Defaults.PREF_THEME_STYLE)
        assertEquals(KeyboardTheme.THEME_CLACKATOO, Defaults.PREF_THEME_COLORS)
        assertEquals(KeyboardTheme.THEME_CLACKATOO, Defaults.PREF_THEME_COLORS_NIGHT)
        assertEquals(KeyboardTheme.STYLE_CLACKATOO, KeyboardTheme.STYLES.first())
    }

    @Test fun defaultColorsAreThePhonesColours() {
        val context = ApplicationProvider.getApplicationContext<App>()
        val colors = KeyboardTheme.getColorsForCurrentTheme(context)
        val palette = ClackatooPalette.resolve(context, false)
        assertEquals(palette.surface, colors.get(ColorType.MAIN_BACKGROUND))
        assertEquals(palette.surfaceContainerHigh, colors.get(ColorType.KEY_BACKGROUND))
        assertEquals(palette.surfaceContainerHighest, colors.get(ColorType.FUNCTIONAL_KEY_BACKGROUND))
        assertEquals(palette.hairline, colors.keyBorder)
        assertEquals(palette.onSurface, colors.get(ColorType.KEY_TEXT))
        assertEquals(palette.onSurfaceVariant, colors.get(ColorType.KEY_HINT_TEXT))
        assertEquals(palette.primary, colors.get(ColorType.EMOJI_CATEGORY_SELECTED)) // the accent: selected things
        assertTrue(colors.hasKeyBorders)
        assertEquals(KeyboardTheme.STYLE_CLACKATOO, colors.themeStyle)
    }

    @Test fun keyBackgroundsAreClackatooDrawables() {
        val context = ApplicationProvider.getApplicationContext<App>()
        val colors = KeyboardTheme.getColorsForCurrentTheme(context)
        for (type in listOf(ColorType.KEY_BACKGROUND, ColorType.FUNCTIONAL_KEY_BACKGROUND, ColorType.SPACE_BAR_BACKGROUND, ColorType.ACTION_KEY_BACKGROUND,
            ColorType.POPUP_KEYS_BACKGROUND, ColorType.MORE_SUGGESTIONS_WORD_BACKGROUND))
            assertTrue(ClackatooStyle.keyBackground(context.resources, colors, type) is ClackatooKeyDrawable, type.name)
        assertEquals(null, ClackatooStyle.keyBackground(context.resources, colors, ColorType.KEY_TEXT))
    }

    @Test fun otherStylesKeepUpstreamBehaviour() {
        val context = ApplicationProvider.getApplicationContext<App>()
        context.prefs().edit {
            putString(Settings.PREF_THEME_STYLE, KeyboardTheme.STYLE_MATERIAL)
            putString(Settings.PREF_THEME_COLORS, KeyboardTheme.THEME_DARK)
            putString(Settings.PREF_THEME_COLORS_NIGHT, KeyboardTheme.THEME_DARK)
        }
        assertFalse(KeyboardTheme.hasKeyBorders(context.prefs())) // upstream default, Clackatoo only forces borders for its own style
        val colors = KeyboardTheme.getColorsForCurrentTheme(context)
        assertEquals(KeyboardTheme.STYLE_MATERIAL, colors.themeStyle)
        assertFalse(colors.hasKeyBorders)
        assertEquals(0xFF263238.toInt(), colors.get(ColorType.MAIN_BACKGROUND)) // HeliBoard's own dark theme
        assertFalse(ClackatooStyle.isActive(context))
        assertEquals(null, ClackatooStyle.defaultTypeface(context)) // no Clackatoo font outside the Clackatoo style
        context.prefs().edit { putBoolean(Settings.PREF_THEME_KEY_BORDERS, true) }
        assertTrue(KeyboardTheme.hasKeyBorders(context.prefs())) // user's border switch still works
    }

    @Test fun clackatooStyleBringsInter() {
        val context = ApplicationProvider.getApplicationContext<App>()
        assertTrue(ClackatooStyle.isActive(context))
        for (asset in listOf(ClackatooStyle.FONT_ASSET, ClackatooStyle.FONT_MEDIUM_ASSET))
            context.assets.open(asset).use { assertTrue(it.readBytes().size > 100_000, asset) }
        assertNotEquals(null, ClackatooStyle.mediumTypeface(context))
    }
}
