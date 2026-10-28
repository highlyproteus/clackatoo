// SPDX-License-Identifier: GPL-3.0-only

package io.github.highlyproteus.clackatoo.models

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import helium314.keyboard.clackatoo.catalog.ModelsProtocol
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * This app's side of the link to the keyboard's `ModelReceiverService`: progress numbers, failure codes and the finished
 * file as a read-only descriptor. Only a keyboard with the same signature is ever bound. Calls block, so use them from
 * the download thread, never the main thread.
 */
internal class KeyboardLink(context: Context, private val keyboardPackage: String) {
    private val app = context.applicationContext
    private var binder: IBinder? = null
    private var bound = false
    private val lock = Any()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            synchronized(lock) { binder = service }
            latch?.countDown()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            synchronized(lock) { binder = null }
        }
    }
    @Volatile private var latch: CountDownLatch? = null

    /** True when the keyboard of this build type is installed with this app's signature. */
    fun keyboardIsGenuine(): Boolean = try {
        app.packageManager.getPackageInfo(keyboardPackage, 0)
        app.packageManager.checkSignatures(app.packageName, keyboardPackage) == PackageManager.SIGNATURE_MATCH
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /** Binds the keyboard's receiver service (this starts the keyboard's process if needed). False when that did not work in time. */
    fun connect(timeoutMs: Long = 8_000): Boolean {
        synchronized(lock) { if (binder?.isBinderAlive == true) return true }
        if (!keyboardIsGenuine()) return false
        val wait = CountDownLatch(1)
        latch = wait
        val intent = Intent().setComponent(ComponentName(keyboardPackage, ModelsProtocol.RECEIVER_SERVICE_CLASS))
        synchronized(lock) {
            if (bound) app.unbindService(connection)
            bound = try { app.bindService(intent, connection, Context.BIND_AUTO_CREATE) } catch (_: SecurityException) { false }
            if (!bound) return false
        }
        wait.await(timeoutMs, TimeUnit.MILLISECONDS)
        return synchronized(lock) { binder?.isBinderAlive == true }
    }

    fun close() {
        synchronized(lock) {
            if (bound) {
                try { app.unbindService(connection) } catch (_: IllegalArgumentException) { }
                bound = false
            }
            binder = null
        }
    }

    fun progress(id: String, bytes: Long, total: Long) {
        oneWay(ModelsProtocol.TX_PROGRESS) { it.writeString(id); it.writeLong(bytes); it.writeLong(total) }
    }

    fun failed(id: String, reason: Int) {
        connect(2_000)
        oneWay(ModelsProtocol.TX_FAILED) { it.writeString(id); it.writeInt(reason) }
    }

    /** Gives [file] to the keyboard. Returns its `HANDOVER_*` answer, or null when the keyboard could not be reached. */
    fun handover(id: String, file: File): Int? = handoverFile(ModelsProtocol.TX_HANDOVER, file) { it.writeString(id) }

    /**
     * Gives [file], a custom model the user picked in the Hugging Face browser, to the keyboard, with [displayName] (the
     * file's base name, cut to `MAX_CUSTOM_NAME_LENGTH`) and the [role] (`CUSTOM_ROLE_*`). Returns its `HANDOVER_*` answer,
     * or null when the keyboard could not be reached.
     */
    fun handoverCustom(displayName: String, role: Int, file: File): Int? =
        handoverFile(ModelsProtocol.TX_CUSTOM_HANDOVER, file) {
            it.writeString(displayName.take(ModelsProtocol.MAX_CUSTOM_NAME_LENGTH))
            it.writeInt(role)
        }

    /** One call that carries [writeArguments] and then [file] as a read-only descriptor; the reply is one `HANDOVER_*` int. */
    private fun handoverFile(code: Int, file: File, writeArguments: (Parcel) -> Unit): Int? {
        if (!connect()) return null
        val remote = synchronized(lock) { binder } ?: return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                data.writeInterfaceToken(ModelsProtocol.RECEIVER_DESCRIPTOR)
                writeArguments(data)
                data.writeFileDescriptor(pfd.fileDescriptor)
                val handled = remote.transact(code, data, reply, 0)
                return readHandoverReply(handled, reply)
            }
        } catch (_: RemoteException) {
            return null
        } catch (_: RuntimeException) { // SecurityException and friends: the keyboard refused us
            return null
        } catch (_: java.io.IOException) {
            return null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    companion object {
        /**
         * The `HANDOVER_*` code in [reply], or null when there is no answer: the keyboard did not handle the call
         * ([handled] false: an older keyboard that does not know the transaction, or a malformed parcel), or its reply is
         * empty. An empty parcel reads as 0, which is `HANDOVER_OK`, so it must never be read as an answer.
         */
        internal fun readHandoverReply(handled: Boolean, reply: Parcel): Int? {
            if (!handled) return null
            reply.setDataPosition(0)
            if (reply.dataSize() == 0) return null
            reply.readException()
            if (reply.dataAvail() < 4) return null
            return reply.readInt()
        }
    }

    private fun oneWay(code: Int, write: (Parcel) -> Unit) {
        val remote = synchronized(lock) { binder } ?: return
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(ModelsProtocol.RECEIVER_DESCRIPTOR)
            write(data)
            remote.transact(code, data, null, IBinder.FLAG_ONEWAY)
        } catch (_: RemoteException) {
        } catch (_: RuntimeException) {
        } finally {
            data.recycle()
        }
    }
}
