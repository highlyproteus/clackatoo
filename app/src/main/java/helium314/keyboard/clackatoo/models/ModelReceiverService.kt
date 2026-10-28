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
import helium314.keyboard.clackatoo.assist.CustomPolicy
import helium314.keyboard.clackatoo.assist.CustomProbe
import helium314.keyboard.clackatoo.assist.ModelCatalog
import helium314.keyboard.clackatoo.assist.ModelKind
import helium314.keyboard.clackatoo.catalog.ModelsProtocol
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.prefs
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
            installCustom = { name, kind, pfd -> installCustom(name, kind, pfd) },
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

    /**
     * A custom (experimental) model from the companion's Hugging Face browser. Refused until the user has accepted the
     * experimental-models notice in Settings; then a regular file of at most [CustomPolicy.MAX_BYTES] goes through
     * [ModelHandover.acceptCustom] (header format, role, test load in the model process). A new model becomes the chosen one of its role.
     */
    private fun installCustom(name: String, kind: ModelKind, pfd: ParcelFileDescriptor): Int {
        if (!AssistSettings.customModelsConfirmed(prefs())) return ModelsProtocol.HANDOVER_CUSTOM_OFF
        val stat = try { Os.fstat(pfd.fileDescriptor) } catch (_: Exception) { return ModelsProtocol.HANDOVER_READ_ERROR }
        if (!OsConstants.S_ISREG(stat.st_mode)) return ModelsProtocol.HANDOVER_READ_ERROR
        if (stat.st_size > CustomPolicy.MAX_BYTES) return ModelsProtocol.HANDOVER_TOO_LARGE
        val store = AssistSettings.modelStore(this)
        store.cleanUp()
        val app = applicationContext
        val handed = FileInputStream(pfd.fileDescriptor).use {
            ModelHandover.acceptCustom(store, kind, name, it, stat.st_size) { file, spec -> CustomProbe.run(app, file, spec) }
        }
        handed.spec?.let { ModelChoice.select(this, kind, it.id) }
        if (handed.code == ModelsProtocol.HANDOVER_OK) ModelDownloads.holder(this).customChanged()
        return handed.code
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
    private val installCustom: (name: String, kind: ModelKind, file: ParcelFileDescriptor) -> Int = { _, _, _ -> ModelsProtocol.HANDOVER_BAD_REQUEST },
) : Binder() {
    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (!isTrustedCaller()) throw SecurityException("not the Clackatoo companion app")
        try {
            data.enforceInterface(ModelsProtocol.RECEIVER_DESCRIPTOR)
            if (code == ModelsProtocol.TX_CUSTOM_HANDOVER) return customHandover(data, reply)
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

    /**
     * `writeString(name); writeInt(role); writeFileDescriptor(file)` and nothing else: a name of 1 to
     * [ModelsProtocol.MAX_CUSTOM_NAME_LENGTH] characters, a role that is one of the `CUSTOM_ROLE_*` numbers, a descriptor.
     * Any other shape is answered with `HANDOVER_BAD_REQUEST` and the descriptor is closed unread. What the file is, is
     * decided by [installCustom], never by anything in this call.
     */
    private fun customHandover(data: Parcel, reply: Parcel?): Boolean {
        val name = data.readString()
        val kind = CustomPolicy.roleOfProtocol(data.readInt())
        val file = data.readFileDescriptor()
        val wellFormed = file != null && name != null && name.length in 1..ModelsProtocol.MAX_CUSTOM_NAME_LENGTH && kind != null && data.dataAvail() == 0
        if (!wellFormed) {
            file?.close()
            reply?.writeNoException()
            reply?.writeInt(ModelsProtocol.HANDOVER_BAD_REQUEST)
            return true
        }
        val result = try { file!!.use { installCustom(name!!, kind!!, it) } } catch (e: Exception) {
            Log.w(TAG, "custom model hand-over failed: ${e.javaClass.simpleName}")
            ModelsProtocol.HANDOVER_READ_ERROR
        }
        reply?.writeNoException()
        reply?.writeInt(result)
        return true
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
