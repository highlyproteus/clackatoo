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

    private class Pending(val continuation: CancellableContinuation<MlResult>) {
        var loaded = false
        /** Set when the service said it is done (the numbers of its reply). */
        var times: Int? = null
        /** Set when the result pipe has been read to its end. */
        var text: String? = null
    }

    private val pending = HashMap<Int, Pending>()
    private var nextId = 1
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

    /** Closes the connection now (the model process exits). */
    fun release() {
        main.removeCallbacks(unbindLater)
        discardWaiting()
        failAll(MlException("stopped", cancelled = true))
        unbind()
    }

    private suspend fun request(what: Int, model: File, extras: Bundle.() -> Unit, afterSend: () -> Unit = {}): MlResult =
        suspendCancellableCoroutine { continuation ->
            main.removeCallbacks(unbindLater)
            val modelFd = try {
                ParcelFileDescriptor.open(model, ParcelFileDescriptor.MODE_READ_ONLY)
            } catch (_: FileNotFoundException) {
                continuation.resumeWithException(MlException("The model file is missing"))
                return@suspendCancellableCoroutine
            }
            val resultPipe = ParcelFileDescriptor.createPipe()
            val resultRead = resultPipe[0]
            val resultWrite = resultPipe[1]
            val id = nextId++
            val entry = Pending(continuation)
            val data = Bundle().apply {
                putParcelable(MlProtocol.KEY_MODEL_FD, modelFd)
                putString(MlProtocol.KEY_MODEL_KEY, "${model.name}:${modelFd.statSize}:${model.lastModified()}")
                putParcelable(MlProtocol.KEY_RESULT_PIPE, resultWrite)
                extras()
            }
            pending[id] = entry
            continuation.invokeOnCancellation {
                main.post {
                    pending.remove(id)
                    service?.let { send(it, Message.obtain(null, MlProtocol.MSG_CANCEL, id, 0)) }
                    scheduleUnbind()
                }
            }
            whenConnected(discard = {
                runCatching { modelFd.close() }
                runCatching { resultWrite.close() }
                runCatching { resultRead.close() }
            }) {
                if (!pending.containsKey(id)) { // cancelled while the connection was being made
                    runCatching { modelFd.close() }
                    runCatching { resultWrite.close() }
                    runCatching { resultRead.close() }
                    return@whenConnected
                }
                val s = service
                val message = Message.obtain(null, what, id, 0).apply { this.data = data; replyTo = replies }
                val sent = s != null && send(s, message)
                // the service has its own copies of the descriptors now (or never will)
                runCatching { modelFd.close() }
                runCatching { resultWrite.close() }
                if (!sent) {
                    runCatching { resultRead.close() }
                    pending.remove(id)?.continuation?.resumeWithException(MlException("The model process is not running"))
                } else {
                    afterSend()
                    Thread({ readResult(id, resultRead) }, "clackatoo-result-pipe").start()
                }
            }
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
                entry.continuation.resumeWithException(errorFor(arg2))
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
        entry.continuation.resume(MlResult(text, MlProtocol.loadMsOf(times), MlProtocol.runMsOf(times)))
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
            if (it.continuation.isActive)
                it.continuation.resumeWithException(error ?: MlException("The model process stopped unexpectedly", modelFailedWhileLoading = !it.loaded))
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
