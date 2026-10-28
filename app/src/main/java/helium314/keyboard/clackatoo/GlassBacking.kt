// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo

import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import androidx.core.graphics.ColorUtils
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.Colors

/**
 * The frosted backing of the glass theme: a mostly opaque tile with a hairline edge ([Colors.backing], [Colors.keyBorder]) behind
 * everything that carries text or icons and is not a key (every suggestion and toolbar button on the strip, the emoji tabs, the text
 * of the voice and check panel, the emoji grid). Text never sits on the clear glass between and around them. Other themes have no backing: [apply] does nothing.
 */
object GlassBacking {
    /** Corner radius of a backing, in dp: like the keys. */
    const val RADIUS_DP = ClackatooStyle.CORNER_RADIUS_DP

    /** The backing tile in [colors], [insetDp] clear from the edges of the view, or null if the colours are not glass. */
    @JvmStatic
    @JvmOverloads
    fun drawable(resources: Resources, colors: Colors, insetHorizontalDp: Float = 0f, insetVerticalDp: Float = 0f): Drawable? {
        if (!colors.translucentWindow) return null
        val density = resources.displayMetrics.density
        val tile = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = RADIUS_DP * density
            setColor(colors.backing)
            setStroke(ClackatooStyle.BORDER_WIDTH_PX.toInt(), colors.keyBorder)
        }
        if (insetHorizontalDp == 0f && insetVerticalDp == 0f) return tile
        return InsetDrawable(tile, (insetHorizontalDp * density).toInt(), (insetVerticalDp * density).toInt(), (insetHorizontalDp * density).toInt(), (insetVerticalDp * density).toInt())
    }

    /** Makes the background of [view] the backing, if [colors] are glass. */
    @JvmStatic
    @JvmOverloads
    fun apply(view: View, colors: Colors, insetHorizontalDp: Float = 0f, insetVerticalDp: Float = 0f) {
        drawable(view.resources, colors, insetHorizontalDp, insetVerticalDp)?.let { view.background = it }
    }

    /** Gap between two chips (each is inset by half of it) and above and below them, in dp. */
    private const val CHIP_INSET_H_DP = 2.5f
    private const val CHIP_INSET_V_DP = 3f
    private const val PRESSED_SHARE = 0.2f
    private const val HIGHLIGHT_SHARE = 0.3f

    /**
     * The backing of one button of the strip (a suggestion, a toolbar key, an emoji tab): a [drawable] tile with a gap around it, tinted
     * with the accent while pressed, or, [highlighted] (a pinned toolbar key), all the time. Null if the colours are not glass.
     */
    @JvmStatic
    @JvmOverloads
    fun chip(resources: Resources, colors: Colors, highlighted: Boolean = false): Drawable? {
        if (!colors.translucentWindow) return null
        val density = resources.displayMetrics.density
        val accent = colors.get(ColorType.TOOL_BAR_KEY_ENABLED_BACKGROUND)
        fun tile(fill: Int, stroke: Int) = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = RADIUS_DP * density
            setColor(fill)
            setStroke(ClackatooStyle.BORDER_WIDTH_PX.toInt(), stroke)
        }
        val rest = if (highlighted) ColorUtils.blendARGB(colors.backing, accent, HIGHLIGHT_SHARE) else colors.backing
        val states = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), tile(ColorUtils.blendARGB(rest, accent, PRESSED_SHARE), accent))
            addState(intArrayOf(), tile(rest, if (highlighted) accent else colors.keyBorder))
        }
        val h = (CHIP_INSET_H_DP * density).toInt()
        val v = (CHIP_INSET_V_DP * density).toInt()
        return InsetDrawable(states, h, v, h, v)
    }

    /** Makes the background of [view] a [chip], if [colors] are glass. */
    @JvmStatic
    fun applyChip(view: View, colors: Colors) {
        chip(view.resources, colors)?.let { view.background = it }
    }
}
