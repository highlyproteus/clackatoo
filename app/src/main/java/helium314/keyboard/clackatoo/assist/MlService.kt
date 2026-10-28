// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo.assist

import android.app.Application
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.os.BundleCompat
import helium314.keyboard.clackatoo.asr.NativeAsr
import helium314.keyboard.clackatoo.ml.NativeMl
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Messages between the keyboard and [MlService].
 *
 * The service is an isolated process and the keyboard does not trust it: everything it sends back is a number in
 * [Message.what], [Message.arg1] or [Message.arg2]. The Bundle of a reply is never read, and the text of a result
 * comes through a pipe that the keyboard reads with a size limit.
 */
object MlProtocol {
    // keyboard -> service
    const val MSG_TRANSCRIBE = 1
    const val MSG_GENERATE = 2
    const val MSG_CANCEL = 3
    /** Live dictation: audio in through a pipe while the user speaks, text events out through another. */
    const val MSG_LIVE = 4
    /** Loads a model file once and frees it again, to find out whether it can be used (experimental custom models). Replies like a request with an empty result. */
    const val MSG_PROBE = 5
    // service -> keyboard; arg1 is always the request id
    const val MSG_LOADED = 10
    /** arg2: the load and run times, see [packTimes]. The text arrives through the result pipe. */
    const val MSG_RESULT = 11
    /** arg2: one of the ERR_ codes. */
    const val MSG_ERROR = 12

    const val ERR_LOAD_FAILED = 1
    const val ERR_FAILED = 2
    const val ERR_CANCELLED = 3
    const val ERR_BAD_REQUEST = 4
    const val ERR_UNAVAILABLE = 5

    // request keys
    const val KEY_MODEL_FD = "modelFd"
    /** Identifies the model file's version, so the service can keep a loaded model. Not a path. */
    const val KEY_MODEL_KEY = "modelKey"
    const val KEY_RESULT_PIPE = "resultPipe"
    const val KEY_LANGUAGE = "language"
    const val KEY_SAMPLES = "samples"
    const val KEY_PCM = "pcm"
    const val KEY_CONTEXT = "context"
    const val KEY_PROMPT_TEXTS = "promptTexts"
    const val KEY_PROMPT_SPECIAL = "promptSpecial"
    const val KEY_MAX_NEW = "maxNew"
    /**
     * Text generation only, optional: the role of each entry of [KEY_PROMPT_TEXTS] ("system", "user" or "assistant"). With it
     * the texts are the contents of a chat, all plain text ([KEY_PROMPT_SPECIAL] is not sent), laid out by the service with
     * the chat template stored in the model file; without it the texts are the already laid-out prompt parts.
     */
    const val KEY_CHAT_ROLES = "chatRoles"
    /** [MSG_PROBE]: which engine loads the file, one of the PROBE_ values; [KEY_CONTEXT] applies to text models. */
    const val KEY_PROBE_ENGINE = "probeEngine"
    const val PROBE_WHISPER = 0
    const val PROBE_PARAKEET = 1
    const val PROBE_LLM = 2
    const val MAX_CHAT_TURNS = 32
    /** [MSG_GENERATE] only: what the text is for, which decides what stays in memory (see [ModelResidency]). */
    const val KEY_PURPOSE = "purpose"
    const val PURPOSE_CLEAN_UP = 1
    const val PURPOSE_GRAMMAR = 2
    val CHAT_ROLES = setOf("system", "user", "assistant")
    /** Live dictation: the voice-activity model (cache key as for the speech model), the audio pipe (read side) and the event pipe (write side). */
    const val KEY_VAD_FD = "vadFd"
    const val KEY_VAD_KEY = "vadKey"
    const val KEY_AUDIO_PIPE = "audioPipe"
    const val KEY_EVENT_PIPE = "eventPipe"

    /**
     * Live dictation events, written to the event pipe as frames: one type byte, a 4-byte big-endian length, then that
     * many bytes of UTF-8 text ([EVENT_END] has none). A partial is the text of the utterance that is still being
     * spoken and replaces the previous partial; a segment is the final text of an utterance. Segments together stay
     * within [MAX_RESULT_BYTES]. The audio goes the other way as 16-bit little-endian samples, at most [MAX_SAMPLES].
     */
    const val EVENT_PARTIAL = 1
    const val EVENT_SEGMENT = 2
    const val EVENT_END = 3
    /** The largest payload of one event frame. */
    const val MAX_EVENT_BYTES = 4096
    /** The most event frames of one live call (about 4 per second of the longest recording); the keyboard cancels the call above it. */
    const val MAX_EVENTS = 4096

    const val SAMPLE_RATE = 16000
    /** Upper bound for one recording, in samples. */
    const val MAX_SAMPLES = SAMPLE_RATE * 120
    /** Upper bounds for a text request; the keyboard stays inside them, the service refuses anything above. */
    const val MIN_CONTEXT = 256
    const val MAX_CONTEXT = 8192
    const val MAX_NEW_TOKENS = 2048
    const val MAX_PROMPT_PARTS = 64
    const val MAX_PROMPT_BYTES = 256 * 1024
    /** A result is at most this many bytes of UTF-8 (below the capacity of a pipe, so the service never blocks writing it). */
    const val MAX_RESULT_BYTES = 32 * 1024

    private const val TIME_LIMIT = 0xFFFF

    /** Load and run time in milliseconds (each capped at 65 s) in one int. */
    fun packTimes(loadMs: Long, runMs: Long): Int =
        (loadMs.coerceIn(0, TIME_LIMIT.toLong()).toInt() shl 16) or runMs.coerceIn(0, TIME_LIMIT.toLong()).toInt()

    fun loadMsOf(packed: Int): Long = ((packed ushr 16) and TIME_LIMIT).toLong()
    fun runMsOf(packed: Int): Long = (packed and TIME_LIMIT).toLong()

    /** Whisper codes the service accepts: two or three letters, or "auto". */
    fun isLanguageCode(code: String) = code == "auto" || (code.length in 2..3 && code.all { it in 'a'..'z' })
}

/** True in the separate, isolated process that runs the models. */
fun Application.isModelProcess(): Boolean {
    if (Build.VERSION.SDK_INT >= 28 && Process.isIsolated()) return true
    val name = if (Build.VERSION.SDK_INT >= 28) Application.getProcessName()
    else runCatching { File("/proc/self/cmdline").readText().trimEnd('\u0000') }.getOrNull()
    return name?.endsWith(":ml") == true
}

/**
 * Runs whisper.cpp and llama.cpp in an isolated process (android:isolatedProcess="true"): its own throw-away UID with
 * no permissions and no access to the keyboard's files, settings, dictionaries or clipboard history. A malformed
 * model file that exploits a bug in the native parsers therefore gets nothing, and a crash or an out-of-memory kill
 * takes down only this process: the keyboard sees the connection die and reports an error. Because the process
 * cannot open the app's files, every model arrives as an open read-only file descriptor, the audio arrives through a
 * pipe, and a loaded model is remembered under the cache key that comes with it. The process exits when the keyboard
 * unbinds, which frees all model memory.
 */
class MlService : Service() {
    private val main = Handler(Looper.getMainLooper()) { message -> dispatch(message); true }
    private val messenger = Messenger(main)
    private val worker = Executors.newSingleThreadExecutor()

    // cancelling (main thread) and running or freeing (worker thread) meet in this registry
    @Volatile private var liveSession: Pair<Int, LiveSession>? = null
    private val calls = CallRegistry { speech, handle, value ->
        if (speech) NativeMl.sttCancel(handle, value) else NativeMl.llmCancel(handle, value)
    }

    // only touched on the worker thread
    private var nativeReady = false
    private var sttKey: String? = null
    private var sttHandle = 0L
    private var asrKey: String? = null
    private var asrHandle = 0L
    private var vadKey: String? = null
    private var vadHandle = 0L
    private var cleanUpKey: String? = null
    private var cleanUpHandle = 0L
    private var grammarKey: String? = null
    private var grammarHandle = 0L
    private val residency = ModelResidency()

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        worker.execute { freeAll() }
        worker.shutdown()
        super.onDestroy()
    }

    /** One decoded request. The descriptors are closed by [run]. */
    private class Request(
        val id: Int,
        val speech: Boolean,
        val probe: Boolean,
        val replyTo: Messenger,
        val modelFd: ParcelFileDescriptor,
        val modelKey: String,
        val resultFd: ParcelFileDescriptor,
    ) {
        var pcmFd: ParcelFileDescriptor? = null
        var language = "auto"
        var samples = 0
        var context = 0
        var texts: List<String> = emptyList()
        var special: List<Boolean> = emptyList()
        var roles: List<String> = emptyList()
        var probeEngine = -1
        var purpose = 0
        var maxNew = 0
        var loadMs = 0L
        var runMs = 0L

        fun closeAll() {
            for (fd in listOfNotNull(modelFd, resultFd, pcmFd)) runCatching { fd.close() }
        }
    }

    private fun dispatch(message: Message) {
        val id = message.arg1
        when (message.what) {
            MlProtocol.MSG_CANCEL -> {
                calls.cancel(id)
                liveSession?.let { (liveId, session) -> if (liveId == id) session.cancel() }
            }
            MlProtocol.MSG_LIVE -> {
                val replyTo = message.replyTo ?: return
                val request = decodeLive(message, replyTo)
                if (request == null) reply(replyTo, MlProtocol.MSG_ERROR, id, MlProtocol.ERR_BAD_REQUEST)
                else worker.execute { runLive(request) }
            }
            MlProtocol.MSG_TRANSCRIBE, MlProtocol.MSG_GENERATE, MlProtocol.MSG_PROBE -> {
                val replyTo = message.replyTo ?: return
                val request = decode(message, replyTo)
                if (request == null) reply(replyTo, MlProtocol.MSG_ERROR, id, MlProtocol.ERR_BAD_REQUEST)
                else worker.execute { run(request) }
            }
        }
    }

    /** Reads and checks a request; null (with every descriptor closed) when it is malformed or out of bounds. */
    private fun decode(message: Message, replyTo: Messenger): Request? {
        val data = message.data ?: return null
        val modelFd = BundleCompat.getParcelable(data, MlProtocol.KEY_MODEL_FD, ParcelFileDescriptor::class.java)
        val resultFd = BundleCompat.getParcelable(data, MlProtocol.KEY_RESULT_PIPE, ParcelFileDescriptor::class.java)
        val pcmFd = BundleCompat.getParcelable(data, MlProtocol.KEY_PCM, ParcelFileDescriptor::class.java)
        val key = data.getString(MlProtocol.KEY_MODEL_KEY)
        val speech = message.what == MlProtocol.MSG_TRANSCRIBE
        val probe = message.what == MlProtocol.MSG_PROBE
        if (modelFd == null || resultFd == null || key == null || key.length > 512) {
            for (fd in listOfNotNull(modelFd, resultFd, pcmFd)) runCatching { fd.close() }
            return null
        }
        val request = Request(message.arg1, speech, probe, replyTo, modelFd, key, resultFd)
        request.pcmFd = pcmFd
        val ok = if (probe) {
            request.probeEngine = data.getInt(MlProtocol.KEY_PROBE_ENGINE, -1)
            request.context = data.getInt(MlProtocol.KEY_CONTEXT, 2048)
            request.probeEngine in MlProtocol.PROBE_WHISPER..MlProtocol.PROBE_LLM &&
                request.context in MlProtocol.MIN_CONTEXT..MlProtocol.MAX_CONTEXT
        } else if (speech) {
            request.language = data.getString(MlProtocol.KEY_LANGUAGE) ?: "auto"
            request.samples = data.getInt(MlProtocol.KEY_SAMPLES)
            pcmFd != null && MlProtocol.isLanguageCode(request.language) && request.samples in 1..MlProtocol.MAX_SAMPLES
        } else {
            request.context = data.getInt(MlProtocol.KEY_CONTEXT, 2048)
            request.maxNew = data.getInt(MlProtocol.KEY_MAX_NEW, 256)
            request.purpose = data.getInt(MlProtocol.KEY_PURPOSE, 0)
            val texts = data.getStringArray(MlProtocol.KEY_PROMPT_TEXTS)
            val special = data.getBooleanArray(MlProtocol.KEY_PROMPT_SPECIAL)
            val roles = data.getStringArray(MlProtocol.KEY_CHAT_ROLES)
            if (roles != null) {
                // a chat: contents and roles, nothing special; the service lays it out with the model's template
                if (texts != null && special == null && roles.size == texts.size && roles.size <= MlProtocol.MAX_CHAT_TURNS &&
                    roles.all { it in MlProtocol.CHAT_ROLES }
                ) {
                    request.texts = texts.toList()
                    request.roles = roles.toList()
                }
            } else if (texts != null && special != null && texts.size == special.size) {
                request.texts = texts.toList()
                request.special = special.toList()
            }
            (request.purpose == MlProtocol.PURPOSE_CLEAN_UP || request.purpose == MlProtocol.PURPOSE_GRAMMAR) &&
                request.texts.size in 1..MlProtocol.MAX_PROMPT_PARTS &&
                request.texts.sumOf { it.length } <= MlProtocol.MAX_PROMPT_BYTES / 4 &&
                request.context in MlProtocol.MIN_CONTEXT..MlProtocol.MAX_CONTEXT &&
                request.maxNew in 1..MlProtocol.MAX_NEW_TOKENS
        }
        if (!ok) {
            request.closeAll()
            return null
        }
        return request
    }

    /** One decoded live request. The descriptors are closed by [runLive]. */
    private class LiveRequest(
        val id: Int,
        val replyTo: Messenger,
        val modelFd: ParcelFileDescriptor,
        val modelKey: String,
        val vadFd: ParcelFileDescriptor,
        val vadKey: String,
        val audioFd: ParcelFileDescriptor,
        val eventFd: ParcelFileDescriptor,
    ) {
        var loadMs = 0L
        var runMs = 0L

        fun closeAll() {
            for (fd in listOf(modelFd, vadFd, audioFd, eventFd)) runCatching { fd.close() }
        }
    }

    /** Reads and checks a live request; null (with every descriptor closed) when it is malformed. */
    private fun decodeLive(message: Message, replyTo: Messenger): LiveRequest? {
        val data = message.data ?: return null
        val modelFd = BundleCompat.getParcelable(data, MlProtocol.KEY_MODEL_FD, ParcelFileDescriptor::class.java)
        val vadFd = BundleCompat.getParcelable(data, MlProtocol.KEY_VAD_FD, ParcelFileDescriptor::class.java)
        val audioFd = BundleCompat.getParcelable(data, MlProtocol.KEY_AUDIO_PIPE, ParcelFileDescriptor::class.java)
        val eventFd = BundleCompat.getParcelable(data, MlProtocol.KEY_EVENT_PIPE, ParcelFileDescriptor::class.java)
        val modelKey = data.getString(MlProtocol.KEY_MODEL_KEY)
        val vadKey = data.getString(MlProtocol.KEY_VAD_KEY)
        val language = data.getString(MlProtocol.KEY_LANGUAGE) ?: "auto"
        if (modelFd == null || vadFd == null || audioFd == null || eventFd == null ||
            modelKey == null || modelKey.length > 512 || vadKey == null || vadKey.length > 512 || !MlProtocol.isLanguageCode(language)
        ) {
            for (fd in listOfNotNull(modelFd, vadFd, audioFd, eventFd)) runCatching { fd.close() }
            return null
        }
        return LiveRequest(message.arg1, replyTo, modelFd, modelKey, vadFd, vadKey, audioFd, eventFd)
    }

    /** The native code refused the file (corrupt, truncated or not a model). */
    private class ModelLoadException(message: String?) : Exception(message)
    private class CancelledException : Exception()

    private inline fun <T> loading(load: () -> T): T = try {
        load()
    } catch (e: IllegalStateException) {
        throw ModelLoadException(e.message)
    }

    private fun run(req: Request) {
        try {
            if (!calls.begin(req.id)) throw CancelledException()
            val text = if (req.probe) probe(req) else if (req.speech) transcribe(req) else generate(req)
            writeResult(req.resultFd, text)
        } catch (_: CancelledException) {
            return reply(req, MlProtocol.ERR_CANCELLED)
        } catch (e: ModelLoadException) {
            Log.w(TAG, "model could not be loaded: ${e.message}")
            return reply(req, MlProtocol.ERR_LOAD_FAILED)
        } catch (e: Throwable) {
            // only fixed texts of the native layer and of this class end up here, never user text
            Log.w(TAG, "request failed: ${e.javaClass.simpleName}: ${e.message}")
            return reply(req, if (e is NativeUnavailable) MlProtocol.ERR_UNAVAILABLE else MlProtocol.ERR_FAILED)
        } finally {
            calls.end(req.id) // from here on no cancel can reach the handle, so it may be freed
            req.closeAll()
        }
        // the result is complete in the pipe now; the numbers tell the keyboard it can read it
        reply(req.replyTo, MlProtocol.MSG_RESULT, req.id, MlProtocol.packTimes(req.loadMs, req.runMs))
    }

    private class NativeUnavailable(message: String) : IllegalStateException(message)

    private fun runLive(req: LiveRequest) {
        try {
            if (!calls.begin(req.id)) throw CancelledException()
            live(req)
        } catch (_: CancelledException) {
            return reply(req.replyTo, MlProtocol.MSG_ERROR, req.id, MlProtocol.ERR_CANCELLED)
        } catch (_: LiveSession.Cancelled) {
            return reply(req.replyTo, MlProtocol.MSG_ERROR, req.id, MlProtocol.ERR_CANCELLED)
        } catch (e: ModelLoadException) {
            Log.w(TAG, "model could not be loaded: ${e.message}")
            return reply(req.replyTo, MlProtocol.MSG_ERROR, req.id, MlProtocol.ERR_LOAD_FAILED)
        } catch (e: Throwable) {
            // only fixed texts of the native layer and of this class end up here, never user text
            Log.w(TAG, "live request failed: ${e.javaClass.simpleName}: ${e.message}")
            val code = if (e is NativeUnavailable || e is LinkageError) MlProtocol.ERR_UNAVAILABLE else MlProtocol.ERR_FAILED
            return reply(req.replyTo, MlProtocol.MSG_ERROR, req.id, code)
        } finally {
            liveSession = null
            calls.end(req.id)
            req.closeAll()
        }
        reply(req.replyTo, MlProtocol.MSG_RESULT, req.id, MlProtocol.packTimes(req.loadMs, req.runMs))
    }

    private fun live(req: LiveRequest) {
        val t0 = SystemClock.elapsedRealtime()
        ensureNative()
        makeRoom(ModelResidency.Slot.ASR, req.modelKey)
        if (asrKey != req.modelKey) {
            // a missing library surfaces here as a LinkageError; 0 means the file or this CPU is not usable
            asrHandle = NativeAsr.load(req.modelFd.detachFd(), threads())
            if (asrHandle == 0L) throw ModelLoadException("the speech model could not be loaded")
            asrKey = req.modelKey
            residency.loaded(ModelResidency.Slot.ASR, req.modelKey)
        }
        makeRoom(ModelResidency.Slot.VAD, req.vadKey)
        if (vadKey != req.vadKey) {
            vadHandle = loading { NativeMl.vadLoad(req.vadFd.detachFd(), 1) }
            vadKey = req.vadKey
            residency.loaded(ModelResidency.Slot.VAD, req.vadKey)
        }
        val asr = asrHandle
        val vad = vadHandle
        req.loadMs = SystemClock.elapsedRealtime() - t0
        reply(req.replyTo, MlProtocol.MSG_LOADED, req.id, 0)

        val session = LiveSession(
            input = ParcelFileDescriptor.AutoCloseInputStream(req.audioFd),
            output = ParcelFileDescriptor.AutoCloseOutputStream(req.eventFd),
            vadFeed = { pcm, offset, count -> NativeMl.vadFeed(vad, pcm, offset, count) },
            vadReset = { NativeMl.vadReset(vad) },
            transcribe = { pcm, offset, count ->
                val t = SystemClock.elapsedRealtime()
                NativeAsr.transcribe(asr, pcm, offset, count).also {
                    // numbers only; never the text
                    Log.d(TAG, "live: recognised $count samples in ${SystemClock.elapsedRealtime() - t} ms")
                }
            },
        )
        liveSession = req.id to session
        // a cancel that came before the session was published must not be lost
        if (calls.isCancelled(req.id)) session.cancel()
        val timing = try {
            session.run()
        } finally {
            if (session.readerStillRunning) {
                // a reader that is still alive may be inside the detector: never free or reuse it, leak it on purpose
                vadHandle = 0L
                vadKey = null
                residency.freed(ModelResidency.Slot.VAD)
            }
        }
        req.runMs = timing.tailMs
        // numbers only; never the text
        Log.i(TAG, "live: first partial after ${timing.firstPartialMs} ms, ${timing.decodeMs} ms of recognition, ${timing.tailMs} ms after the audio ended")
    }

    private fun ensureNative() {
        if (nativeReady) return
        if (!NativeMl.isAvailable) throw NativeUnavailable("The speech and text engines are not built for this phone")
        val devices = NativeMl.init(applicationInfo.nativeLibraryDir)
        // proves what the isolated process could load (no user data here)
        Log.i(TAG, "model process uid ${Process.myUid()}, isolated ${Build.VERSION.SDK_INT >= 28 && Process.isIsolated()}, " +
            "$devices ggml devices; ${NativeMl.systemInfo().trim()}")
        if (devices == 0) throw NativeUnavailable("No CPU backend could be loaded")
        nativeReady = true
    }

    private fun threads() = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)

    /** A cancel that arrived before this point ends the request here; one that arrives later finds the handle attached. */
    private fun register(req: Request, handle: Long) {
        if (!calls.attach(req.id, req.speech, handle)) throw CancelledException()
    }

    private fun transcribe(req: Request): String {
        val t0 = SystemClock.elapsedRealtime()
        ensureNative()
        // nothing is registered for cancelling at this point, so nothing can reach a handle being freed
        makeRoom(ModelResidency.Slot.STT, req.modelKey)
        if (sttKey != req.modelKey) {
            sttHandle = loading { NativeMl.sttLoad(req.modelFd.detachFd(), threads()) }
            sttKey = req.modelKey
            residency.loaded(ModelResidency.Slot.STT, req.modelKey)
        }
        register(req, sttHandle)
        req.loadMs = SystemClock.elapsedRealtime() - t0
        reply(req.replyTo, MlProtocol.MSG_LOADED, req.id, 0)

        // the audio arrives through a pipe, because a Binder message is limited to 1 MB; it only ever lives in memory
        val pcm = ShortArray(req.samples)
        val pcmFd = req.pcmFd ?: throw IOException("no audio")
        try {
            DataInputStream(ParcelFileDescriptor.AutoCloseInputStream(pcmFd).buffered()).use { input ->
                val bytes = ByteArray(req.samples * 2)
                input.readFully(bytes)
                for (i in 0 until req.samples) pcm[i] = ((bytes[2 * i].toInt() and 0xff) or (bytes[2 * i + 1].toInt() shl 8)).toShort()
            }
        } finally {
            req.pcmFd = null
        }
        val t1 = SystemClock.elapsedRealtime()
        val text = NativeMl.sttTranscribe(sttHandle, pcm, req.language) ?: throw CancelledException()
        req.runMs = SystemClock.elapsedRealtime() - t1
        return text
    }

    private fun generate(req: Request): String {
        val t0 = SystemClock.elapsedRealtime()
        ensureNative()
        val slot = if (req.purpose == MlProtocol.PURPOSE_GRAMMAR) ModelResidency.Slot.GRAMMAR else ModelResidency.Slot.CLEAN_UP
        makeRoom(slot, req.modelKey)
        val held = if (slot == ModelResidency.Slot.GRAMMAR) grammarKey else cleanUpKey
        if (held != req.modelKey) {
            val loaded = loading { NativeMl.llmLoad(req.modelFd.detachFd(), req.context, threads()) }
            Log.i(TAG, "text model loaded, ${if (NativeMl.llmLoadMode(loaded) == 1) "mapped from its descriptor" else "read into memory"}")
            if (slot == ModelResidency.Slot.GRAMMAR) { grammarHandle = loaded; grammarKey = req.modelKey } else { cleanUpHandle = loaded; cleanUpKey = req.modelKey }
            residency.loaded(slot, req.modelKey)
        }
        val handle = if (slot == ModelResidency.Slot.GRAMMAR) grammarHandle else cleanUpHandle
        register(req, handle)
        req.loadMs = SystemClock.elapsedRealtime() - t0
        reply(req.replyTo, MlProtocol.MSG_LOADED, req.id, 0)

        val t1 = SystemClock.elapsedRealtime()
        val text = (if (req.roles.isNotEmpty()) NativeMl.llmChat(handle, req.roles, req.texts, req.maxNew)
        else NativeMl.llmGenerate(handle, req.texts, req.special, req.maxNew)) ?: throw CancelledException()
        req.runMs = SystemClock.elapsedRealtime() - t1
        return text
    }

    /**
     * Loads the file with the engine the keyboard names and frees it again: the test load of an experimental custom model.
     * Everything cached is dropped first so that two big models are never in memory at once. Nothing is run and nothing is
     * kept: a later request for the same file loads it again.
     */
    private fun probe(req: Request): String {
        val t0 = SystemClock.elapsedRealtime()
        ensureNative()
        dropCached()
        val fd = req.modelFd.detachFd()
        when (req.probeEngine) {
            MlProtocol.PROBE_WHISPER -> NativeMl.sttFree(loading { NativeMl.sttLoad(fd, threads()) })
            MlProtocol.PROBE_PARAKEET -> {
                val handle = NativeAsr.load(fd, threads())
                if (handle == 0L) throw ModelLoadException("the speech model could not be loaded")
                NativeAsr.free(handle)
            }
            else -> NativeMl.llmFree(loading { NativeMl.llmLoad(fd, req.context, threads()) })
        }
        req.loadMs = SystemClock.elapsedRealtime() - t0
        reply(req.replyTo, MlProtocol.MSG_LOADED, req.id, 0)
        return ""
    }

    /** Frees every loaded model (the next request loads what it needs again). */
    private fun dropCached() = freeAll()

    /**
     * Frees what a request for [slot] with the file [key] must not keep in memory (see [ModelResidency]). Called on the worker
     * thread before the request registers anything for cancelling, so no cancel can reach a handle that is freed here.
     */
    private fun makeRoom(slot: ModelResidency.Slot, key: String) {
        residency.toFree(slot, key).forEach { free(it) }
    }

    private fun free(slot: ModelResidency.Slot) {
        when (slot) {
            ModelResidency.Slot.STT -> { if (sttHandle != 0L) NativeMl.sttFree(sttHandle); sttHandle = 0L; sttKey = null }
            ModelResidency.Slot.ASR -> { if (asrHandle != 0L) NativeAsr.free(asrHandle); asrHandle = 0L; asrKey = null }
            ModelResidency.Slot.VAD -> { if (vadHandle != 0L) NativeMl.vadFree(vadHandle); vadHandle = 0L; vadKey = null }
            ModelResidency.Slot.CLEAN_UP -> { if (cleanUpHandle != 0L) NativeMl.llmFree(cleanUpHandle); cleanUpHandle = 0L; cleanUpKey = null }
            ModelResidency.Slot.GRAMMAR -> { if (grammarHandle != 0L) NativeMl.llmFree(grammarHandle); grammarHandle = 0L; grammarKey = null }
        }
        residency.freed(slot)
    }

    private fun writeResult(fd: ParcelFileDescriptor, text: String) {
        var bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > MlProtocol.MAX_RESULT_BYTES) bytes = bytes.copyOf(MlProtocol.MAX_RESULT_BYTES)
        ParcelFileDescriptor.AutoCloseOutputStream(fd).use { it.write(bytes) }
    }

    private fun reply(req: Request, errorCode: Int) = reply(req.replyTo, MlProtocol.MSG_ERROR, req.id, errorCode)

    private fun reply(to: Messenger, what: Int, id: Int, arg2: Int) {
        try {
            to.send(Message.obtain(null, what, id, arg2))
        } catch (_: Exception) {
            // the keyboard went away; nothing left to tell
        }
    }

    private fun freeAll() {
        ModelResidency.Slot.entries.forEach { free(it) }
        residency.clear()
    }

    private companion object {
        const val TAG = "ClackatooMlService"
    }
}
