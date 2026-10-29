// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo.models

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Parcel
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.clackatoo.catalog.Catalog
import helium314.keyboard.clackatoo.catalog.ModelsProtocol
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CompanionRequestsTest {
    @Test fun `foreground start is delivered before releasing the binding that keeps the queued download alive`() {
        val events = Collections.synchronizedList(ArrayList<String>())
        val callback = CountDownLatch(1)
        val unbound = CountDownLatch(1)
        val remote = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                events += "request"
                reply!!.writeNoException()
                reply.writeInt(ModelsProtocol.REQUEST_OK)
                return true
            }
        }
        val app = object : ContextWrapper(ApplicationProvider.getApplicationContext<Application>()) {
            override fun getApplicationContext(): Context = this
            override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean {
                events += "bind"
                connection.onServiceConnected(intent.component!!, remote)
                return true
            }
            override fun startForegroundService(intent: Intent): ComponentName? {
                events += "foreground-start"
                return intent.component
            }
            override fun unbindService(connection: ServiceConnection) {
                events += "unbind"
                unbound.countDown()
            }
        }
        CompanionRequests.send(app, ModelsProtocol.COMPANION_PACKAGE, ModelsProtocol.ACTION_DOWNLOAD, Catalog.WHISPER_TINY.id) {
            if (it == ModelsProtocol.REQUEST_OK) {
                app.startForegroundService(Intent().setComponent(ComponentName(ModelsProtocol.COMPANION_PACKAGE, ModelsProtocol.DOWNLOAD_SERVICE_CLASS)))
            }
            callback.countDown()
        }
        assertTrue(callback.await(5, TimeUnit.SECONDS))
        assertTrue(unbound.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("bind", "request", "foreground-start", "unbind"), events.toList())
    }
}
