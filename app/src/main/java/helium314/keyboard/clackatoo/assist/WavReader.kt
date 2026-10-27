// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo.assist

/** Reads 16 kHz mono 16-bit PCM WAV files (used by the debug entry point). */
object WavReader {
    class WavException(message: String) : Exception(message)

    /** The samples of [bytes]; throws [WavException] when it is not a 16 kHz mono 16-bit PCM WAV. */
    fun read(bytes: ByteArray): ShortArray {
        if (bytes.size < 12 || String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF" || String(bytes, 8, 4, Charsets.US_ASCII) != "WAVE")
            throw WavException("not a WAV file")
        var offset = 12L // chunk sizes come from the file: add them as Long, an Int sum can overflow
        var formatOk = false
        while (offset + 8 <= bytes.size) {
            val at = offset.toInt()
            val id = String(bytes, at, 4, Charsets.US_ASCII)
            val size = le32(bytes, at + 4)
            val body = at + 8
            if (size < 0) throw WavException("bad chunk size")
            when (id) {
                "fmt " -> {
                    if (size < 16 || body + 16 > bytes.size) throw WavException("bad format chunk")
                    val tag = le16(bytes, body)
                    val channels = le16(bytes, body + 2)
                    val rate = le32(bytes, body + 4)
                    val bits = le16(bytes, body + 14)
                    if (tag != 1 || channels != 1 || rate != MlProtocol.SAMPLE_RATE || bits != 16)
                        throw WavException("needs 16 kHz mono 16-bit PCM")
                    formatOk = true
                }
                "data" -> {
                    if (!formatOk) throw WavException("data before format")
                    val end = minOf(bytes.size.toLong(), body.toLong() + size).toInt()
                    val n = (end - body) / 2
                    if (n > MlProtocol.MAX_SAMPLES) throw WavException("too long")
                    return ShortArray(n) { i -> le16(bytes, body + 2 * i).toShort() }
                }
            }
            offset = body.toLong() + size + (size and 1)
        }
        throw WavException("no audio data")
    }

    private fun le16(b: ByteArray, o: Int) = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)
    private fun le32(b: ByteArray, o: Int) = le16(b, o) or (le16(b, o + 2) shl 16)
}
