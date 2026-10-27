// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo

import android.content.Context
import android.content.res.Resources
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.content.ContextCompat
import helium314.keyboard.keyboard.KeyboardTheme
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.Colors
import helium314.keyboard.latin.common.DefaultColors
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ResourceUtils
import helium314.keyboard.latin.utils.prefs

/** Everything that is specific to the Clackatoo style, kept apart from upstream's styles so upstream stays mergeable. */
object ClackatooStyle {
    /** Corner radius of keys and panels, in dp (hub design rule: square, not pills). */
    const val CORNER_RADIUS_DP = 6f
    /** Corner radius of small elements, in dp. */
    const val SMALL_CORNER_RADIUS_DP = 4f
    /** The hairline edge of keys is one physical pixel wide. */
    const val BORDER_WIDTH_PX = 1f

    /** Inter 4.1 (SIL OFL 1.1, third_party/inter): Regular for key labels, Medium where HeliBoard asks for bold. */
    const val FONT_ASSET = "fonts/Inter-Regular.ttf"
    const val FONT_MEDIUM_ASSET = "fonts/Inter-Medium.ttf"

    @JvmStatic
    fun isActive(context: Context) =
        context.prefs().getString(Settings.PREF_THEME_STYLE, Defaults.PREF_THEME_STYLE) == KeyboardTheme.STYLE_CLACKATOO

    /**
     * Key background for [color] using [colors], or null if [color] is not drawn as a key (then the
     * background from the theme is used). Keys, the space bar and the action key get the thin border; keys in
     * popups and more suggestions are only filled, because they sit inside a bordered panel.
     */
    @JvmStatic
    fun keyBackground(resources: Resources, colors: Colors, color: ColorType): Drawable? {
        val density = resources.displayMetrics.density
        val bordered = when (color) {
            ColorType.KEY_BACKGROUND, ColorType.FUNCTIONAL_KEY_BACKGROUND, ColorType.SPACE_BAR_BACKGROUND,
            ColorType.ACTION_KEY_BACKGROUND -> true
            ColorType.POPUP_KEYS_BACKGROUND, ColorType.ACTION_KEY_POPUP_KEYS_BACKGROUND,
            ColorType.MORE_SUGGESTIONS_WORD_BACKGROUND -> false
            else -> return null
        }
        // the accent: the edge of a pressed key, and of the Enter key
        val accent = colors.get(ColorType.TOOL_BAR_KEY_ENABLED_BACKGROUND)
        val border = if (color == ColorType.ACTION_KEY_BACKGROUND) accent else colors.keyBorder
        return ClackatooKeyDrawable(
            CORNER_RADIUS_DP * density,
            if (bordered) BORDER_WIDTH_PX else 0f,
            border,
            accent
        )
    }

    /** Bundled Inter Regular, used for key labels unless the user picked a custom font. */
    @JvmStatic
    fun defaultTypeface(context: Context): Typeface? = loadFont(context, FONT_ASSET)

    /** Bundled Inter Medium, used where HeliBoard uses bold. */
    @JvmStatic
    fun mediumTypeface(context: Context): Typeface? = loadFont(context, FONT_MEDIUM_ASSET)

    private fun loadFont(context: Context, asset: String): Typeface? {
        if (!isActive(context)) return null
        return runCatching { Typeface.createFromAsset(context.assets, asset) }.getOrNull()
    }

    /**
     * The colours of the Clackatoo colour theme: the phone's own colours, in the phone's dark theme (or in [forcedDark]
     * if that is not null, as in the colour settings' preview).
     */
    @JvmStatic
    fun colors(
        context: Context, themeStyle: String, hasBorders: Boolean, forcedDark: Boolean?, keyboardBackground: Drawable?,
    ): Colors {
        fun dark(context: Context) = forcedDark ?: ResourceUtils.isNight(context.resources)
        val palette = ClackatooPalette.resolve(context, dark(context))
        return DefaultColors(
            themeStyle,
            hasBorders,
            accent = palette.primary,
            background = palette.surface,
            keyBackground = palette.surfaceContainerHigh,
            functionalKey = palette.surfaceContainerHighest,
            spaceBar = palette.surfaceContainerHigh,
            keyText = palette.onSurface,
            keyHintText = palette.onSurfaceVariant,
            keyboardBackground = keyboardBackground,
            keyBorderColor = palette.hairline,
            pressedMix = palette.primary,
            actionKeyBackground = palette.actionKey,
            actionKeyIcon = palette.actionKeyContent,
            autoCorrectText = palette.selectedText,
            popupBackground = palette.surfaceContainerHighest,
            // the keyboard compares this with the phone's colours now, and rebuilds itself if they differ
            changeCheck = { ClackatooPalette.resolve(it, dark(it)) != palette },
        )
    }

    /** Background of an enabled (pinned) toolbar key: a square with the accent at low opacity instead of a round glow. */
    @JvmStatic
    fun styleSquareHighlight(drawable: GradientDrawable, accent: Int, resources: Resources) {
        drawable.shape = GradientDrawable.RECTANGLE
        drawable.cornerRadius = SMALL_CORNER_RADIUS_DP * resources.displayMetrics.density
        drawable.setColor(ColorUtils.setAlphaComponent(accent, 0x33))
    }

    /** The keyboard's own toast (used where Android shows none): square like the keys, in the keyboard's colours. */
    @JvmStatic
    fun styleToast(view: TextView, colors: Colors) {
        val density = view.resources.displayMetrics.density
        view.background = GradientDrawable().apply {
            cornerRadius = CORNER_RADIUS_DP * density
            setColor(colors.get(ColorType.POPUP_KEYS_BACKGROUND))
            setStroke(BORDER_WIDTH_PX.toInt(), colors.keyBorder)
        }
        view.setTextColor(colors.get(ColorType.KEY_TEXT))
    }

    /** Square replacement for the round / pill backgrounds used in the suggestion strip, or null for other styles. */
    @JvmStatic
    fun squareDrawable(context: Context, resId: Int): Drawable? =
        if (isActive(context)) ContextCompat.getDrawable(context, resId) else null
}
