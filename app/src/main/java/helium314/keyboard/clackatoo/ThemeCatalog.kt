// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo

import android.content.Context
import helium314.keyboard.keyboard.KeyboardTheme
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.getStringResourceOrName

/** The groups of the theme picker, in the order they are shown. */
enum class ThemeGroup { FOLLOW_PHONE, GLASS, OMARCHY, CLASSIC, CUSTOM }

/**
 * The colour themes by group. A theme is a string, the value of the theme preference (`theme_colors`, `theme_colors_night`),
 * so that the old preferences and the user's own themes keep working: the built-in HeliBoard themes keep their names,
 * the new ones are `glass`, `glass_dark`, `glass_light` and `omarchy_<name>`, and any other value is a theme of the user.
 */
object ThemeCatalog {
    /** Glass that follows the phone's dark and light mode and takes its tint and accent from the phone's colours. */
    const val GLASS = "glass"
    const val GLASS_DARK = "glass_dark"
    const val GLASS_LIGHT = "glass_light"

    val glass = listOf(GLASS, GLASS_DARK, GLASS_LIGHT)

    fun isGlass(id: String) = id in glass
    fun isOmarchy(id: String) = OmarchyThemes.byId(id) != null

    /** The themes of HeliBoard that are colours only, that is the old [KeyboardTheme.getAvailableDefaultColors] without the new ones. */
    fun classic(prefs: android.content.SharedPreferences, isNight: Boolean) =
        KeyboardTheme.getAvailableDefaultColors(prefs, isNight).filter { it != KeyboardTheme.THEME_CLACKATOO && it != KeyboardTheme.THEME_DYNAMIC }

    fun groupOf(id: String, userThemes: Collection<String>): ThemeGroup = when {
        id == KeyboardTheme.THEME_CLACKATOO || id == KeyboardTheme.THEME_DYNAMIC -> ThemeGroup.FOLLOW_PHONE
        isGlass(id) -> ThemeGroup.GLASS
        isOmarchy(id) -> ThemeGroup.OMARCHY
        id in userThemes -> ThemeGroup.CUSTOM
        else -> ThemeGroup.CLASSIC
    }

    /** The name to show for a value of the theme preference. */
    fun displayName(context: Context, id: String): String = when {
        isGlass(id) -> context.getString(when (id) {
            GLASS_DARK -> R.string.theme_name_glass_dark
            GLASS_LIGHT -> R.string.theme_name_glass_light
            else -> R.string.theme_name_glass
        })
        else -> OmarchyThemes.byId(id)?.name ?: id.getStringResourceOrName("theme_name_", context)
    }
}
