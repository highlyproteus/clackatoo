// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo.assist

/**
 * Cuts a live recording into utterances from the speech probabilities of the voice-activity detector (one per
 * [WINDOW] samples, 16 kHz). Pure logic, no Android: the model process feeds it and reads the finished segments.
 *
 * A window is speech when its probability is at least [threshold]. A speech window opens a segment [padSamples]
 * before it (never before the end of the previous segment, so segments never overlap). The segment closes
 * [padSamples] after the last speech window once [minSilenceWindows] silent windows in a row have passed, or at once
 * when it reaches [maxSegmentSamples], so the recogniser never gets more than that in one piece.
 *
 * Ranges are sample indices in the recording (`start until end`). All methods may be called from different threads.
 */
class LiveSegmenter(
    private val threshold: Float = 0.5f,
    private val padSamples: Int = 5_120,
    private val minSilenceWindows: Int = 19,
    private val maxSegmentSamples: Int = 240_000,
) {
    private var windows = 0L
    private var samplesSeen = 0
    private var start = -1
    private var lastSpeechEnd = 0
    private var silentRun = 0
    private var previousEnd = 0
    private var opened = false
    private val closed = ArrayDeque<IntRange>()

    /** One speech probability per [WINDOW] samples, in order. */
    @Synchronized
    fun feed(probs: FloatArray) {
        for (p in probs) {
            val w = windows++.toInt()
            samplesSeen = (w + 1) * WINDOW
            if (p >= threshold) {
                if (start < 0) {
                    start = maxOf(previousEnd, w * WINDOW - padSamples)
                    opened = true
                }
                lastSpeechEnd = samplesSeen
                silentRun = 0
            } else if (start >= 0) {
                silentRun++
                if (silentRun >= minSilenceWindows) closeAt(minOf(samplesSeen, lastSpeechEnd + padSamples))
            }
            if (start >= 0 && samplesSeen - start >= maxSegmentSamples) closeAt(samplesSeen)
        }
    }

    /** The recording has ended: closes the open segment the way a long silence would. */
    @Synchronized
    fun finish() {
        if (start >= 0) closeAt(minOf(samplesSeen, lastSpeechEnd + padSamples))
    }

    /** The segment that is still open, from its start to the end of the audio seen so far; null when there is none. */
    @Synchronized
    fun openSegment(): IntRange? = if (start >= 0) start until samplesSeen else null

    /** The oldest segment that has closed and was not returned yet, or null. */
    @Synchronized
    fun pollClosed(): IntRange? = closed.removeFirstOrNull()

    /** True once any segment was opened, whether or not it has closed. */
    @Synchronized
    fun hadSpeech(): Boolean = opened

    private fun closeAt(end: Int) {
        closed.addLast(start until end)
        previousEnd = end
        start = -1
        silentRun = 0
    }

    companion object {
        /** Samples per probability. */
        const val WINDOW = 512
    }
}
