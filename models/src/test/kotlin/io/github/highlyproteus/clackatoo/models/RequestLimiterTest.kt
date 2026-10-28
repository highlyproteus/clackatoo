// SPDX-License-Identifier: GPL-3.0-only

package io.github.highlyproteus.clackatoo.models

import io.github.highlyproteus.clackatoo.models.RequestLimiter.Verdict
import kotlin.test.Test
import kotlin.test.assertEquals

class RequestLimiterTest {
    private var t = 1_000_000L
    private val limiter = RequestLimiter(now = { t })

    @Test fun `a running download is not started again`() {
        assertEquals(Verdict.START, limiter.tryStart("a"))
        assertEquals(Verdict.ALREADY, limiter.tryStart("a"))
        assertEquals(Verdict.START, limiter.tryStart("b"))
    }

    @Test fun `after a cancel or a failure the id waits for the cool-down, then may start`() {
        limiter.tryStart("a")
        limiter.failed("a")
        assertEquals(Verdict.LATER, limiter.tryStart("a"))
        t += RequestLimiter.COOLDOWN_MS - 1
        assertEquals(Verdict.LATER, limiter.tryStart("a"))
        t += 1
        assertEquals(Verdict.START, limiter.tryStart("a"))
        // another id was never affected
        assertEquals(Verdict.START, limiter.tryStart("b"))
    }

    @Test fun `a handed-over model is not fetched again right away`() {
        limiter.tryStart("a")
        limiter.handedOver("a")
        assertEquals(Verdict.LATER, limiter.tryStart("a"))
        t += RequestLimiter.HANDED_OVER_MS
        assertEquals(Verdict.START, limiter.tryStart("a"))
    }

    @Test fun `released keeps the cool-down that failed set`() {
        limiter.tryStart("a")
        limiter.failed("a")
        limiter.released("a")
        assertEquals(Verdict.LATER, limiter.tryStart("a"))
        limiter.tryStart("b")
        limiter.released("b") // ended some other way: free to start again
        assertEquals(Verdict.START, limiter.tryStart("b"))
    }

    @Test fun `there is a cap on new downloads per hour that slides`() {
        val ids = (1..RequestLimiter.MAX_PER_HOUR).map { "m$it" }
        for (id in ids) {
            assertEquals(Verdict.START, limiter.tryStart(id))
            limiter.released(id)
            t += 1_000
        }
        assertEquals(Verdict.LATER, limiter.tryStart("one-more"))
        t += 3_600_000L - RequestLimiter.MAX_PER_HOUR * 1_000L // the first start is an hour old
        assertEquals(Verdict.START, limiter.tryStart("one-more"))
        assertEquals(Verdict.LATER, limiter.tryStart("and-another")) // the second is still inside the hour, and the cap is full again
    }

    @Test fun `a real user never meets the limits`() {
        // five different models in a row, one retry of a failed one after a minute
        for (id in listOf("a", "b", "c", "d", "e")) {
            assertEquals(Verdict.START, limiter.tryStart(id))
            limiter.handedOver(id)
        }
        limiter.tryStart("f"); limiter.failed("f")
        t += 60_000
        assertEquals(Verdict.START, limiter.tryStart("f"))
    }
}
