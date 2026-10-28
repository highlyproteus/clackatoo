// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo.assist

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Parcel
import android.os.Parcelable
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class MlClientTest {
    /** A Parcelable that counts how often it is rebuilt from a parcel. */
    class Tracked() : Parcelable {
        override fun describeContents() = 0
        override fun writeToParcel(dest: Parcel, flags: Int) = dest.writeInt(42)

        companion object {
            @JvmField var created = 0
            @JvmField val CREATOR = object : Parcelable.Creator<Tracked> {
                override fun createFromParcel(source: Parcel): Tracked { source.readInt(); created++; return Tracked() }
                override fun newArray(size: Int) = arrayOfNulls<Tracked>(size)
            }
        }
    }

    // ---- the reply binder reads three integers and nothing else

    private class Received(val what: Int, val arg1: Int, val arg2: Int)

    private fun binderAndInbox(): Pair<Messenger, MutableList<Received>> {
        val inbox = ArrayList<Received>()
        val binder = ReplyBinder(Handler(Looper.getMainLooper())) { what, a1, a2 -> inbox += Received(what, a1, a2) }
        return Messenger(binder) to inbox
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test fun `a reply delivers its three numbers`() {
        val (messenger, inbox) = binderAndInbox()
        messenger.send(Message.obtain(null, MlProtocol.MSG_RESULT, 7, 123456))
        idle()
        assertEquals(1, inbox.size)
        assertEquals(MlProtocol.MSG_RESULT, inbox[0].what)
        assertEquals(7, inbox[0].arg1)
        assertEquals(123456, inbox[0].arg2)
    }

    @Test fun `a reply with an object is dropped and the object is never rebuilt`() {
        val (messenger, inbox) = binderAndInbox()
        Tracked.created = 0
        messenger.send(Message.obtain(null, MlProtocol.MSG_RESULT, 7, 1).apply { obj = Tracked() })
        idle()
        assertEquals(0, Tracked.created, "obj must never be read from the parcel")
        assertTrue(inbox.isEmpty())
        // the next, ordinary reply still arrives
        messenger.send(Message.obtain(null, MlProtocol.MSG_LOADED, 8, 0))
        idle()
        assertEquals(1, inbox.size)
        assertEquals(8, inbox[0].arg1)
    }

    @Test fun `a reply with a Bundle delivers its numbers and the Bundle is not needed`() {
        val (messenger, inbox) = binderAndInbox()
        messenger.send(Message.obtain(null, MlProtocol.MSG_ERROR, 3, MlProtocol.ERR_CANCELLED).apply {
            data = android.os.Bundle().apply { putString("x", "ignored") }
        })
        idle()
        assertEquals(1, inbox.size)
        assertEquals(MlProtocol.ERR_CANCELLED, inbox[0].arg2)
    }

    @Test fun `a transaction of another kind is not answered`() {
        val binder = ReplyBinder(Handler(Looper.getMainLooper())) { _, _, _ -> error("must not be called") }
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken("android.os.IMessenger")
            assertEquals(false, binder.transact(Messenger(binder).binderCode() + 1, data, reply, 0))
        } finally {
            data.recycle()
            reply.recycle()
        }
        idle()
    }

    private fun Messenger.binderCode() = android.os.IBinder.FIRST_CALL_TRANSACTION

    // ---- a request that never got a connection gives its descriptors back

    /** Context whose bindService only records the connection (or refuses). */
    private class FakeContext(base: Context, private val bindResult: Boolean) : ContextWrapper(base) {
        var connection: ServiceConnection? = null
        override fun getApplicationContext(): Context = this
        override fun bindService(service: Intent, conn: ServiceConnection, flags: Int): Boolean {
            connection = conn
            return bindResult
        }
        override fun unbindService(conn: ServiceConnection) {}
    }

    /** The number of open file descriptors of this process: /proc/self/fd on Linux, /dev/fd on macOS. */
    private fun openDescriptors(): Int =
        (File("/proc/self/fd").takeIf { it.exists() } ?: File("/dev/fd")).list()?.size ?: error("cannot list the open descriptors")

    private fun modelFile(): File = File.createTempFile("model", ".gguf").apply { writeText("not a model"); deleteOnExit() }

    private fun prompt() = listOf(PromptPart("hello", special = false))

    @Test fun `descriptors of queued requests are closed when binding fails`() {
        val client = MlClient(FakeContext(ApplicationProvider.getApplicationContext(), bindResult = false))
        val model = modelFile()
        val before = openDescriptors()
        repeat(3) {
            assertFailsWith<MlException> { runBlocking { client.generate(model, 2048, prompt(), 16) } }
        }
        assertEquals(before, openDescriptors(), "the model descriptor and both pipe ends of each request are closed")
    }

    @Test fun `descriptors of queued requests are closed when the connection is lost before it was made`() {
        val context = FakeContext(ApplicationProvider.getApplicationContext(), bindResult = true)
        val client = MlClient(context)
        val model = modelFile()
        val before = openDescriptors()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val requests = List(3) { scope.async { client.generate(model, 2048, prompt(), 16) } }
        assertTrue(openDescriptors() > before, "the queued requests hold descriptors (${openDescriptors()} vs $before)")
        context.connection!!.onServiceDisconnected(ComponentName("x", "y"))
        runBlocking { requests.forEach { assertFailsWith<MlException> { it.await() } } }
        assertEquals(before, openDescriptors())
    }

    @Test fun `descriptors of queued requests are closed when the client is released`() {
        val context = FakeContext(ApplicationProvider.getApplicationContext(), bindResult = true)
        val client = MlClient(context)
        val model = modelFile()
        val before = openDescriptors()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val request = scope.async { client.generate(model, 2048, prompt(), 16) }
        assertTrue(openDescriptors() > before)
        client.release()
        runBlocking { assertFailsWith<MlException> { request.await() } }
        assertEquals(before, openDescriptors())
    }

    // ---- live dictation: the event frames of the model process are checked

    /**
     * A stand-in for the model process. It handles each message inside [Messenger.send], before the client closes its
     * copies of the descriptors (nothing is parcelled in this process, so they are the client's own objects).
     * [onLive] runs at that moment too, before the client starts to read its event pipe: Robolectric's pipes are plain
     * files (no blocking reads, end of file when nothing is left), so the frames a test wants the client to see must
     * already be in the pipe by then.
     */
    private class FakeService(private val onLive: (FakeService) -> Unit = {}) {
        class Received(val what: Int, val id: Int, val replyTo: Messenger?, val fds: Map<String, android.os.ParcelFileDescriptor>)

        val requests = ArrayList<Received>()
        val cancelled = ArrayList<Int>()
        val messenger = Messenger(object : Handler(Looper.getMainLooper()) {
            override fun sendMessageAtTime(msg: Message, uptimeMillis: Long): Boolean {
                handle(msg)
                return true
            }
        })

        private fun handle(m: Message) {
            if (m.what == MlProtocol.MSG_CANCEL) {
                cancelled += m.arg1
                return
            }
            val fds = HashMap<String, android.os.ParcelFileDescriptor>()
            for (key in listOf(MlProtocol.KEY_MODEL_FD, MlProtocol.KEY_VAD_FD, MlProtocol.KEY_AUDIO_PIPE, MlProtocol.KEY_EVENT_PIPE)) {
                androidx.core.os.BundleCompat.getParcelable(m.data, key, android.os.ParcelFileDescriptor::class.java)?.let { fds[key] = it }
            }
            requests += Received(m.what, m.arg1, m.replyTo, fds)
            if (m.what == MlProtocol.MSG_LIVE) onLive(this)
        }

        val live get() = requests.single { it.what == MlProtocol.MSG_LIVE }
        fun events() = android.os.ParcelFileDescriptor.AutoCloseOutputStream(live.fds.getValue(MlProtocol.KEY_EVENT_PIPE))
    }

    private fun frame(type: Int, payload: ByteArray = ByteArray(0), length: Int = payload.size) =
        byteArrayOf(type.toByte(), (length ushr 24).toByte(), (length ushr 16).toByte(), (length ushr 8).toByte(), length.toByte()) + payload

    /** Runs the main looper until [done] or a few seconds have passed (the frames arrive from a reader thread). */
    private fun waitFor(done: () -> Boolean) {
        repeat(200) {
            idle()
            if (done()) return
            Thread.sleep(15)
        }
    }

    private val unexpected = "The model process sent something unexpected"
    private val stopped = "The model process stopped unexpectedly"

    /** The call ends with [message] (which tells the checks that fired apart from a plain end of stream), and the service is told to stop. */
    private fun assertEnds(call: LiveCall, service: FakeService, message: String) {
        waitFor { call.result.isCompleted }
        assertTrue(call.result.isCompleted, "the call must end")
        val failure = assertFailsWith<MlException> { runBlocking { call.await() } }
        assertEquals(message, failure.message)
        waitFor { service.cancelled.isNotEmpty() }
        assertEquals(1, service.cancelled.size, "the model process is told to stop")
    }

    /** Starts a live call whose event pipe already holds [bytes] when the client begins to read it, and connects it. */
    private fun liveCallWithEvents(bytes: ByteArray, onText: (String, String) -> Unit = { _, _ -> }): Pair<LiveCall, FakeService> {
        val service = FakeService { it.events().use { out -> out.write(bytes) } }
        val context = FakeContext(ApplicationProvider.getApplicationContext(), bindResult = true)
        val client = MlClient(context)
        val call = client.live(modelFile(), modelFile(), "en", onText)
        context.connection!!.onServiceConnected(ComponentName("x", "y"), service.messenger.binder)
        idle()
        return call to service
    }

    @Test fun `an event longer than the limit cancels the live call`() {
        val (call, service) = liveCallWithEvents(frame(MlProtocol.EVENT_PARTIAL, length = MlProtocol.MAX_EVENT_BYTES + 1))
        assertEnds(call, service, unexpected)
    }

    @Test fun `an event of an unknown type cancels the live call`() {
        val (call, service) = liveCallWithEvents(frame(9, "hi".toByteArray()))
        assertEnds(call, service, unexpected)
    }

    @Test fun `an end event with text, a cut-off event and a missing end all fail the call`() {
        for ((bytes, message) in listOf(
            frame(MlProtocol.EVENT_END, "x".toByteArray()) to unexpected,
            frame(MlProtocol.EVENT_SEGMENT, "hello".toByteArray()).copyOf(8) to unexpected,
            frame(MlProtocol.EVENT_SEGMENT, "hello".toByteArray()) to stopped, // the stream ends without an end event
            ByteArray(0) to stopped,
        )) {
            val (call, service) = liveCallWithEvents(bytes)
            assertEnds(call, service, message)
        }
    }

    @Test fun `more text than a result may hold cancels the live call`() {
        val segment = frame(MlProtocol.EVENT_SEGMENT, ByteArray(MlProtocol.MAX_EVENT_BYTES) { 'a'.code.toByte() })
        val all = ByteArray(0).let { var acc = it; repeat(MlProtocol.MAX_RESULT_BYTES / MlProtocol.MAX_EVENT_BYTES + 1) { acc += segment }; acc }
        val (call, service) = liveCallWithEvents(all)
        assertEnds(call, service, unexpected)
    }

    @Test fun `frames are shown in order and the call returns the committed text`() {
        val texts = ArrayList<Pair<String, String>>()
        val bytes = frame(MlProtocol.EVENT_PARTIAL, "so um".toByteArray()) +
            frame(MlProtocol.EVENT_SEGMENT, "So, um, I need".toByteArray()) +
            frame(MlProtocol.EVENT_PARTIAL, "to send\u0007 it".toByteArray()) + // a control character is cleaned away
            frame(MlProtocol.EVENT_SEGMENT, "to send it.".toByteArray()) +
            frame(MlProtocol.EVENT_END)
        val (call, service) = liveCallWithEvents(bytes) { c, p -> texts += c to p }
        val id = service.live.id
        waitFor { texts.size >= 4 }
        assertEquals(listOf("" to "so um", "So, um, I need" to "", "So, um, I need" to "to send it", "So, um, I need to send it." to ""), texts)
        // not finished until the model process says it is: the text alone does not complete the call
        assertFalse(call.result.isCompleted)
        service.live.replyTo!!.send(Message.obtain(null, MlProtocol.MSG_RESULT, id, MlProtocol.packTimes(1200, 340)))
        waitFor { call.result.isCompleted }
        val result = runBlocking { call.await() }
        assertEquals("So, um, I need to send it.", result.text)
        assertEquals(1200, result.loadMs)
        assertEquals(340, result.runMs)
    }

    @Test fun `audio written to the call goes out as little-endian samples, then the pipe closes`() {
        val call = LiveCall()
        call.write(shortArrayOf(1, -2, 0x1234), 3) // queued before the writer exists
        call.startWriter(object : java.io.ByteArrayOutputStream() {
            override fun close() {
                closed = true
                super.close()
            }
        }.also { out = it })
        call.write(shortArrayOf(7, 8, 9, 10), 2) // only the first 2 of the block count
        call.finish()
        repeat(300) { if (!closed) Thread.sleep(10) }
        assertTrue(closed, "the writer closes the pipe after the end marker")
        assertContentEquals(byteArrayOf(1, 0, -2, -1, 0x34, 0x12, 7, 0, 8, 0), out.toByteArray())
        call.write(shortArrayOf(5), 1) // after the end nothing is queued for a pipe that is gone: no harm
    }

    @Volatile private var closed = false
    private var out = java.io.ByteArrayOutputStream()

    @Test fun `cancelling a live call tells the model process and ends the call as cancelled`() {
        val (call, service) = liveCallWithEvents(frame(MlProtocol.EVENT_END)) // a well-formed stream; the service has not replied yet
        call.cancel()
        waitFor { service.cancelled.isNotEmpty() }
        assertEquals(listOf(service.live.id), service.cancelled)
        val failure = assertFailsWith<MlException> { runBlocking { call.await() } }
        assertTrue(failure.cancelled)
    }

    @Test fun `the descriptors of a live call are closed when the connection never comes`() {
        val context = FakeContext(ApplicationProvider.getApplicationContext(), bindResult = false)
        val client = MlClient(context)
        val before = openDescriptors()
        val call = client.live(modelFile(), modelFile(), "en") { _, _ -> }
        assertFailsWith<MlException> { runBlocking { call.await() } }
        assertEquals(before, openDescriptors())
    }

    @Test fun `the frame reader accepts exactly the documented frames`() {
        fun read(bytes: ByteArray) = LiveFrames.read(java.io.ByteArrayInputStream(bytes))
        assertEquals(null, read(ByteArray(0)))
        assertIs<LiveFrames.Frame.End>(read(frame(MlProtocol.EVENT_END)))
        assertEquals("hé", (read(frame(MlProtocol.EVENT_PARTIAL, "hé".toByteArray())) as LiveFrames.Frame.Partial).text)
        assertEquals(MlProtocol.MAX_EVENT_BYTES, (read(frame(MlProtocol.EVENT_SEGMENT, ByteArray(MlProtocol.MAX_EVENT_BYTES) { 65 })) as LiveFrames.Frame.Segment).size)
        assertFailsWith<LiveFrames.ProtocolException> { read(frame(MlProtocol.EVENT_SEGMENT, length = MlProtocol.MAX_EVENT_BYTES + 1)) }
        assertFailsWith<LiveFrames.ProtocolException> { read(frame(MlProtocol.EVENT_SEGMENT, length = -1)) }
        assertFailsWith<LiveFrames.ProtocolException> { read(frame(0)) }
        assertFailsWith<LiveFrames.ProtocolException> { read(frame(4)) }
        assertFailsWith<LiveFrames.ProtocolException> { read(frame(9)) }
        assertFailsWith<LiveFrames.ProtocolException> { read(byteArrayOf(1, 0, 0)) }
        assertFailsWith<LiveFrames.ProtocolException> { read(frame(MlProtocol.EVENT_SEGMENT, "abc".toByteArray(), length = 5)) }
    }
}
