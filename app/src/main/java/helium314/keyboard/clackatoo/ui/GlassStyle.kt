// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo.ui

import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import helium314.keyboard.clackatoo.ClackatooPalette
import kotlin.math.roundToInt

/**
 * The numbers behind the glass look of the settings. Text never sits directly on clear glass:
 *
 * - **Clear parts** (the window behind everything) show the wallpaper under a light tint, [SCRIM_ALPHA_BLUR] where the
 *   phone blurs windows (the window is blurred as well, Android 12+, `WindowManager.isCrossWindowBlurEnabled`) and a
 *   heavier [SCRIM_ALPHA_FLAT] where it does not (Android 11 and older, battery saver), so the result still looks
 *   deliberate.
 * - **Frosted backings** carry every piece of text and every icon: the panels (rows, groups, cards), and small
 *   backings behind the page title, the section headers, the hints and the toolbar icons that would otherwise be on the
 *   clear window. They are tinted with the theme's container colour, have a hairline border, and keep the same alpha
 *   with and without blur. Dialogs are lightly frosted where the phone blurs windows and solid where it does not.
 *
 * The text must stay readable whatever the wallpaper is, so the alphas are fixed and `GlassStyleTest` checks 4.5:1
 * (WCAG AA) for every text surface over the worst wallpapers, in light and dark mode, with and without blur. A light
 * container over a dark wallpaper (and a dark one over a white wallpaper) is what limits how clear a backing can be, so
 * dark mode needs the stronger tint.
 */
object GlassStyle {
    /** Switch in the settings ("Glass background"); off means solid Material 3 surfaces. */
    const val PREF_GLASS = "clackatoo_glass_settings"
    const val DEFAULT_GLASS = true

    /** Share of the window's surface colour laid over the wallpaper where the phone blurs it. */
    const val SCRIM_ALPHA_BLUR = 0.26f
    /** … where it does not blur: heavier, because the wallpaper is sharp. */
    const val SCRIM_ALPHA_FLAT = 0.62f
    /** Share of a panel's container colour laid over the window background, in light and in dark mode. */
    const val PANEL_ALPHA_LIGHT = 0.60f
    const val PANEL_ALPHA_DARK = 0.74f
    /** Backings of text and icons outside panels (title, headers, hints, toolbar icons): a little stronger than panels. */
    const val BACKING_ALPHA_LIGHT = 0.70f
    const val BACKING_ALPHA_DARK = 0.80f
    /**
     * Dialogs on a phone that blurs: frosted, over the settings screen, which is blurred by [DIALOG_BACKDROP_BLUR_DP]
     * while a dialog is open (see `GlassDialogEffect`), so what shows through is soft. Without blur they are solid.
     */
    const val DIALOG_ALPHA_LIGHT = 0.80f
    const val DIALOG_ALPHA_DARK = 0.84f
    /** Blur of the settings content behind an open dialog (Compose `Modifier.blur`, Android 12+; not the window blur). */
    const val DIALOG_BACKDROP_BLUR_DP = 12
    /** The hairline around backings: share of the text colour. */
    const val BORDER_ALPHA = 0.22f
    const val BLUR_RADIUS_DP = 32
    /** How much the screen behind a dialog is darkened with glass (the platform default is 0.6). */
    const val DIALOG_DIM = 0.32f

    fun scrimAlpha(blur: Boolean) = if (blur) SCRIM_ALPHA_BLUR else SCRIM_ALPHA_FLAT
    fun isDark(@ColorInt surface: Int) = ColorUtils.calculateLuminance(surface) < 0.5

    @ColorInt fun scrim(@ColorInt surface: Int, blur: Boolean) = withAlpha(surface, scrimAlpha(blur))
    @ColorInt fun panel(@ColorInt container: Int, @ColorInt surface: Int) =
        withAlpha(container, if (isDark(surface)) PANEL_ALPHA_DARK else PANEL_ALPHA_LIGHT)
    @ColorInt fun backing(@ColorInt container: Int, @ColorInt surface: Int) =
        withAlpha(container, if (isDark(surface)) BACKING_ALPHA_DARK else BACKING_ALPHA_LIGHT)
    @ColorInt fun dialog(@ColorInt container: Int, @ColorInt surface: Int) =
        withAlpha(container, if (isDark(surface)) DIALOG_ALPHA_DARK else DIALOG_ALPHA_LIGHT)

    /** What the user sees of the window background above [wallpaper] (an opaque colour). */
    @ColorInt fun scrimOver(@ColorInt surface: Int, @ColorInt wallpaper: Int, blur: Boolean) =
        ColorUtils.compositeColors(scrim(surface, blur), wallpaper)

    /** What the user sees of a panel: its container colour over the window background over [wallpaper]. */
    @ColorInt fun panelOver(@ColorInt container: Int, @ColorInt surface: Int, @ColorInt wallpaper: Int, blur: Boolean) =
        ColorUtils.compositeColors(panel(container, surface), scrimOver(surface, wallpaper, blur))

    /** What the user sees of a text backing (title, header, hint, toolbar icon). */
    @ColorInt fun backingOver(@ColorInt container: Int, @ColorInt surface: Int, @ColorInt wallpaper: Int, blur: Boolean) =
        ColorUtils.compositeColors(backing(container, surface), scrimOver(surface, wallpaper, blur))

    /**
     * What the user sees of a dialog: with blur its frosted container over whatever the blurred window behind it shows
     * ([behind], conservatively an unblurred extreme or colour), without blur the solid container.
     */
    @ColorInt fun dialogOver(@ColorInt container: Int, @ColorInt surface: Int, @ColorInt behind: Int, blur: Boolean) =
        if (blur) ColorUtils.compositeColors(dialog(container, surface), behind) else container or (0xFF shl 24)

    /** Wallpapers (and what a blurred dialog shows behind it) that [readable] guarantees its colours against. */
    private val WORST_WALLPAPERS = intArrayOf(
        0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF808080.toInt(), 0xFF404040.toInt(), 0xFFC0C0C0.toInt(),
        0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFFFFFF00.toInt(), 0xFF00FFFF.toInt(), 0xFFFF00FF.toInt(),
    )

    /** The backgrounds a text colour can land on with glass: every backing, panel and dialog over the worst wallpapers, with and without blur. */
    private fun backgrounds(@ColorInt container: Int, @ColorInt surface: Int): List<Int> =
        WORST_WALLPAPERS.flatMap { wallpaper -> listOf(true, false).flatMap { blur ->
            listOf(panelOver(container, surface, wallpaper, blur), backingOver(container, surface, wallpaper, blur), dialogOver(container, surface, wallpaper, blur))
        } }

    /**
     * [text] as it can be used on any frosted surface: itself where it already keeps 4.5:1 on all of them over the
     * worst wallpapers, otherwise moved towards [toward] (the main text colour) in steps until it does. The accent
     * colour and the secondary text colour are the ones that need it; clear panels are what limits them.
     */
    @ColorInt fun readable(@ColorInt text: Int, @ColorInt toward: Int, @ColorInt container: Int, @ColorInt surface: Int): Int {
        val backgrounds = backgrounds(container, surface)
        for (step in 0..READABLE_STEPS) {
            val candidate = ColorUtils.blendARGB(text, toward, step / READABLE_STEPS.toFloat())
            if (backgrounds.all { ClackatooPalette.contrast(candidate, it) >= ClackatooPalette.MIN_TEXT_CONTRAST }) return candidate
        }
        return toward
    }

    private const val READABLE_STEPS = 20

    @ColorInt private fun withAlpha(@ColorInt color: Int, alpha: Float) =
        ColorUtils.setAlphaComponent(color, (alpha * 255).roundToInt())
}
