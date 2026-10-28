// SPDX-License-Identifier: GPL-3.0-only

package io.github.highlyproteus.clackatoo.models

import android.os.Parcel
import helium314.keyboard.clackatoo.catalog.Catalog
import helium314.keyboard.clackatoo.catalog.ModelsProtocol
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** What the companion does with calls to its download service: only the keyboard, only the documented shape. */
@RunWith(RobolectricTestRunner::class)
class DownloadBinderTest {
    private var trusted = true
    private val requests = ArrayList<Request>()
    private val binder = DownloadBinder({ trusted }) { requests += it; ModelsProtocol.REQUEST_OK }

    private fun call(token: String? = ModelsProtocol.DOWNLOAD_DESCRIPTOR, code: Int = ModelsProtocol.TX_REQUEST, write: (Parcel) -> Unit): Parcel {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        if (token != null) data.writeInterfaceToken(token)
        write(data)
        data.setDataPosition(0)
        binder.transact(code, data, reply, 0)
        reply.setDataPosition(0)
        return reply
    }

    private fun answer(reply: Parcel): Int { reply.readException(); return reply.readInt() }

    @Test fun `a download of a catalog id from the keyboard is passed on`() {
        val reply = call { it.writeString(ModelsProtocol.ACTION_DOWNLOAD); it.writeString(Catalog.WHISPER_TINY.id) }
        assertEquals(ModelsProtocol.REQUEST_OK, answer(reply))
        assertEquals(listOf<Request>(Request.Download(Catalog.WHISPER_TINY)), requests)
    }

    @Test fun `a cancel is passed on too`() {
        call { it.writeString(ModelsProtocol.ACTION_CANCEL); it.writeString(Catalog.S1_MINI.id) }
        assertEquals(listOf<Request>(Request.Cancel(Catalog.S1_MINI)), requests)
    }

    @Test fun `a caller that is not the keyboard is turned away and starts nothing`() {
        trusted = false
        val reply = call { it.writeString(ModelsProtocol.ACTION_DOWNLOAD); it.writeString(Catalog.WHISPER_TINY.id) }
        assertFailsWith<SecurityException> { reply.readException() }
        assertTrue(requests.isEmpty())
    }

    @Test fun `unknown ids, URLs, extra data and short or foreign calls are refused`() {
        val refused = ModelsProtocol.REQUEST_REFUSED
        assertEquals(refused, answer(call { it.writeString(ModelsProtocol.ACTION_DOWNLOAD); it.writeString("evil") }))
        assertEquals(refused, answer(call { it.writeString(ModelsProtocol.ACTION_DOWNLOAD); it.writeString(Catalog.WHISPER_TINY.url) }))
        assertEquals(refused, answer(call { it.writeString(ModelsProtocol.ACTION_DOWNLOAD); it.writeString(Catalog.WHISPER_TINY.id); it.writeString("https://evil.example/") }))
        assertEquals(refused, answer(call { it.writeString(ModelsProtocol.ACTION_DOWNLOAD); it.writeString(Catalog.WHISPER_TINY.id); it.writeLong(5) }))
        assertEquals(refused, answer(call { it.writeString(ModelsProtocol.ACTION_DOWNLOAD) }))
        assertEquals(refused, answer(call { }))
        assertEquals(refused, answer(call(token = null) { it.writeString(ModelsProtocol.ACTION_DOWNLOAD); it.writeString(Catalog.WHISPER_TINY.id) }))
        assertEquals(refused, answer(call(token = "other") { it.writeString(ModelsProtocol.ACTION_DOWNLOAD); it.writeString(Catalog.WHISPER_TINY.id) }))
        assertEquals(refused, answer(call { it.writeString("evil.action"); it.writeString(Catalog.WHISPER_TINY.id) }))
        assertTrue(requests.isEmpty())
    }
}
