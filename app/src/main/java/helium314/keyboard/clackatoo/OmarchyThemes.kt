// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo

import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils

/**
 * The colour roles of one Omarchy theme (a `colors.toml`), as generated into [OMARCHY_THEME_DATA] by
 * tools/omarchy-themes/generate.py. Only colours: nothing of Omarchy's wallpapers or other images is used.
 */
internal data class OmarchyColors(
    val id: String,
    val name: String,
    val dark: Boolean,
    /** SHA-256 of the `colors.toml` these colours were read from */
    val sha256: String,
    @ColorInt val accent: Int,
    @ColorInt val selection: Int,
    @ColorInt val muted: Int,
    @ColorInt val background: Int,
    @ColorInt val darkBackground: Int,
    @ColorInt val darkerBackground: Int,
    @ColorInt val lighterBackground: Int,
    @ColorInt val foreground: Int,
    @ColorInt val darkForeground: Int,
    @ColorInt val lightForeground: Int,
    @ColorInt val brightForeground: Int,
)

/** A keyboard colour theme made from an Omarchy theme. [id] is what the theme preference stores. */
class OmarchyTheme internal constructor(val id: String, val name: String, val palette: ClackatooPalette)

object OmarchyThemes {
    /** Prefix of the theme preference's value: `omarchy_nord`. */
    const val ID_PREFIX = "omarchy_"
    /** Where Omarchy's colours come from, for the About text. */
    const val SOURCE = "https://github.com/basecamp/omarchy"
    val commit get() = OMARCHY_COMMIT

    /** All 23 themes, in the order of the generator (alphabetical, as in Omarchy). */
    val all: List<OmarchyTheme> = OMARCHY_THEME_DATA.map { OmarchyTheme(ID_PREFIX + it.id, it.name, palette(it)) }

    private val byId = all.associateBy { it.id }

    @JvmStatic
    fun byId(id: String): OmarchyTheme? = byId[id]

    // the smallest contrast between two surfaces for them to be told apart (a letter key against the keyboard, a function key against a letter key)
    private const val MIN_SURFACE_STEP = 1.10

    /**
     * Mapping of the roles to the keyboard, in the same layers as the Clackatoo colours (surface, letter keys, function
     * keys): the keyboard is `background`. In a dark theme letter keys are `lighter_background` and function keys
     * `selection`; in a light theme letter keys are `dark_background` and function keys `darker_background`, the way
     * Material's light containers get darker with their level. Where a theme gives two layers (nearly) the same colour,
     * the next layer is a mix of the surface with `foreground`. Labels are `foreground`, hint letters `light_foreground`,
     * and the accent is `accent`. Labels and hints are raised to 4.5:1 on every surface and key, in every state, if they are weaker.
     */
    internal fun palette(c: OmarchyColors): ClackatooPalette {
        fun distinct(candidate: Int, from: Int, towards: Int, share: Float) =
            if (ClackatooPalette.contrast(candidate, from) >= MIN_SURFACE_STEP) candidate
            else ColorUtils.blendARGB(from, towards, share)
        val key = if (c.dark) distinct(c.lighterBackground, c.background, c.foreground, 0.09f)
            else distinct(c.darkBackground, c.background, c.foreground, 0.06f)
        val functionKey = if (c.dark) distinct(c.selection, key, c.foreground, 0.10f)
            else distinct(c.darkerBackground, key, c.foreground, 0.06f)
        return ClackatooPalette(
            dark = c.dark,
            surface = c.background,
            surfaceContainerHigh = key,
            surfaceContainerHighest = functionKey,
            onSurface = c.foreground,
            onSurfaceVariant = c.lightForeground,
            primary = c.accent,
        ).withRaisedText().withReadableText()
    }
}
