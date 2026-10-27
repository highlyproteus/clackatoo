// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo.assist

import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AssistLanguagesTest {
    @Test fun `the keyboard language picks the language and ignores region`() {
        assertEquals(AssistLanguages.ENGLISH, AssistLanguages.forLocale(Locale("en", "US")))
        assertEquals(AssistLanguages.ENGLISH, AssistLanguages.forLocale(Locale.UK))
        assertEquals(AssistLanguages.SPANISH, AssistLanguages.forLocale(Locale("es", "MX")))
        assertEquals(AssistLanguages.SPANISH, AssistLanguages.forLocale(Locale("es")))
    }

    @Test fun `other languages are not supported yet`() {
        assertNull(AssistLanguages.forLocale(Locale.FRENCH))
        assertNull(AssistLanguages.forLocale(null))
    }

    @Test fun `only English is cleaned up with S1-mini`() {
        assertTrue(AssistLanguages.ENGLISH.cleanUpWithS1Mini)
        assertFalse(AssistLanguages.SPANISH.cleanUpWithS1Mini)
        assertEquals("es", AssistLanguages.SPANISH.whisperCode)
    }

    @Test fun `language codes are unique`() {
        assertEquals(AssistLanguages.all.size, AssistLanguages.all.map { it.code }.toSet().size)
    }
}

class WavReaderTest {
    private fun le(v: Int, bytes: Int) = ByteArray(bytes) { (v shr (8 * it)).toByte() }

    private fun wav(rate: Int = 16000, channels: Int = 1, bits: Int = 16, samples: ShortArray = shortArrayOf(1, -2, 300), extra: ByteArray = ByteArray(0)): ByteArray {
        val out = ByteArrayOutputStream()
        val data = ByteArray(samples.size * 2) { i -> (samples[i / 2].toInt() shr (8 * (i % 2))).toByte() }
        out.write("RIFF".toByteArray()); out.write(le(36 + data.size + extra.size, 4)); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); out.write(le(16, 4)); out.write(le(1, 2)); out.write(le(channels, 2))
        out.write(le(rate, 4)); out.write(le(rate * channels * bits / 8, 4)); out.write(le(channels * bits / 8, 2)); out.write(le(bits, 2))
        out.write(extra)
        out.write("data".toByteArray()); out.write(le(data.size, 4)); out.write(data)
        return out.toByteArray()
    }

    @Test fun `reads 16 kHz mono PCM`() {
        assertContentEquals(shortArrayOf(1, -2, 300), WavReader.read(wav()))
    }

    @Test fun `skips extra chunks before the data`() {
        val list = "LIST".toByteArray() + le(5, 4) + ByteArray(5) + ByteArray(1) // odd size is padded
        assertContentEquals(shortArrayOf(1, -2, 300), WavReader.read(wav(extra = list)))
    }

    @Test fun `rejects other formats`() {
        assertFailsWith<WavReader.WavException> { WavReader.read(wav(rate = 44100)) }
        assertFailsWith<WavReader.WavException> { WavReader.read(wav(channels = 2)) }
        assertFailsWith<WavReader.WavException> { WavReader.read(wav(bits = 8)) }
        assertFailsWith<WavReader.WavException> { WavReader.read("not a wav file at all".toByteArray()) }
        assertFailsWith<WavReader.WavException> { WavReader.read(ByteArray(3)) }
    }

    @Test fun `a chunk size that overflows the position is an error, not a crash`() {
        // "LIST" claiming Int.MAX_VALUE bytes: body + size overflows Int; this used to throw IndexOutOfBounds
        val list = "LIST".toByteArray() + le(Int.MAX_VALUE, 4)
        assertFailsWith<WavReader.WavException> { WavReader.read(wav(extra = list)) }
        // a size with the top bit set is negative as an Int
        val negative = "LIST".toByteArray() + le(-1, 4)
        assertFailsWith<WavReader.WavException> { WavReader.read(wav(extra = negative)) }
        val almost = "LIST".toByteArray() + le(Int.MAX_VALUE - 8, 4)
        assertFailsWith<WavReader.WavException> { WavReader.read(wav(extra = almost)) }
    }

    @Test fun `a truncated data chunk gives the samples that are there`() {
        val bytes = wav(samples = ShortArray(100) { 7 })
        assertEquals(40, WavReader.read(bytes.copyOf(44 + 80)).size)
    }
}

class WordDiffTest {
    @Test fun `changed words are found in the corrected text`() {
        val corrected = "I went to the store and bought apples."
        val ranges = assertNotNull(WordDiff.changedRanges("i has went to the store and buyed apples.", corrected))
        val words = ranges.map { corrected.substring(it.first, it.last + 1) }
        assertTrue("I" in words[0])
        assertTrue(words.any { it.contains("bought") })
        assertFalse(words.any { it.contains("store") })
    }

    @Test fun `identical texts have no changes`() {
        assertEquals(emptyList(), WordDiff.changedRanges("same text.", "same text."))
        assertEquals(0.0, WordDiff.changedWordShare("same text.", "same text."))
    }

    @Test fun `a completely different text changes every word`() {
        assertEquals(1.0, WordDiff.changedWordShare("alpha beta gamma delta", "one two three four"))
    }

    @Test fun `very long texts are not diffed`() {
        val long = "word ".repeat(3000)
        assertNull(WordDiff.changedRanges(long, long + "x"))
    }
}

class AudioRecorderLogicTest {
    @Test fun `silence and clicks are not speech`() {
        assertTrue(AudioRecorder.isSilent(ShortArray(0)))
        assertTrue(AudioRecorder.isSilent(ShortArray(16000)))
        assertTrue(AudioRecorder.isSilent(ShortArray(2000) { 5 })) // under a quarter of a second
        assertFalse(AudioRecorder.isSilent(ShortArray(16000) { if (it % 2 == 0) 3000 else -3000 }))
    }

    @Test fun `levels are between 0 and 1 and grow with the volume`() {
        assertEquals(0f, AudioRecorder.levelOf(0.0))
        assertEquals(1f, AudioRecorder.levelOf(32768.0))
        val quiet = AudioRecorder.levelOf(100.0)
        val loud = AudioRecorder.levelOf(5000.0)
        assertTrue(quiet in 0f..1f && loud in 0f..1f && quiet < loud)
    }
}
