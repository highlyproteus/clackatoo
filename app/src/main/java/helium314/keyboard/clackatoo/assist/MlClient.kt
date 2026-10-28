// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo.assist

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Parcel
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class MlResult(val text: String, val loadMs: Long, val runMs: Long)

/**
 * [modelFailedWhileLoading] is true when the model process died or refused before it had loaded the model: the file
 * is probably corrupt, and the caller should stop using it. [cancelled]: the request was cancelled, not failed.
 * The message is chosen by this class, never taken from the model process.
 */
class MlException(message: String, val modelFailedWhileLoading: Boolean = false, val cancelled: Boolean = false) : Exception(message)

/**
 * The keyboard's side of [MlService]. All calls must come from the main thread. The connection is opened when a
 * request needs it and closed after [IDLE_MS] without requests, which makes the model process exit.
 *
 * The model process is isolated and not trusted. A request carries the model as an open read-only file descriptor
 * plus a cache key (never a path), and the audio through a pipe. The service answers with numbers only; the result
 * text is read from a pipe and limited to [MlProtocol.MAX_RESULT_BYTES], and the Bundle of a reply is never read.
 */
class MlClient(context: Context) {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val replies = Messenger(ReplyBinder(main) { what, arg1, arg2 -> handleReply(what, arg1, arg2) })

    /** How a request ends: a coroutine that waits for it ([request]), or a live call ([live]). */
    private interface Completion {
        val isActive: Boolean
        fun resume(result: MlResult)
        fun fail(error: MlException)
    }

    private fun CancellableContinuation<MlResult>.asCompletion() = object : Completion {
        override val isActive get() = this@asCompletion.isActive
        override fun resume(result: MlResult) = this@asCompletion.resume(result)
        override fun fail(error: MlException) = this@asCompletion.resumeWithException(error)
    }

    private class Pending(val completion: Completion) {
        var loaded = false
        /** Set when the service said it is done (the numbers of its reply). */
        var times: Int? = null
        /** Set when the result pipe has been read to its end. */
        var text: String? = null
    }

    private val pending = HashMap<Int, Pending>()
    private var service: Messenger? = null
    private var connecting = false
    /** A request that waits for the connection: [run] sends it; [discard] closes what it holds when it will never be sent. */
    private class Waiting(val run: () -> Unit, val discard: () -> Unit)
    private val waitingForService = ArrayList<Waiting>()
    private val unbindLater = Runnable { if (pending.isEmpty()) unbind() }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = Messenger(binder)
            connecting = false
            val actions = ArrayList(waitingForService)
            waitingForService.clear()
            actions.forEach { it.run() }
        }

        override fun onServiceDisconnected(name: ComponentName) = connectionLost()
        override fun onBindingDied(name: ComponentName) = connectionLost()
        override fun onNullBinding(name: ComponentName) = connectionLost()
    }

    suspend fun transcribe(model: File, language: String, pcm: ShortArray): MlResult {
        require(pcm.isNotEmpty() && pcm.size <= MlProtocol.MAX_SAMPLES)
        val pipe = ParcelFileDescriptor.createPipe()
        val readSide = pipe[0]
        val writeSide = pipe[1]
        try {
            return request(MlProtocol.MSG_TRANSCRIBE, model, extras = {
                putString(MlProtocol.KEY_LANGUAGE, language)
                putInt(MlProtocol.KEY_SAMPLES, pcm.size)
                putParcelable(MlProtocol.KEY_PCM, readSide)
            }, afterSend = {
                readSide.close() // the service holds its own copy now
                Thread({ writePcm(writeSide, pcm) }, "clackatoo-pcm-pipe").start()
            })
        } catch (e: Throwable) {
            runCatching { readSide.close() }
            runCatching { writeSide.close() }
            throw e
        }
    }

    /** [prompt]'s special parts may carry control tokens (the chat template); all others never do. */
    suspend fun generate(model: File, contextTokens: Int, prompt: List<PromptPart>, maxNewTokens: Int): MlResult {
        require(contextTokens in MlProtocol.MIN_CONTEXT..MlProtocol.MAX_CONTEXT && maxNewTokens in 1..MlProtocol.MAX_NEW_TOKENS)
        require(prompt.isNotEmpty() && prompt.size <= MlProtocol.MAX_PROMPT_PARTS)
        return request(MlProtocol.MSG_GENERATE, model, extras = {
            putInt(MlProtocol.KEY_CONTEXT, contextTokens)
            putStringArray(MlProtocol.KEY_PROMPT_TEXTS, Array(prompt.size) { prompt[it].text })
            putBooleanArray(MlProtocol.KEY_PROMPT_SPECIAL, BooleanArray(prompt.size) { prompt[it].special })
            putInt(MlProtocol.KEY_MAX_NEW, maxNewTokens)
        })
    }

    /**
     * Text generation for a model that has its own chat template (an experimental custom model): [turns] are laid out by
     * the model process with the template stored in the model file. The contents are plain text and can never become
     * control tokens.
     */
    suspend fun chat(model: File, contextTokens: Int, turns: List<ChatPrompt.Turn>, maxNewTokens: Int): MlResult {
        require(contextTokens in MlProtocol.MIN_CONTEXT..MlProtocol.MAX_CONTEXT && maxNewTokens in 1..MlProtocol.MAX_NEW_TOKENS)
        require(turns.isNotEmpty() && turns.size <= MlProtocol.MAX_CHAT_TURNS && turns.all { it.role in MlProtocol.CHAT_ROLES })
        return request(MlProtocol.MSG_GENERATE, model, extras = {
            putInt(MlProtocol.KEY_CONTEXT, contextTokens)
            putStringArray(MlProtocol.KEY_CHAT_ROLES, Array(turns.size) { turns[it].role })
            putStringArray(MlProtocol.KEY_PROMPT_TEXTS, Array(turns.size) { turns[it].content })
            putInt(MlProtocol.KEY_MAX_NEW, maxNewTokens)
        })
    }

    /**
     * The test load of an experimental custom model: the model process loads [model] with the engine of [spec] and frees it
     * again. Returns normally when it loaded; a [MlException] when the file could not be loaded or the process died.
     */
    suspend fun probe(model: File, spec: ModelSpec): MlResult {
        val engine = when {
            spec.kind != ModelKind.SPEECH -> MlProtocol.PROBE_LLM
            spec.engine == SpeechEngine.PARAKEET -> MlProtocol.PROBE_PARAKEET
            else -> MlProtocol.PROBE_WHISPER
        }
        return request(MlProtocol.MSG_PROBE, model, extras = {
            putInt(MlProtocol.KEY_PROBE_ENGINE, engine)
            putInt(MlProtocol.KEY_CONTEXT, spec.contextTokens)
        })
    }

    /**
     * Live dictation with Parakeet: audio goes to the model process while the user speaks ([LiveCall.write]) and
     * [onText] gets the text so far on the main thread: the finished utterances joined by single spaces, and the
     * utterance still being spoken. [LiveCall.finish] ends the audio; [LiveCall.await] returns the whole text once the
     * end event, the end of the event stream and the service's result have all arrived. Must be called on the main
     * thread. Everything the model process sends is checked ([LiveFrames]) and sanitised; a frame that breaks the rules
     * ends the call with an error.
     */
    fun live(model: File, vad: File, language: String, onText: (committed: String, partial: String) -> Unit): LiveCall {
        val call = LiveCall()
        val audioPipe = ParcelFileDescriptor.createPipe() // the service reads [0], we write [1]
        val eventPipe = ParcelFileDescriptor.createPipe() // the service writes [1], we read [0]
        var vadFd: ParcelFileDescriptor? = null
        fun closeAll() {
            for (fd in listOfNotNull(audioPipe[0], audioPipe[1], eventPipe[0], eventPipe[1], vadFd)) runCatching { fd.close() }
        }
        try {
            vadFd = ParcelFileDescriptor.open(vad, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (_: FileNotFoundException) {
            closeAll()
            call.result.completeExceptionally(MlException("The voice detection model is missing"))
            return call
        }
        val completion = object : Completion {
            override val isActive get() = call.result.isActive
            override fun resume(result: MlResult) {
                call.stop() // nothing more is wanted from the audio thread, whatever it still sends
                call.result.complete(result)
            }
            override fun fail(error: MlException) {
                call.stop()
                call.result.completeExceptionally(error)
            }
        }
        val id = submit(MlProtocol.MSG_LIVE, model, completion, readResultPipe = false, extras = {
            putString(MlProtocol.KEY_LANGUAGE, language)
            putParcelable(MlProtocol.KEY_VAD_FD, vadFd)
            putString(MlProtocol.KEY_VAD_KEY, "${vad.name}:${vadFd!!.statSize}:${vad.lastModified()}")
            putParcelable(MlProtocol.KEY_AUDIO_PIPE, audioPipe[0])
            putParcelable(MlProtocol.KEY_EVENT_PIPE, eventPipe[1])
        }, afterSend = {
            // the service has its own copies of its ends now
            runCatching { audioPipe[0].close() }
            runCatching { eventPipe[1].close() }
            runCatching { vadFd?.close() }
            call.attachPipes(audioPipe[1], eventPipe[0])
            call.startWriter(ParcelFileDescriptor.AutoCloseOutputStream(audioPipe[1]))
            Thread({ readEvents(call, eventPipe[0], onText) }, "clackatoo-live-events").start()
        }, discardExtras = ::closeAll)
        if (id == null) return call
        call.attach(id, onCancel = { cancelLive(id, call) })
        return call
    }

    private fun cancelLive(id: Int, call: LiveCall) {
        call.stop()
        cancelRequest(id)
        call.result.completeExceptionally(MlException("cancelled", cancelled = true))
    }

    /** Reads the event frames of [id] until the service ends them, and reports them in order on the main thread. */
    private fun readEvents(call: LiveCall, read: ParcelFileDescriptor, onText: (String, String) -> Unit) {
        // The newest text goes to the main thread through one slot and at most one pending post, so a process that sends
        // frames faster than the keyboard can show them cannot queue work (or memory) on the main thread.
        val latest = java.util.concurrent.atomic.AtomicReference<Pair<String, String>?>(null)
        val posted = java.util.concurrent.atomic.AtomicBoolean(false)
        val committed = ArrayList<String>()
        var partial = ""
        var segmentBytes = 0
        var events = 0
        var failure: String? = null
        var ended = false
        try {
            ParcelFileDescriptor.AutoCloseInputStream(read).use { input ->
                while (!ended && !call.stopped) {
                    val frame = LiveFrames.read(input) ?: break
                    if (++events > MlProtocol.MAX_EVENTS) throw LiveFrames.ProtocolException("too many events")
                    when (frame) {
                        is LiveFrames.Frame.End -> ended = true
                        is LiveFrames.Frame.Partial -> partial = OutputSanitizer.clean(frame.text, multiLine = false)
                        is LiveFrames.Frame.Segment -> {
                            segmentBytes += frame.size
                            if (segmentBytes > MlProtocol.MAX_RESULT_BYTES) throw LiveFrames.ProtocolException("too much text")
                            val text = OutputSanitizer.clean(frame.text, multiLine = false)
                            if (text.isNotEmpty()) committed += text
                            partial = ""
                        }
                    }
                    if (!ended) {
                        latest.set(committed.joinToString(" ") to partial)
                        if (posted.compareAndSet(false, true)) {
                            main.post {
                                posted.set(false)
                                val text = latest.get()
                                if (text != null && !call.stopped) onText(text.first, text.second)
                            }
                        }
                    }
                }
                // the model process closes its end right after the end event: the text is final only at that end of
                // file, and anything sent after the end event is a broken stream
                if (ended) LiveFrames.readEndOfStream(input)
            }
            if (!ended && !call.stopped) failure = "The model process stopped unexpectedly"
        } catch (_: LiveFrames.ProtocolException) {
            failure = "The model process sent something unexpected"
        } catch (_: IOException) {
            if (!call.stopped) failure = "The model process stopped unexpectedly"
        }
        val text = committed.joinToString(" ")
        main.post {
            // the last text first: a call that ended right after a burst of frames still shows its final state
            latest.getAndSet(null)?.let { if (!call.stopped) onText(it.first, it.second) }
            if (call.stopped) return@post
            val id = call.id ?: return@post
            if (failure != null) failRequest(id, MlException(failure))
            else pending[id]?.let { it.text = text; complete(id, it) }
        }
    }

    /** Ends [id] with [error], telling the service to stop. */
    private fun failRequest(id: Int, error: MlException) {
        val entry = pending.remove(id) ?: return
        service?.let { send(it, Message.obtain(null, MlProtocol.MSG_CANCEL, id, 0)) }
        scheduleUnbind()
        entry.completion.fail(error)
    }

    /** Closes the connection now (the model process exits). */
    fun release() {
        main.removeCallbacks(unbindLater)
        discardWaiting()
        failAll(MlException("stopped", cancelled = true))
        unbind()
    }

    private suspend fun request(what: Int, model: File, extras: Bundle.() -> Unit, afterSend: () -> Unit = {}): MlResult =
        suspendCancellableCoroutine { continuation ->
            val id = submit(what, model, continuation.asCompletion(), readResultPipe = true, extras, afterSend, discardExtras = {})
            if (id != null) continuation.invokeOnCancellation { cancelRequest(id) }
        }

    /** Tells the service to stop [id] and forgets it. Safe to call from any thread. */
    private fun cancelRequest(id: Int) {
        main.post {
            pending.remove(id)
            service?.let { send(it, Message.obtain(null, MlProtocol.MSG_CANCEL, id, 0)) }
            scheduleUnbind()
        }
    }

    /**
     * Sends a request of kind [what] as soon as the connection exists and registers [completion] for its end. Returns
     * its id, or null when it failed at once ([completion] has been told). With [readResultPipe] the text of the result
     * arrives through a pipe of its own, otherwise the caller supplies it ([extras] adds the request's own descriptors
     * and [discardExtras] closes them when the request is never sent).
     */
    private fun submit(
        what: Int, model: File, completion: Completion, readResultPipe: Boolean,
        extras: Bundle.() -> Unit, afterSend: () -> Unit, discardExtras: () -> Unit,
    ): Int? {
        main.removeCallbacks(unbindLater)
        val modelFd = try {
            ParcelFileDescriptor.open(model, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (_: FileNotFoundException) {
            discardExtras()
            completion.fail(MlException("The model file is missing"))
            return null
        }
        val resultPipe = if (readResultPipe) ParcelFileDescriptor.createPipe() else null
        val resultRead = resultPipe?.get(0)
        val resultWrite = resultPipe?.get(1)
        val id = nextRequestId()
        val entry = Pending(completion)
        val data = Bundle().apply {
            putParcelable(MlProtocol.KEY_MODEL_FD, modelFd)
            putString(MlProtocol.KEY_MODEL_KEY, "${model.name}:${modelFd.statSize}:${model.lastModified()}")
            if (resultWrite != null) putParcelable(MlProtocol.KEY_RESULT_PIPE, resultWrite)
            extras()
        }
        pending[id] = entry
        fun closeOurs() {
            runCatching { modelFd.close() }
            runCatching { resultWrite?.close() }
            runCatching { resultRead?.close() }
        }
        whenConnected(discard = {
            closeOurs()
            discardExtras()
        }) {
            if (!pending.containsKey(id)) { // cancelled while the connection was being made
                closeOurs()
                discardExtras()
                return@whenConnected
            }
            val s = service
            val message = Message.obtain(null, what, id, 0).apply { this.data = data; replyTo = replies }
            val sent = s != null && send(s, message)
            // the service has its own copies of the descriptors now (or never will)
            runCatching { modelFd.close() }
            runCatching { resultWrite?.close() }
            if (!sent) {
                runCatching { resultRead?.close() }
                discardExtras()
                pending.remove(id)?.completion?.fail(MlException("The model process is not running"))
            } else {
                afterSend()
                if (resultRead != null) Thread({ readResult(id, resultRead) }, "clackatoo-result-pipe").start()
            }
        }
        return id
    }

    private fun whenConnected(discard: () -> Unit, action: () -> Unit) {
        if (service != null) {
            action()
            return
        }
        waitingForService += Waiting(action, discard)
        if (!connecting) {
            connecting = true
            val intent = Intent(context, MlService::class.java)
            if (!context.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
                connecting = false
                discardWaiting()
                failAll(MlException("Could not start the model process"))
            }
        }
    }

    private fun send(to: Messenger, message: Message): Boolean = try {
        to.send(message)
        true
    } catch (_: Exception) {
        false
    }

    /** Only the numbers of a reply are used (see [ReplyBinder]), because the sender is not trusted. */
    private fun handleReply(what: Int, id: Int, arg2: Int) {
        val entry = pending[id] ?: return
        when (what) {
            MlProtocol.MSG_LOADED -> entry.loaded = true
            MlProtocol.MSG_RESULT -> {
                entry.times = arg2
                complete(id, entry)
            }
            MlProtocol.MSG_ERROR -> {
                pending.remove(id)
                entry.completion.fail(errorFor(arg2))
                scheduleUnbind()
            }
        }
    }

    private fun errorFor(code: Int) = when (code) {
        MlProtocol.ERR_LOAD_FAILED -> MlException("The model file could not be loaded", modelFailedWhileLoading = true)
        MlProtocol.ERR_CANCELLED -> MlException("cancelled", cancelled = true)
        MlProtocol.ERR_UNAVAILABLE -> MlException("The speech and text engines are not available on this phone")
        MlProtocol.ERR_BAD_REQUEST -> MlException("The model process refused the request")
        else -> MlException("The model process failed")
    }

    /** Finishes the request once both the reply and the result text are here. */
    private fun complete(id: Int, entry: Pending) {
        val times = entry.times ?: return
        val text = entry.text ?: return
        pending.remove(id)
        entry.completion.resume(MlResult(text, MlProtocol.loadMsOf(times), MlProtocol.runMsOf(times)))
        scheduleUnbind()
    }

    /** Reads the result text (at most [MlProtocol.MAX_RESULT_BYTES]) until the service closes its end of the pipe. */
    private fun readResult(id: Int, read: ParcelFileDescriptor) {
        val bytes = try {
            ParcelFileDescriptor.AutoCloseInputStream(read).use { input ->
                val buffer = ByteArray(MlProtocol.MAX_RESULT_BYTES)
                var n = 0
                while (n < buffer.size) {
                    val r = input.read(buffer, n, buffer.size - n)
                    if (r < 0) break
                    n += r
                }
                buffer.copyOf(n)
            }
        } catch (_: IOException) {
            ByteArray(0)
        }
        val text = String(bytes, Charsets.UTF_8)
        main.post { pending[id]?.let { it.text = text; complete(id, it) } }
    }

    /** The model process died or could not be reached. */
    private fun connectionLost() {
        service = null
        connecting = false
        discardWaiting()
        failAll(null)
        unbind()
    }

    /** Requests that never got a connection are dropped: the descriptors they hold would otherwise stay open in this process. */
    private fun discardWaiting() {
        val dropped = ArrayList(waitingForService)
        waitingForService.clear()
        dropped.forEach { it.discard() }
    }

    private fun failAll(error: MlException?) {
        val all = pending.values.toList()
        pending.clear()
        all.forEach {
            if (it.completion.isActive)
                it.completion.fail(error ?: MlException("The model process stopped unexpectedly", modelFailedWhileLoading = !it.loaded))
        }
    }

    private fun scheduleUnbind() {
        main.removeCallbacks(unbindLater)
        main.postDelayed(unbindLater, IDLE_MS)
    }

    private fun unbind() {
        service = null
        connecting = false
        try {
            context.unbindService(connection)
        } catch (_: IllegalArgumentException) {
            // was not bound
        }
    }

    private fun writePcm(write: ParcelFileDescriptor, pcm: ShortArray) {
        try {
            ParcelFileDescriptor.AutoCloseOutputStream(write).buffered(1 shl 16).use { out ->
                val bytes = ByteArray(2 * 4096)
                var i = 0
                while (i < pcm.size) {
                    val n = minOf(4096, pcm.size - i)
                    for (k in 0 until n) {
                        bytes[2 * k] = pcm[i + k].toInt().toByte()
                        bytes[2 * k + 1] = (pcm[i + k].toInt() shr 8).toByte()
                    }
                    out.write(bytes, 0, 2 * n)
                    i += n
                }
            }
        } catch (_: IOException) {
            // the service stopped reading (cancelled or crashed)
        }
    }

    companion object {
        const val IDLE_MS = 60_000L

        /**
         * One counter for the whole process: the model process matches cancels and its running request on the id alone, so
         * two clients of the same process (the keyboard's own and the test load of a custom model) must never share ids.
         */
        private val ids = java.util.concurrent.atomic.AtomicInteger(0)

        internal fun nextRequestId(): Int = ids.incrementAndGet()
    }
}

/**
 * The binder behind the `replyTo` [Messenger] that the keyboard gives the model process. A plain Handler-backed
 * Messenger unparcels a whole [Message] before any code of ours runs, including `obj`, which is read as a framework
 * Parcelable chosen by the sender. The model process is isolated and not trusted, so this binder answers the
 * Messenger's `send` call itself and reads only the three integers of the message (`what`, `arg1`, `arg2`), then stops:
 * `obj` (when present), the Bundle and everything after it are never read. [deliver] runs on [handler]'s thread.
 */
internal class ReplyBinder(private val handler: Handler, private val deliver: (what: Int, arg1: Int, arg2: Int) -> Unit) : Binder() {
    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code != FIRST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags)
        data.enforceInterface(MESSENGER_DESCRIPTOR)
        if (data.readInt() == 0) return true // no message
        // Message.writeToParcel: what, arg1, arg2, then 1 plus an object, or 0
        val what = data.readInt()
        val arg1 = data.readInt()
        val arg2 = data.readInt()
        if (data.readInt() != 0) return true // a message with an object is not one of ours: dropped unread
        handler.post { deliver(what, arg1, arg2) }
        return true
    }

    private companion object {
        /** The interface token of the platform's IMessenger, which [Messenger.send] writes first. */
        const val MESSENGER_DESCRIPTOR = "android.os.IMessenger"
    }
}


/**
 * A live dictation in progress, see [MlClient.live]. [write] and [finish] may be called from any thread (the audio
 * thread calls [write]); [cancel] and [await] belong to the main thread.
 */
class LiveCall internal constructor() {
    internal val result = kotlinx.coroutines.CompletableDeferred<MlResult>()
    private val queue = java.util.concurrent.LinkedBlockingQueue<Any>()
    @Volatile internal var stopped = false
    @Volatile internal var id: Int? = null
    private var onCancel: (() -> Unit)? = null
    private var audioOut: ParcelFileDescriptor? = null
    private var eventsIn: ParcelFileDescriptor? = null
    private val pipeLock = Any()

    /** The keyboard's ends of the two pipes, closed by [stop] so that no thread stays blocked on them. */
    internal fun attachPipes(audio: ParcelFileDescriptor, events: ParcelFileDescriptor) {
        synchronized(pipeLock) {
            if (!stopped) {
                audioOut = audio
                eventsIn = events
                return
            }
        }
        runCatching { audio.close() }
        runCatching { events.close() }
    }

    internal fun attach(id: Int, onCancel: () -> Unit) {
        this.id = id
        this.onCancel = onCancel
    }

    /** Queues a copy of samples[0, count) for the model process; never blocks the caller. */
    fun write(samples: ShortArray, count: Int) {
        if (stopped || count <= 0) return
        queue.put(samples.copyOf(count))
    }

    /** No more audio: the model process decodes what is left and ends the call. */
    fun finish() {
        queue.put(END)
    }

    /** Ends the call at once and drops the audio. [await] then throws a cancelled [MlException]. */
    fun cancel() {
        if (stopped) return
        onCancel?.invoke() ?: run {
            stop()
            result.completeExceptionally(MlException("cancelled", cancelled = true))
        }
    }

    /** Returns when the last event, the service's reply and the end of the event pipe have all arrived. */
    suspend fun await(): MlResult = result.await()

    internal fun stop() {
        stopped = true
        queue.clear()
        queue.put(END)
        val pipes = synchronized(pipeLock) { listOfNotNull(audioOut, eventsIn).also { audioOut = null; eventsIn = null } }
        pipes.forEach { runCatching { it.close() } }
    }

    /** Drains the queue into the audio pipe on a thread of its own, so the audio thread never waits for the pipe. */
    internal fun startWriter(out: java.io.OutputStream) {
        Thread({
            val bytes = ByteArray(2 * 4096)
            try {
                out.buffered(1 shl 16).use { buffered ->
                    while (!stopped) {
                        val item = queue.take()
                        if (item === END) break
                        val pcm = item as ShortArray
                        var i = 0
                        while (i < pcm.size && !stopped) {
                            val n = minOf(4096, pcm.size - i)
                            for (k in 0 until n) {
                                bytes[2 * k] = pcm[i + k].toInt().toByte()
                                bytes[2 * k + 1] = (pcm[i + k].toInt() shr 8).toByte()
                            }
                            buffered.write(bytes, 0, 2 * n)
                            i += n
                        }
                        if (queue.isEmpty()) buffered.flush()
                    }
                }
            } catch (_: IOException) {
                // the service stopped reading (finished, cancelled or crashed)
            }
        }, "clackatoo-live-pipe").start()
    }

    private companion object {
        val END = Any()
    }
}

/** Reads and checks the event frames of [MlProtocol] from the model process, which is not trusted. */
internal object LiveFrames {
    sealed interface Frame {
        class Partial(val text: String) : Frame
        class Segment(val text: String, val size: Int) : Frame
        data object End : Frame
    }

    class ProtocolException(message: String) : IOException(message)

    /** The next frame, or null when the stream ends cleanly between frames. Anything else wrong throws [ProtocolException]. */
    fun read(input: java.io.InputStream): Frame? {
        val type = input.read()
        if (type < 0) return null
        if (type !in MlProtocol.EVENT_PARTIAL..MlProtocol.EVENT_END) throw ProtocolException("unknown event type")
        val length = (readByte(input) shl 24) or (readByte(input) shl 16) or (readByte(input) shl 8) or readByte(input)
        if (length < 0 || length > MlProtocol.MAX_EVENT_BYTES) throw ProtocolException("event too long")
        if (type == MlProtocol.EVENT_END) {
            if (length != 0) throw ProtocolException("the end event carries text")
            return Frame.End
        }
        val payload = ByteArray(length)
        var n = 0
        while (n < length) {
            val r = input.read(payload, n, length - n)
            if (r < 0) throw ProtocolException("event cut short")
            n += r
        }
        val text = String(payload, Charsets.UTF_8)
        return if (type == MlProtocol.EVENT_PARTIAL) Frame.Partial(text) else Frame.Segment(text, length)
    }

    /** Waits for the end of the stream after [Frame.End]; any byte after the end event throws [ProtocolException]. */
    fun readEndOfStream(input: java.io.InputStream) {
        if (input.read() >= 0) throw ProtocolException("data after the end event")
    }

    private fun readByte(input: java.io.InputStream): Int {
        val b = input.read()
        if (b < 0) throw ProtocolException("event cut short")
        return b
    }
}
