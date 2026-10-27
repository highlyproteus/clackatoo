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
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    private fun openDescriptors(): Int = File("/proc/self/fd").list()?.size ?: error("no /proc/self/fd")

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
}
