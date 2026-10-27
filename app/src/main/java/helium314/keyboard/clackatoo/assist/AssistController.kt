// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo.assist

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.R
import helium314.keyboard.latin.RichInputMethodManager
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.utils.InputTypeUtils
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.SettingsActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Voice typing (Voice key) and the grammar check (Check key). Everything runs on the phone: audio is recorded to
 * memory, speech and text models run in the separate model process ([MlService]), and nothing is sent anywhere.
 * Recording only happens while [AssistPanelView] is on screen showing the recording state: it never starts unless
 * the panel can be seen, and it stops (the audio is dropped) as soon as the panel is hidden or detached, the keyboard
 * hides, the field changes, or the language changes.
 */
class AssistController(private val ime: LatinIME) : AssistPanelView.Listener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val main = Handler(Looper.getMainLooper())
    private val client by lazy { MlClient(ime) }
    private val store get() = AssistSettings.modelStore(ime)
    private var panel: AssistPanelView? = null
    private var recorder: VoiceRecorder? = null
    /** Makes the recorder; replaced in tests. */
    internal var recorderFactory: (onLevel: (Float) -> Unit, onFull: () -> Unit) -> VoiceRecorder = { onLevel, onFull -> AudioRecorder(onLevel, onFull) }
    private var job: Job? = null
    /** Increases whenever the editor, keyboard visibility or language changes; results of older work are dropped. */
    private var session = 0

    private enum class Mode { NONE, LISTENING, WORKING, PREVIEW, MESSAGE }
    private var mode = Mode.NONE
    internal val isListening get() = mode == Mode.LISTENING
    internal val isIdle get() = mode == Mode.NONE
    private var language: AssistLanguage? = null

    private class PendingCorrection(val target: CheckTarget, val corrected: String)
    private var pending: PendingCorrection? = null

    fun attachPanel(view: AssistPanelView) {
        panel?.listener = null
        panel = view
        view.listener = this
        if (mode != Mode.NONE) close()
    }

    // ------------------------------------------------------------------ voice

    fun onVoiceKey() {
        if (mode == Mode.LISTENING) {
            onStop()
            return
        }
        if (mode != Mode.NONE) close()
        val info = ime.currentInputEditorInfo
        if (info == null || InputTypeUtils.isAnyPasswordInputType(info.inputType)) return
        val language = currentLanguage() ?: return message(R.string.assist_voice_title, unsupportedLanguageText())
        this.language = language
        val speech = store.best(ModelKind.SPEECH)
            ?: return message(R.string.assist_voice_title, missingModelText(ModelKind.SPEECH, R.string.assist_speech_model_missing), settings = true)
        if (!AudioRecorderAvailability.hasPermission(ime)) {
            VoicePermissionActivity.start(ime)
            return
        }
        // never record without the recording panel on screen (it is hidden, for example, in toolbar-only mode)
        val view = panel
        if (view == null) return panelHidden()
        when (startListening(view)) {
            ListeningStart.PANEL_HIDDEN -> return panelHidden()
            ListeningStart.MIC_UNAVAILABLE -> return message(R.string.assist_voice_title, ime.getString(R.string.assist_mic_unavailable))
            ListeningStart.STARTED -> Log.d(TAG, "recording started; speech model ${speech.fileName}")
        }
    }

    internal enum class ListeningStart { STARTED, PANEL_HIDDEN, MIC_UNAVAILABLE }

    /**
     * Starts recording and shows the recording state on [view], unless [view] cannot be seen (checked before the
     * microphone is opened, and again after the panel was shown). From then on the recording lasts only while the panel
     * is on screen: [onPanelUnavailable] and the level callback (every 100 ms) stop it and drop the audio otherwise.
     */
    internal fun startListening(view: AssistPanelView): ListeningStart {
        if (!view.canBeSeen()) return ListeningStart.PANEL_HIDDEN
        val rec = recorderFactory(
            { level ->
                main.post {
                    if (mode == Mode.LISTENING) {
                        if (panel?.isOnScreen() == true) panel?.setLevel(level) else close()
                    }
                }
            },
            { main.post { if (mode == Mode.LISTENING) onStop() } },
        )
        if (!rec.start()) return ListeningStart.MIC_UNAVAILABLE
        recorder = rec
        mode = Mode.LISTENING
        view.showListening()
        if (!view.isShown) { // the panel could not be shown after all
            close()
            return ListeningStart.PANEL_HIDDEN
        }
        return ListeningStart.STARTED
    }

    /** The panel is hidden, detached or being replaced: nothing may keep running behind it. */
    override fun onPanelUnavailable() {
        if (mode != Mode.NONE && panel?.isOnScreen() != true) close()
    }

    private fun panelHidden() {
        KeyboardSwitcher.getInstance().showToast(ime.getString(R.string.assist_panel_hidden), false)
    }

    override fun onStop() {
        val rec = recorder ?: return
        val pcm = rec.stop()
        recorder = null
        val language = language ?: return close()
        if (AudioRecorder.isSilent(pcm)) return message(R.string.assist_voice_title, ime.getString(R.string.assist_no_speech))
        transcribeAndInsert(pcm, language)
    }

    /**
     * The path both a real recording and the debug WAV entry point take after the audio is in memory: speech to
     * text, English clean-up with S1-mini, then insert into the field.
     */
    fun transcribeAndInsert(pcm: ShortArray, language: AssistLanguage) {
        val speech = store.best(ModelKind.SPEECH)
            ?: return message(R.string.assist_voice_title, missingModelText(ModelKind.SPEECH, R.string.assist_speech_model_missing), settings = true)
        // the same rule as for the Voice key, whichever way the audio got here (the debug WAV entry point too)
        val info = ime.currentInputEditorInfo
        if (info == null || InputTypeUtils.isAnyPasswordInputType(info.inputType)) return close()
        val multiLine = FieldKind.acceptsLineBreaks(info.inputType)
        val maxChars = OutputSanitizer.maxCharsForClip(pcm.size)
        val mySession = session
        mode = Mode.WORKING
        panel?.showWorking(ime.getString(R.string.assist_transcribing))
        job = scope.launch {
            try {
                val spec = speech
                val raw = try {
                    client.transcribe(store.file(spec), language.whisperCode, pcm)
                } catch (e: MlException) {
                    if (e.modelFailedWhileLoading) store.markBroken(spec)
                    throw e
                }
                // no transcripts in any log, in any build
                Log.i(TAG, "transcribed in ${raw.runMs} ms (load ${raw.loadMs} ms)")
                var text = OutputSanitizer.clean(raw.text, multiLine, maxChars)
                val cleanUp = store.best(ModelKind.CLEAN_UP)
                if (text.isNotBlank() && language.cleanUpWithS1Mini && cleanUp != null && AssistSettings.s1MiniEnabled(ime.prefs())) {
                    panel?.showWorking(ime.getString(R.string.assist_cleaning))
                    try {
                        val prompt = S1MiniPrompt.build(text, AssistSettings.styling(ime.prefs()))
                        val cleaned = client.generate(store.file(cleanUp), cleanUp.contextTokens, prompt, MAX_NEW_TOKENS_CLEAN_UP)
                        Log.i(TAG, "cleaned in ${cleaned.runMs} ms (load ${cleaned.loadMs} ms)")
                        text = OutputSanitizer.clean(S1MiniPrompt.parse(cleaned.text), multiLine, maxChars)
                    } catch (e: MlException) {
                        if (e.cancelled) throw e
                        if (e.modelFailedWhileLoading) store.markBroken(cleanUp)
                        Log.w(TAG, "clean-up failed, inserting the plain transcript: ${e.message}")
                    }
                }
                if (mySession != session) return@launch
                if (text.isBlank()) return@launch message(R.string.assist_voice_title, ime.getString(R.string.assist_no_speech))
                insert(text)
                close()
            } catch (e: CancellationException) {
                throw e
            } catch (e: MlException) {
                if (mySession == session && !e.cancelled) message(R.string.assist_voice_title, failureText(e), settings = e.modelFailedWhileLoading)
            } catch (e: Exception) {
                Log.w(TAG, "voice typing failed: ${e.javaClass.simpleName}")
                if (mySession == session) message(R.string.assist_voice_title, ime.getString(R.string.assist_failed, e.javaClass.simpleName))
            }
        }
    }

    private fun insert(text: String) {
        val logic = ime.inputLogic
        logic.finishInput()
        val before = logic.mConnection.getTextBeforeCursor(2, 0)
        val prepared = Dictation.prepareInsertion(before, text)
        if (prepared.isNotEmpty()) ime.onTextInput(prepared)
    }

    // ------------------------------------------------------------------ grammar check

    fun onCheckKey() {
        if (mode != Mode.NONE) close()
        // the same rule as for the Voice key: nothing runs behind a panel that nobody can see (toolbar-only mode)
        if (panel?.canBeSeen() != true) return panelHidden()
        ime.inputLogic.finishInput() // composing text is part of what is read
        val info = ime.currentInputEditorInfo
        if (SensitiveField.isSensitive(info)) return message(R.string.assist_check_title, ime.getString(R.string.assist_check_private))
        val language = currentLanguage() ?: return message(R.string.assist_check_title, unsupportedLanguageText())
        val grammar = store.best(ModelKind.GRAMMAR)
            ?: return message(R.string.assist_check_title, missingModelText(ModelKind.GRAMMAR, R.string.assist_grammar_model_missing), settings = true)
        val target = when (val read = readTarget()) {
            is CheckTarget.Result.Found -> read.target
            CheckTarget.Result.Empty -> return message(R.string.assist_check_title, ime.getString(R.string.assist_check_empty))
            CheckTarget.Result.TooLong -> return message(R.string.assist_check_title, ime.getString(R.string.assist_check_too_long))
            CheckTarget.Result.Unreadable -> return message(R.string.assist_check_title, ime.getString(R.string.assist_check_unreadable))
        }
        val multiLine = FieldKind.acceptsLineBreaks(info?.inputType ?: 0)
        this.language = language
        val mySession = session
        mode = Mode.WORKING
        panel?.showWorking(ime.getString(R.string.assist_checking))
        job = scope.launch {
            try {
                val format = grammar.format ?: PromptFormat.CHATML_NO_THINK
                var unreliable = 0
                var checkedAny = false
                val corrected = StringBuilder()
                var totalMs = 0L
                for (chunk in TextChunker.split(target.text, CHUNK_CHARS)) {
                    if (chunk.isBlank()) {
                        corrected.append(chunk)
                        continue
                    }
                    val result = try {
                        client.generate(store.file(grammar), grammar.contextTokens, GrammarPrompt.build(format, chunk),
                            (chunk.toByteArray().size + 48).coerceAtMost(MlProtocol.MAX_NEW_TOKENS))
                    } catch (e: MlException) {
                        if (e.modelFailedWhileLoading) store.markBroken(grammar)
                        throw e
                    }
                    totalMs += result.runMs
                    checkedAny = true
                    when (val parsed = GrammarPrompt.parse(chunk, result.text)) {
                        GrammarResult.Unchanged -> corrected.append(chunk)
                        // the model's text is untrusted: no control or invisible characters that the user's own chunk did not have, and no line breaks it did not have
                        is GrammarResult.Corrected -> corrected.append(
                            OutputSanitizer.clean(parsed.text, multiLine || '\n' in chunk || '\t' in chunk, original = chunk),
                        )
                        GrammarResult.Unreliable -> { unreliable++; corrected.append(chunk) }
                    }
                }
                Log.i(TAG, "checked ${target.text.length} characters in $totalMs ms")
                if (mySession != session) return@launch
                val text = corrected.toString()
                when {
                    !checkedAny || (unreliable > 0 && text == target.text) ->
                        message(R.string.assist_check_title, ime.getString(R.string.assist_check_unreliable))
                    text == target.text -> message(R.string.assist_check_title, ime.getString(R.string.assist_check_none))
                    else -> {
                        pending = PendingCorrection(target, text)
                        mode = Mode.PREVIEW
                        panel?.showPreview(text, WordDiff.changedRanges(target.text, text))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: MlException) {
                if (mySession == session && !e.cancelled) message(R.string.assist_check_title, failureText(e), settings = e.modelFailedWhileLoading)
            } catch (e: Exception) {
                Log.w(TAG, "check failed: ${e.javaClass.simpleName}")
                if (mySession == session) message(R.string.assist_check_title, ime.getString(R.string.assist_failed, e.javaClass.simpleName))
            }
        }
    }

    override fun onAccept() {
        val pending = pending ?: return close()
        // never touch a field that turned sensitive since the check started
        if (SensitiveField.isSensitive(ime.currentInputEditorInfo)) return close()
        val logic = ime.inputLogic
        logic.finishInput()
        val connection = logic.mConnection
        var replaced = false
        connection.beginBatchEdit()
        try {
            // Read the text and the selection again together, now, inside the batch edit, and replace only if it is
            // exactly what was checked and shown: same text, same selection, same cursor.
            val current = (readTarget() as? CheckTarget.Result.Found)?.target
            when (val replacement = TextReplacement.plan(pending.target, current, pending.corrected)) {
                null -> Unit
                is Replacement.OverSelection -> {
                    ime.onTextInput(replacement.text)
                    replaced = true
                }
                is Replacement.OverField -> if (connection.setSelection(0, replacement.length)) {
                    ime.onTextInput(replacement.text)
                    replaced = true
                }
            }
        } finally {
            connection.endBatchEdit()
        }
        if (!replaced) {
            this.pending = null
            return message(R.string.assist_check_title, ime.getString(R.string.assist_check_changed))
        }
        close()
    }

    /**
     * What the grammar check would work on right now: the selection, or else (only with nothing selected) the whole
     * field. Text and selection come from one call to the editor, not from the keyboard's caches.
     */
    private fun readTarget(): CheckTarget.Result {
        val connection = ime.inputLogic.mConnection
        return CheckTarget.from(
            EditorReader.snapshot(ime.currentInputConnection, CheckTarget.MAX_CHARS),
            connection.expectedSelectionStart, connection.expectedSelectionEnd,
        )
    }

    // ------------------------------------------------------------------ panel buttons, lifecycle

    override fun onCancel() = close()
    override fun onClose() = close()

    override fun onOpenSettings() {
        close()
        ime.startActivity(Intent(ime, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Stops everything and shows the keyboard again. Safe to call at any time. */
    fun close() {
        job?.cancel()
        job = null
        recorder?.cancel() // drops the audio
        recorder = null
        pending = null
        mode = Mode.NONE
        panel?.hide()
    }

    /** The editor, the keyboard's visibility or the language changed: nothing started before may continue. */
    fun onSessionChanged() {
        session++
        close()
    }

    fun onDestroy() {
        close()
        client.release()
        scope.cancel()
    }

    // ------------------------------------------------------------------ helpers

    private fun currentLanguage(): AssistLanguage? =
        AssistLanguages.forLocale(RichInputMethodManager.getInstance().currentSubtypeLocale)

    private fun message(titleRes: Int, text: CharSequence, settings: Boolean = false) {
        job = null
        recorder?.cancel()
        recorder = null
        pending = null
        mode = Mode.MESSAGE
        panel?.showMessage(ime.getString(titleRes), text, settings)
    }

    private fun unsupportedLanguageText(): String {
        val locale = RichInputMethodManager.getInstance().currentSubtypeLocale
        return ime.getString(R.string.assist_unsupported_language, locale.getDisplayLanguage(locale))
    }

    private fun missingModelText(kind: ModelKind, missingRes: Int): String {
        val broken = ModelCatalog.forKind(kind).any { store.status(it) is ModelStatus.Broken }
        return ime.getString(if (broken) R.string.assist_model_broken else missingRes)
    }

    private fun failureText(e: MlException): String =
        if (e.modelFailedWhileLoading) ime.getString(R.string.assist_model_broken)
        else ime.getString(R.string.assist_failed, e.message ?: "unknown")

    private companion object {
        const val TAG = "ClackatooAssist"
        const val CHUNK_CHARS = 600
        const val MAX_NEW_TOKENS_CLEAN_UP = 600
    }
}

object AudioRecorderAvailability {
    fun hasPermission(context: Context) =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
}
