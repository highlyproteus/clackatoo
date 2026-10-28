// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo.assist

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LiveSessionTest {
    /** 16 kHz samples: [silence] then a loud tone, as little-endian bytes. */
    private fun audio(vararg parts: Pair<Boolean, Int>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((loud, samples) in parts) repeat(samples) { i ->
            val v = if (loud) (if (i % 2 == 0) 8000 else -8000) else 0
            out.write(v and 0xff)
            out.write((v shr 8) and 0xff)
        }
        return out.toByteArray()
    }

    /** Speech wherever the samples are loud. */
    private val vadFeed: (ShortArray, Int, Int) -> FloatArray? = { pcm, offset, count ->
        FloatArray(count / LiveSegmenter.WINDOW) { w ->
            var peak = 0
            for (i in 0 until LiveSegmenter.WINDOW) peak = maxOf(peak, kotlin.math.abs(pcm[offset + w * LiveSegmenter.WINDOW + i].toInt()))
            if (peak > 1000) 0.9f else 0.05f
        }
    }

    /** An input that blocks until chunks are added; closing it ends the stream. */
    private class QueueInput : InputStream() {
        private val queue = LinkedBlockingQueue<ByteArray>()
        private var current = ByteArray(0)
        private var position = 0
        @Volatile private var closed = false
        fun add(bytes: ByteArray) = queue.put(bytes)
        override fun read(): Int = throw UnsupportedOperationException()
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            while (position >= current.size) {
                current = queue.poll(20, java.util.concurrent.TimeUnit.MILLISECONDS) ?: if (closed) return -1 else continue
                position = 0
            }
            val n = minOf(len, current.size - position)
            System.arraycopy(current, position, b, off, n)
            position += n
            return n
        }
        override fun close() { closed = true }
    }

    private fun frames(bytes: ByteArray): List<LiveFrames.Frame> {
        val input = ByteArrayInputStream(bytes)
        return generateSequence { LiveFrames.read(input) }.toList()
    }

    private fun session(input: InputStream, output: OutputStream, transcribe: (ShortArray, Int, Int) -> String?) =
        LiveSession(input, output, vadFeed, vadReset = {}, transcribe = transcribe)

    @Test fun `two utterances give two segments and an end`() {
        val bytes = audio(false to 8000, true to 32000, false to 24000, true to 16000, false to 4000)
        val out = ByteArrayOutputStream()
        val seen = ArrayList<Pair<Int, Int>>()
        session(ByteArrayInputStream(bytes), out) { _, offset, count ->
            synchronized(seen) { seen += offset to count }
            "text $count"
        }.run()
        val all = frames(out.toByteArray())
        assertIs<LiveFrames.Frame.End>(all.last())
        assertEquals(1, all.count { it is LiveFrames.Frame.End })
        val segments = all.filterIsInstance<LiveFrames.Frame.Segment>()
        assertEquals(2, segments.size)
        // everything sent to the recogniser lies inside the audio, and the two segments do not overlap
        val closedRanges = synchronized(seen) { seen.toList() }
        closedRanges.forEach { (offset, count) -> assertTrue(offset >= 0 && count > 0 && offset + count <= bytes.size / 2) }
        assertTrue(segments[0].text.startsWith("text "))
    }

    @Test fun `a long utterance shows partial text before it is finished`() {
        val bytes = audio(false to 4000, true to 48000) // 3 s of speech, no silence after
        val out = ByteArrayOutputStream()
        // the audio arrives at about 10 times real time, so the recogniser sees an utterance that is still open
        val input = QueueInput()
        val feeder = Thread {
            bytes.toList().chunked(3200).forEach { input.add(it.toByteArray()); Thread.sleep(10) }
            input.close()
        }
        feeder.start()
        session(input, out) { _, _, count -> "n$count" }.run()
        feeder.join()
        val all = frames(out.toByteArray())
        assertTrue(all.any { it is LiveFrames.Frame.Partial }, "no partial: ${all.map { it.javaClass.simpleName }}")
        assertEquals(1, all.count { it is LiveFrames.Frame.Segment })
        assertIs<LiveFrames.Frame.End>(all.last())
        // the partials come before the segment
        assertTrue(all.indexOfFirst { it is LiveFrames.Frame.Partial } < all.indexOfFirst { it is LiveFrames.Frame.Segment })
    }

    @Test fun `silence gives no segment and still ends`() {
        val out = ByteArrayOutputStream()
        session(ByteArrayInputStream(audio(false to 32000)), out) { _, _, _ -> error("nothing to recognise") }.run()
        assertEquals(listOf(LiveFrames.Frame.End), frames(out.toByteArray()))
    }

    @Test fun `an event text over the limit is cut on a character boundary`() {
        val long = "é".repeat(MlProtocol.MAX_EVENT_BYTES) // 2 bytes each
        val out = ByteArrayOutputStream()
        session(ByteArrayInputStream(audio(true to 16000, false to 20000)), out) { _, _, _ -> long }.run()
        val segment = frames(out.toByteArray()).filterIsInstance<LiveFrames.Frame.Segment>().single()
        assertEquals(MlProtocol.MAX_EVENT_BYTES, segment.size)
        assertEquals("é".repeat(MlProtocol.MAX_EVENT_BYTES / 2), segment.text)
    }

    @Test fun `a failing recogniser fails the session`() {
        val out = ByteArrayOutputStream()
        assertFailsWith<LiveSession.Failed> {
            session(ByteArrayInputStream(audio(true to 16000, false to 20000)), out) { _, _, _ -> null }.run()
        }
    }

    @Test fun `a failing detector fails the session instead of hanging`() {
        val s = LiveSession(ByteArrayInputStream(audio(true to 16000)), ByteArrayOutputStream(), { _, _, _ -> null }, {}, { _, _, _ -> "x" })
        assertFailsWith<LiveSession.Failed> { s.run() }
    }

    @Test fun `a closed event pipe fails the session`() {
        val closed = object : OutputStream() {
            override fun write(b: Int) = throw IOException("closed")
        }
        assertFailsWith<LiveSession.Failed> {
            session(ByteArrayInputStream(audio(true to 16000, false to 20000)), closed) { _, _, _ -> "x" }.run()
        }
    }

    @Test fun `cancel ends a session that waits for audio`() {
        val input = QueueInput()
        input.add(audio(true to 4000))
        val s = session(input, ByteArrayOutputStream()) { _, _, _ -> "x" }
        var failure: Throwable? = null
        val thread = Thread { try { s.run() } catch (e: Throwable) { failure = e } }
        thread.start()
        Thread.sleep(200)
        s.cancel()
        thread.join(5000)
        assertTrue(!thread.isAlive)
        assertIs<LiveSession.Cancelled>(failure)
    }

    @Test fun `a reader that stays blocked after cancel is reported, so the detector is not reused`() {
        val release = java.util.concurrent.CountDownLatch(1)
        val stuck = object : InputStream() {
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(b: ByteArray, off: Int, len: Int): Int { release.await(); return -1 } // close() does not wake it
        }
        val s = LiveSession(stuck, ByteArrayOutputStream(), vadFeed, {}, { _, _, _ -> "x" }, readerJoinMs = 100)
        var failure: Throwable? = null
        val thread = Thread { try { s.run() } catch (e: Throwable) { failure = e } }
        thread.start()
        Thread.sleep(200)
        s.cancel()
        thread.join(5000)
        assertIs<LiveSession.Cancelled>(failure)
        assertTrue(s.readerStillRunning)
        release.countDown()
    }

    @Test fun `a reader that ends in time leaves the detector free`() {
        val s = session(ByteArrayInputStream(audio(true to 4000)), ByteArrayOutputStream()) { _, _, _ -> "x" }
        s.run()
        assertEquals(false, s.readerStillRunning)
    }

    @Test fun `the detector is reset at the start and at the end of a recording`() {
        var resets = 0
        val s = LiveSession(ByteArrayInputStream(audio(true to 4000)), ByteArrayOutputStream(), vadFeed, { resets++ }, { _, _, _ -> "x" })
        s.run()
        assertEquals(2, resets)
    }

    @Test fun `the recorded audio is read as little-endian samples`() {
        val samples = ShortArray(2000) { (it * 31 - 1000).toShort() }
        val bytes = ByteArray(samples.size * 2) { i -> (samples[i / 2].toInt() shr (8 * (i % 2))).toByte() }
        var got: ShortArray? = null
        val s = LiveSession(ByteArrayInputStream(bytes), ByteArrayOutputStream(), { _, _, count -> FloatArray(count / 512) { 0.9f } }, {}, { pcm, offset, count ->
            got = pcm.copyOfRange(offset, offset + count)
            "x"
        })
        s.run()
        assertContentEquals(samples, got)
    }

    @Test fun `cut keeps whole characters`() {
        val text = "aé😀".toByteArray(Charsets.UTF_8) // 1 + 2 + 4 bytes
        assertEquals("aé😀", String(LiveSession.cut(text, 7), Charsets.UTF_8))
        assertEquals("aé", String(LiveSession.cut(text, 6), Charsets.UTF_8))
        assertEquals("aé", String(LiveSession.cut(text, 4), Charsets.UTF_8))
        assertEquals("a", String(LiveSession.cut(text, 2), Charsets.UTF_8))
        assertEquals("", String(LiveSession.cut(text, 0), Charsets.UTF_8))
    }
}
