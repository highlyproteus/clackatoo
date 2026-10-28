// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo.ui

import android.content.Context
import helium314.keyboard.latin.R
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.preferences.SwitchPreference

/** The "Glass background" switch; found by search and shown on the home screen. */
fun createGlassSettings(context: Context) = listOf(
    Setting(context, GlassStyle.PREF_GLASS, R.string.glass_background, R.string.glass_background_summary) {
        SwitchPreference(it, GlassStyle.DEFAULT_GLASS)
    },
)
