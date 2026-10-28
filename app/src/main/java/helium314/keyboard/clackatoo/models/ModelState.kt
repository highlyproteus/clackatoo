// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo.models

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Where a downloadable model is on this phone. Observe it with [ModelDownloads.state]. */
sealed interface ModelState {
    data object NotInstalled : ModelState
    /** [bytes] of [total] received by the companion app (numbers only; [total] is the catalog size). */
    data class Downloading(val bytes: Long, val total: Long) : ModelState
    /** The finished file has been handed over and is being checked and moved into place. */
    data object Installing : ModelState
    data object Installed : ModelState
    /** [message] was written by the keyboard (never taken from the companion) and is safe to show. */
    data class Failed(val message: String) : ModelState
}

/**
 * The states of all models, as flows. Plain Kotlin (no Android) so the transitions can be tested. [isInstalled] says
 * whether a model's file is on the phone, and decides the starting state of a model nobody has touched yet.
 */
internal class ModelStateHolder(private val isInstalled: (String) -> Boolean) {
    private val flows = ConcurrentHashMap<String, MutableStateFlow<ModelState>>()

    private val customVersion = MutableStateFlow(0)

    /** Counts changes of the experimental custom models (one arrived, one was deleted), so screens that list them can re-read the folder. */
    fun customChanges(): StateFlow<Int> = customVersion

    fun customChanged() {
        customVersion.value += 1
    }

    /** Called whenever the companion shows a sign of life for a model (a request, a progress report). */
    var onActivity: (String) -> Unit = {}

    private fun flow(id: String): MutableStateFlow<ModelState> =
        flows.getOrPut(id) { MutableStateFlow(if (isInstalled(id)) ModelState.Installed else ModelState.NotInstalled) }

    fun state(id: String): StateFlow<ModelState> = flow(id)

    fun current(id: String): ModelState = flow(id).value

    /** A request was sent to the companion. */
    fun requested(id: String, total: Long) {
        flow(id).value = ModelState.Downloading(0, total)
        onActivity(id)
    }

    /** Progress from the companion: ignored once the file is being installed or is installed. */
    fun progress(id: String, bytes: Long, total: Long) {
        onActivity(id)
        val flow = flow(id)
        while (true) {
            val old = flow.value
            if (old is ModelState.Installing || old is ModelState.Installed) return
            val new = ModelState.Downloading(bytes.coerceIn(0, total.coerceAtLeast(0)), total.coerceAtLeast(0))
            if (flow.compareAndSet(old, new)) return
        }
    }

    fun installing(id: String) {
        flow(id).value = ModelState.Installing
    }

    fun installed(id: String) {
        flow(id).value = ModelState.Installed
    }

    fun failed(id: String, message: String) {
        flow(id).value = ModelState.Failed(message)
    }

    /** The user cancelled (or the companion says it stopped): back to not installed, unless the file arrived anyway. */
    fun cancelled(id: String) {
        val flow = flow(id)
        while (true) {
            val old = flow.value
            if (old is ModelState.Installed || old is ModelState.Installing) return
            if (flow.compareAndSet(old, ModelState.NotInstalled)) return
        }
    }

    /** Re-reads which files are on the phone (an import, a deletion or adb changed them) without touching running downloads. */
    fun refresh(ids: Iterable<String>) {
        for (id in ids) refreshOne(id)
    }

    private fun refreshOne(id: String) {
        val flow = flow(id)
        while (true) {
            val old = flow.value
            if (old is ModelState.Downloading || old is ModelState.Installing) return
            val new = when {
                isInstalled(id) -> ModelState.Installed
                old is ModelState.Installed -> ModelState.NotInstalled
                else -> return
            }
            if (new == old || flow.compareAndSet(old, new)) return
        }
    }
}
