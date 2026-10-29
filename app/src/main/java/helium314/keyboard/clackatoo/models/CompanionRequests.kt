// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo.models

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import helium314.keyboard.clackatoo.catalog.ModelsProtocol
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Sends one request (download or cancel of a catalog id, nothing else) to the companion app's download service over a
 * Binder call: bind, one transaction, deliver its result, unbind. The result callback starts accepted downloads in the
 * foreground while the binding still keeps them alive; the started service then survives unbinding or leaving settings.
 * The companion checks the caller and signature permission. Runs on a background thread; [onResult] gets a `REQUEST_*`
 * code, or null when the companion could not be reached.
 */
internal object CompanionRequests {
    private val executor = Executors.newSingleThreadExecutor()

    fun send(context: Context, companionPackage: String, action: String, id: String, onResult: (Int?) -> Unit) {
        val app = context.applicationContext
        executor.execute { transact(app, companionPackage, action, id, onResult) }
    }

    private fun transact(app: Context, companionPackage: String, action: String, id: String, onResult: (Int?) -> Unit) {
        val latch = CountDownLatch(1)
        var binder: IBinder? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                binder = service
                latch.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) {}
            override fun onNullBinding(name: ComponentName) = latch.countDown()
        }
        val intent = Intent().setComponent(ComponentName(companionPackage, ModelsProtocol.DOWNLOAD_SERVICE_CLASS))
        val bound = try { app.bindService(intent, connection, Context.BIND_AUTO_CREATE) } catch (_: SecurityException) { false }
        try {
            val result = if (!bound || !latch.await(8, TimeUnit.SECONDS)) null
                else binder?.let { request(it, action, id) }
            // In particular, do not unbind an accepted but not-yet-started download before its foreground start.
            onResult(result)
        } finally {
            app.unbindService(connection) // also after a bindService that returned false, as the documentation asks
        }
    }

    private fun request(remote: IBinder, action: String, id: String): Int? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ModelsProtocol.DOWNLOAD_DESCRIPTOR)
            data.writeString(action)
            data.writeString(id)
            remote.transact(ModelsProtocol.TX_REQUEST, data, reply, 0)
            reply.readException()
            return reply.readInt()
        } catch (_: RemoteException) {
            return null
        } catch (_: RuntimeException) {
            return null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
}
