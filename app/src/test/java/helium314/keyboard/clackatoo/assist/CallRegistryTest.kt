// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo.assist

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CallRegistryTest {
    private data class Flag(val speech: Boolean, val handle: Long, val value: Boolean)

    private val flags = ArrayList<Flag>()
    private val registry = CallRegistry { speech, handle, value -> flags += Flag(speech, handle, value) }

    @Test fun `a cancel reaches the handle of the running request`() {
        assertTrue(registry.begin(1))
        assertTrue(registry.attach(1, speech = false, handle = 11))
        flags.clear()
        registry.cancel(1)
        assertEquals(listOf(Flag(false, 11, true)), flags)
    }

    @Test fun `attaching clears the flag left by an earlier request`() {
        registry.begin(1); registry.attach(1, true, 7); registry.cancel(1); registry.end(1)
        flags.clear()
        registry.begin(2)
        assertTrue(registry.attach(2, true, 7)) // the same model, so the same handle
        assertEquals(listOf(Flag(true, 7, false)), flags)
    }

    @Test fun `a cancel before the request has a handle is not lost`() {
        assertTrue(registry.begin(3))
        registry.cancel(3) // arrives while the model is still loading
        assertTrue(flags.isEmpty())
        assertFalse(registry.attach(3, false, 5))
    }

    @Test fun `a request cancelled before it starts never runs`() {
        registry.cancel(4)
        assertFalse(registry.begin(4))
        assertTrue(registry.begin(5)) // the next one is not affected
    }

    @Test fun `a cancel after the request ended never touches its handle`() {
        registry.begin(6); registry.attach(6, false, 9); registry.end(6)
        flags.clear()
        registry.cancel(6) // the handle may be freed by now
        assertTrue(flags.isEmpty())
    }

    @Test fun `a late cancel of an earlier request does not stop the next one`() {
        registry.begin(7); registry.attach(7, false, 1); registry.end(7)
        registry.begin(8); registry.attach(8, false, 1)
        flags.clear()
        registry.cancel(7)
        assertTrue(flags.isEmpty())
        assertTrue(registry.attach(8, false, 1)) // still not cancelled
        registry.cancel(8)
        assertEquals(listOf(Flag(false, 1, false), Flag(false, 1, true)), flags)
    }

    @Test fun `ending one request keeps the state of the running one`() {
        registry.begin(10); registry.attach(10, true, 2)
        registry.end(9) // a different, finished request
        flags.clear()
        registry.cancel(10)
        assertEquals(listOf(Flag(true, 2, true)), flags)
    }

    @Test fun `only the running request can attach`() {
        registry.begin(11)
        assertFailsWith<IllegalStateException> { registry.attach(12, false, 1) }
    }

    @Test fun `cancels for requests that never come are forgotten after a while`() {
        for (id in 1000 until 1200) registry.cancel(id)
        assertTrue(registry.begin(1000)) // forgotten
        assertFalse(registry.begin(1199)) // still remembered
    }

    @Test fun `concurrent cancels and attaches never touch a detached handle`() {
        // a worker attaches, runs, ends; another thread cancels at random: no flag may be set for a detached handle
        val live = HashSet<Long>()
        val violations = java.util.concurrent.atomic.AtomicInteger()
        val reg = CallRegistry { _, handle, value -> if (value && handle !in synchronizedLive(live)) violations.incrementAndGet() }
        val canceller = Thread { for (i in 1..20_000) reg.cancel(i / 10) }
        canceller.start()
        for (id in 1..2000) {
            if (!reg.begin(id)) continue
            synchronized(live) { live += id.toLong() }
            reg.attach(id, false, id.toLong())
            reg.end(id)
            synchronized(live) { live -= id.toLong() }
        }
        canceller.join()
        assertEquals(0, violations.get())
    }

    private fun synchronizedLive(live: Set<Long>): Set<Long> = synchronized(live) { HashSet(live) }
}
