// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.Context
import android.content.SharedPreferences
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.text.format.Formatter
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.lifecycle.compose.LifecycleResumeEffect
import helium314.keyboard.clackatoo.assist.AssistSettings
import helium314.keyboard.clackatoo.assist.CustomImportResult
import helium314.keyboard.clackatoo.assist.CustomPolicy
import helium314.keyboard.clackatoo.assist.CustomProbe
import helium314.keyboard.clackatoo.assist.CustomReject
import helium314.keyboard.clackatoo.assist.ImportResult
import helium314.keyboard.clackatoo.assist.ModelCatalog
import helium314.keyboard.clackatoo.assist.ModelKind
import helium314.keyboard.clackatoo.assist.ModelSpec
import helium314.keyboard.clackatoo.catalog.MULTILINGUAL
import helium314.keyboard.clackatoo.assist.ModelStatus
import helium314.keyboard.clackatoo.assist.RejectReason
import helium314.keyboard.clackatoo.assist.S1Styling
import helium314.keyboard.clackatoo.assist.SpeechEngine
import helium314.keyboard.clackatoo.models.DeviceProfile
import helium314.keyboard.clackatoo.models.ModelChoice
import helium314.keyboard.clackatoo.models.ModelSuggestions
import helium314.keyboard.clackatoo.models.ModelDownloads
import helium314.keyboard.clackatoo.models.ModelState
import helium314.keyboard.clackatoo.ui.ModelCard
import helium314.keyboard.clackatoo.ui.ModelCardState
import helium314.keyboard.clackatoo.ui.SettingsGroup
import helium314.keyboard.clackatoo.ui.SettingsHint
import helium314.keyboard.clackatoo.ui.SettingsRow
import helium314.keyboard.clackatoo.ui.SettingsSectionHeader
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.NextScreenIcon
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.dialogs.ListPickerDialog
import helium314.keyboard.settings.filePicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val modelPickerIntent = Intent(Intent.ACTION_OPEN_DOCUMENT)
    .addCategory(Intent.CATEGORY_OPENABLE)
    .setType("*/*")

@Composable
fun VoiceTypingScreen(onClickBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    var refresh by remember { mutableIntStateOf(0) }
    val s1Enabled by rememberCleanUpEnabled(prefs)
    var styling by remember { mutableStateOf(AssistSettings.styling(prefs)) }
    var showStyles by remember { mutableStateOf(false) }

    SearchSettingsScreen(onClickBack = onClickBack, title = stringResource(R.string.settings_screen_voice_typing), settings = emptyList()) {
        Scaffold(contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)) { innerPadding ->
            Column(Modifier.verticalScroll(rememberScrollState()).then(Modifier.padding(innerPadding)).padding(bottom = 16.dp)) {
                SettingsHint(stringResource(R.string.assist_voice_summary))
                SettingsHint(stringResource(R.string.assist_voice_languages))
                SettingsSectionHeader(stringResource(R.string.assist_speech_models))
                ModelCards(ModelKind.SPEECH, refresh) { refresh++ }
                CustomModelRows(ModelKind.SPEECH) { refresh++ }
                SettingsSectionHeader(stringResource(R.string.assist_cleanup_models))
                // the on/off switch heads its section; the keyboard's voice panel flips the same preference
                SettingsGroup(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    SettingsRow(
                        title = stringResource(R.string.assist_s1_mini_switch),
                        subtitle = stringResource(if (s1Enabled) R.string.assist_s1_mini_summary else R.string.assist_s1_mini_off_summary),
                        icon = R.drawable.ic_model_cleanup,
                        onClick = { AssistSettings.toggleS1Mini(prefs) },
                    ) { Switch(checked = s1Enabled, onCheckedChange = null) }
                    if (s1Enabled) SettingsRow(
                        title = stringResource(R.string.assist_s1_styling),
                        subtitle = stylingName(styling),
                        onClick = { showStyles = true },
                    ) { NextScreenIcon() }
                }
                ModelCards(ModelKind.CLEAN_UP, refresh) { refresh++ }
                CustomModelRows(ModelKind.CLEAN_UP) { refresh++ }
                ImportRow { refresh++ }
                // the credits the licences of S1-mini and Parakeet require wherever they are used
                SettingsHint(stringResource(R.string.assist_s1_mini_credit))
                SettingsHint(stringResource(R.string.assist_parakeet_credit))
                LicencesRow()
            }
        }
    }
    if (showStyles)
        ListPickerDialog(
            onDismissRequest = { showStyles = false },
            items = S1Styling.entries,
            selectedItem = styling,
            getItemName = { stylingName(it) },
            onItemSelected = {
                styling = it
                prefs.edit { putString(AssistSettings.PREF_S1_STYLING, it.value) }
            },
        )
}

/**
 * The "Clean up English text" switch's state, which the keyboard's voice panel changes too, also while this screen is
 * in the background (the settings activity stops listening to preferences when it stops): it follows every change of
 * the stored value for as long as the screen exists, and is read again whenever the screen resumes.
 */
@Composable
internal fun rememberCleanUpEnabled(prefs: SharedPreferences): State<Boolean> {
    val state = remember(prefs) { mutableStateOf(AssistSettings.s1MiniEnabled(prefs)) }
    DisposableEffect(prefs) {
        // held by onDispose: SharedPreferences keeps its listeners only weakly
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { changed, key ->
            if (key == AssistSettings.PREF_S1_MINI_ENABLED || key == null) state.value = AssistSettings.s1MiniEnabled(changed)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    LifecycleResumeEffect(prefs) {
        state.value = AssistSettings.s1MiniEnabled(prefs)
        onPauseOrDispose {}
    }
    return state
}

@Composable
fun GrammarCheckScreen(onClickBack: () -> Unit) {
    var refresh by remember { mutableIntStateOf(0) }
    SearchSettingsScreen(onClickBack = onClickBack, title = stringResource(R.string.settings_screen_grammar_check), settings = emptyList()) {
        Scaffold(contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)) { innerPadding ->
            Column(Modifier.verticalScroll(rememberScrollState()).then(Modifier.padding(innerPadding)).padding(bottom = 16.dp)) {
                SettingsHint(stringResource(R.string.assist_grammar_summary))
                SettingsHint(stringResource(R.string.assist_voice_languages))
                SettingsHint(stringResource(R.string.assist_grammar_private))
                SettingsSectionHeader(stringResource(R.string.assist_grammar_models))
                ModelCards(ModelKind.GRAMMAR, refresh) { refresh++ }
                CustomModelRows(ModelKind.GRAMMAR) { refresh++ }
                ImportRow { refresh++ }
                LicencesRow()
            }
        }
    }
}

/** The MIT notices of whisper.cpp, llama.cpp, parakeet.cpp, ggml and Silero VAD, which the MIT licence requires to ship with the app (assets/licenses). */
@Composable
private fun LicencesRow() {
    val ctx = LocalContext.current
    var show by remember { mutableStateOf(false) }
    SettingsGroup(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        SettingsRow(
            title = stringResource(R.string.assist_licenses),
            subtitle = stringResource(R.string.assist_licenses_summary),
            onClick = { show = true },
        )
    }
    if (show) {
        val notices = remember { runCatching { ctx.assets.open(NOTICES_ASSET).bufferedReader().use { it.readText() } }.getOrDefault("") }
        AlertDialog(
            onDismissRequest = { show = false },
            title = { Text(stringResource(R.string.assist_licenses)) },
            text = { Text(notices, style = MaterialTheme.typography.bodySmall, modifier = Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { show = false }) { Text(stringResource(R.string.dialog_close)) } },
        )
    }
}

private const val NOTICES_ASSET = "licenses/ml-notices.txt"

@Composable
private fun stylingName(styling: S1Styling) = stringResource(
    when (styling) {
        S1Styling.CASUAL -> R.string.assist_style_casual
        S1Styling.SEMI_CASUAL -> R.string.assist_style_semi_casual
        S1Styling.SEMI_FORMAL -> R.string.assist_style_semi_formal
        S1Styling.FORMAL -> R.string.assist_style_formal
    }
)

/**
 * One card per model of [kind] (the catalog's options for that role): name, what it is for, maker and size, and its
 * state with Download (or Get Clackatoo Models when the companion app is missing) / progress and Cancel / Ready. The model
 * suggested for this phone ([ModelSuggestions]) comes first and carries a label; then what is on the phone or on its way,
 * the rest folds into "More models". Below come the experimental custom models of the role, marked as such. With several
 * models installed the cards carry a radio to choose the one the role uses. The files come from [ModelDownloads] and the
 * choice from [ModelChoice]; [refresh] forces a re-read (after an import).
 */
@Composable
private fun ModelCards(kind: ModelKind, refresh: Int, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    @Suppress("UNUSED_EXPRESSION") refresh // read so the cards update after an import
    var tick by remember { mutableIntStateOf(0) }
    val customVersion by ModelDownloads.customChanges(ctx).collectAsState()
    val specs = ModelCatalog.forKind(kind).filter { it.bundledAsset == null }
    // every card's state is read here, so the radio follows a finished download (which also becomes the chosen model)
    val states = specs.map { ModelDownloads.state(it.id).collectAsState().value }
    val store = AssistSettings.modelStore(ctx)
    val customs = remember(refresh, tick, customVersion, states) { store.customSpecs(kind) }
    val installed = specs.filter { store.status(it) is ModelStatus.Installed } + customs.filter { store.status(it) is ModelStatus.Installed }
    val chosen = remember(refresh, tick, customVersion, states) { ModelChoice.active(ctx, kind)?.id }
    val companion = ModelDownloads.isCompanionInstalled(ctx)
    val profile = remember { DeviceProfile.read(ctx) }
    val suggestedId = remember(profile, kind) { ModelSuggestions.suggestedId(profile, kind, ModelSuggestions.keyboardLanguages()) }
    // shown: the suggested model first, then what is on the phone or on its way; the rest folds into "More models"
    fun busy(spec: ModelSpec) = ModelDownloads.state(spec.id).value.let { it is ModelState.Downloading || it is ModelState.Installing || it is ModelState.Failed }
    val suggested = specs.firstOrNull { it.id == suggestedId }
    val others = specs.filter { it != suggested && (it in installed || busy(it)) }
    val shown = listOfNotNull(suggested) + others
    val more = specs - shown.toSet()
    var expanded by rememberSaveable(kind) { mutableStateOf(false) }
    @Composable fun card(spec: ModelSpec) {
        val download = ModelDownloads.state(spec.id).collectAsState().value
        val status = store.status(spec)
        val cardState = when {
            download is ModelState.Downloading -> ModelCardState.Downloading(download.bytes, download.total)
            download is ModelState.Installing -> ModelCardState.Installing
            download is ModelState.Failed -> ModelCardState.Failed(download.message)
            status is ModelStatus.Installed -> ModelCardState.Installed
            status is ModelStatus.Broken -> ModelCardState.Failed(stringResource(R.string.assist_model_broken_status))
            !ModelChoice.runsHere(spec) -> ModelCardState.Unsupported
            else -> ModelCardState.NotInstalled
        }
        ModelCard(
            title = spec.shortName,
            fullName = spec.displayName,
            subtitle = modelSubtitle(ctx, spec, ModelSuggestions.mayBeSlow(profile, spec.id)),
            icon = modelIcon(spec),
            state = cardState,
            downloadLabel = stringResource(if (companion) R.string.models_download else R.string.models_get_companion),
            onDownload = { ModelDownloads.download(ctx, spec.id) },
            onCancel = { ModelDownloads.cancel(ctx, spec.id) },
            onDelete = { ModelDownloads.delete(ctx, spec.id); tick++; onChanged() },
            selectable = installed.size > 1,
            selected = chosen == spec.id,
            onSelect = { if (ModelChoice.select(ctx, kind, spec.id)) tick++ },
            badge = if (spec.id == suggestedId) stringResource(R.string.models_suggested) else null,
        )
    }
    shown.forEach { card(it) }
    if (more.isNotEmpty()) {
        SettingsGroup(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            SettingsRow(
                title = stringResource(R.string.models_more, more.size),
                onClick = { expanded = !expanded },
            ) { Icon(painterResource(R.drawable.ic_arrow_left), null, Modifier.rotate(if (expanded) 90f else -90f)) }
        }
        if (expanded) more.forEach { card(it) }
    }
    // experimental custom models: never suggested, always labelled, chosen like any other model
    customs.forEach { spec ->
        val status = store.status(spec)
        ModelCard(
            title = spec.shortName,
            fullName = spec.displayName,
            subtitle = customSubtitle(ctx, spec),
            icon = modelIcon(spec),
            state = if (status is ModelStatus.Broken) ModelCardState.Failed(stringResource(R.string.assist_model_broken_status)) else ModelCardState.Installed,
            downloadLabel = "",
            onDownload = {},
            onCancel = {},
            onDelete = { ModelDownloads.deleteCustom(ctx, spec); tick++; onChanged() },
            selectable = installed.size > 1,
            selected = chosen == spec.id,
            onSelect = { if (ModelChoice.select(ctx, kind, spec.id)) tick++ },
            badge = stringResource(R.string.models_experimental),
            badgeIsWarning = true,
        )
    }
}

/** What a custom model is, from what the keyboard knows about the file: its role, English-only or not, and its size. */
private fun customSubtitle(ctx: Context, spec: ModelSpec): String {
    val what = ctx.getString(
        when {
            spec.engine == SpeechEngine.PARAKEET -> R.string.model_facts_live
            spec.kind == ModelKind.SPEECH -> R.string.model_facts_speech
            spec.kind == ModelKind.CLEAN_UP -> R.string.model_facts_cleanup
            else -> R.string.model_facts_grammar
        }
    )
    val english = if (spec.languages == listOf("en")) " \u00b7 " + ctx.getString(R.string.model_lang_english) else ""
    return "$what$english \u00b7 ${Formatter.formatShortFileSize(ctx, spec.sizeBytes)}"
}

/**
 * The two ways to bring in a model of your own, below the cards of a role: a file from the phone's storage, or a model the
 * user finds on Hugging Face in the Clackatoo Models app. Both are behind a one-time notice; the file import checks the
 * format, size and does one test load of the file (in the isolated model process) before it keeps it.
 */
@Composable
private fun CustomModelRows(kind: ModelKind, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = ctx.prefs()
    var working by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<(() -> Unit)?>(null) } // what to do once the notice is accepted
    var rejected by remember { mutableStateOf<String?>(null) }
    val picker = filePicker { uri ->
        working = true
        scope.launch {
            val outcome = withContext(Dispatchers.IO) { importCustomModel(ctx, kind, uri) }
            working = false
            ModelDownloads.customChanged(ctx)
            onChanged()
            if (outcome.error == null) Toast.makeText(ctx, ctx.getString(R.string.custom_import_done, outcome.name), Toast.LENGTH_LONG).show()
            else rejected = outcome.error
        }
    }
    fun afterNotice(action: () -> Unit) {
        if (AssistSettings.customModelsConfirmed(prefs)) action() else notice = action
    }
    SettingsGroup(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        SettingsRow(
            title = if (working) stringResource(R.string.custom_importing) else stringResource(R.string.custom_row_import),
            subtitle = stringResource(R.string.custom_row_import_summary),
            onClick = { if (!working) afterNotice { picker.launch(modelPickerIntent) } },
        )
        SettingsRow(
            title = stringResource(R.string.custom_row_browse),
            subtitle = stringResource(if (ModelDownloads.isCompanionInstalled(ctx)) R.string.custom_row_browse_summary else R.string.custom_row_browse_missing),
            onClick = { afterNotice { ModelDownloads.openCompanion(ctx) } },
        )
    }
    if (kind != ModelKind.SPEECH) SettingsHint(stringResource(R.string.custom_clean_up_note))
    notice?.let { action ->
        AlertDialog(
            onDismissRequest = { notice = null },
            title = { Text(stringResource(R.string.custom_notice_title)) },
            text = { Text(stringResource(R.string.custom_notice_text), modifier = Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = {
                TextButton(onClick = {
                    prefs.edit { putBoolean(AssistSettings.PREF_CUSTOM_MODELS_CONFIRMED, true) }
                    notice = null
                    action()
                }) { Text(stringResource(R.string.custom_notice_accept)) }
            },
            dismissButton = { TextButton(onClick = { notice = null }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
    rejected?.let { message ->
        AlertDialog(
            onDismissRequest = { rejected = null },
            title = { Text(stringResource(R.string.custom_rejected_title)) },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { rejected = null }) { Text(stringResource(R.string.dialog_close)) } },
        )
    }
}

/** The outcome of importing a custom model: its name, or the message that says why it was refused. */
private class CustomImportOutcome(val name: String?, val error: String?)

/**
 * Copies the file the user picked into the custom model folder of [kind], checking it on the way (size cap, format from
 * its header, one test load in the model process), and makes it the model of that role. Blocks: call it off the main thread.
 */
private fun importCustomModel(ctx: Context, kind: ModelKind, uri: Uri): CustomImportOutcome {
    val resolver = ctx.contentResolver
    var name: String? = null
    var size = -1L
    try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                name = c.getString(0)
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
    } catch (_: Exception) { }
    val input = try { resolver.openInputStream(uri) } catch (_: Exception) { null }
        ?: return CustomImportOutcome(null, ctx.getString(R.string.custom_reject_read))
    val store = AssistSettings.modelStore(ctx)
    store.cleanUp()
    val result = input.use { store.importCustom(kind, name, it, size) { file, spec -> CustomProbe.run(ctx, file, spec) } }
    return when (result) {
        is CustomImportResult.Imported -> {
            ModelChoice.select(ctx, kind, result.spec.id)
            CustomImportOutcome(result.spec.shortName, null)
        }
        is CustomImportResult.Rejected -> CustomImportOutcome(null, ctx.getString(
            when (result.reason) {
                CustomReject.TOO_LARGE -> R.string.custom_reject_too_large
                CustomReject.NOT_A_MODEL -> R.string.custom_reject_not_a_model
                CustomReject.WRONG_ROLE -> if (kind == ModelKind.SPEECH) R.string.custom_reject_wrong_role_speech else R.string.custom_reject_wrong_role_text
                CustomReject.NOT_ENOUGH_SPACE -> R.string.custom_reject_no_space
                CustomReject.READ_ERROR -> R.string.custom_reject_read
                CustomReject.LOAD_FAILED -> R.string.custom_reject_load
                CustomReject.TOO_MANY -> R.string.custom_reject_too_many
            }, *(if (result.reason == CustomReject.TOO_MANY) arrayOf<Any>(CustomPolicy.MAX_MODELS) else emptyArray<Any>()),
        ))
    }
}

@DrawableRes
private fun modelIcon(spec: ModelSpec) = when {
    spec.engine == SpeechEngine.PARAKEET -> R.drawable.ic_model_live
    spec.kind == ModelKind.SPEECH -> R.drawable.ic_model_speech
    spec.kind == ModelKind.CLEAN_UP -> R.drawable.ic_model_cleanup
    else -> R.drawable.ic_grammar_check
}

/** One line of facts: what it does, languages, size, maker (the maker as text, never a logo). */
private fun modelSubtitle(ctx: Context, spec: ModelSpec, maySlow: Boolean = false): String {
    val what = ctx.getString(
        when {
            spec.id == "parakeet-tdt-ctc-110m" -> R.string.model_facts_live_fast
            spec.engine == SpeechEngine.PARAKEET -> R.string.model_facts_live
            spec.kind == ModelKind.SPEECH -> R.string.model_facts_speech
            spec.kind == ModelKind.CLEAN_UP -> R.string.model_facts_cleanup
            else -> R.string.model_facts_grammar
        }
    )
    val languages = when {
        spec.languages == listOf("en") -> ctx.getString(R.string.model_lang_english)
        spec.languages.isEmpty() || MULTILINGUAL in spec.languages -> ctx.getString(R.string.model_lang_multilingual)
        else -> ctx.getString(R.string.model_lang_count, spec.languages.size)
    }
    val by = if (spec.maker.isBlank()) "" else " \u00b7 " + ctx.getString(R.string.model_card_by, spec.maker.substringBefore(","))
    val slow = if (maySlow) " \u00b7 " + ctx.getString(R.string.models_may_be_slow) else ""
    return "$what \u00b7 $languages \u00b7 ${Formatter.formatShortFileSize(ctx, spec.sizeBytes)}$by$slow"
}

@Composable
private fun ImportRow(onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var importing by remember { mutableStateOf(false) }
    val picker = filePicker { uri ->
        importing = true
        scope.launch {
            val message = withContext(Dispatchers.IO) { importModel(ctx, uri) }
            importing = false
            ModelDownloads.refresh(ctx)
            onChanged()
            Toast.makeText(ctx, message, Toast.LENGTH_LONG).show()
        }
    }
    // a secondary action: the cards above are the way to get a model
    SettingsGroup(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        SettingsRow(
            title = if (importing) stringResource(R.string.assist_importing) else stringResource(R.string.assist_import_model),
            subtitle = stringResource(R.string.assist_import_model_summary),
            onClick = { if (!importing) picker.launch(modelPickerIntent) },
        )
    }
}

/** Copies the file the user picked into the model folder (validated first); returns the message to show. */
private fun importModel(ctx: android.content.Context, uri: Uri): String {
    val resolver = ctx.contentResolver
    val name = try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (_: Exception) {
        null
    }
    val input = try { resolver.openInputStream(uri) } catch (_: Exception) { null }
        ?: return ctx.getString(R.string.assist_import_error)
    val store = AssistSettings.modelStore(ctx)
    store.cleanUp()
    val result = input.use { store.import(name, it) }
    return when (result) {
        is ImportResult.Imported -> ctx.getString(R.string.assist_import_done, result.spec.displayName)
        is ImportResult.Rejected -> ctx.getString(
            when (result.reason) {
                RejectReason.UNKNOWN_FILE_NAME -> R.string.assist_import_unknown
                RejectReason.NOT_A_MODEL -> R.string.assist_import_not_a_model
                RejectReason.WRONG_SIZE -> R.string.assist_import_wrong_size
                RejectReason.WRONG_CONTENT -> R.string.assist_import_wrong_content
                RejectReason.NOT_ENOUGH_SPACE -> R.string.assist_import_no_space
                RejectReason.READ_ERROR -> R.string.assist_import_error
            }
        )
    }
}
