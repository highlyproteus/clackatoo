// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo.ui

import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import helium314.keyboard.clackatoo.ThemeCatalog
import helium314.keyboard.clackatoo.ThemeSelection
import helium314.keyboard.clackatoo.assist.ModelCatalog
import helium314.keyboard.clackatoo.assist.ModelKind
import helium314.keyboard.clackatoo.assist.ModelSpec
import helium314.keyboard.clackatoo.models.DeviceProfile
import helium314.keyboard.clackatoo.models.ModelChoice
import helium314.keyboard.clackatoo.models.ModelDownloads
import helium314.keyboard.clackatoo.models.ModelState
import helium314.keyboard.clackatoo.models.ModelSuggestions
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.KeyboardTheme
import helium314.keyboard.latin.BuildConfig
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.JniUtils
import helium314.keyboard.latin.utils.NextScreenIcon
import helium314.keyboard.latin.utils.ResourceUtils
import helium314.keyboard.latin.utils.SubtypeLocaleUtils.displayName
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.UncachedInputMethodManagerUtils
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.screens.gesturedata.END_DATE_EPOCH_MILLIS
import helium314.keyboard.settings.screens.gesturedata.TWO_WEEKS_IN_MILLIS

/** Where the entries of the home screen lead. */
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

/**
 * The home screen below its header ([HomeHeader], drawn by the search screen): the set-up card while Clackatoo is not on and
 * chosen, else a field to try the keyboard in with quick buttons, and the setup checklist while a model is missing. Then
 * four tiles with the state of swipe typing, voice typing, grammar check and the languages, theme swatches, and every other
 * settings screen in "More settings".
 */
@Composable
fun HomeContent(actions: HomeActions) {
    // the state is read again whenever a preference changed or the user comes back to this screen
    observePrefChanges()
    val ctx = LocalContext.current
    val homeState = remember { HomeStateHolder() }
    homeState.refreshOnResume()

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (keyboardIsOn(ctx, homeState.version)) {
            TryPanel(actions)
            SetupChecklistCard()
        } else SetUpCard(onSetUp = actions.onSetUp, refresh = homeState.version)

        StateTiles(actions)

        LookAndFeel(actions)

        SettingsGroup(title = stringResource(R.string.home_group_more)) {
            HomeRow(R.string.settings_screen_preferences, actions.onPreferences, R.drawable.ic_settings_preferences,
                stringResource(R.string.home_preferences_summary))
            HomeRow(R.string.settings_screen_correction, actions.onTextCorrection, R.drawable.ic_settings_correction,
                stringResource(R.string.home_correction_summary))
            HomeRow(R.string.settings_screen_secondary_layouts, actions.onLayouts, R.drawable.ic_settings_layout)
            HomeRow(R.string.dictionary_settings_category, actions.onDictionaries, R.drawable.ic_dictionary)
            HomeRow(R.string.settings_screen_appearance, actions.onAppearance, R.drawable.ic_settings_layout)
            HomeRow(R.string.settings_screen_toolbar, actions.onToolbar, R.drawable.ic_settings_toolbar)
            // we don't even show the menu if data gathering phase ended more than 2 weeks ago
            if (helium314.keyboard.clackatoo.GestureDataPolicy.GATHERING_ALLOWED
                && JniUtils.sHaveGestureLib && System.currentTimeMillis() < END_DATE_EPOCH_MILLIS + TWO_WEEKS_IN_MILLIS)
                HomeRow(R.string.gesture_data_screen, actions.onDataGathering, R.drawable.ic_settings_gesture)
            HomeRow(R.string.settings_screen_advanced, actions.onAdvanced, R.drawable.ic_settings_advanced)
            HomeRow(R.string.settings_screen_about, actions.onAbout, R.drawable.ic_settings_about,
                stringResource(R.string.version_text, BuildConfig.VERSION_NAME))
        }
    }
}

/**
 * Reads the settings' counter of preference changes, so that this composable is composed again when one happens. Every section
 * of the home screen does it itself: Compose skips a section whose arguments did not change, and would never show the new state.
 * Inline, so that the state is read in the caller's scope, which is the one to compose again.
 */
@Composable
private inline fun observePrefChanges() {
    (LocalContext.current.getActivity() as? SettingsActivity)?.prefChanged?.collectAsState()?.value
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

/** True once Clackatoo is switched on and chosen as the keyboard; [refresh] makes it be read again. */
private fun keyboardIsOn(ctx: Context, @Suppress("UNUSED_PARAMETER") refresh: Int): Boolean {
    val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    return UncachedInputMethodManagerUtils.isThisImeEnabled(ctx, imm) && UncachedInputMethodManagerUtils.isThisImeCurrent(ctx, imm)
}

/** The card that starts the set-up, while Clackatoo is not switched on or not the chosen keyboard. */
@Composable
private fun SetUpCard(onSetUp: () -> Unit, refresh: Int) {
    val ctx = LocalContext.current
    val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    @Suppress("UNUSED_EXPRESSION") refresh
    val enabled = UncachedInputMethodManagerUtils.isThisImeEnabled(ctx, imm)
    val appName = stringResource(ctx.applicationInfo.labelRes)
    SettingsPanel {
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

/** The try field and the quick buttons: the theme picker, Voice typing and the languages. */
@Composable
private fun TryPanel(actions: HomeActions) {
    SettingsPanel {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TryField()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuickAction(R.drawable.ic_settings_appearance, stringResource(R.string.theme_picker_title), actions.onTheme, Modifier.weight(1f))
                QuickAction(R.drawable.sym_keyboard_voice_lxx, stringResource(R.string.home_quick_voice), actions.onVoiceTyping, Modifier.weight(1f))
                QuickAction(R.drawable.ic_settings_languages, stringResource(R.string.home_quick_languages), actions.onLanguages, Modifier.weight(1f))
            }
        }
    }
}

/** What the setup checklist needs to know about one model role. */
private class RoleModel(val active: ModelSpec?, val suggested: ModelSpec?, val state: ModelState, val runsHere: Boolean) {
    val step get() = SetupStep.of(runsHere, active != null)
}

/** The model [kind] uses now, the one suggested for this phone and its download state (which makes the checklist follow a download). */
@Composable
private fun roleModel(kind: ModelKind, profile: DeviceProfile, languages: Set<String>): RoleModel {
    val ctx = LocalContext.current
    val suggested = remember(profile, kind, languages) { ModelSuggestions.suggestedId(profile, kind, languages)?.let { ModelCatalog.byId(it) } }
    val state = suggested?.let { ModelDownloads.state(it.id).collectAsState().value } ?: ModelState.NotInstalled
    val runs = ModelCatalog.forKind(kind).any { it.bundledAsset == null && ModelChoice.runsHere(it) }
    return RoleModel(ModelChoice.active(ctx, kind), suggested, state, runs)
}

/**
 * "Setup": the keyboard is on (a check), then the voice and the grammar model with the one suggested for this phone and a
 * Download button, progress while it comes in, and a check when it is ready. A role no model of which runs on this phone is
 * left out; if that is both, one line says why. The whole card goes once everything is done.
 */
@Composable
private fun SetupChecklistCard() {
    observePrefChanges()
    val ctx = LocalContext.current
    val profile = remember { DeviceProfile.read(ctx) }
    val languages = ModelSuggestions.keyboardLanguages()
    val voice = roleModel(ModelKind.SPEECH, profile, languages)
    val grammar = roleModel(ModelKind.GRAMMAR, profile, languages)
    val checklist = SetupChecklist.forPhone(voice.step, grammar.step) ?: return
    SettingsPanel {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(stringResource(R.string.home_checklist_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp))
            ChecklistRow(done = true, title = stringResource(R.string.home_checklist_keyboard))
            if (checklist.unsupported)
                Text(
                    stringResource(R.string.home_checklist_unsupported),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            else {
                if (checklist.voice != SetupStep.SKIPPED) ModelStepRow(stringResource(R.string.home_checklist_voice), voice)
                if (checklist.grammar != SetupStep.SKIPPED) ModelStepRow(stringResource(R.string.home_checklist_grammar), grammar)
            }
        }
    }
}

@Composable
private fun ModelStepRow(title: String, role: RoleModel) {
    val ctx = LocalContext.current
    val spec = role.suggested
    if (role.active != null) {
        ChecklistRow(done = true, title = title, subtitle = role.active.shortName)
        return
    }
    val suggested = spec?.let { stringResource(R.string.home_checklist_suggested, it.shortName) }
    val label = stringResource(if (ModelDownloads.isCompanionInstalled(ctx)) R.string.models_download else R.string.models_get_companion)
    // a long label ("Get Clackatoo Models") would squeeze the name, so it goes below the text, as in the model cards
    val buttonBelow = label.length > SHORT_LABEL
    val button: (@Composable (Modifier) -> Unit)? = spec?.let { s ->
        { m -> FilledTonalButton(onClick = { ModelDownloads.download(ctx, s.id) }, modifier = m) { Text(label) } }
    }
    val buttonBelowText: (@Composable ColumnScope.() -> Unit)? = if (button != null && buttonBelow) ({ button(Modifier.padding(top = 8.dp)) }) else null
    val buttonBesideText: (@Composable () -> Unit)? = if (button != null && !buttonBelow) ({ button(Modifier) }) else null
    when (val state = role.state) {
        is ModelState.Downloading -> ChecklistRow(
            done = false, title = title,
            below = { DownloadProgress(ModelCardState.Downloading(state.bytes, state.total)) },
            trailing = spec?.let { s -> { TextButton(onClick = { ModelDownloads.cancel(ctx, s.id) }) { Text(stringResource(R.string.models_cancel)) } } },
        )
        is ModelState.Installing -> ChecklistRow(
            done = false, title = title, subtitle = stringResource(R.string.models_installing),
            below = { LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp)) },
        )
        is ModelState.Failed -> ChecklistRow(
            done = false, title = title, subtitle = state.message, subtitleIsError = true,
            below = buttonBelowText, trailing = buttonBesideText,
        )
        else -> ChecklistRow(done = false, title = title, subtitle = suggested, below = buttonBelowText, trailing = buttonBesideText)
    }
}

private const val SHORT_LABEL = 12

/** Swipe typing, Voice typing, Grammar check and Languages as 2 x 2 tiles with their state. */
@Composable
private fun StateTiles(actions: HomeActions) {
    observePrefChanges()
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    val swipeState = onOff(prefs.getBoolean(Settings.PREF_GESTURE_INPUT, Defaults.PREF_GESTURE_INPUT))
    val tiles = listOfNotNull<@Composable (Modifier) -> Unit>(
        if (JniUtils.sHaveGestureLib) ({ m -> StateTile(stringResource(R.string.home_tile_swipe), swipeState, R.drawable.ic_settings_gesture, actions.onGestureTyping, m) }) else null,
        { m -> StateTile(stringResource(R.string.settings_screen_voice_typing), AssistState.voice(ctx), R.drawable.sym_keyboard_voice_lxx, actions.onVoiceTyping, m) },
        { m -> StateTile(stringResource(R.string.settings_screen_grammar_check), AssistState.grammar(ctx), R.drawable.ic_grammar_check, actions.onGrammarCheck, m) },
        { m ->
            val languages = SubtypeSettings.getEnabledSubtypes(true).joinToString(", ") { it.displayName() }
            StateTile(stringResource(R.string.home_tile_languages), languages, R.drawable.ic_settings_languages, actions.onLanguages, m)
        },
    )
    for (row in tiles.chunked(2)) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for (tile in row) tile(Modifier.weight(1f).fillMaxHeight())
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

/**
 * Four theme swatches as live miniatures of the keyboard (the current theme first and marked, then Glass and two Omarchy
 * themes) that apply the theme on a tap, for day or night as the phone is now, "See all themes" for the picker, and the
 * switch for the glass settings background.
 */
@Composable
private fun LookAndFeel(actions: HomeActions) {
    observePrefChanges()
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    val dayNight = ThemeSelection.dayNightOn(prefs)
    val night = ThemeSelection.editsNight(dayNight, ResourceUtils.isNight(ctx.resources))
    val current = ThemeSelection.current(prefs, night)
    SettingsGroup(title = stringResource(R.string.home_group_look_feel)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (id in ThemeSelection.swatches(current)) {
                // the colours are what the keyboard will use: the same code builds them
                val colors = remember(id, night) { runCatching { KeyboardTheme.getColorsForThemeName(ctx, id, night) }.getOrNull() }
                ThemeSwatch(
                    name = ThemeCatalog.displayName(ctx, id), colors = colors, selected = id == current,
                    onClick = {
                        ThemeSelection.apply(prefs, id, night)
                        KeyboardSwitcher.getInstance().setThemeNeedsReload()
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        val day = ThemeCatalog.displayName(ctx, ThemeSelection.current(prefs, false))
        val nightName = ThemeCatalog.displayName(ctx, ThemeSelection.current(prefs, true))
        // day and night only when they differ
        HomeRow(R.string.home_see_all_themes, actions.onTheme, R.drawable.ic_settings_appearance, if (dayNight && day != nightName) "$day / $nightName" else day)
        SettingsActivity.settingsContainer[GlassStyle.PREF_GLASS]?.Preference()
    }
}

/** Green of the "ready" check: white on it keeps 4.5:1 (checked in GlassStyleTest). */
internal val READY_GREEN = androidx.compose.ui.graphics.Color(0xFF1B7F3B)
