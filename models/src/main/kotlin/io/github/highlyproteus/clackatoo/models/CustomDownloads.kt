// SPDX-License-Identifier: GPL-3.0-only

package io.github.highlyproteus.clackatoo.models

import helium314.keyboard.clackatoo.catalog.ModelsProtocol
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** One download the user chose in this app's own Hugging Face browser. */
internal class CustomJob(
    /** `custom:<n>`: the id in [DownloadTracker] and in the Cancel intent. */
    val id: String,
    val job: DownloadJob,
    /** A `ModelsProtocol.CUSTOM_ROLE_*`. */
    val role: Int,
    /** The file's base name, what the keyboard is told. */
    val displayName: String,
    val cancelled: AtomicBoolean = AtomicBoolean(false),
)

/**
 * The process-internal queue of custom downloads. Only this app's own screen puts anything in it ([enqueue]); the
 * service takes the jobs out when it is started and never reads anything the start Intent or a Binder caller carries,
 * so the keyboard (or any other app) cannot make it fetch an address.
 */
internal object CustomDownloads {
    private const val PREFIX = "custom:"

    private val counter = AtomicInteger()
    private val pending = ArrayList<CustomJob>()
    private val known = ConcurrentHashMap<String, CustomJob>() // queued and running, for Cancel

    fun isCustomId(id: String?) = id != null && id.startsWith(PREFIX)

    /** True for the id of a queued or running custom download. */
    fun isKnown(id: String?) = id != null && known.containsKey(id)

    /** Queues a download for the service, or null when [role] is not one of the three. The caller then starts the service. */
    fun enqueue(job: DownloadJob, role: Int, displayName: String): CustomJob? {
        if (role != ModelsProtocol.CUSTOM_ROLE_SPEECH && role != ModelsProtocol.CUSTOM_ROLE_CLEAN_UP && role != ModelsProtocol.CUSTOM_ROLE_GRAMMAR) return null
        if (job.sizeBytes <= 0 || job.sizeBytes > ModelsProtocol.MAX_CUSTOM_BYTES) return null
        val custom = CustomJob(PREFIX + counter.incrementAndGet(), job, role, displayName.take(ModelsProtocol.MAX_CUSTOM_NAME_LENGTH))
        known[custom.id] = custom
        synchronized(pending) { pending += custom }
        DownloadTracker.update(Progress(custom.id, Progress.Phase.QUEUED, 0, job.sizeBytes, title = custom.displayName, role = role))
        return custom
    }

    /** Hands the queued jobs to the service, oldest first, and empties the queue. */
    fun takePending(): List<CustomJob> = synchronized(pending) { pending.toList().also { pending.clear() } }

    /** Takes back a job that never started (the service could not be started), and shows it as cancelled. */
    fun withdraw(custom: CustomJob) {
        if (synchronized(pending) { pending.remove(custom) }) abandon(custom) else custom.cancelled.set(true)
    }

    /** Cancels everything that is queued (the service cannot run): shown as cancelled, nothing is left to start. */
    fun withdrawAll() = takePending().forEach { abandon(it) }

    private fun abandon(custom: CustomJob) {
        custom.cancelled.set(true)
        known.remove(custom.id)
        DownloadTracker.update(Progress(custom.id, Progress.Phase.CANCELLED, 0, custom.job.sizeBytes, ModelsProtocol.FAIL_CANCELLED, custom.displayName, custom.role))
    }

    /** Asks a queued or running custom download to stop. */
    fun cancel(id: String) {
        known[id]?.cancelled?.set(true)
    }

    fun cancelAll() = known.values.forEach { it.cancelled.set(true) }

    /** The job has ended (whatever way): its Cancel flag is not needed any more. */
    fun finished(custom: CustomJob) {
        known.remove(custom.id)
    }
}
