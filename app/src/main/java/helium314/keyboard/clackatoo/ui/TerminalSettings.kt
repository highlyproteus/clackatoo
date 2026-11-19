// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import helium314.keyboard.clackatoo.TerminalKeys
import helium314.keyboard.latin.R
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.preferences.SwitchPreference

/** What the keyboard does differently in a terminal ([TerminalKeys]); found by search and shown on the Terminal screen. */
fun createTerminalSettings(context: Context) = listOf(
    Setting(context, TerminalKeys.PREF_TERMINAL_KEYS, R.string.terminal_keys, R.string.terminal_keys_summary) {
        SwitchPreference(it, TerminalKeys.DEFAULT_TERMINAL_KEYS)
    },
)

@Composable
fun TerminalScreen(onClickBack: () -> Unit) {
    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.settings_screen_terminal),
        settings = listOf(TerminalKeys.PREF_TERMINAL_KEYS),
    )
}
