// SPDX-License-Identifier: GPL-3.0-only

package io.github.highlyproteus.clackatoo.models

import android.os.Binder
import android.os.Parcel
import helium314.keyboard.clackatoo.catalog.ModelsProtocol

/**
 * The Binder behind [DownloadService]: the keyboard's requests. Every call is checked for its caller ([isTrustedCaller]:
 * the keyboard's package and signature, decided by the service from `Binder.getCallingUid()`), then for its exact shape
 * (interface token, an action string, one id string, nothing left over) and then by [RequestParser] (a catalog id and
 * one of two actions). The answer is one int, a `REQUEST_*` code; a call from anyone else is turned away with a
 * SecurityException and starts nothing.
 */
internal class DownloadBinder(
    private val isTrustedCaller: () -> Boolean,
    private val onRequest: (Request) -> Int,
) : Binder() {
    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (!isTrustedCaller()) throw SecurityException("not the Clackatoo keyboard")
        if (code != ModelsProtocol.TX_REQUEST) return super.onTransact(code, data, reply, flags)
        val answer = try {
            data.enforceInterface(ModelsProtocol.DOWNLOAD_DESCRIPTOR)
            val action = data.readString()
            val id = data.readString()
            val request = if (data.dataAvail() == 0) RequestParser.check(action, id) else null
            if (request == null) ModelsProtocol.REQUEST_REFUSED else onRequest(request)
        } catch (_: RuntimeException) { // a malformed parcel: refuse, never crash this app
            ModelsProtocol.REQUEST_REFUSED
        }
        reply?.writeNoException()
        reply?.writeInt(answer)
        return true
    }
}
