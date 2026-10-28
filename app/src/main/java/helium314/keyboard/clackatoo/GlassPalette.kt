// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo

import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import helium314.keyboard.clackatoo.ClackatooPalette.Companion.BLACK
import helium314.keyboard.clackatoo.ClackatooPalette.Companion.WHITE

/**
 * The colours of the glass keyboard. Text and icons never sit on clear glass: the parts of the keyboard window that carry
 * none (the gaps between keys, the edges) are clear, a light tint over the blurred app behind, and everything that carries
 * a label (keys, the suggestion strip, the toolbar, panels) has a frosted backing, a tile that is mostly opaque
 * ([BACKING_ALPHA]) with a hairline edge. What is behind the keyboard is unknown (white page, black page, a photo), so
 * the backings are what the labels' 4.5:1 is measured on (see [ClackatooPalette.withReadableText], which looks at the
 * backings over white, black, grey and saturated colours); the clear parts need no contrast and stay as clear as is pretty.
 * Where the phone cannot blur, the clear parts get a heavier tint, so that the app behind does not shine through unblurred.
 */
object GlassPalette {
    /** Radius of the blur behind the keyboard window, in dp. */
    const val BLUR_RADIUS_DP = 28
    /** Opacity of the tint (0-1) of the clear parts where the window can blur what is behind it, and where it cannot. */
    const val CLEAR_TINT_BLURRED = 0.26f
    const val CLEAR_TINT_PLAIN = 0.86f
    /** Opacity (0-255) of the frosted backings: keys, strip, toolbar, panels. The same with and without blur. */
    const val BACKING_ALPHA = 0xC7
    private const val MAX_BACKING_ALPHA = 0xF2
    private const val BACKING_ALPHA_STEP = 6

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
        // The backings are as opaque as the labels need, and the same with and without blur: the clearer tint is the worse case for
        // them (a backing over a clear surface shows more of what is behind), so it decides. Hint letters, which are fainter, are
        // raised instead of making the backings more opaque.
        var alpha = BACKING_ALPHA
        while (alpha + BACKING_ALPHA_STEP <= MAX_BACKING_ALPHA && !candidate(base, CLEAR_TINT_BLURRED, alpha).hasReadableLabels()) alpha += BACKING_ALPHA_STEP
        val palette = candidate(base, if (blur) CLEAR_TINT_BLURRED else CLEAR_TINT_PLAIN, alpha)
        // if even the most opaque backing is not enough (odd system colours), black or white labels take over
        return palette.withRaisedText().withReadableText()
    }

    private fun candidate(base: ClackatooPalette, tint: Float, alpha: Int): ClackatooPalette {
        val dark = base.dark
        // the tint is the base surface, without its own translucency
        val surface = opaque(base.surface)
        // Tiles are the surface made a little lighter (dark glass) or white (light glass), frosted: mostly opaque, so the labels keep their
        // contrast on tiles however light or busy the app behind is, which leaves the surface around them free to be clear.
        fun tile(towards: Int, share: Float) = ColorUtils.setAlphaComponent(ColorUtils.blendARGB(surface, towards, share), alpha)
        val letterKey = if (dark) tile(WHITE, 0.16f) else tile(WHITE, 1f)
        return ClackatooPalette(
            dark = dark,
            surface = ColorUtils.setAlphaComponent(surface, (tint * 255).toInt()),
            surfaceContainerHigh = letterKey,
            surfaceContainerHighest = if (dark) tile(WHITE, 0.26f) else tile(BLACK, 0.08f),
            onSurface = base.onSurface,
            onSurfaceVariant = base.onSurfaceVariant,
            primary = base.primary,
            // popups and key previews are drawn over keys and over the app, so they are solid
            popup = ColorUtils.blendARGB(surface, if (dark) WHITE else BLACK, if (dark) 0.18f else 0.05f),
            hairlineAlpha = if (dark) 0x66 else 0x59,
            backing = letterKey,
        )
    }

    private fun opaque(@ColorInt color: Int) = ColorUtils.setAlphaComponent(color, 255)
}
