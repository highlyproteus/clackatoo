// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.clackatoo

import android.graphics.Color
import androidx.core.content.edit
import androidx.core.graphics.ColorUtils
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.clackatoo.ClackatooPalette.Companion.BLACK
import helium314.keyboard.clackatoo.ClackatooPalette.Companion.MIN_TEXT_CONTRAST
import helium314.keyboard.clackatoo.ClackatooPalette.Companion.WHITE
import helium314.keyboard.keyboard.ColorSetting
import helium314.keyboard.keyboard.KeyboardTheme
import helium314.keyboard.latin.App
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.Colors
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class ThemePalettesTest {
    @Before @After fun resetPrefs() {
        ApplicationProvider.getApplicationContext<App>().prefs().edit { clear() }
    }

    private val expectedOmarchy = listOf(
        "catppuccin-latte", "catppuccin", "ethereal", "everforest", "flexoki-light", "gruvbox", "hackerman", "jade-horse",
        "kanagawa", "last-horizon", "lumon", "lupine", "matte-black", "miasma", "nord", "retro-82", "ristretto", "rose-pine",
        "solitude", "starship", "tokyo-night", "vantablack", "white",
    )

    /** The colours of the background [type] as they look over [backdrop] (white or black behind the keyboard window). */
    private fun lookOver(colors: Colors, type: ColorType, backdrop: Int): Int {
        val main = ColorUtils.compositeColors(colors.get(ColorType.MAIN_BACKGROUND), backdrop)
        val onKeyboard = type != ColorType.MAIN_BACKGROUND && type != ColorType.STRIP_BACKGROUND &&
            type != ColorType.KEY_PREVIEW_BACKGROUND && type != ColorType.POPUP_KEYS_BACKGROUND
        return ColorUtils.compositeColors(colors.get(type), if (onKeyboard) main else backdrop)
    }

    private fun assertReadable(colors: Colors, what: String) {
        val pairs = listOf(
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
        )
        for ((name, text, background) in pairs) for (backdrop in listOf(WHITE, BLACK)) {
            val ratio = ColorUtils.calculateContrast(colors.get(text), lookOver(colors, background, backdrop))
            assertTrue(ratio >= MIN_TEXT_CONTRAST, "$what: $name has $ratio:1 over ${if (backdrop == WHITE) "white" else "black"}")
        }
    }

    // ---- Omarchy

    @Test fun thereAre23OmarchyThemesWithTheNamesOfThePlan() {
        assertEquals(expectedOmarchy, OMARCHY_THEME_DATA.map { it.id })
        assertEquals(expectedOmarchy.map { OmarchyThemes.ID_PREFIX + it }, OmarchyThemes.all.map { it.id })
        assertEquals(40, OMARCHY_COMMIT.length)
        for (data in OMARCHY_THEME_DATA) assertTrue(Regex("[0-9a-f]{64}").matches(data.sha256), "${data.id} has no checksum")
    }

    @Test fun everyOmarchyPaletteHasReadableLabelsAndHintsOnEveryKeyInEveryState() {
        for (theme in OmarchyThemes.all) {
            val p = theme.palette
            // the palette is already what its own guards would make of it: nothing is left to raise
            assertEquals(p, p.withReadableText(), theme.id)
            assertEquals(p, p.withRaisedText(), theme.id)
            val backgrounds = mapOf(
                "surface" to p.surface, "letter key" to p.surfaceContainerHigh, "function key" to p.surfaceContainerHighest,
                "pressed letter key" to p.pressed(p.surfaceContainerHigh), "pressed function key" to p.pressed(p.surfaceContainerHighest),
                "Enter key" to p.actionKey, "popup" to p.popup,
            )
            for ((name, background) in backgrounds) {
                assertTrue(ClackatooPalette.contrast(p.onSurface, background) >= MIN_TEXT_CONTRAST,
                    "${theme.id}: label on $name has ${ClackatooPalette.contrast(p.onSurface, background)}:1")
                if (name == "surface" || name == "letter key" || name == "function key")
                    assertTrue(ClackatooPalette.contrast(p.onSurfaceVariant, background) >= MIN_TEXT_CONTRAST,
                        "${theme.id}: hint on $name has ${ClackatooPalette.contrast(p.onSurfaceVariant, background)}:1")
            }
            assertTrue(ClackatooPalette.contrast(p.actionKeyContent, p.actionKey) >= MIN_TEXT_CONTRAST, "${theme.id}: Enter glyph")
            assertTrue(ClackatooPalette.contrast(p.selectedText, p.surface) >= MIN_TEXT_CONTRAST, "${theme.id}: selected suggestion")
        }
    }

    @Test fun everyOmarchyThemeKeepsItsLayersApartAndItsLightOrDarkMode() {
        for ((data, theme) in OMARCHY_THEME_DATA.zip(OmarchyThemes.all)) {
            val p = theme.palette
            assertEquals(data.dark, p.dark, theme.id)
            assertEquals(data.background, p.surface, theme.id)
            assertEquals(data.accent, p.primary, theme.id)
            assertTrue(ClackatooPalette.contrast(p.surface, p.surfaceContainerHigh) >= 1.05, "${theme.id}: letter keys do not stand out from the surface")
            assertTrue(ClackatooPalette.contrast(p.surfaceContainerHigh, p.surfaceContainerHighest) >= 1.05, "${theme.id}: function keys do not stand out from letter keys")
            // dark themes are dark, light themes light: the surface is on the side of the label's opposite
            assertEquals(data.dark, ColorUtils.calculateLuminance(p.surface) < 0.5, theme.id)
        }
    }

    @Test fun omarchyThemeColoursReachTheKeyboardThroughTheThemePreference() {
        val context = ApplicationProvider.getApplicationContext<App>()
        for (theme in OmarchyThemes.all) {
            context.prefs().edit { putString(Settings.PREF_THEME_COLORS, theme.id) }
            val colors = KeyboardTheme.getColorsForCurrentTheme(context)
            assertEquals(theme.palette.surface, colors.get(ColorType.MAIN_BACKGROUND), theme.id)
            assertEquals(theme.palette.onSurface, colors.get(ColorType.KEY_TEXT), theme.id)
            assertEquals(theme.palette.surfaceContainerHigh, colors.get(ColorType.KEY_BACKGROUND), theme.id)
            assertTrue(!colors.translucentWindow)
            assertReadable(colors, theme.id)
        }
    }

    // ---- glass

    private val phonePalettes = listOf(true, false).map { ClackatooPalette.fallback(it) }

    /** [layers] drawn one over the other over [backdrop]: what a pixel looks like. */
    private fun over(backdrop: Int, vararg layers: Int) = layers.fold(backdrop) { below, layer -> ColorUtils.compositeColors(layer, below) }

    /** Every glass theme, in the phone's dark and light mode, with and without blur. */
    private fun forEveryGlass(check: (palette: ClackatooPalette, colors: Colors, what: String) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<App>()
        for (id in ThemeCatalog.glass) for (dark in listOf(true, false)) for (blur in listOf(true, false)) {
            val palette = when (id) {
                ThemeCatalog.GLASS_DARK -> GlassPalette.fixed(true, blur)
                ThemeCatalog.GLASS_LIGHT -> GlassPalette.fixed(false, blur)
                else -> GlassPalette.build(ClackatooPalette.resolve(context, dark), blur)
            }
            val colors = ClackatooStyle.paletteColors(context, KeyboardTheme.STYLE_CLACKATOO, true, null, true, if (blur) 28 else 0) { palette }
            assertTrue(colors.translucentWindow)
            check(palette, colors, "$id dark=$dark blur=$blur")
        }
    }

    /**
     * Every piece of text or icon of the glass keyboard against what it is really drawn on: its own backing (a key's tile, the strip's
     * backing, a popup) over the clear surface of the window over each backdrop that can be behind the keyboard.
     * A text surface that is not listed here is not guarded.
     */
    private fun assertGlassReadable(colors: Colors, what: String) {
        val main = colors.get(ColorType.MAIN_BACKGROUND)
        val backing = colors.backing
        val tile = colors.get(ColorType.KEY_BACKGROUND)
        val functionKey = colors.get(ColorType.FUNCTIONAL_KEY_BACKGROUND)
        val spaceBar = colors.get(ColorType.SPACE_BAR_BACKGROUND)
        val enter = colors.get(ColorType.ACTION_KEY_BACKGROUND)
        val popup = colors.get(ColorType.POPUP_KEYS_BACKGROUND)
        // (name, text, layers it is drawn on, from the clear surface up; a popup is drawn over the app and not under the surface)
        val surfaces = listOf(
            // keys
            Triple("key label on letter key", ColorType.KEY_TEXT, listOf(main, tile)),
            Triple("hint letter on letter key", ColorType.KEY_HINT_TEXT, listOf(main, tile)),
            Triple("key icon on letter key", ColorType.KEY_ICON, listOf(main, tile)),
            Triple("label on function key", ColorType.FUNCTIONAL_KEY_TEXT, listOf(main, functionKey)),
            Triple("hint letter on function key", ColorType.KEY_HINT_TEXT, listOf(main, functionKey)),
            Triple("icon on function key", ColorType.KEY_ICON, listOf(main, functionKey)),
            Triple("label on space bar", ColorType.KEY_TEXT, listOf(main, spaceBar)),
            Triple("language name on space bar", ColorType.SPACE_BAR_TEXT, listOf(main, spaceBar)),
            Triple("Enter on Enter key", ColorType.ACTION_KEY_ICON, listOf(main, enter)),
            // the strip: suggestions and the toolbar, on its frosted backing
            Triple("suggestion on strip", ColorType.SUGGESTED_WORD, listOf(main, backing)),
            Triple("typed word on strip", ColorType.SUGGESTION_TYPED_WORD, listOf(main, backing)),
            Triple("valid typed word on strip", ColorType.SUGGESTION_VALID_WORD, listOf(main, backing)),
            Triple("selected suggestion on strip", ColorType.SUGGESTION_AUTO_CORRECT, listOf(main, backing)),
            Triple("toolbar icon on strip", ColorType.TOOL_BAR_KEY, listOf(main, backing)),
            Triple("toolbar arrow on strip", ColorType.TOOL_BAR_EXPAND_KEY, listOf(main, backing, colors.get(ColorType.TOOL_BAR_EXPAND_KEY_BACKGROUND))),
            Triple("emoji tab icon on strip", ColorType.EMOJI_CATEGORY, listOf(main, backing)),
            Triple("remove-suggestion icon on strip", ColorType.REMOVE_SUGGESTION_ICON, listOf(main, backing)),
            // emoji grid and the clipboard
            Triple("emoji on emoji key", ColorType.EMOJI_KEY_TEXT, listOf(main, tile)),
            Triple("clip text on clip key", ColorType.KEY_TEXT, listOf(main, tile)),
            Triple("clipboard placeholder", ColorType.KEY_TEXT, listOf(main, backing)),
            Triple("clipboard icon on strip", ColorType.CLIPBOARD_SUGGESTION_ICON, listOf(main, backing, colors.get(ColorType.CLIPBOARD_SUGGESTION_BACKGROUND))),
            // the voice and check panel: title, text and hints on the backing, buttons like keys
            Triple("panel title and text", ColorType.KEY_TEXT, listOf(main, backing)),
            Triple("panel hint (live text still being spoken)", ColorType.KEY_HINT_TEXT, listOf(main, backing)),
            Triple("panel button", ColorType.KEY_TEXT, listOf(main, tile)),
            Triple("panel main button", ColorType.ACTION_KEY_ICON, listOf(main, enter)),
        )
        for ((name, text, layers) in surfaces) for (backdrop in ClackatooPalette.BACKDROPS) {
            val ratio = ColorUtils.calculateContrast(colors.get(text), over(backdrop, *layers.toIntArray()))
            assertTrue(ratio >= MIN_TEXT_CONTRAST, "$what: $name has $ratio:1 over #${Integer.toHexString(backdrop)}")
        }
        // popups, key previews and the more-suggestions panel are solid, drawn over the keys and the app, and carry text of their own
        for (type in listOf(ColorType.POPUP_KEYS_BACKGROUND, ColorType.KEY_PREVIEW_BACKGROUND, ColorType.MORE_SUGGESTIONS_BACKGROUND, ColorType.ACTION_KEY_POPUP_KEYS_BACKGROUND))
            assertEquals(255, colors.get(type) ushr 24, "$what: $type is not solid")
        val moreSuggestions = listOf(
            "suggestion in more suggestions" to ColorType.SUGGESTED_WORD, "typed word in more suggestions" to ColorType.SUGGESTION_TYPED_WORD,
            "valid word in more suggestions" to ColorType.SUGGESTION_VALID_WORD, "selected word in more suggestions" to ColorType.SUGGESTION_AUTO_CORRECT,
            "hint in more suggestions" to ColorType.MORE_SUGGESTIONS_HINT,
        )
        val textOnPopups = moreSuggestions.map { (name, type) -> Triple(name, type, listOf(colors.get(ColorType.MORE_SUGGESTIONS_BACKGROUND), colors.get(ColorType.MORE_SUGGESTIONS_WORD_BACKGROUND))) } + listOf(
            Triple("popup key label on popup", ColorType.POPUP_KEY_TEXT, listOf(popup)),
            Triple("popup icon on popup", ColorType.POPUP_KEY_ICON, listOf(popup)),
            Triple("popup key label on Enter popup", ColorType.POPUP_KEY_TEXT, listOf(colors.get(ColorType.ACTION_KEY_POPUP_KEYS_BACKGROUND))),
            Triple("key preview", ColorType.KEY_PREVIEW_TEXT, listOf(colors.get(ColorType.KEY_PREVIEW_BACKGROUND))),
        )
        for ((name, text, layers) in textOnPopups) for (backdrop in ClackatooPalette.BACKDROPS) {
            val ratio = ColorUtils.calculateContrast(colors.get(text), over(backdrop, *layers.toIntArray()))
            assertTrue(ratio >= MIN_TEXT_CONTRAST, "$what: $name has $ratio:1 over #${Integer.toHexString(backdrop)}")
        }
    }

    @Test fun glassKeepsEveryTextSurfaceReadableOverWhiteBlackGreyAndSaturatedBackdropsWithAndWithoutBlur() {
        forEveryGlass { _, colors, what -> assertGlassReadable(colors, what) }
    }

    @Test fun theTestCoversWorstCasesOfEveryKind() {
        // white, black, mid grey, the three primaries and the three secondaries
        assertEquals(9, ClackatooPalette.BACKDROPS.distinct().size)
        assertTrue(WHITE in ClackatooPalette.BACKDROPS && BLACK in ClackatooPalette.BACKDROPS && 0xFF808080.toInt() in ClackatooPalette.BACKDROPS)
    }

    @Test fun glassIsClearBetweenTheBackingsAndTintedHeavierWithoutBlur() {
        for (dark in listOf(true, false)) {
            val blurred = GlassPalette.build(ClackatooPalette.fallback(dark), true)
            val plain = GlassPalette.build(ClackatooPalette.fallback(dark), false)
            val tintBlurred = (blurred.surface ushr 24) / 255f
            val tintPlain = (plain.surface ushr 24) / 255f
            // the clear parts: 20-35% where the window blurs
            assertTrue(tintBlurred in 0.20f..0.35f, "dark=$dark blurred tint $tintBlurred")
            // heavier without blur, so that the unblurred app does not shine through
            assertTrue(tintPlain >= 0.5f && tintPlain > tintBlurred + 0.2f, "dark=$dark plain tint $tintPlain")
            // frosted backings: 70-80% (more only where the labels need it), the same with and without blur for the fixed themes
            for (p in listOf(blurred, plain)) {
                val backing = (p.backing ushr 24) / 255f
                assertTrue(backing in 0.70f..0.95f, "dark=$dark backing $backing")
                assertEquals(p.backing, p.surfaceContainerHigh, "the strip's backing is the letter keys' tile")
                assertTrue((p.surfaceContainerHigh ushr 24) in 0xB4..0xF2 && (p.surfaceContainerHighest ushr 24) in 0xB4..0xF2)
                // popups are drawn over keys and over the app, so they are solid
                assertEquals(255, p.popup ushr 24)
            }
            val fixedBlurred = GlassPalette.fixed(dark, true)
            val fixedPlain = GlassPalette.fixed(dark, false)
            assertEquals(fixedBlurred.backing, fixedPlain.backing, "dark=$dark: the backings do not change without blur")
            assertEquals(fixedBlurred.surfaceContainerHighest, fixedPlain.surfaceContainerHighest)
            assertTrue((fixedBlurred.surface ushr 24) < (fixedPlain.surface ushr 24))
        }
    }

    @Test fun otherThemesHaveNoBacking() {
        val context = ApplicationProvider.getApplicationContext<App>()
        for (name in listOf(KeyboardTheme.THEME_DARK, KeyboardTheme.THEME_CLACKATOO, OmarchyThemes.all.first().id)) {
            context.prefs().edit { putString(Settings.PREF_THEME_COLORS, name) }
            val colors = KeyboardTheme.getColorsForCurrentTheme(context)
            assertEquals(Color.TRANSPARENT, colors.backing, name)
        }
    }

    @Test fun glassFollowsTheBlurSettingOfThePhone() {
        val context = ApplicationProvider.getApplicationContext<App>()
        context.prefs().edit { putString(Settings.PREF_THEME_COLORS, ThemeCatalog.GLASS) }
        val colors = KeyboardTheme.getColorsForCurrentTheme(context)
        assertTrue(colors.translucentWindow)
        // robolectric has no cross-window blur: the plain tint, and no blur radius
        assertEquals(0, colors.windowBlurRadiusDp)
        assertEquals(false, colors.haveColorsChanged(context))
    }

    // ---- existing settings keep working

    @Test fun classicThemesAndTheClackatooThemeAreUnchanged() {
        val context = ApplicationProvider.getApplicationContext<App>()
        for (name in listOf(KeyboardTheme.THEME_DARK, KeyboardTheme.THEME_LIGHT, KeyboardTheme.THEME_BLACK, KeyboardTheme.THEME_OCEAN, KeyboardTheme.THEME_CLACKATOO)) {
            context.prefs().edit { putString(Settings.PREF_THEME_COLORS, name) }
            val colors = KeyboardTheme.getColorsForCurrentTheme(context)
            assertTrue(!colors.translucentWindow, name)
            assertEquals(0, colors.windowBlurRadiusDp, name)
        }
        context.prefs().edit { putString(Settings.PREF_THEME_COLORS, KeyboardTheme.THEME_DARK) }
        assertEquals(0xFF263238.toInt(), KeyboardTheme.getColorsForCurrentTheme(context).get(ColorType.MAIN_BACKGROUND))
    }

    @Test fun aUserThemeKeepsItsColoursEvenWithOneOfTheNewNames() {
        val context = ApplicationProvider.getApplicationContext<App>()
        val prefs = context.prefs()
        for (name in listOf("my theme", ThemeCatalog.GLASS, OmarchyThemes.all.first().id)) {
            KeyboardTheme.writeUserColors(prefs, name, listOf(ColorSetting(KeyboardTheme.COLOR_BACKGROUND, false, 0xFF123456.toInt())))
            prefs.edit { putString(Settings.PREF_THEME_COLORS, name) }
            val colors = KeyboardTheme.getColorsForCurrentTheme(context)
            assertEquals(0xFF123456.toInt(), colors.get(ColorType.MAIN_BACKGROUND), name)
            assertTrue(!colors.translucentWindow, name)
        }
    }

    @Test fun newCustomThemesDoNotTakeTheNamesOfBuiltInOnes() {
        val prefs = ApplicationProvider.getApplicationContext<App>().prefs()
        assertNotEquals(ThemeCatalog.GLASS, KeyboardTheme.getUnusedThemeName(ThemeCatalog.GLASS, prefs))
        assertNotEquals("omarchy_nord", KeyboardTheme.getUnusedThemeName("omarchy_nord", prefs))
        assertEquals("my theme", KeyboardTheme.getUnusedThemeName("my theme", prefs))
    }

    @Test fun themesAreGroupedForThePicker() {
        val none = emptyList<String>()
        assertEquals(ThemeGroup.FOLLOW_PHONE, ThemeCatalog.groupOf(KeyboardTheme.THEME_CLACKATOO, none))
        assertEquals(ThemeGroup.GLASS, ThemeCatalog.groupOf(ThemeCatalog.GLASS_DARK, none))
        assertEquals(ThemeGroup.OMARCHY, ThemeCatalog.groupOf("omarchy_nord", none))
        assertEquals(ThemeGroup.CLASSIC, ThemeCatalog.groupOf(KeyboardTheme.THEME_OCEAN, none))
        assertEquals(ThemeGroup.CUSTOM, ThemeCatalog.groupOf("my theme", listOf("my theme")))
        assertNotNull(OmarchyThemes.byId("omarchy_nord"))
        assertNull(OmarchyThemes.byId("nord"))
    }
}
