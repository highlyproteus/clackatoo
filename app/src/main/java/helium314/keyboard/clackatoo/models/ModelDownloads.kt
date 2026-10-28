// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo.models

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import helium314.keyboard.clackatoo.assist.AssistSettings
import helium314.keyboard.clackatoo.assist.ModelCatalog
import helium314.keyboard.clackatoo.assist.ModelStatus
import helium314.keyboard.clackatoo.catalog.ModelsProtocol
import helium314.keyboard.latin.BuildConfig
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.Log
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.StateFlow

/**
 * One-tap model downloads. The keyboard never fetches anything itself: the separate app "Clackatoo Models" (which holds
 * the INTERNET permission) downloads the pinned file, and hands it over for the keyboard to check and install
 * ([ModelReceiverService], [ModelHandover]). This object is the keyboard's side: it tells the companion which catalog id
 * to fetch and exposes what happens as [ModelState] flows.
 *
 * Call [init] once at startup ([helium314.keyboard.latin.App] does); the flows know which models are installed from then on.
 */
object ModelDownloads {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val stallChecks = ConcurrentHashMap<String, Runnable>()
    private var holder: ModelStateHolder? = null
    private var appContext: Context? = null

    /** The companion's package name for this build (a debug keyboard talks to the debug companion). */
    internal val companionPackage: String get() = BuildConfig.COMPANION_APPLICATION_ID

    @Synchronized
    fun init(context: Context) {
        if (holder != null) return
        val app = context.applicationContext
        appContext = app
        holder = ModelStateHolder(isInstalled = { id ->
            val spec = ModelCatalog.byId(id)
            spec != null && AssistSettings.modelStore(app).status(spec) is ModelStatus.Installed
        }).also { it.onActivity = ::watchStall }
    }

    @Synchronized
    internal fun holder(context: Context): ModelStateHolder {
        init(context)
        return holder!!
    }

    private fun holder(): ModelStateHolder = holder ?: error("ModelDownloads.init(context) has not been called")

    /** The state of the catalog model [id] (an unknown id stays [ModelState.NotInstalled]). */
    fun state(id: String): StateFlow<ModelState> = holder().state(id)

    /** Re-reads which model files are on the phone, after an import, a deletion or a copy with adb. Running downloads are untouched. */
    fun refresh(context: Context) {
        holder(context).refresh(ModelCatalog.all.filter { it.bundledAsset == null }.map { it.id })
    }

    /** True when the signed Clackatoo Models app of this build type is installed (a look-alike with another signature does not count). */
    fun isCompanionInstalled(context: Context): Boolean {
        val pm = context.packageManager
        return try {
            pm.getPackageInfo(companionPackage, 0)
            pm.checkSignatures(context.packageName, companionPackage) == PackageManager.SIGNATURE_MATCH
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    /**
     * Starts downloading the catalog model [id]. When Clackatoo Models is not installed this opens its download page in
     * the browser instead (the keyboard itself never fetches anything). Does nothing for an unknown id or while the model
     * is already downloading or installing.
     */
    fun download(context: Context, id: String) {
        val spec = ModelCatalog.byId(id) ?: return
        val app = context.applicationContext
        val states = holder(app)
        when (states.current(id)) {
            is ModelState.Downloading, ModelState.Installing -> return
            else -> Unit
        }
        if (!isCompanionInstalled(app)) return openReleasesPage(app, id)
        states.requested(id, spec.sizeBytes)
        try {
            ContextCompat.startForegroundService(app, request(ModelsProtocol.ACTION_DOWNLOAD, id))
        } catch (e: Exception) { // not allowed to start it right now, or the permission is missing
            Log.w(TAG, "could not start the download: ${e.javaClass.simpleName}")
            cancelStallCheck(id)
            states.failed(id, app.getString(R.string.models_failed_start))
        }
    }

    /** Stops a running download of [id] and deletes the companion's partial file. */
    fun cancel(context: Context, id: String) {
        if (ModelCatalog.byId(id) == null) return
        val app = context.applicationContext
        val states = holder(app)
        if (states.current(id) is ModelState.Installing) return // too late to stop, the file is arriving
        cancelStallCheck(id)
        states.cancelled(id)
        if (!isCompanionInstalled(app)) return
        try {
            ContextCompat.startForegroundService(app, request(ModelsProtocol.ACTION_CANCEL, id))
        } catch (e: Exception) {
            Log.w(TAG, "could not send the cancel: ${e.javaClass.simpleName}")
        }
    }

    /** Deletes the installed file of [id]. A running download is cancelled first. */
    fun delete(context: Context, id: String) {
        val spec = ModelCatalog.byId(id) ?: return
        val app = context.applicationContext
        val states = holder(app)
        if (states.current(id) is ModelState.Downloading) cancel(app, id)
        AssistSettings.modelStore(app).delete(spec)
        states.refresh(listOf(id))
    }

    private fun request(action: String, id: String) = Intent(action)
        .setComponent(ComponentName(companionPackage, ModelsProtocol.DOWNLOAD_SERVICE_CLASS))
        .putExtra(ModelsProtocol.EXTRA_ID, id)

    private fun openReleasesPage(context: Context, id: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ModelsProtocol.RELEASES_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            holder(context).failed(id, context.getString(R.string.models_no_browser))
        }
    }

    // ---- what the companion tells us, as user-facing text written here (never taken from the companion)

    internal fun failureMessage(reason: Int): String = appContext!!.getString(
        when (reason) {
            ModelsProtocol.FAIL_NETWORK -> R.string.models_failed_network
            ModelsProtocol.FAIL_SIZE -> R.string.models_failed_size
            ModelsProtocol.FAIL_DIGEST -> R.string.models_failed_digest
            ModelsProtocol.FAIL_NO_SPACE -> R.string.models_failed_space
            ModelsProtocol.FAIL_UNTRUSTED_HOST -> R.string.models_failed_host
            else -> R.string.models_failed_other
        }
    )

    internal fun handoverMessage(result: Int): String = appContext!!.getString(
        when (result) {
            ModelsProtocol.HANDOVER_WRONG_SIZE -> R.string.assist_import_wrong_size
            ModelsProtocol.HANDOVER_WRONG_CONTENT -> R.string.assist_import_wrong_content
            ModelsProtocol.HANDOVER_NOT_A_MODEL -> R.string.assist_import_not_a_model
            ModelsProtocol.HANDOVER_NO_SPACE -> R.string.assist_import_no_space
            else -> R.string.models_failed_install
        }
    )

    // ---- a download whose companion stops talking (killed, crashed) must not show "downloading" forever

    private fun watchStall(id: String) {
        cancelStallCheck(id)
        val check = Runnable {
            stallChecks.remove(id)
            val states = holder ?: return@Runnable
            if (states.current(id) is ModelState.Downloading) states.failed(id, appContext!!.getString(R.string.models_failed_stalled))
        }
        stallChecks[id] = check
        mainHandler.postDelayed(check, STALL_MS)
    }

    private fun cancelStallCheck(id: String) {
        stallChecks.remove(id)?.let { mainHandler.removeCallbacks(it) }
    }

    private const val STALL_MS = 120_000L
    private const val TAG = "ClackatooModels"
}
