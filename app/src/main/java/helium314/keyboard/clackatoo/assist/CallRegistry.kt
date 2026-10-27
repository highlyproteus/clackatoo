// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo.assist

/**
 * Keeps cancelling and freeing apart in [MlService]. A cancel arrives on the service's main thread while the call it
 * is meant for runs on the worker thread, so three things must hold, and all of them are decided under one lock:
 *
 * - a cancel reaches a native handle only while that handle is attached to the running request, and the worker
 *   detaches it ([end]) before it can free or replace it: no cancel ever touches a freed handle;
 * - a cancel for a request that already ended, or for one that has not started, never flips the flag of a later
 *   request: it is matched by request id;
 * - a cancel that arrives before the request has a handle is not lost: [attach] reports it.
 *
 * [setCancelFlag] sets or clears the cancel flag of a native handle.
 */
class CallRegistry(private val setCancelFlag: (speech: Boolean, handle: Long, value: Boolean) -> Unit) {
    private val lock = Any()
    private val cancelled = LinkedHashSet<Int>()
    private var activeId = -1
    private var activeSpeech = false
    private var activeHandle = 0L

    /** Starts [id]. False when it was cancelled before it began: it must not run. */
    fun begin(id: Int): Boolean = synchronized(lock) {
        if (cancelled.remove(id)) return false
        activeId = id
        activeHandle = 0L
        true
    }

    /**
     * Makes [handle] the target of [cancel] for the running request [id] and clears its flag (a flag left by an
     * earlier request must not end this one). False when [id] has been cancelled in the meantime.
     */
    fun attach(id: Int, speech: Boolean, handle: Long): Boolean = synchronized(lock) {
        check(id == activeId) { "request $id is not the running one" }
        setCancelFlag(speech, handle, false)
        activeSpeech = speech
        activeHandle = handle
        !cancelled.contains(id)
    }

    /** Cancels [id]: stops its native call if it is running, or marks it so that it never starts. */
    fun cancel(id: Int) = synchronized(lock) {
        cancelled.add(id)
        if (cancelled.size > MAX_REMEMBERED) cancelled.remove(cancelled.first())
        if (id == activeId && activeHandle != 0L) setCancelFlag(activeSpeech, activeHandle, true)
    }

    /** Request [id] is over: its handle may now be freed, and no cancel can reach it any more. */
    fun end(id: Int) = synchronized(lock) {
        if (id == activeId) {
            activeId = -1
            activeHandle = 0L
        }
        cancelled.remove(id)
        Unit
    }

    private companion object {
        /** Cancels for requests that never arrive are forgotten after this many. */
        const val MAX_REMEMBERED = 64
    }
}
