// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo.ml

/**
 * JNI access to whisper.cpp (speech to text) and llama.cpp (text generation). Handles are opaque; every call that
 * can fail throws [IllegalStateException]. Text crosses JNI as UTF-8 bytes.
 *
 * Models are loaded from file descriptors, never from paths: the model process is an isolated process that cannot
 * open the app's files. [llmLoad] and [sttLoad] take over the descriptor (detach it from its ParcelFileDescriptor
 * first) and close it, also when loading fails.
 *
 * Cancelling: [llmCancel] and [sttCancel] set a flag that the running call polls; the flag is NOT reset by a new
 * call, so the caller resets it (cancel = false) before starting one. A cancelled generate/transcribe returns null.
 * The caller must not free a handle while a cancel can still reach it.
 */
object NativeMl {
    /** False when the native library cannot be loaded (for example on 32-bit or x86 builds). */
    val isAvailable: Boolean = try {
        System.loadLibrary("c++_shared")
        System.loadLibrary("clackatoo_ml")
        true
    } catch (_: UnsatisfiedLinkError) {
        false
    }

    /** Loads the ggml CPU backends from [nativeLibDir]; call once before loading a model. Returns the number of devices found. */
    external fun init(nativeLibDir: String): Int
    external fun systemInfo(): String

    external fun llmLoad(fd: Int, nCtx: Int, threads: Int): Long
    /** 1 when the model was memory-mapped from its descriptor, 0 when it was read into memory. */
    external fun llmLoadMode(handle: Long): Int
    external fun llmFree(handle: Long)
    external fun llmCancel(handle: Long, cancel: Boolean)
    /** Greedy decoding of [parts] (UTF-8); a part with its [special] flag set may contain control tokens, the others never produce any. Null: cancelled. */
    private external fun llmGenerateParts(handle: Long, parts: Array<ByteArray>, special: BooleanArray, maxNewTokens: Int): ByteArray?

    external fun sttLoad(fd: Int, threads: Int): Long
    external fun sttFree(handle: Long)
    external fun sttCancel(handle: Long, cancel: Boolean)
    private external fun sttTranscribeBytes(handle: Long, pcm16: ShortArray, language: String): ByteArray?

    /** Null when cancelled. */
    fun llmGenerate(handle: Long, texts: List<String>, special: List<Boolean>, maxNewTokens: Int): String? {
        require(texts.size == special.size)
        val bytes = llmGenerateParts(handle, Array(texts.size) { texts[it].toByteArray(Charsets.UTF_8) }, special.toBooleanArray(), maxNewTokens)
        return bytes?.let { String(it, Charsets.UTF_8) }
    }

    /** [pcm16]: 16 kHz mono 16-bit samples. [language]: a Whisper code such as "en". Null when cancelled. */
    fun sttTranscribe(handle: Long, pcm16: ShortArray, language: String): String? =
        sttTranscribeBytes(handle, pcm16, language)?.let { String(it, Charsets.UTF_8).trim() }
}
