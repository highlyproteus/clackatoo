// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo.assist

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * One live dictation inside the model process: reads 16 kHz mono 16-bit little-endian audio from [input] while the
 * user is still speaking, finds utterances with the voice-activity detector and the [LiveSegmenter], and writes what
 * the speech recogniser makes of them to [output] as event frames ([MlProtocol.EVENT_PARTIAL] for the utterance that
 * is still being spoken, [MlProtocol.EVENT_SEGMENT] for a finished one, then [MlProtocol.EVENT_END]).
 *
 * The audio only ever lives in memory here, capped at [MlProtocol.MAX_SAMPLES]. The native calls are passed in so the
 * logic can be tested without the native libraries. [run] returns when the input has ended and everything is decoded,
 * and throws [Cancelled] after [cancel], [Failed] when a native call fails or [output] is gone.
 *
 * [cancel] may be called from any thread: the reader stops at its next read, the decoder after the recognition that
 * is running (there is no way into the native call). A reader still blocked in a read when [run] returns leaves the
 * detector alone, so the caller may reuse it.
 */
class LiveSession(
    private val input: InputStream,
    private val output: OutputStream,
    /** pcm[offset, offset + count) -> one speech probability per 512 samples; null when detection failed. */
    private val vadFeed: (pcm: ShortArray, offset: Int, count: Int) -> FloatArray?,
    private val vadReset: () -> Unit,
    /** pcm[offset, offset + count) -> text; null when recognition failed. */
    private val transcribe: (pcm: ShortArray, offset: Int, count: Int) -> String?,
    private val segmenter: LiveSegmenter = LiveSegmenter(),
    private val nanoTime: () -> Long = System::nanoTime,
) {
    class Cancelled : Exception()
    class Failed(message: String) : Exception(message)

    /** [tailMs]: from the end of the audio to the last event, which is what the user waits for after tapping Stop. */
    class Timing(val tailMs: Long, val firstPartialMs: Long, val decodeMs: Long)

    private val monitor = Object()
    private var changed = false
    @Volatile private var cancelled = false
    private var pcm = ShortArray(INITIAL_SAMPLES)
    private var count = 0
    private var inputEnded = false
    private var inputEndedAt = 0L
    private var readerError: String? = null
    private var reader: Thread? = null

    fun cancel() {
        cancelled = true
        signal()
    }

    private fun signal() = synchronized(monitor) {
        changed = true
        monitor.notifyAll()
    }

    fun run(): Timing {
        val startedAt = nanoTime()
        vadReset() // nothing of an earlier recording may leak into this one
        val thread = Thread({ readLoop() }, "clackatoo-live-audio")
        reader = thread
        thread.start()
        var firstPartialMs = -1L
        var decodeNs = 0L
        var committedBytes = 0
        var partialStart = -1
        var partialSamples = 0
        try {
            while (true) {
                // read first: once the reader reports the end, finish() has run and every closed segment is queued
                val ended = synchronized(monitor) { changed = false; inputEnded }
                check()
                val closedRange = segmenter.pollClosed()
                if (closedRange != null) {
                    val t0 = nanoTime()
                    val text = decode(closedRange)
                    decodeNs += nanoTime() - t0
                    val bytes = cut(text.toByteArray(Charsets.UTF_8), minOf(MlProtocol.MAX_EVENT_BYTES, MlProtocol.MAX_RESULT_BYTES - committedBytes))
                    committedBytes += bytes.size
                    writeEvent(MlProtocol.EVENT_SEGMENT, bytes)
                    partialStart = -1
                    continue
                }
                val open = segmenter.openSegment()
                if (open != null) {
                    if (open.first != partialStart) {
                        partialStart = open.first
                        partialSamples = 0
                    }
                    val length = open.last + 1 - open.first
                    if (length >= MIN_PARTIAL_SAMPLES && length - partialSamples >= MIN_PARTIAL_SAMPLES) {
                        val t0 = nanoTime()
                        val text = decode(open)
                        decodeNs += nanoTime() - t0
                        partialSamples = length
                        writeEvent(MlProtocol.EVENT_PARTIAL, cut(text.toByteArray(Charsets.UTF_8), MlProtocol.MAX_EVENT_BYTES))
                        if (firstPartialMs < 0) firstPartialMs = (nanoTime() - startedAt) / NS_PER_MS
                        continue
                    }
                }
                if (ended) {
                    val error = synchronized(monitor) { readerError }
                    if (error != null) throw Failed(error)
                    writeEvent(MlProtocol.EVENT_END, ByteArray(0))
                    val endedAt = nanoTime()
                    return Timing((endedAt - synchronized(monitor) { inputEndedAt }) / NS_PER_MS, firstPartialMs, decodeNs / NS_PER_MS)
                }
                synchronized(monitor) {
                    while (!changed && !cancelled) monitor.wait()
                }
            }
        } finally {
            cancelled = true // from here on the reader must not touch the detector
            runCatching { input.close() }
            runCatching { output.close() }
            reader?.join(READER_JOIN_MS)
        }
    }

    private fun check() {
        if (cancelled) throw Cancelled()
    }

    /** Recognises [range] (clamped to the audio that exists); "" for a range with no audio. */
    private fun decode(range: IntRange): String {
        val (data, available) = synchronized(monitor) { pcm to count }
        val start = range.first
        val end = minOf(range.last + 1, available)
        if (end - start <= 0) return ""
        return transcribe(data, start, end - start) ?: throw Failed("speech recognition failed")
    }

    private fun writeEvent(type: Int, payload: ByteArray) {
        check()
        val frame = ByteArray(5 + payload.size)
        frame[0] = type.toByte()
        frame[1] = (payload.size ushr 24).toByte()
        frame[2] = (payload.size ushr 16).toByte()
        frame[3] = (payload.size ushr 8).toByte()
        frame[4] = payload.size.toByte()
        payload.copyInto(frame, 5)
        try {
            output.write(frame)
            output.flush()
        } catch (_: IOException) {
            throw Failed("the event pipe is closed")
        }
    }

    private fun readLoop() {
        var failure: String? = null
        try {
            read()
        } catch (_: IOException) {
            // the keyboard closed its end: whatever arrived is all there is
        } catch (e: Exception) {
            failure = e.message ?: e.javaClass.simpleName
        }
        if (cancelled) return
        try {
            if (failure == null) finishAudio()
            vadReset()
        } catch (e: Exception) {
            failure = failure ?: (e.message ?: e.javaClass.simpleName)
        }
        segmenter.finish()
        synchronized(monitor) {
            readerError = failure
            inputEnded = true
            inputEndedAt = nanoTime()
            changed = true
            monitor.notifyAll()
        }
    }

    /** Samples handed to the detector so far (only the reader thread uses it). */
    private var fed = 0

    private fun read() {
        val bytes = ByteArray(READ_BYTES)
        var carry = 0 // 0 or 1 leftover byte at the start of [bytes]
        while (!cancelled) {
            val n = input.read(bytes, carry, bytes.size - carry)
            if (n < 0) return
            if (cancelled) return
            val total = carry + n
            val samples = total / 2
            synchronized(monitor) {
                if (count + samples > pcm.size) pcm = pcm.copyOf(minOf(maxOf(pcm.size * 2, count + samples), MlProtocol.MAX_SAMPLES))
                val take = minOf(samples, MlProtocol.MAX_SAMPLES - count)
                for (i in 0 until take) pcm[count + i] = ((bytes[2 * i].toInt() and 0xff) or (bytes[2 * i + 1].toInt() shl 8)).toShort()
                count += take
            }
            carry = total - 2 * samples
            if (carry == 1) bytes[0] = bytes[total - 1]
            while (count - fed >= VAD_CHUNK) {
                if (cancelled) return
                feedDetector(fed, VAD_CHUNK, pcm)
                fed += VAD_CHUNK
                signal()
            }
            if (count >= MlProtocol.MAX_SAMPLES) return
        }
    }

    private fun feedDetector(offset: Int, length: Int, data: ShortArray) {
        val probs = vadFeed(data, offset, length) ?: throw IllegalStateException("voice detection failed")
        segmenter.feed(probs)
    }

    /** The last samples, padded with silence up to whole detector windows. */
    private fun finishAudio() {
        val rest = count - fed
        if (rest <= 0 || cancelled) return
        val padded = ShortArray((rest + LiveSegmenter.WINDOW - 1) / LiveSegmenter.WINDOW * LiveSegmenter.WINDOW)
        System.arraycopy(pcm, fed, padded, 0, rest)
        fed = count
        feedDetector(0, padded.size, padded)
    }

    companion object {
        const val MIN_PARTIAL_SAMPLES = 8_000
        private const val INITIAL_SAMPLES = MlProtocol.SAMPLE_RATE * 30
        /** Three detector windows per call, about 96 ms of audio. */
        const val VAD_CHUNK = 3 * LiveSegmenter.WINDOW
        private const val READ_BYTES = 8192
        private const val READER_JOIN_MS = 2_000L
        private const val NS_PER_MS = 1_000_000L

        /** [bytes] cut to at most [max] bytes, not in the middle of a UTF-8 character. */
        fun cut(bytes: ByteArray, max: Int): ByteArray {
            if (max <= 0) return ByteArray(0)
            if (bytes.size <= max) return bytes
            var end = max
            while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end-- // bytes[end] is a continuation byte: back up to its lead byte
            return bytes.copyOf(end)
        }
    }
}
