// SPDX-License-Identifier: GPL-3.0-only

package io.github.highlyproteus.clackatoo.models

/**
 * Keeps the keyboard (or anything that gets past the signature checks) from using this app as a signalling channel to
 * Hugging Face by starting and cancelling downloads at will: a model that is already queued, running or just handed
 * over is not started again, a cancelled or failed one waits [cooldownMs] before the next try, and there are at most
 * [maxPerHour] new downloads per hour. The numbers never bother a real user: a download takes minutes, and a person
 * starts a handful a day. [now] is the clock in milliseconds.
 */
internal class RequestLimiter(
    private val now: () -> Long = System::currentTimeMillis,
    private val cooldownMs: Long = COOLDOWN_MS,
    private val handedOverMs: Long = HANDED_OVER_MS,
    private val maxPerHour: Int = MAX_PER_HOUR,
) {
    enum class Verdict { START, ALREADY, LATER }

    private val running = HashSet<String>()
    private val blockedUntil = HashMap<String, Long>()
    private val starts = ArrayDeque<Long>()

    /** Whether a download of [id] may start now; if so, it is counted and considered running. */
    @Synchronized
    fun tryStart(id: String): Verdict {
        if (id in running) return Verdict.ALREADY
        val t = now()
        if ((blockedUntil[id] ?: Long.MIN_VALUE) > t) return Verdict.LATER
        while (starts.isNotEmpty() && t - starts.first() >= HOUR_MS) starts.removeFirst()
        if (starts.size >= maxPerHour) return Verdict.LATER
        starts.addLast(t)
        running += id
        return Verdict.START
    }

    /** The download of [id] ended without the keyboard having the file (cancelled, failed, refused). */
    @Synchronized
    fun failed(id: String) {
        running -= id
        blockedUntil[id] = now() + cooldownMs
    }

    /** The keyboard accepted the file: do not start it again for a while. */
    @Synchronized
    fun handedOver(id: String) {
        running -= id
        blockedUntil[id] = now() + handedOverMs
    }

    /** The download of [id] is over, however it ended; keeps any cool-down that is already set. */
    @Synchronized
    fun released(id: String) {
        running -= id
    }

    companion object {
        const val COOLDOWN_MS = 30_000L
        const val HANDED_OVER_MS = 60_000L
        const val MAX_PER_HOUR = 20
        private const val HOUR_MS = 3_600_000L
    }
}
