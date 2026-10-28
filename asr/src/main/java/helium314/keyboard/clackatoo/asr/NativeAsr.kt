// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo.asr

/**
 * JNI access to NVIDIA Parakeet speech recognition (parakeet.cpp). Used only by the isolated model process.
 * A handle is opaque; [load] returns 0 when the model cannot be used (not a Parakeet GGUF, or a CPU without the
 * Arm dot-product instructions). Loading the class throws [UnsatisfiedLinkError] when the native library is missing.
 */
object NativeAsr {
    init {
        System.loadLibrary("c++_shared")
        System.loadLibrary("clackatoo_asr")
    }

    /** Takes over [fd] (detach it from its ParcelFileDescriptor first) and closes it, also when loading fails. 0: failed. */
    external fun load(fd: Int, threads: Int): Long
    external fun free(handle: Long)
    private external fun transcribeBytes(handle: Long, pcm: ShortArray, offset: Int, count: Int): ByteArray?

    /** Transcribes pcm[offset, offset + count), 16 kHz mono 16-bit samples. Null when recognition fails. */
    fun transcribe(handle: Long, pcm: ShortArray, offset: Int, count: Int): String? =
        transcribeBytes(handle, pcm, offset, count)?.let { String(it, Charsets.UTF_8).trim() }
}
