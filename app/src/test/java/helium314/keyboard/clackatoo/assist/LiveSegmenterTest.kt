// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo.assist

import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveSegmenterTest {
    private val window = LiveSegmenter.WINDOW

    private fun LiveSegmenter.speech(windows: Int) = feed(FloatArray(windows) { 0.9f })
    private fun LiveSegmenter.silence(windows: Int) = feed(FloatArray(windows) { 0.1f })
    private fun LiveSegmenter.closed(): List<IntRange> = generateSequence { pollClosed() }.toList()

    @Test fun `silence only gives no segments`() {
        val s = LiveSegmenter()
        s.silence(200)
        assertNull(s.pollClosed())
        assertNull(s.openSegment())
        assertFalse(s.hadSpeech())
        s.finish()
        assertNull(s.pollClosed())
        assertFalse(s.hadSpeech())
    }

    @Test fun `speech then 19 silent windows closes one segment padded by 5120 samples on each side`() {
        val s = LiveSegmenter()
        s.silence(20)
        s.speech(20)
        s.silence(19)
        val closed = s.closed()
        assertEquals(listOf(20 * window - 5_120 until 40 * window + 5_120), closed)
        assertNull(s.openSegment())
        assertTrue(s.hadSpeech())
    }

    @Test fun `18 silent windows do not close the segment`() {
        val s = LiveSegmenter()
        s.silence(20)
        s.speech(20)
        s.silence(18)
        assertNull(s.pollClosed())
        val open = assertNotNull(s.openSegment())
        assertEquals(20 * window - 5_120, open.first)
        assertEquals(58 * window, open.last + 1) // up to everything seen so far
        // speech resumes: still the same segment
        s.speech(5)
        assertNull(s.pollClosed())
        assertEquals(20 * window - 5_120, s.openSegment()!!.first)
    }

    @Test fun `a segment never starts before the audio does`() {
        val s = LiveSegmenter()
        s.speech(3)
        assertEquals(0, s.openSegment()!!.first)
    }

    @Test fun `16 seconds of continuous speech are closed at the 240000 sample limit`() {
        val s = LiveSegmenter()
        s.speech(500) // 256,000 samples
        val closed = s.closed()
        assertTrue(closed.isNotEmpty())
        val first = closed.first()
        assertEquals(0, first.first)
        // the first window boundary at or after 240,000 samples
        assertEquals(469 * window, first.last + 1)
        assertTrue(first.last + 1 - first.first >= 240_000)
        assertTrue(first.last + 1 - first.first < 240_000 + window)
        // what is left of the 16 s is a new segment that starts where the first ended
        assertEquals(first.last + 1, s.openSegment()!!.first)
    }

    @Test fun `finish closes an open segment the way a silence would`() {
        val s = LiveSegmenter()
        s.silence(10)
        s.speech(10)
        s.silence(3)
        s.finish()
        // the padded end lies after the audio that exists, so it is cut there
        assertEquals(listOf(10 * window - 5_120 until 23 * window), s.closed())
        assertNull(s.openSegment())
        s.finish()
        assertNull(s.pollClosed())
    }

    @Test fun `finish pads the end by 5120 samples when the audio goes on that long`() {
        val s = LiveSegmenter()
        s.silence(20)
        s.speech(10)
        s.silence(15) // not enough to close by itself
        s.finish()
        assertEquals(listOf(20 * window - 5_120 until 30 * window + 5_120), s.closed())
    }

    @Test fun `segments never overlap, whatever the probabilities`() {
        val random = Random(42)
        repeat(50) { round ->
            val s = LiveSegmenter()
            val ranges = ArrayList<IntRange>()
            // streaks of speech and silence of random length, fed in random batch sizes
            var speaking = random.nextBoolean()
            repeat(40) {
                val windows = 1 + random.nextInt(if (speaking) 700 else 60)
                var left = windows
                while (left > 0) {
                    val n = minOf(left, 1 + random.nextInt(6))
                    s.feed(FloatArray(n) { if (speaking) 0.8f else 0.2f })
                    left -= n
                    ranges += s.closed()
                }
                speaking = !speaking
            }
            s.finish()
            ranges += s.closed()
            assertTrue(ranges.isNotEmpty(), "round $round")
            ranges.forEach { assertTrue(it.last >= it.first, "round $round: $it is empty") }
            ranges.zipWithNext().forEach { (a, b) -> assertTrue(b.first > a.last, "round $round: $a and $b overlap") }
        }
    }

    @Test fun `a probability exactly at the threshold is speech`() {
        val s = LiveSegmenter(threshold = 0.5f)
        s.feed(floatArrayOf(0.5f))
        assertTrue(s.hadSpeech())
    }
}
