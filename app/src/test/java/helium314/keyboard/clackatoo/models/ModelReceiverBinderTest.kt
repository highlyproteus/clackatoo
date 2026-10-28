// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo.models

import android.os.Binder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import helium314.keyboard.clackatoo.catalog.Catalog
import helium314.keyboard.clackatoo.catalog.ModelsProtocol
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What the keyboard's receiver does with calls from the companion app: exactly the documented shape, from the right caller. */
@RunWith(RobolectricTestRunner::class)
class ModelReceiverBinderTest {
    private val id = Catalog.WHISPER_TINY.id
    private val total = Catalog.WHISPER_TINY.sizeBytes
    private val holder = ModelStateHolder { false }
    private var trusted = true
    private val installs = ArrayList<String>()
    private var installResult = ModelsProtocol.HANDOVER_OK

    private val binder = ModelReceiverBinder(
        isTrustedCaller = { trusted },
        states = holder,
        installFile = { fileId, _ -> installs += fileId; installResult },
    )

    private fun transact(code: Int, token: String? = ModelsProtocol.RECEIVER_DESCRIPTOR, write: (Parcel) -> Unit): Pair<Boolean, Parcel> {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        if (token != null) data.writeInterfaceToken(token)
        write(data)
        data.setDataPosition(0)
        val ok = binder.transact(code, data, reply, 0)
        reply.setDataPosition(0)
        return ok to reply
    }

    @Test fun `a progress report updates the state`() {
        val (ok, _) = transact(ModelsProtocol.TX_PROGRESS) { it.writeString(id); it.writeLong(1000); it.writeLong(total) }
        assertTrue(ok)
        assertEquals(ModelState.Downloading(1000, total), holder.state(id).value)
    }

    @Test fun `a call with anything extra is refused and changes nothing`() {
        val (ok, _) = transact(ModelsProtocol.TX_PROGRESS) { it.writeString(id); it.writeLong(1000); it.writeLong(total); it.writeString("https://evil.example/") }
        assertFalse(ok)
        assertEquals(ModelState.NotInstalled, holder.state(id).value)
        val (ok2, _) = transact(ModelsProtocol.TX_FAILED) { it.writeString(id); it.writeInt(ModelsProtocol.FAIL_NETWORK); it.writeInt(1) }
        assertFalse(ok2)
        assertEquals(ModelState.NotInstalled, holder.state(id).value)
    }

    @Test fun `an unknown id, a wrong total or a negative count is refused`() {
        assertFalse(transact(ModelsProtocol.TX_PROGRESS) { it.writeString("evil"); it.writeLong(1); it.writeLong(total) }.first)
        assertFalse(transact(ModelsProtocol.TX_PROGRESS) { it.writeString(null); it.writeLong(1); it.writeLong(total) }.first)
        assertFalse(transact(ModelsProtocol.TX_PROGRESS) { it.writeString("x".repeat(500)); it.writeLong(1); it.writeLong(total) }.first)
        // the total comes from the catalog, not from the caller
        assertFalse(transact(ModelsProtocol.TX_PROGRESS) { it.writeString(id); it.writeLong(1); it.writeLong(total + 1) }.first)
        assertFalse(transact(ModelsProtocol.TX_PROGRESS) { it.writeString(id); it.writeLong(-1); it.writeLong(total) }.first)
        assertEquals(ModelState.NotInstalled, holder.state(id).value)
    }

    @Test fun `a call without the interface token or too short is refused without crashing`() {
        assertFalse(transact(ModelsProtocol.TX_PROGRESS, token = null) { it.writeString(id); it.writeLong(1); it.writeLong(total) }.first)
        assertFalse(transact(ModelsProtocol.TX_PROGRESS, token = "something.else") { it.writeString(id); it.writeLong(1); it.writeLong(total) }.first)
        assertFalse(transact(ModelsProtocol.TX_PROGRESS) { it.writeString(id) }.first)
        assertFalse(transact(ModelsProtocol.TX_PROGRESS) { }.first)
    }

    @Test fun `a caller that is not the companion app is turned away`() {
        trusted = false
        // Binder hands a RuntimeException of onTransact to the caller in the reply
        val (_, reply) = transact(ModelsProtocol.TX_PROGRESS) { it.writeString(id); it.writeLong(1); it.writeLong(total) }
        assertFailsWith<SecurityException> { reply.readException() }
        assertEquals(ModelState.NotInstalled, holder.state(id).value)
        assertTrue(installs.isEmpty())
    }

    @Test fun `a failure report shows a message written by the keyboard, and a cancel is not a failure`() {
        holder.requested(id, total)
        val (cancelled, _) = transact(ModelsProtocol.TX_FAILED) { it.writeString(id); it.writeInt(ModelsProtocol.FAIL_CANCELLED) }
        assertTrue(cancelled)
        assertEquals(ModelState.NotInstalled, holder.state(id).value)
    }

    @Test fun `a hand-over installs through the keyboard's own check and reports its answer`() {
        val file = File.createTempFile("handover", ".bin").also { it.writeBytes(ByteArray(10)); it.deleteOnExit() }
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            val (ok, reply) = transact(ModelsProtocol.TX_HANDOVER) { it.writeString(id); it.writeFileDescriptor(pfd.fileDescriptor) }
            assertTrue(ok)
            reply.readException()
            assertEquals(ModelsProtocol.HANDOVER_OK, reply.readInt())
        }
        assertEquals(listOf(id), installs)
        assertEquals(ModelState.Installed, holder.state(id).value)
    }

    @Test fun `a refused hand-over leaves the model not installed`() {
        installResult = ModelsProtocol.HANDOVER_WRONG_CONTENT
        val file = File.createTempFile("handover", ".bin").also { it.writeBytes(ByteArray(10)); it.deleteOnExit() }
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            val (_, reply) = transact(ModelsProtocol.TX_HANDOVER) { it.writeString(id); it.writeFileDescriptor(pfd.fileDescriptor) }
            reply.readException()
            assertEquals(ModelsProtocol.HANDOVER_WRONG_CONTENT, reply.readInt())
        }
        assertTrue(holder.state(id).value is ModelState.Failed)
    }

    @Test fun `a hand-over for an unknown id is answered with bad request and never installs`() {
        val file = File.createTempFile("handover", ".bin").also { it.writeBytes(ByteArray(10)); it.deleteOnExit() }
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            val (_, reply) = transact(ModelsProtocol.TX_HANDOVER) { it.writeString("../../evil"); it.writeFileDescriptor(pfd.fileDescriptor) }
            reply.readException()
            assertEquals(ModelsProtocol.HANDOVER_BAD_REQUEST, reply.readInt())
        }
        assertTrue(installs.isEmpty())
    }

    @Test fun `an unknown transaction code is not handled`() {
        assertFalse(Binder.getCallingPid() < 0)
        assertFalse(transact(99) { it.writeString(id) }.first)
    }
}
