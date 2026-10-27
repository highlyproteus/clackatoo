// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo

import android.content.Context
import android.os.Build
import androidx.annotation.ColorInt
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils

/**
 * The colours of the Clackatoo keyboard: Material 3 colour roles taken from
 * the phone's own theme, in the phone's dark or light mode.
 *
 * - Android 14 and later: the system role colours (`android.R.color.system_surface_container_high_dark`, ...).
 * - Android 12 and 13: the nearest system tones of the neutral and accent palettes.
 * - Below Android 12: a fixed fallback table.
 *
 * @param dark whether the palette is for the phone's dark theme; part of the value so that a switch between dark and
 * light counts as a change
 */
data class ClackatooPalette(
    val dark: Boolean,
    /** keyboard background */
    @ColorInt val surface: Int,
    /** letter keys */
    @ColorInt val surfaceContainerHigh: Int,
    /** function keys, popups */
    @ColorInt val surfaceContainerHighest: Int,
    /** key labels, toolbar icons */
    @ColorInt val onSurface: Int,
    /** hint letters, secondary text */
    @ColorInt val onSurfaceVariant: Int,
    /** the accent: active and selected things only */
    @ColorInt val primary: Int,
) {
    /** The hairline edge of keys: 1 pixel of [onSurface] at 12% (dark) or 16% (light). */
    @get:ColorInt val hairline get() = ColorUtils.setAlphaComponent(onSurface, if (dark) 31 else 41)

    /** Fill of a pressed key: the key colour with a share of [primary]. */
    @ColorInt fun pressed(@ColorInt fill: Int) = ColorUtils.blendARGB(fill, primary, PRESSED_SHARE)

    /** Fill of the Enter key: the quiet accent treatment, a function key with a share of [primary]. */
    @get:ColorInt val actionKey get() = ColorUtils.blendARGB(surfaceContainerHighest, primary, ACTION_KEY_SHARE)

    /** The Enter key's glyph or label: [primary] where that is readable on [actionKey], else [onSurface]. */
    @get:ColorInt val actionKeyContent get() = if (contrast(primary, actionKey) >= MIN_TEXT_CONTRAST) primary else onSurface

    /** The selected suggestion: [primary] where that is readable on [surface], else [onSurface]. */
    @get:ColorInt val selectedText get() = if (contrast(primary, surface) >= MIN_TEXT_CONTRAST) primary else onSurface

    /**
     * Guards the contrast of text (at least 4.5:1, WCAG AA) against odd system colours: labels fall back to black or
     * white, hint letters to the label colour, wherever the key colours leave too little contrast.
     */
    fun withReadableText(): ClackatooPalette {
        val resting = listOf(surface, surfaceContainerHigh, surfaceContainerHighest)
        // labels also have to be readable on the Enter key and on keys while they are pressed; hint letters (which the Enter key has none of) only on keys at rest
        val backgrounds = resting + listOf(actionKey, pressed(surfaceContainerHigh), pressed(surfaceContainerHighest), pressed(actionKey))
        fun readable(@ColorInt text: Int) = backgrounds.all { contrast(text, it) >= MIN_TEXT_CONTRAST }
        val label = when {
            readable(onSurface) -> onSurface
            readable(BLACK) && !readable(WHITE) -> BLACK
            readable(WHITE) && !readable(BLACK) -> WHITE
            else -> listOf(BLACK, WHITE).maxBy { text -> backgrounds.minOf { contrast(text, it) } }
        }
        val variant = if (resting.all { contrast(onSurfaceVariant, it) >= MIN_TEXT_CONTRAST }) onSurfaceVariant else label
        return copy(onSurface = label, onSurfaceVariant = variant)
    }

    companion object {
        const val MIN_TEXT_CONTRAST = 4.5
        /** share of [primary] in a pressed key */
        const val PRESSED_SHARE = 0.2f
        private const val ACTION_KEY_SHARE = 0.2f
        private const val BLACK = 0xFF000000.toInt()
        private const val WHITE = 0xFFFFFFFF.toInt()

        /** WCAG contrast ratio of [text] on [background] (taken as opaque, as system colours are). */
        fun contrast(@ColorInt text: Int, @ColorInt background: Int) =
            ColorUtils.calculateContrast(text, ColorUtils.setAlphaComponent(background, 255))

        /** The fallback table of the design spec, for Android below 12. */
        fun fallback(dark: Boolean) = if (dark) ClackatooPalette(
            dark = true,
            surface = 0xFF0E0F11.toInt(),
            surfaceContainerHigh = 0xFF1E2024.toInt(),
            surfaceContainerHighest = 0xFF282B30.toInt(),
            onSurface = 0xFFECEDEF.toInt(),
            onSurfaceVariant = 0xFFA3A7AE.toInt(),
            primary = 0xFF9DB8F5.toInt(),
        ) else ClackatooPalette(
            dark = false,
            surface = 0xFFF5F6F8.toInt(),
            surfaceContainerHigh = 0xFFE6E8EC.toInt(),
            surfaceContainerHighest = 0xFFDADDE2.toInt(),
            onSurface = 0xFF15171A.toInt(),
            onSurfaceVariant = 0xFF5A5F67.toInt(),
            primary = 0xFF3A64C8.toInt(),
        )

        /** The system palettes that Android 12 and 13 offer; the tone numbers are those of `android.R.color.system_<palette>_<tone>`. */
        enum class Tonal { NEUTRAL1, NEUTRAL2, ACCENT1 }

        /**
         * For Android 12 and 13, which have tones but no role colours. [tone] returns the system colour `system_<palette>_<n>`.
         * The tone numbers are the ones nearest to the Material 3 roles: dark surface 6 -> 10, container high 17 -> 20, highest 22 -> 30
         * (one step apart so that the three stay different), on surface 90 -> 90, on surface variant 80, primary 80;
         * light surface 98 -> 99, container high 92 -> 95, highest 90 -> 90, on surface 10, on surface variant 30, primary 40.
         */
        fun fromTones(dark: Boolean, tone: (Tonal, Int) -> Int) = if (dark) ClackatooPalette(
            dark = true,
            surface = tone(Tonal.NEUTRAL1, 900),
            surfaceContainerHigh = tone(Tonal.NEUTRAL1, 800),
            surfaceContainerHighest = tone(Tonal.NEUTRAL1, 700),
            onSurface = tone(Tonal.NEUTRAL1, 100),
            onSurfaceVariant = tone(Tonal.NEUTRAL2, 200),
            primary = tone(Tonal.ACCENT1, 200),
        ) else ClackatooPalette(
            dark = false,
            surface = tone(Tonal.NEUTRAL1, 10),
            surfaceContainerHigh = tone(Tonal.NEUTRAL1, 50),
            surfaceContainerHighest = tone(Tonal.NEUTRAL1, 100),
            onSurface = tone(Tonal.NEUTRAL1, 900),
            onSurfaceVariant = tone(Tonal.NEUTRAL2, 700),
            primary = tone(Tonal.ACCENT1, 600),
        )

        /** The palette of the phone right now, for the phone's dark theme [dark] (not the keyboard's own day/night setting). */
        fun resolve(context: Context, dark: Boolean): ClackatooPalette {
            val palette = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> fromRoles(context, dark)
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> fromTones(dark) { palette, n -> ContextCompat.getColor(context, tone(palette, n)) }
                else -> fallback(dark)
            }
            return palette.withReadableText()
        }

        @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        private fun fromRoles(context: Context, dark: Boolean): ClackatooPalette {
            fun c(id: Int) = ContextCompat.getColor(context, id)
            return if (dark) ClackatooPalette(
                dark = true,
                surface = c(android.R.color.system_surface_dark),
                surfaceContainerHigh = c(android.R.color.system_surface_container_high_dark),
                surfaceContainerHighest = c(android.R.color.system_surface_container_highest_dark),
                onSurface = c(android.R.color.system_on_surface_dark),
                onSurfaceVariant = c(android.R.color.system_on_surface_variant_dark),
                primary = c(android.R.color.system_primary_dark),
            ) else ClackatooPalette(
                dark = false,
                surface = c(android.R.color.system_surface_light),
                surfaceContainerHigh = c(android.R.color.system_surface_container_high_light),
                surfaceContainerHighest = c(android.R.color.system_surface_container_highest_light),
                onSurface = c(android.R.color.system_on_surface_light),
                onSurfaceVariant = c(android.R.color.system_on_surface_variant_light),
                primary = c(android.R.color.system_primary_light),
            )
        }

        // only the tones that fromTones asks for
        @RequiresApi(Build.VERSION_CODES.S)
        private fun tone(palette: Tonal, n: Int): Int = when (palette) {
            Tonal.NEUTRAL1 -> when (n) {
                10 -> android.R.color.system_neutral1_10
                50 -> android.R.color.system_neutral1_50
                100 -> android.R.color.system_neutral1_100
                700 -> android.R.color.system_neutral1_700
                800 -> android.R.color.system_neutral1_800
                900 -> android.R.color.system_neutral1_900
                else -> throw IllegalArgumentException("tone $n")
            }
            Tonal.NEUTRAL2 -> when (n) {
                200 -> android.R.color.system_neutral2_200
                700 -> android.R.color.system_neutral2_700
                else -> throw IllegalArgumentException("tone $n")
            }
            Tonal.ACCENT1 -> when (n) {
                200 -> android.R.color.system_accent1_200
                600 -> android.R.color.system_accent1_600
                else -> throw IllegalArgumentException("tone $n")
            }
        }
    }
}
