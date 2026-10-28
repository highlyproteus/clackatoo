// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo.ui

import android.content.Context
import android.os.Build
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import helium314.keyboard.clackatoo.ThemeCatalog
import helium314.keyboard.latin.BuildConfig
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.JniUtils
import helium314.keyboard.latin.utils.NextScreenIcon
import helium314.keyboard.latin.utils.SubtypeLocaleUtils.displayName
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.UncachedInputMethodManagerUtils
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.screens.gesturedata.END_DATE_EPOCH_MILLIS
import helium314.keyboard.settings.screens.gesturedata.TWO_WEEKS_IN_MILLIS

/** Where the rows of the home screen lead. */
class HomeActions(
    val onSetUp: () -> Unit,
    val onLanguages: () -> Unit,
    val onPreferences: () -> Unit,
    val onGestureTyping: () -> Unit,
    val onDataGathering: () -> Unit,
    val onTextCorrection: () -> Unit,
    val onLayouts: () -> Unit,
    val onDictionaries: () -> Unit,
    val onVoiceTyping: () -> Unit,
    val onGrammarCheck: () -> Unit,
    val onTheme: () -> Unit,
    val onAppearance: () -> Unit,
    val onToolbar: () -> Unit,
    val onAdvanced: () -> Unit,
    val onAbout: () -> Unit,
)

/** The home screen (concept "glass settings home"): status or set-up card, then groups of rows that show their state. */
@Composable
fun HomeContent(actions: HomeActions) {
    val ctx = LocalContext.current
    // the state rows are read again whenever a preference changed or the user comes back to this screen
    (ctx.getActivity() as? SettingsActivity)?.prefChanged?.collectAsState()?.value
    val prefs = ctx.prefs()
    val homeState = remember { HomeStateHolder() }
    homeState.refreshOnResume()

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        KeyboardStatusCard(onSetUp = actions.onSetUp, refresh = homeState.version)

        SettingsGroup(title = stringResource(R.string.home_group_typing)) {
            val enabledSubtypes = SubtypeSettings.getEnabledSubtypes(true)
            HomeRow(R.string.language_and_layouts_title, actions.onLanguages, R.drawable.ic_settings_languages,
                enabledSubtypes.joinToString(", ") { it.displayName() })
            HomeRow(R.string.settings_screen_preferences, actions.onPreferences, R.drawable.ic_settings_preferences,
                stringResource(R.string.home_preferences_summary))
            if (JniUtils.sHaveGestureLib)
                HomeRow(R.string.settings_screen_gesture, actions.onGestureTyping, R.drawable.ic_settings_gesture,
                    onOff(prefs.getBoolean(Settings.PREF_GESTURE_INPUT, Defaults.PREF_GESTURE_INPUT)))
            // we don't even show the menu if data gathering phase ended more than 2 weeks ago
            if (helium314.keyboard.clackatoo.GestureDataPolicy.GATHERING_ALLOWED
                && JniUtils.sHaveGestureLib && System.currentTimeMillis() < END_DATE_EPOCH_MILLIS + TWO_WEEKS_IN_MILLIS)
                HomeRow(R.string.gesture_data_screen, actions.onDataGathering, R.drawable.ic_settings_gesture)
            HomeRow(R.string.settings_screen_correction, actions.onTextCorrection, R.drawable.ic_settings_correction,
                stringResource(R.string.home_correction_summary))
            HomeRow(R.string.settings_screen_secondary_layouts, actions.onLayouts, R.drawable.ic_settings_layout)
            HomeRow(R.string.dictionary_settings_category, actions.onDictionaries, R.drawable.ic_dictionary)
        }

        SettingsGroup(title = stringResource(R.string.home_group_voice_writing)) {
            val voice = AssistState.voice(ctx)
            HomeRow(R.string.settings_screen_voice_typing, actions.onVoiceTyping, R.drawable.sym_keyboard_voice_lxx, voice)
            HomeRow(R.string.settings_screen_grammar_check, actions.onGrammarCheck, R.drawable.ic_grammar_check, AssistState.grammar(ctx))
        }

        SettingsGroup(title = stringResource(R.string.home_group_look_feel)) {
            val dayNight = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && prefs.getBoolean(Settings.PREF_THEME_DAY_NIGHT, Defaults.PREF_THEME_DAY_NIGHT)
            val day = ThemeCatalog.displayName(ctx, prefs.getString(Settings.PREF_THEME_COLORS, Defaults.PREF_THEME_COLORS)!!)
            val night = ThemeCatalog.displayName(ctx, prefs.getString(Settings.PREF_THEME_COLORS_NIGHT, Defaults.PREF_THEME_COLORS_NIGHT)!!)
            // day and night only when they differ
            HomeRow(R.string.theme_picker_title, actions.onTheme, R.drawable.ic_settings_appearance, if (dayNight && day != night) "$day / $night" else day)
            HomeRow(R.string.settings_screen_appearance, actions.onAppearance, R.drawable.ic_settings_layout)
            HomeRow(R.string.settings_screen_toolbar, actions.onToolbar, R.drawable.ic_settings_toolbar)
            SettingsActivity.settingsContainer[GlassStyle.PREF_GLASS]?.Preference()
        }

        SettingsGroup {
            HomeRow(R.string.settings_screen_advanced, actions.onAdvanced, R.drawable.ic_settings_advanced)
            HomeRow(R.string.settings_screen_about, actions.onAbout, R.drawable.ic_settings_about,
                stringResource(R.string.version_text, BuildConfig.VERSION_NAME))
        }
    }
}

@Composable
private fun HomeRow(titleId: Int, onClick: () -> Unit, icon: Int, subtitle: String? = null) {
    SettingsRow(title = stringResource(titleId), subtitle = subtitle, icon = icon, onClick = onClick) { NextScreenIcon() }
}

@Composable
private fun onOff(on: Boolean) = stringResource(if (on) R.string.home_state_on else R.string.home_state_off)

/** Counts how often the user came back to the screen, so the rows that read system state are read again. */
private class HomeStateHolder {
    var version by mutableIntStateOf(0)
}

@Composable
private fun HomeStateHolder.refreshOnResume() {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) version++ }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}

/** "Clackatoo is your keyboard" once it is switched on and chosen, otherwise a card that starts the set-up. */
@Composable
private fun KeyboardStatusCard(onSetUp: () -> Unit, refresh: Int) {
    val ctx = LocalContext.current
    val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    @Suppress("UNUSED_EXPRESSION") refresh
    val enabled = UncachedInputMethodManagerUtils.isThisImeEnabled(ctx, imm)
    val current = enabled && UncachedInputMethodManagerUtils.isThisImeCurrent(ctx, imm)
    val appName = stringResource(ctx.applicationInfo.labelRes)
    SettingsPanel {
        if (current)
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(40.dp).background(READY_GREEN, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_setup_check), null, Modifier.size(24.dp), tint = Color.White)
                }
                Text(stringResource(R.string.home_status_ready, appName), style = MaterialTheme.typography.titleMedium)
            }
        else
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.home_setup_title, appName), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(if (enabled) R.string.home_setup_select else R.string.home_setup_enable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onSetUp) { Text(stringResource(R.string.home_setup_action)) }
            }
    }
}

/** Green of the "ready" check: white on it keeps 4.5:1 (checked in GlassStyleTest). */
internal val READY_GREEN = Color(0xFF1B7F3B)
