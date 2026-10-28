// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo.models

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import helium314.keyboard.clackatoo.assist.AssistSettings
import helium314.keyboard.clackatoo.assist.ModelCatalog
import helium314.keyboard.clackatoo.catalog.ModelsProtocol
import helium314.keyboard.latin.utils.Log
import java.io.FileInputStream

/**
 * The keyboard's end of the link to the "Clackatoo Models" companion app: it receives download progress and, at the end,
 * the finished model file. The service is exported but needs the signature permission `<applicationId>.permission.MODELS`
 * to be bound, so only apps signed with the Clackatoo key can reach it, and every call is checked again against the
 * caller's signature. A call is the model id and numbers only, nothing else; a call with anything extra is refused. The
 * file arrives as a read-only descriptor and goes through [ModelHandover], i.e. the usual size and SHA-256 check.
 */
class ModelReceiverService : Service() {
    private val binder by lazy {
        ModelReceiverBinder(
            isTrustedCaller = { isTrusted(Binder.getCallingUid()) },
            states = ModelDownloads.holder(this),
            installFile = { id, pfd -> install(id, pfd) },
        )
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun isTrusted(uid: Int): Boolean {
        val pm = packageManager
        val companion = pm.getPackagesForUid(uid).orEmpty().contains(ModelDownloads.companionPackage)
        return companion && pm.checkSignatures(uid, applicationInfo.uid) == PackageManager.SIGNATURE_MATCH
    }

    private fun install(id: String, pfd: ParcelFileDescriptor): Int {
        val spec = ModelCatalog.byId(id) ?: return ModelsProtocol.HANDOVER_BAD_REQUEST
        // a regular file of exactly the catalog size: anything else (a pipe, a socket, a file of another size) is refused unread
        val stat = try { Os.fstat(pfd.fileDescriptor) } catch (_: Exception) { return ModelsProtocol.HANDOVER_READ_ERROR }
        if (!OsConstants.S_ISREG(stat.st_mode)) return ModelsProtocol.HANDOVER_READ_ERROR
        if (stat.st_size != spec.sizeBytes) return ModelsProtocol.HANDOVER_WRONG_SIZE
        val store = AssistSettings.modelStore(this)
        store.cleanUp()
        val result = FileInputStream(pfd.fileDescriptor).use { ModelHandover.accept(store, id, it) }
        if (result == ModelsProtocol.HANDOVER_OK) ModelChoice.select(this, spec.kind, spec.id)
        return result
    }
}

/**
 * The Binder behind [ModelReceiverService]. Plain Binder transactions instead of AIDL so that the exact shape of every
 * call is checked here: the interface token, then the fields of the call in order, then nothing: leftover data refuses
 * the call.
 */
internal class ModelReceiverBinder(
    private val isTrustedCaller: () -> Boolean,
    private val states: ModelStateHolder,
    private val installFile: (id: String, file: ParcelFileDescriptor) -> Int,
) : Binder() {
    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (!isTrustedCaller()) throw SecurityException("not the Clackatoo companion app")
        try {
            data.enforceInterface(ModelsProtocol.RECEIVER_DESCRIPTOR)
            val id = data.readString()
            val spec = ModelCatalog.byId(id)
            if (id == null || id.length > ModelsProtocol.MAX_ID_LENGTH || spec == null) return refuse(reply, code)
            when (code) {
                ModelsProtocol.TX_PROGRESS -> {
                    val bytes = data.readLong()
                    val total = data.readLong()
                    if (data.dataAvail() != 0 || bytes < 0 || total != spec.sizeBytes) return refuse(reply, code)
                    states.progress(id, bytes, total)
                }
                ModelsProtocol.TX_FAILED -> {
                    val reason = data.readInt()
                    if (data.dataAvail() != 0) return refuse(reply, code)
                    if (reason == ModelsProtocol.FAIL_CANCELLED) states.cancelled(id) else states.failed(id, ModelDownloads.failureMessage(reason))
                }
                ModelsProtocol.TX_HANDOVER -> {
                    val file = data.readFileDescriptor()
                    if (file == null || data.dataAvail() != 0) {
                        file?.close()
                        return refuse(reply, code)
                    }
                    states.installing(id)
                    val result = try { file.use { installFile(id, it) } } catch (e: Exception) {
                        Log.w(TAG, "model hand-over failed: ${e.javaClass.simpleName}")
                        ModelsProtocol.HANDOVER_READ_ERROR
                    }
                    if (result == ModelsProtocol.HANDOVER_OK) states.installed(id)
                    else states.failed(id, ModelDownloads.handoverMessage(result))
                    reply?.writeNoException()
                    reply?.writeInt(result)
                }
                else -> return super.onTransact(code, data, reply, flags)
            }
            return true
        } catch (e: RuntimeException) {
            // a malformed parcel (wrong token, short data): refuse, never crash the keyboard
            Log.w(TAG, "refused a malformed model message: ${e.javaClass.simpleName}")
            return false
        }
    }

    private fun refuse(reply: Parcel?, code: Int): Boolean {
        if (code == ModelsProtocol.TX_HANDOVER) {
            reply?.writeNoException()
            reply?.writeInt(ModelsProtocol.HANDOVER_BAD_REQUEST)
            return true
        }
        return false
    }

    private companion object { const val TAG = "ClackatooModels" }
}
