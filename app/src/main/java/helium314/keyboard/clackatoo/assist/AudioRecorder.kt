// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo.assist

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.sqrt

/** What the controller needs from a recorder (and what tests replace). */
interface VoiceRecorder {
    /** Starts recording; false when the microphone cannot be opened. */
    fun start(): Boolean
    /** Stops recording and returns the samples recorded so far. */
    fun stop(): ShortArray
    /** Stops recording and drops everything recorded. */
    fun cancel()
}

/**
 * Records 16 kHz mono audio into memory. Nothing is ever written to a file. Recording runs only between [start] and
 * [stop] / [cancel]; the caller (the voice panel) makes sure that is only while the panel is on screen.
 */
class AudioRecorder(
    private val onLevel: (Float) -> Unit,
    private val onFull: () -> Unit,
    /** Called on the recording thread with every block just read (samples [0, count)); the receiver must copy what it keeps. */
    private val onChunk: ((ShortArray, Int) -> Unit)? = null,
) : VoiceRecorder {
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    private var buffer = ShortArray(0)
    private var length = 0

    val isRecording get() = running

    /** Starts recording; false when the microphone cannot be opened (no permission, or in use). */
    @SuppressLint("MissingPermission") // the caller checks RECORD_AUDIO before calling
    override fun start(): Boolean {
        if (running) return true
        val minBuffer = AudioRecord.getMinBufferSize(MlProtocol.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) return false
        val chunk = MlProtocol.SAMPLE_RATE / 10 // 100 ms per level update
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, MlProtocol.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, chunk * 4),
            )
        } catch (_: Exception) {
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return false
        }
        try {
            rec.startRecording()
        } catch (_: IllegalStateException) {
            rec.release()
            return false
        }
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            rec.release()
            return false
        }
        buffer = ShortArray(MlProtocol.SAMPLE_RATE * 30) // grows up to MAX_SAMPLES
        length = 0
        record = rec
        running = true
        thread = Thread({ readLoop(rec, chunk) }, "clackatoo-audio").also { it.start() }
        return true
    }

    private fun readLoop(rec: AudioRecord, chunk: Int) {
        val temp = ShortArray(chunk)
        while (running) {
            val n = rec.read(temp, 0, chunk)
            if (n <= 0) {
                if (n < 0) break // error or stopped
                continue
            }
            var sum = 0.0
            for (i in 0 until n) sum += temp[i].toDouble() * temp[i]
            onLevel(levelOf(sqrt(sum / n)))
            onChunk?.invoke(temp, n)
            synchronized(this) {
                if (length + n > buffer.size) {
                    if (buffer.size >= MlProtocol.MAX_SAMPLES) {
                        running = false
                    } else {
                        buffer = buffer.copyOf(minOf(buffer.size * 2, MlProtocol.MAX_SAMPLES))
                    }
                }
                if (running) {
                    val take = minOf(n, buffer.size - length)
                    System.arraycopy(temp, 0, buffer, length, take)
                    length += take
                    if (length >= MlProtocol.MAX_SAMPLES) running = false
                }
            }
        }
        if (length >= MlProtocol.MAX_SAMPLES) onFull()
    }

    /** Stops recording and returns the samples recorded so far. */
    override fun stop(): ShortArray {
        halt()
        return synchronized(this) { buffer.copyOf(length) }.also { clear() }
    }

    /** Stops recording and drops everything recorded. */
    override fun cancel() {
        halt()
        clear()
    }

    private fun halt() {
        running = false
        thread?.join(500)
        thread = null
        record?.let {
            try { it.stop() } catch (_: IllegalStateException) { }
            it.release()
        }
        record = null
    }

    private fun clear() = synchronized(this) {
        buffer = ShortArray(0)
        length = 0
    }

    companion object {
        /** Maps the RMS of 16-bit samples to 0..1 on a logarithmic scale (speech sits around 0.4..0.9). */
        fun levelOf(rms: Double): Float {
            if (rms <= 1.0) return 0f
            val db = 20 * kotlin.math.log10(rms / 32768.0) // -90 .. 0
            return ((db + 60) / 50).coerceIn(0.0, 1.0).toFloat()
        }

        /** True when the recording is so quiet that there is nothing to transcribe. */
        fun isSilent(pcm: ShortArray): Boolean {
            if (pcm.size < MlProtocol.SAMPLE_RATE / 4) return true // under a quarter of a second
            var peak = 0
            for (s in pcm) peak = maxOf(peak, kotlin.math.abs(s.toInt()))
            return peak < 300
        }
    }
}

/**
 * Debug builds' stand-in for the microphone (see [DebugWavReceiver]): plays [pcm] into the callbacks in blocks of
 * [BLOCK] samples, one block every 100 ms like [AudioRecorder], and calls [onFull] when it is all sent (which the
 * controller treats as a press on Stop).
 */
internal class ScriptedRecorder(
    private val pcm: ShortArray,
    private val onLevel: (Float) -> Unit,
    private val onFull: () -> Unit,
    private val onChunk: ((ShortArray, Int) -> Unit)?,
) : VoiceRecorder {
    @Volatile private var running = false
    @Volatile private var sent = 0
    private var thread: Thread? = null

    override fun start(): Boolean {
        running = true
        thread = Thread({
            val block = ShortArray(BLOCK)
            var offset = 0
            while (running && offset < pcm.size) {
                val n = minOf(BLOCK, pcm.size - offset)
                System.arraycopy(pcm, offset, block, 0, n)
                var sum = 0.0
                for (i in 0 until n) sum += block[i].toDouble() * block[i]
                onLevel(AudioRecorder.levelOf(kotlin.math.sqrt(sum / n)))
                onChunk?.invoke(block, n)
                offset += n
                sent = offset
                try { Thread.sleep(100) } catch (_: InterruptedException) { return@Thread }
            }
            if (running) onFull()
        }, "clackatoo-debug-audio").also { it.start() }
        return true
    }

    override fun stop(): ShortArray {
        running = false
        thread?.join(500)
        return pcm.copyOf(sent)
    }

    override fun cancel() {
        running = false
        thread?.interrupt()
    }

    private companion object {
        const val BLOCK = 1_600
    }
}
