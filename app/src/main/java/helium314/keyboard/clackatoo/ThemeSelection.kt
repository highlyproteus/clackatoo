// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo

import android.content.SharedPreferences
import android.os.Build
import androidx.core.content.edit
import helium314.keyboard.keyboard.KeyboardTheme
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings

/**
 * Choosing a colour theme from the settings, shared by the theme picker and the swatches of the home screen: which of the two
 * theme preferences a tap sets, and what else a theme needs. Nothing is stored beyond the existing preferences
 * ([Settings.PREF_THEME_COLORS], [Settings.PREF_THEME_COLORS_NIGHT], [Settings.PREF_THEME_STYLE]). The caller tells the keyboard to
 * reload its theme afterwards (`KeyboardSwitcher.setThemeNeedsReload`).
 */
object ThemeSelection {
    /** Whether the phone's dark mode picks the keyboard theme (the day/night switch of the picker; Android 9 and up). */
    fun dayNightOn(prefs: SharedPreferences) =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && prefs.getBoolean(Settings.PREF_THEME_DAY_NIGHT, Defaults.PREF_THEME_DAY_NIGHT)

    /** True when a tap sets the night theme: day and night are chosen separately and the phone is in dark mode now. */
    fun editsNight(dayNightOn: Boolean, phoneIsNight: Boolean) = dayNightOn && phoneIsNight

    fun prefKey(night: Boolean) = if (night) Settings.PREF_THEME_COLORS_NIGHT else Settings.PREF_THEME_COLORS

    /** The theme now selected for day, or for night. */
    fun current(prefs: SharedPreferences, night: Boolean): String =
        prefs.getString(prefKey(night), if (night) Defaults.PREF_THEME_COLORS_NIGHT else Defaults.PREF_THEME_COLORS)!!

    /** Selects [id] for day or [night]. Glass and Omarchy are made for the Clackatoo style (8dp keys with a hairline), which shows them as designed. */
    fun apply(prefs: SharedPreferences, id: String, night: Boolean) {
        prefs.edit {
            putString(prefKey(night), id)
            if (ThemeCatalog.isGlass(id) || ThemeCatalog.isOmarchy(id)) putString(Settings.PREF_THEME_STYLE, KeyboardTheme.STYLE_CLACKATOO)
        }
    }

    /** The themes the home screen offers as swatches after the current one, in order of preference. */
    private val SWATCH_CHOICES = listOf(
        ThemeCatalog.GLASS, OmarchyThemes.ID_PREFIX + "tokyo-night", OmarchyThemes.ID_PREFIX + "catppuccin-latte",
        OmarchyThemes.ID_PREFIX + "gruvbox", OmarchyThemes.ID_PREFIX + "nord",
    )

    /** The theme [current] first, then Glass and two Omarchy themes (the next ones when [current] is one of them): [COUNT] in all. */
    fun swatches(current: String): List<String> = (listOf(current) + SWATCH_CHOICES).distinct().take(COUNT)

    const val COUNT = 4
}
