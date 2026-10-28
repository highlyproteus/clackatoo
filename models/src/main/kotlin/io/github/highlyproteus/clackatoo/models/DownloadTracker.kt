// SPDX-License-Identifier: GPL-3.0-only

package io.github.highlyproteus.clackatoo.models

import java.util.concurrent.CopyOnWriteArrayList

/** What this app shows for one download (numbers and a phase only). */
internal data class Progress(
    val id: String,
    val phase: Phase,
    val bytes: Long,
    val total: Long,
    /** A `ModelsProtocol.FAIL_*` or `HANDOVER_*` code for [Phase.FAILED]. */
    val reason: Int = 0,
    /** What to call the download on screen when it is not a catalog model (a custom one: its file name). */
    val title: String? = null,
    /** A `ModelsProtocol.CUSTOM_ROLE_*` for a custom download, 0 otherwise. */
    val role: Int = 0,
) {
    enum class Phase { QUEUED, DOWNLOADING, VERIFYING, HANDING_OVER, DONE, FAILED, CANCELLED }
}

/** In-process list of downloads of this session, for the screen and the notification. */
internal object DownloadTracker {
    interface Listener { fun onChanged(all: List<Progress>) }

    private val items = LinkedHashMap<String, Progress>()
    private val listeners = CopyOnWriteArrayList<Listener>()

    @Synchronized
    fun snapshot(): List<Progress> = items.values.toList()

    @Synchronized
    fun get(id: String): Progress? = items[id]

    fun update(progress: Progress) {
        val all = synchronized(this) {
            items.remove(progress.id) // a repeated download goes to the end of the list
            items[progress.id] = progress
            items.values.toList()
        }
        listeners.forEach { it.onChanged(all) }
    }

    fun add(listener: Listener) {
        listeners.add(listener)
        listener.onChanged(snapshot())
    }

    fun remove(listener: Listener) {
        listeners.remove(listener)
    }
}
