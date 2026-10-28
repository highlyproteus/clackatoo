// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo.ui

import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import kotlin.math.roundToInt

/**
 * The numbers behind the glass look of the settings: the window shows the wallpaper behind it under a tinted scrim
 * (blurred as well on Android 12+ when the phone allows it), and the panels on top are translucent with a hairline
 * border. The text must stay readable whatever the wallpaper is, so the alphas below are fixed and
 * `GlassStyleTest` checks 4.5:1 (WCAG AA) for the text colours over the worst wallpapers, in light and dark mode.
 * The blur is a bonus and never counted on: it cannot lower the contrast the scrim guarantees.
 */
object GlassStyle {
    /** Switch in the settings ("Glass background"); off means solid Material 3 surfaces. */
    const val PREF_GLASS = "clackatoo_glass_settings"
    const val DEFAULT_GLASS = true

    /** Share of the window's surface colour laid over the wallpaper. */
    const val SCRIM_ALPHA = 0.72f
    /** Share of a panel's container colour laid over the scrim. */
    const val PANEL_ALPHA = 0.74f
    /** The hairline around panels: share of the text colour. */
    const val BORDER_ALPHA = 0.22f
    const val BLUR_RADIUS_DP = 32

    @ColorInt fun scrim(@ColorInt surface: Int) = withAlpha(surface, SCRIM_ALPHA)
    @ColorInt fun panel(@ColorInt container: Int) = withAlpha(container, PANEL_ALPHA)

    /** What the user sees of the window background above [wallpaper] (an opaque colour). */
    @ColorInt fun scrimOver(@ColorInt surface: Int, @ColorInt wallpaper: Int) =
        ColorUtils.compositeColors(scrim(surface), wallpaper)

    /** What the user sees of a panel: its container colour over the scrim over [wallpaper]. */
    @ColorInt fun panelOver(@ColorInt container: Int, @ColorInt surface: Int, @ColorInt wallpaper: Int) =
        ColorUtils.compositeColors(panel(container), scrimOver(surface, wallpaper))

    @ColorInt private fun withAlpha(@ColorInt color: Int, alpha: Float) =
        ColorUtils.setAlphaComponent(color, (alpha * 255).roundToInt())
}
