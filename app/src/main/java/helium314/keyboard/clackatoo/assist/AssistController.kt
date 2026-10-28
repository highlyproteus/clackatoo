// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo.assist

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.text.format.Formatter
import android.os.Handler
import android.os.Looper
import helium314.keyboard.clackatoo.catalog.Catalog
import helium314.keyboard.clackatoo.models.DeviceProfile
import helium314.keyboard.clackatoo.models.ModelChoice
import helium314.keyboard.clackatoo.models.ModelSuggestions
import helium314.keyboard.clackatoo.models.MissingModels
import helium314.keyboard.clackatoo.models.ModelDownloads
import helium314.keyboard.clackatoo.models.ModelState
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    /** Makes the recorder; replaced in tests. [onChunk] gets every block of samples as it is recorded (live text), or is null. */
    internal var recorderFactory: RecorderFactory = { onLevel, onFull, onChunk -> AudioRecorder(onLevel, onFull, onChunk) }
    /** The live dictation (Parakeet) that goes with the recording, or null (Whisper transcribes after Stop). */
    private var liveCall: LiveCall? = null
    private var job: Job? = null
    /** Increases whenever the editor, keyboard visibility or language changes; results of older work are dropped. */
    private var session = 0

    private enum class Mode { NONE, STARTING, LISTENING, WORKING, PREVIEW, MESSAGE, SHEET }
    private var mode = Mode.NONE
    /** The clock of the start timeout and of the timing log; tests replace nothing but may read it. */
    internal var elapsedRealtime: () -> Long = android.os.SystemClock::elapsedRealtime

    /**
     * A recording that has been asked for and is waiting for its panel to be on screen: the microphone is closed, nothing is
     * recorded, no live call exists. [token] tells a late callback of an earlier start from the current one.
     */
    private class PendingStart(
        val token: Int,
        val observer: android.view.ViewTreeObserver,
        val onPreDraw: android.view.ViewTreeObserver.OnPreDrawListener,
        val timeout: Runnable,
    )
    private var starting: PendingStart? = null
    private var startToken = 0
    /** While recording: a pre-draw listener that ends the recording on the first frame in which the panel is no longer on screen. */
    private var levelWatch: Pair<android.view.ViewTreeObserver, android.view.ViewTreeObserver.OnPreDrawListener>? = null

    private fun watchPanel(view: AssistPanelView) {
        stopWatchingPanel()
        val observer = view.viewTreeObserver
        val onPreDraw = android.view.ViewTreeObserver.OnPreDrawListener {
            if (mode == Mode.LISTENING && !view.isOnScreen()) close()
            true
        }
        observer.addOnPreDrawListener(onPreDraw)
        levelWatch = observer to onPreDraw
    }

    private fun stopWatchingPanel() {
        val (observer, listener) = levelWatch ?: return
        levelWatch = null
        if (observer.isAlive) observer.removeOnPreDrawListener(listener)
    }

    /** Everything that waits for or watches the panel ends: a pending start and the frame watch of a recording. */
    private fun endWatches() {
        endPendingStart()
        stopWatchingPanel()
    }
    internal val isListening get() = mode == Mode.LISTENING
    internal val isIdle get() = mode == Mode.NONE
    /** A start is waiting for its panel to be on screen; the microphone is closed. */
    internal val isStarting get() = mode == Mode.STARTING
    private var language: AssistLanguage? = null

    private class PendingCorrection(val target: CheckTarget, val corrected: String)
    private var pending: PendingCorrection? = null
    /** The models offered by the needs-a-model sheet that is showing, and the job that follows their downloads. */
    private var sheetSpecs: List<ModelSpec> = emptyList()
    private var sheetTitleRes = 0
    private var sheetJob: Job? = null

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
        if (mode == Mode.STARTING) { // the key pressed again while the panel is coming up: cancel, the microphone was never opened
            close()
            return
        }
        if (mode != Mode.NONE) close()
        val info = ime.currentInputEditorInfo
        if (info == null || InputTypeUtils.isAnyPasswordInputType(info.inputType)) return
        val language = currentLanguage() ?: return message(R.string.assist_voice_title, unsupportedLanguageText())
        this.language = language
        val speech = speechModel() ?: return missingModel(ModelKind.SPEECH, language, R.string.assist_voice_title, R.string.assist_speech_model_missing)
        if (!AudioRecorderAvailability.hasPermission(ime)) {
            VoicePermissionActivity.start(ime)
            return
        }
        // never record without the recording panel on screen (it is hidden, for example, in toolbar-only mode)
        val view = panel
        if (view == null) return panelHidden()
        if (speech.engine == SpeechEngine.PARAKEET) return startLive(view, speech, language, recorderFactory)
        startListening(view) { started ->
            when (started) {
                ListeningStart.PANEL_HIDDEN -> panelHidden()
                ListeningStart.MIC_UNAVAILABLE -> message(R.string.assist_voice_title, ime.getString(R.string.assist_mic_unavailable))
                ListeningStart.STARTED -> Log.d(TAG, "recording started; speech model ${speech.fileName}")
            }
        }
    }

    /**
     * The speech model to use: the one chosen in Settings if it is installed (and meant for the current language), else
     * the first installed one of the catalog (Parakeet before Whisper), whose engine runs on this phone. [only] limits
     * the engine. A file that failed to load is marked broken, so the next try finds another model.
     */
    internal fun speechModel(only: SpeechEngine? = null): ModelSpec? {
        val code = language?.code ?: currentLanguage()?.code
        return store.best(ModelKind.SPEECH, ModelChoice.selectedId(ime, ModelKind.SPEECH), code) {
            (only == null || it.engine == only) && ModelChoice.runsHere(it)
        }
    }

    /**
     * Live dictation: installs the bundled voice-activity model if needed (off the main thread), then records with the
     * audio going to the model process while it is spoken. Text shows in the panel only; it reaches the field after Stop.
     */
    private fun startLive(view: AssistPanelView, speech: ModelSpec, lang: AssistLanguage, factory: RecorderFactory) {
        val mySession = session
        job?.cancel()
        job = scope.launch {
            val installed = withContext(Dispatchers.IO) { store.ensureBundled(ModelCatalog.SILERO_VAD) }
            if (mySession != session || mode != Mode.NONE || view !== panel) return@launch
            if (!installed) return@launch message(R.string.assist_voice_title, ime.getString(R.string.assist_vad_unavailable))
            // the live call is created only once the microphone opens, which is once the panel is on screen
            startListening(view, factory = factory, newLiveCall = {
                client.live(store.file(speech), store.file(ModelCatalog.SILERO_VAD), lang.code) { committed, partial ->
                    if (mode == Mode.LISTENING) panel?.showLiveText(committed, partial)
                }
            }) { started ->
                when (started) {
                    ListeningStart.PANEL_HIDDEN -> panelHidden()
                    ListeningStart.MIC_UNAVAILABLE -> message(R.string.assist_voice_title, ime.getString(R.string.assist_mic_unavailable))
                    ListeningStart.STARTED -> {
                        Log.d(TAG, "live recording started; speech model ${speech.fileName}")
                        liveCall?.let { watchLive(it, speech, mySession) }
                    }
                }
            }
        }
    }

    /**
     * A live call can only end by itself before Stop if something went wrong (the model could not be loaded, the model
     * process died): say so now instead of recording on until the user taps Stop.
     */
    private fun watchLive(call: LiveCall, speech: ModelSpec, mySession: Int) {
        scope.launch {
            try {
                call.await()
            } catch (e: MlException) {
                if (e.cancelled || mySession != session || mode != Mode.LISTENING || liveCall !== call) return@launch
                if (e.modelFailedWhileLoading) store.markBroken(speech)
                message(R.string.assist_voice_title, failureText(e), settings = e.modelFailedWhileLoading)
            }
        }
    }

    internal enum class ListeningStart { STARTED, PANEL_HIDDEN, MIC_UNAVAILABLE }

    /**
     * Asks for a recording and shows the recording state on [view]; [onResult] gets the outcome, on the main thread, either
     * at once or later. The microphone opens only once the panel is on screen (laid out, visible, with area up the whole
     * chain, in a shown window): until then the start is pending (mode STARTING) with the microphone closed, nothing recorded
     * and no live call. The panel's pre-draws after it was shown (its layout has run by then: `!isLayoutRequested`) are when this is checked,
     * with a timeout of [START_TIMEOUT_MS]; a start that cannot show the panel in time is cancelled with the microphone still
     * closed and reported as [ListeningStart.PANEL_HIDDEN]. Every way out of STARTING ([close]) removes the listener and the
     * timeout, and a late callback of a cancelled start does nothing (token). Once recording, the recording lasts only while the
     * panel is on screen: a pre-draw watch ([watchPanel]) ends it on the first frame in which the panel is not on screen,
     * [onPanelUnavailable] ends it at once when the panel is hidden or detached, and the level callback (every 100 ms) is a
     * backstop that stops it and drops the audio otherwise.
     */
    internal fun startListening(
        view: AssistPanelView,
        newLiveCall: (() -> LiveCall)? = null,
        factory: RecorderFactory = recorderFactory,
        onResult: (ListeningStart) -> Unit = {},
    ) {
        if (!view.canBeSeen()) return onResult(ListeningStart.PANEL_HIDDEN)
        endPendingStart()
        val token = ++startToken
        mode = Mode.STARTING
        view.showListening()
        if (!view.isShown) { // the panel could not be shown after all
            close()
            return onResult(ListeningStart.PANEL_HIDDEN)
        }
        val shownAt = elapsedRealtime()
        fun openIfOnScreen(): Boolean {
            // isLayoutRequested: a panel that was shown before keeps its old size while it is GONE, so "has area" alone says nothing
            // about a panel that has just been made visible again: its layout must have run since (making it visible requests one)
            if (token != startToken || mode != Mode.STARTING || view.isLayoutRequested || !view.isLaidOut || !view.isOnScreen()) return false
            endPendingStart()
            openMicrophone(view, newLiveCall, factory, shownAt, onResult)
            return true
        }
        if (openIfOnScreen()) return
        val observer = view.viewTreeObserver
        val onPreDraw = android.view.ViewTreeObserver.OnPreDrawListener { openIfOnScreen(); true }
        val timeout = Runnable {
            if (token == startToken && mode == Mode.STARTING) {
                Log.d(TAG, "the panel was not on screen after ${elapsedRealtime() - shownAt} ms: start cancelled, microphone never opened")
                close()
                onResult(ListeningStart.PANEL_HIDDEN)
            }
        }
        starting = PendingStart(token, observer, onPreDraw, timeout)
        observer.addOnPreDrawListener(onPreDraw)
        main.postDelayed(timeout, START_TIMEOUT_MS)
        view.invalidate() // make sure a draw, and so a pre-draw, follows
    }

    /** Ends a pending start: no listener, no timeout. Safe to call at any time. */
    private fun endPendingStart() {
        val pendingStart = starting ?: return
        starting = null
        if (pendingStart.observer.isAlive) pendingStart.observer.removeOnPreDrawListener(pendingStart.onPreDraw)
        main.removeCallbacks(pendingStart.timeout)
    }

    /** The panel is on screen: open the microphone, then (live) the call, and start recording. */
    private fun openMicrophone(
        view: AssistPanelView, newLiveCall: (() -> LiveCall)?, factory: RecorderFactory, shownAt: Long, onResult: (ListeningStart) -> Unit,
    ) {
        val call = newLiveCall?.invoke()
        var recorderOfThisStart: VoiceRecorder? = null // set below: callbacks of an older recorder do nothing to a newer recording
        val rec = factory(
            { level ->
                main.post {
                    if (mode == Mode.LISTENING && recorder === recorderOfThisStart) {
                        if (panel?.isOnScreen() == true) panel?.setLevel(level) else close()
                    }
                }
            },
            { main.post { if (mode == Mode.LISTENING && recorder === recorderOfThisStart) onStop() } },
            call?.let { c -> { block, count -> c.write(block, count) } },
        )
        if (!rec.start()) {
            call?.cancel()
            close()
            return onResult(ListeningStart.MIC_UNAVAILABLE)
        }
        recorder = rec
        recorderOfThisStart = rec
        liveCall = call
        mode = Mode.LISTENING
        watchPanel(view)
        Log.d(TAG, "microphone opened ${elapsedRealtime() - shownAt} ms after the panel was shown, layout pending: ${view.isLayoutRequested}")
        if (!view.isShown) { // the panel went away while the microphone was opening
            close()
            return onResult(ListeningStart.PANEL_HIDDEN)
        }
        onResult(ListeningStart.STARTED)
    }

    /** The panel is hidden, detached or being replaced: nothing may keep running behind it. */
    override fun onPanelUnavailable() {
        // a start that is waiting for its panel is cancelled by any real loss (hidden, detached), not by "not on screen yet"
        if (mode == Mode.STARTING || (mode != Mode.NONE && panel?.isOnScreen() != true)) close()
    }

    private fun panelHidden() {
        KeyboardSwitcher.getInstance().showToast(ime.getString(R.string.assist_panel_hidden), false)
    }

    override fun onStop() {
        if (mode == Mode.STARTING) return close() // Stop while the panel is still coming up: nothing was recorded
        val rec = recorder ?: return
        stopWatchingPanel()
        val pcm = rec.stop()
        recorder = null
        val language = language ?: return close()
        if (AudioRecorder.isSilent(pcm)) return message(R.string.assist_voice_title, ime.getString(R.string.assist_no_speech))
        val call = liveCall
        if (call != null) return finishLive(call, pcm.size, language)
        transcribeAndInsert(pcm, language)
    }

    /** Stop was pressed during live dictation: the model process decodes what is left, then the text goes into the field. */
    private fun finishLive(call: LiveCall, samples: Int, language: AssistLanguage) {
        liveCall = null
        // the same rule as for the Voice key
        val info = ime.currentInputEditorInfo
        if (info == null || InputTypeUtils.isAnyPasswordInputType(info.inputType)) {
            call.cancel()
            return close()
        }
        val speech = speechModel()
        val mySession = session
        mode = Mode.WORKING
        panel?.showWorking(ime.getString(R.string.assist_transcribing))
        call.finish()
        launchVoiceJob(mySession) {
            val raw = try {
                call.await()
            } catch (e: MlException) {
                if (e.modelFailedWhileLoading && speech != null) store.markBroken(speech)
                throw e
            } catch (e: CancellationException) {
                call.cancel() // the panel was closed or the field changed: the model process stops decoding
                throw e
            }
            // no transcripts in any log, in any build
            Log.i(TAG, "live text ready ${raw.runMs} ms after the audio ended (load ${raw.loadMs} ms)")
            if (mySession != session) return@launchVoiceJob
            finishTranscript(raw.text, language, samples)
        }
    }

    /**
     * The path both a real recording and the debug WAV entry point take after the audio is in memory: speech to
     * text, English clean-up with S1-mini, then insert into the field.
     */
    fun transcribeAndInsert(pcm: ShortArray, language: AssistLanguage) {
        val speech = speechModel(SpeechEngine.WHISPER)
            ?: return message(R.string.assist_voice_title, missingModelText(ModelKind.SPEECH, R.string.assist_speech_model_missing), settings = true)
        // the same rule as for the Voice key, whichever way the audio got here (the debug WAV entry point too)
        val info = ime.currentInputEditorInfo
        if (info == null || InputTypeUtils.isAnyPasswordInputType(info.inputType)) return close()
        val mySession = session
        endWatches()
        mode = Mode.WORKING
        panel?.showWorking(ime.getString(R.string.assist_transcribing))
        launchVoiceJob(mySession) {
            val raw = try {
                client.transcribe(store.file(speech), language.whisperCode, pcm)
            } catch (e: MlException) {
                if (e.modelFailedWhileLoading) store.markBroken(speech)
                throw e
            }
            // no transcripts in any log, in any build
            Log.i(TAG, "transcribed in ${raw.runMs} ms (load ${raw.loadMs} ms)")
            finishTranscript(raw.text, language, pcm.size)
        }
    }

    /** Debug builds' WAV entry point: the chosen speech model decides between the live path and the Whisper path. */
    fun debugTranscribe(pcm: ShortArray, language: AssistLanguage) {
        this.language = language // the model is chosen for this language
        if (mode != Mode.NONE) close() // a recording or a pending start never survives a debug transcription
        if (speechModel()?.engine == SpeechEngine.PARAKEET) debugLive(pcm, language) else transcribeAndInsert(pcm, language)
    }

    /**
     * Debug builds' stand-in for the microphone with Parakeet: runs the live path with [pcm] fed in blocks of 1,600
     * samples every 100 ms, and presses Stop when it is all sent.
     */
    fun debugLive(pcm: ShortArray, language: AssistLanguage) {
        this.language = language
        val speech = speechModel()?.takeIf { it.engine == SpeechEngine.PARAKEET }
            ?: return message(R.string.assist_voice_title, missingModelText(ModelKind.SPEECH, R.string.assist_speech_model_missing), settings = true)
        val info = ime.currentInputEditorInfo
        if (info == null || InputTypeUtils.isAnyPasswordInputType(info.inputType)) return close()
        if (mode != Mode.NONE) close()
        this.language = language
        val view = panel ?: return panelHidden()
        startLive(view, speech, language) { onLevel, onFull, onChunk -> ScriptedRecorder(pcm, onLevel, onFull, onChunk) }
    }

    /** Runs [body] as the voice job of session [mySession] and reports failures in the panel. */
    private fun launchVoiceJob(mySession: Int, body: suspend () -> Unit) {
        job = scope.launch {
            try {
                body()
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

    /**
     * What both speech engines share once there is a transcript: the field rules, English clean-up with S1-mini, then
     * insertion. [samples] is the length of the recording (it limits how much text a clip can plausibly hold).
     */
    private suspend fun finishTranscript(text: String, language: AssistLanguage, samples: Int) {
        val mySession = session
        val info = ime.currentInputEditorInfo
        if (info == null || InputTypeUtils.isAnyPasswordInputType(info.inputType)) return close()
        val multiLine = FieldKind.acceptsLineBreaks(info.inputType)
        val maxChars = OutputSanitizer.maxCharsForClip(samples)
        var text = OutputSanitizer.clean(text, multiLine, maxChars)
        val cleanUp = store.best(ModelKind.CLEAN_UP, ModelChoice.selectedId(ime, ModelKind.CLEAN_UP), language.code, ModelChoice::runsHere)
        if (text.isNotBlank() && language.cleanUpWithS1Mini && cleanUp != null && AssistSettings.s1MiniEnabled(ime.prefs())) {
            panel?.showWorking(ime.getString(R.string.assist_cleaning))
            try {
                val styling = AssistSettings.styling(ime.prefs())
                val cleaned = if (cleanUp.ownTemplate) { // an experimental custom model: its own chat template
                    client.chat(store.file(cleanUp), cleanUp.contextTokens, S1MiniPrompt.turns(text, styling), MAX_NEW_TOKENS_CLEAN_UP, MlProtocol.PURPOSE_CLEAN_UP)
                } else {
                    client.generate(store.file(cleanUp), cleanUp.contextTokens, S1MiniPrompt.build(text, styling), MAX_NEW_TOKENS_CLEAN_UP, MlProtocol.PURPOSE_CLEAN_UP)
                }
                Log.i(TAG, "cleaned in ${cleaned.runMs} ms (load ${cleaned.loadMs} ms)")
                text = OutputSanitizer.clean(S1MiniPrompt.parse(cleaned.text), multiLine, maxChars)
            } catch (e: MlException) {
                if (e.cancelled) throw e
                if (e.modelFailedWhileLoading) store.markBroken(cleanUp)
                Log.w(TAG, "clean-up failed, inserting the plain transcript: ${e.message}")
            }
        }
        if (mySession != session) return
        if (text.isBlank()) return message(R.string.assist_voice_title, ime.getString(R.string.assist_no_speech))
        insert(text)
        close()
    }

    private fun insert(text: String) {
        val logic = ime.inputLogic
        logic.finishInput()
        val before = logic.mConnection.getTextBeforeCursor(2, 0)
        val prepared = Dictation.prepareInsertion(before, text)
        if (prepared.isNotEmpty()) ime.onTextInput(prepared)
    }

    // ------------------------------------------------------------------ needs-a-model sheet

    /**
     * The feature has no model. A model that is installed but could not be loaded keeps the old "import it again" message;
     * otherwise the panel offers to download the recommended models (the sheet), or to get the Clackatoo Models app.
     */
    private fun missingModel(kind: ModelKind, language: AssistLanguage, titleRes: Int, missingRes: Int) {
        val offered = when (val plan = MissingModels.plan(kind, language.cleanUpWithS1Mini, store::status, ModelChoice::runsHere, ::suggestedModel)) {
            is MissingModels.Plan.Offer -> plan.specs
            MissingModels.Plan.Broken, MissingModels.Plan.Nothing -> return message(titleRes, missingModelText(kind, missingRes), settings = true)
            MissingModels.Plan.Unsupported -> return message(titleRes, ime.getString(R.string.models_unsupported_phone))
        }
        endWatches()
        job = null
        recorder?.cancel()
        recorder = null
        dropLive()
        pending = null
        sheetJob?.cancel()
        mode = Mode.SHEET
        sheetSpecs = offered
        sheetTitleRes = if (kind == ModelKind.SPEECH) R.string.models_sheet_voice_title else R.string.models_sheet_grammar_title
        val text = if (kind == ModelKind.SPEECH) R.string.models_sheet_voice_text else R.string.models_sheet_grammar_text
        panel?.showModelSheet(ime.getString(sheetTitleRes), ime.getString(text), sheetItems(offered), ModelDownloads.isCompanionInstalled(ime))
    }

    /** The catalog id suggested for [kind] on this phone (see [ModelSuggestions]). */
    private fun suggestedModel(kind: ModelKind): String? =
        ModelSuggestions.suggestedId(DeviceProfile.read(ime), kind, ModelSuggestions.keyboardLanguages())

    private fun sheetItems(specs: List<ModelSpec>) = specs.map { AssistPanelView.SheetItem(it.id, sheetLabel(it), it.sizeBytes) }

    private fun sheetLabel(spec: ModelSpec): String = ime.getString(
        when (spec.id) {
            Catalog.PARAKEET_TDT_V3.id, Catalog.PARAKEET_TDT_CTC_110M.id -> R.string.models_label_parakeet
            Catalog.WHISPER_TINY.id, Catalog.WHISPER_BASE.id, Catalog.WHISPER_SMALL.id -> R.string.models_label_whisper
            Catalog.S1_MINI.id -> R.string.models_label_clean_up
            Catalog.QWEN3_5_0_8B.id -> R.string.models_label_grammar
            else -> R.string.models_label_other
        }
    )

    override fun onSheetDownload(ids: List<String>) {
        if (mode != Mode.SHEET) return
        val chosen = sheetSpecs.filter { it.id in ids }
        if (chosen.isEmpty()) return
        // without the companion app this opens its download page in the browser; nothing is downloaded by the keyboard
        if (!ModelDownloads.isCompanionInstalled(ime)) {
            ModelDownloads.download(ime, chosen.first().id)
            return close()
        }
        sheetSpecs = chosen
        chosen.forEach { ModelDownloads.download(ime, it.id) }
        sheetJob?.cancel()
        sheetJob = scope.launch {
            combine(chosen.map { ModelDownloads.state(it.id) }) { it.toList() }.collect { states ->
                if (mode != Mode.SHEET) return@collect
                val statuses = chosen.zip(states).associate { (spec, state) -> spec.id to stateText(state) }
                val progress = when {
                    states.any { it is ModelState.Failed } -> AssistPanelView.SheetProgress.FAILED
                    states.all { it is ModelState.Installed } -> AssistPanelView.SheetProgress.DONE
                    states.any { it is ModelState.NotInstalled } -> AssistPanelView.SheetProgress.FAILED // cancelled elsewhere
                    else -> AssistPanelView.SheetProgress.RUNNING
                }
                panel?.showSheetProgress(ime.getString(sheetTitleRes), sheetItems(chosen), statuses, progress)
            }
        }
    }

    override fun onSheetCancel() {
        if (mode != Mode.SHEET) return
        sheetSpecs.forEach { ModelDownloads.cancel(ime, it.id) }
        close()
    }

    private fun stateText(state: ModelState): String = when (state) {
        ModelState.NotInstalled -> ime.getString(R.string.models_failed_other)
        is ModelState.Downloading -> ime.getString(
            R.string.models_downloading, Formatter.formatShortFileSize(ime, state.bytes), Formatter.formatShortFileSize(ime, state.total),
        )
        ModelState.Installing -> ime.getString(R.string.models_installing)
        ModelState.Installed -> ime.getString(R.string.models_ready)
        is ModelState.Failed -> state.message
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
        val grammar = store.best(ModelKind.GRAMMAR, ModelChoice.selectedId(ime, ModelKind.GRAMMAR), language.code, ModelChoice::runsHere)
            ?: return missingModel(ModelKind.GRAMMAR, language, R.string.assist_check_title, R.string.assist_grammar_model_missing)
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
                    val maxNew = (chunk.toByteArray().size + 48).coerceAtMost(MlProtocol.MAX_NEW_TOKENS)
                    val result = try {
                        if (grammar.ownTemplate) client.chat(store.file(grammar), grammar.contextTokens, GrammarPrompt.turns(chunk), maxNew, MlProtocol.PURPOSE_GRAMMAR)
                        else client.generate(store.file(grammar), grammar.contextTokens, GrammarPrompt.build(format, chunk), maxNew, MlProtocol.PURPOSE_GRAMMAR)
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
        endWatches()
        job?.cancel()
        job = null
        sheetJob?.cancel()
        sheetJob = null
        recorder?.cancel() // drops the audio
        recorder = null
        dropLive()
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

    private fun dropLive() {
        liveCall?.cancel()
        liveCall = null
    }

    private fun message(titleRes: Int, text: CharSequence, settings: Boolean = false) {
        endWatches()
        job = null
        recorder?.cancel()
        recorder = null
        dropLive()
        pending = null
        sheetJob?.cancel()
        sheetJob = null
        mode = Mode.MESSAGE
        panel?.showMessage(ime.getString(titleRes), text, settings)
    }

    private fun unsupportedLanguageText(): String {
        val locale = RichInputMethodManager.getInstance().currentSubtypeLocale
        return ime.getString(R.string.assist_unsupported_language, locale.getDisplayLanguage(locale))
    }

    private fun missingModelText(kind: ModelKind, missingRes: Int): String {
        val broken = (ModelCatalog.forKind(kind) + store.customSpecs(kind)).any { store.status(it) is ModelStatus.Broken }
        return ime.getString(if (broken) R.string.assist_model_broken else missingRes)
    }

    private fun failureText(e: MlException): String =
        if (e.modelFailedWhileLoading) ime.getString(R.string.assist_model_broken)
        else ime.getString(R.string.assist_failed, e.message ?: "unknown")

    companion object {
        /** How long a start waits for its panel to be on screen before it is cancelled (the slowest first sighting measured was about 100 ms). */
        internal const val START_TIMEOUT_MS = 1_000L
        private const val TAG = "ClackatooAssist"
        private const val CHUNK_CHARS = 600
        private const val MAX_NEW_TOKENS_CLEAN_UP = 600
    }
}

/** Makes the recorder: level and "buffer full" callbacks, and (live text) a callback for every block of samples. */
typealias RecorderFactory = (onLevel: (Float) -> Unit, onFull: () -> Unit, onChunk: ((ShortArray, Int) -> Unit)?) -> VoiceRecorder

object AudioRecorderAvailability {
    fun hasPermission(context: Context) =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
}
