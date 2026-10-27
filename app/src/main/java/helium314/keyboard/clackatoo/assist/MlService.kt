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
    private val calls = CallRegistry { speech, handle, value ->
        if (speech) NativeMl.sttCancel(handle, value) else NativeMl.llmCancel(handle, value)
    }

    // only touched on the worker thread
    private var nativeReady = false
    private var sttKey: String? = null
    private var sttHandle = 0L
    private val llmHandles = HashMap<String, Long>()

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
            MlProtocol.MSG_CANCEL -> calls.cancel(id)
            MlProtocol.MSG_TRANSCRIBE, MlProtocol.MSG_GENERATE -> {
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
        if (modelFd == null || resultFd == null || key == null || key.length > 512) {
            for (fd in listOfNotNull(modelFd, resultFd, pcmFd)) runCatching { fd.close() }
            return null
        }
        val request = Request(message.arg1, speech, replyTo, modelFd, key, resultFd)
        request.pcmFd = pcmFd
        val ok = if (speech) {
            request.language = data.getString(MlProtocol.KEY_LANGUAGE) ?: "auto"
            request.samples = data.getInt(MlProtocol.KEY_SAMPLES)
            pcmFd != null && MlProtocol.isLanguageCode(request.language) && request.samples in 1..MlProtocol.MAX_SAMPLES
        } else {
            request.context = data.getInt(MlProtocol.KEY_CONTEXT, 2048)
            request.maxNew = data.getInt(MlProtocol.KEY_MAX_NEW, 256)
            val texts = data.getStringArray(MlProtocol.KEY_PROMPT_TEXTS)
            val special = data.getBooleanArray(MlProtocol.KEY_PROMPT_SPECIAL)
            if (texts != null && special != null && texts.size == special.size) {
                request.texts = texts.toList()
                request.special = special.toList()
            }
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
            val text = if (req.speech) transcribe(req) else generate(req)
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
        if (sttKey != req.modelKey) {
            // nothing is registered for cancelling at this point, so nothing can reach the handle being freed
            if (sttHandle != 0L) NativeMl.sttFree(sttHandle)
            sttHandle = 0L
            sttKey = null
            sttHandle = loading { NativeMl.sttLoad(req.modelFd.detachFd(), threads()) }
            sttKey = req.modelKey
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
        val handle = llmHandles[req.modelKey] ?: loading {
            NativeMl.llmLoad(req.modelFd.detachFd(), req.context, threads())
        }.also {
            llmHandles[req.modelKey] = it
            Log.i(TAG, "text model loaded, ${if (NativeMl.llmLoadMode(it) == 1) "mapped from its descriptor" else "read into memory"}")
        }
        register(req, handle)
        req.loadMs = SystemClock.elapsedRealtime() - t0
        reply(req.replyTo, MlProtocol.MSG_LOADED, req.id, 0)

        val t1 = SystemClock.elapsedRealtime()
        val text = NativeMl.llmGenerate(handle, req.texts, req.special, req.maxNew) ?: throw CancelledException()
        req.runMs = SystemClock.elapsedRealtime() - t1
        return text
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
        if (sttHandle != 0L) NativeMl.sttFree(sttHandle)
        sttHandle = 0L
        llmHandles.values.forEach { NativeMl.llmFree(it) }
        llmHandles.clear()
    }

    private companion object {
        const val TAG = "ClackatooMlService"
    }
}
