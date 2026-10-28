// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo

import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import helium314.keyboard.clackatoo.ClackatooPalette.Companion.BLACK
import helium314.keyboard.clackatoo.ClackatooPalette.Companion.WHITE

/**
 * The colours of the glass keyboard: the keyboard window is translucent, letter and function keys are lighter glass
 * tiles with a strong hairline. What is behind the keyboard is unknown (white page, black page, a photo), so the tint is
 * made as opaque as it takes for the labels to keep 4.5:1 over white and over black (see [ClackatooPalette.withReadableText],
 * which looks at both). Where blur is available that is just enough; where it is not, the tint starts from a high opacity
 * so that the app behind does not shine through unblurred.
 */
object GlassPalette {
    /** Radius of the blur behind the keyboard window, in dp. */
    const val BLUR_RADIUS_DP = 28
    /** Opacity of the tint (0-1) to start from where the window can blur what is behind it, and where it cannot. */
    private const val MIN_TINT_BLURRED = 0.40f
    private const val MIN_TINT_PLAIN = 0.90f
    private const val MAX_TINT = 0.97f
    private const val TINT_STEP = 0.02f

    /** The accent of the fixed dark and light glass themes (the follow-the-phone glass takes the phone's). */
    @get:ColorInt val ACCENT_DARK = 0xFF5BC8FF.toInt()
    @get:ColorInt val ACCENT_LIGHT = 0xFF0B6FA8.toInt()

    /** Fixed glass in the dark or light mode, in the palette's own colours. */
    fun fixed(dark: Boolean, blur: Boolean): ClackatooPalette {
        val base = ClackatooPalette.fallback(dark)
        return build(base.copy(primary = if (dark) ACCENT_DARK else ACCENT_LIGHT), blur)
    }

    /** Glass tinted with the colours of [base] (a palette of the phone), with [blur] if the window can blur. */
    fun build(base: ClackatooPalette, blur: Boolean): ClackatooPalette {
        var tint = if (blur) MIN_TINT_BLURRED else MIN_TINT_PLAIN
        var palette = candidate(base, tint)
        // the tint is as opaque as the labels need; hint letters, which are fainter, are raised instead of making the tint more opaque
        while (tint < MAX_TINT && !palette.hasReadableLabels()) {
            tint += TINT_STEP
            palette = candidate(base, tint)
        }
        // if even the most opaque tint is not enough (odd system colours), black or white labels take over
        return palette.withRaisedText().withReadableText()
    }

    private fun candidate(base: ClackatooPalette, tint: Float): ClackatooPalette {
        val dark = base.dark
        // the tint is the base surface, without its own translucency
        val surface = opaque(base.surface)
        // Tiles are the tint made a little lighter (dark glass) or white (light glass) and more opaque than the tint around them, so the labels keep their
        // contrast on tiles however light the app behind is; that lets the tint stay as translucent as the labels on the strip and toolbar allow.
        fun tile(towards: Int, share: Float, alpha: Int) = ColorUtils.setAlphaComponent(ColorUtils.blendARGB(surface, towards, share), alpha)
        return ClackatooPalette(
            dark = dark,
            surface = ColorUtils.setAlphaComponent(surface, (tint * 255).toInt()),
            surfaceContainerHigh = if (dark) tile(WHITE, 0.14f, 0x94) else tile(WHITE, 1f, 0xA6),
            surfaceContainerHighest = if (dark) tile(WHITE, 0.24f, 0xA6) else tile(BLACK, 0.07f, 0x99),
            onSurface = base.onSurface,
            onSurfaceVariant = base.onSurfaceVariant,
            primary = base.primary,
            // popups are drawn over keys and over the app, so they are nearly opaque
            popup = ColorUtils.setAlphaComponent(ColorUtils.blendARGB(surface, if (dark) WHITE else BLACK, if (dark) 0.18f else 0.05f), 0xF2),
            hairlineAlpha = if (dark) 0x5C else 0x47,
        )
    }

    private fun opaque(@ColorInt color: Int) = ColorUtils.setAlphaComponent(color, 255)
}
