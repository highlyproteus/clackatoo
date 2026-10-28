// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import helium314.keyboard.clackatoo.assist.AssistSettings
import helium314.keyboard.clackatoo.assist.ImportResult
import helium314.keyboard.clackatoo.assist.ModelCatalog
import helium314.keyboard.clackatoo.assist.ModelKind
import helium314.keyboard.clackatoo.assist.ModelStatus
import helium314.keyboard.clackatoo.assist.RejectReason
import helium314.keyboard.clackatoo.assist.S1Styling
import helium314.keyboard.clackatoo.assist.SpeechEngine
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.dialogs.ListPickerDialog
import helium314.keyboard.settings.filePicker
import helium314.keyboard.settings.preferences.Preference
import helium314.keyboard.settings.preferences.PreferenceCategory
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
    var s1Enabled by remember { mutableStateOf(AssistSettings.s1MiniEnabled(prefs)) }
    var styling by remember { mutableStateOf(AssistSettings.styling(prefs)) }
    var showStyles by remember { mutableStateOf(false) }

    SearchSettingsScreen(onClickBack = onClickBack, title = stringResource(R.string.settings_screen_voice_typing), settings = emptyList()) {
        Scaffold(contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)) { innerPadding ->
            Column(Modifier.verticalScroll(rememberScrollState()).then(Modifier.padding(innerPadding))) {
                InfoText(stringResource(R.string.assist_voice_summary))
                InfoText(stringResource(R.string.assist_voice_languages))
                PreferenceCategory(stringResource(R.string.assist_settings_models))
                ModelRows(ModelKind.SPEECH, refresh)
                ModelRows(ModelKind.CLEAN_UP, refresh)
                ImportRow { refresh++ }
                PreferenceCategory(stringResource(R.string.assist_s1_mini_switch))
                Preference(
                    name = stringResource(R.string.assist_s1_mini_switch),
                    description = stringResource(R.string.assist_s1_mini_summary),
                    onClick = {
                        s1Enabled = !s1Enabled
                        prefs.edit { putBoolean(AssistSettings.PREF_S1_MINI_ENABLED, s1Enabled) }
                    },
                ) { Switch(checked = s1Enabled, onCheckedChange = null) }
                Preference(
                    name = stringResource(R.string.assist_s1_styling),
                    description = stylingName(styling),
                    onClick = { showStyles = true },
                )
                // the credit S1-mini's licence requires wherever it is used
                InfoText(stringResource(R.string.assist_s1_mini_credit))
                InfoText(stringResource(R.string.assist_parakeet_credit))
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

@Composable
fun GrammarCheckScreen(onClickBack: () -> Unit) {
    var refresh by remember { mutableIntStateOf(0) }
    SearchSettingsScreen(onClickBack = onClickBack, title = stringResource(R.string.settings_screen_grammar_check), settings = emptyList()) {
        Scaffold(contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)) { innerPadding ->
            Column(Modifier.verticalScroll(rememberScrollState()).then(Modifier.padding(innerPadding))) {
                InfoText(stringResource(R.string.assist_grammar_summary))
                InfoText(stringResource(R.string.assist_voice_languages))
                InfoText(stringResource(R.string.assist_grammar_private))
                PreferenceCategory(stringResource(R.string.assist_settings_models))
                ModelRows(ModelKind.GRAMMAR, refresh)
                ImportRow { refresh++ }
                LicencesRow()
            }
        }
    }
}

/** The MIT notices of whisper.cpp, llama.cpp and ggml, which the MIT licence requires to ship with the app (assets/licenses). */
@Composable
private fun LicencesRow() {
    val ctx = LocalContext.current
    var show by remember { mutableStateOf(false) }
    Preference(
        name = stringResource(R.string.assist_licenses),
        description = stringResource(R.string.assist_licenses_summary),
        onClick = { show = true },
    )
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
private fun InfoText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

@Composable
private fun stylingName(styling: S1Styling) = stringResource(
    when (styling) {
        S1Styling.CASUAL -> R.string.assist_style_casual
        S1Styling.SEMI_CASUAL -> R.string.assist_style_semi_casual
        S1Styling.SEMI_FORMAL -> R.string.assist_style_semi_formal
        S1Styling.FORMAL -> R.string.assist_style_formal
    }
)

/** One row per model of [kind]: name, licence and whether it is installed. [refresh] forces a re-read. */
@Composable
private fun ModelRows(kind: ModelKind, refresh: Int) {
    val ctx = LocalContext.current
    val store = AssistSettings.modelStore(ctx)
    @Suppress("UNUSED_EXPRESSION") refresh // read so the rows update after an import
    ModelCatalog.forKind(kind).forEach { spec ->
        val status = when (val s = store.status(spec)) {
            ModelStatus.Missing -> stringResource(R.string.assist_model_missing)
            is ModelStatus.Installed -> stringResource(R.string.assist_model_installed, Formatter.formatShortFileSize(ctx, s.sizeBytes))
            is ModelStatus.Broken -> stringResource(R.string.assist_model_broken_status)
        }
        Preference(name = spec.displayName, description = "$status\n${spec.licence}, ${spec.fileName}", onClick = {})
        if (spec.engine == SpeechEngine.PARAKEET) InfoText(stringResource(R.string.assist_parakeet_live_hint))
    }
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
            onChanged()
            Toast.makeText(ctx, message, Toast.LENGTH_LONG).show()
        }
    }
    Preference(
        name = if (importing) stringResource(R.string.assist_importing) else stringResource(R.string.assist_import_model),
        description = stringResource(R.string.assist_import_model_summary),
        onClick = { if (!importing) picker.launch(modelPickerIntent) },
    )
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
