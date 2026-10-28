// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo.assist

import helium314.keyboard.clackatoo.assist.ModelResidency.Slot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModelResidencyTest {
    private val r = ModelResidency()

    /** Does what the service does for a request: free what [toFree] names, then load [key] into [need] when it is not there. */
    private fun use(need: Slot, key: String): Set<Slot> {
        val freed = r.toFree(need, key)
        freed.forEach { r.freed(it) }
        if (r.keyOf(need) != key) r.loaded(need, key)
        return freed
    }

    private fun voiceWithClean() { use(Slot.ASR, "v3"); use(Slot.VAD, "vad"); use(Slot.CLEAN_UP, "s1") }

    @Test fun `voice then check frees the speech engine, the voice detector and the clean-up model`() {
        voiceWithClean()
        assertEquals(setOf(Slot.ASR, Slot.VAD, Slot.CLEAN_UP), r.resident())
        assertEquals(setOf(Slot.ASR, Slot.VAD, Slot.CLEAN_UP), use(Slot.GRAMMAR, "qwen"))
        assertEquals(setOf(Slot.GRAMMAR), r.resident())
    }

    @Test fun `check then voice frees the grammar model`() {
        use(Slot.GRAMMAR, "qwen")
        assertEquals(setOf(Slot.GRAMMAR), use(Slot.ASR, "v3"))
        assertEquals(setOf(Slot.ASR), r.resident())
        assertEquals(emptySet(), use(Slot.VAD, "vad"))
        use(Slot.CLEAN_UP, "s1")
        assertEquals(setOf(Slot.ASR, Slot.VAD, Slot.CLEAN_UP), r.resident())
    }

    @Test fun `clean-up after a transcription keeps the speech engine`() {
        use(Slot.STT, "whisper")
        assertEquals(emptySet(), use(Slot.CLEAN_UP, "s1"))
        assertEquals(setOf(Slot.STT, Slot.CLEAN_UP), r.resident())
        // the next dictation with the same files frees nothing
        assertEquals(emptySet(), use(Slot.STT, "whisper"))
        assertEquals(emptySet(), use(Slot.CLEAN_UP, "s1"))
    }

    @Test fun `two grammar models in a row keep one`() {
        use(Slot.GRAMMAR, "a")
        assertEquals(setOf(Slot.GRAMMAR), use(Slot.GRAMMAR, "b"))
        assertEquals("b", r.keyOf(Slot.GRAMMAR))
        assertEquals(setOf(Slot.GRAMMAR), r.resident())
        assertEquals(emptySet(), use(Slot.GRAMMAR, "b")) // the same file again stays
    }

    @Test fun `a different clean-up model replaces the old one`() {
        use(Slot.STT, "whisper"); use(Slot.CLEAN_UP, "s1")
        assertEquals(setOf(Slot.CLEAN_UP), use(Slot.CLEAN_UP, "custom"))
        assertEquals(setOf(Slot.STT, Slot.CLEAN_UP), r.resident())
    }

    @Test fun `the two speech engines never stay together`() {
        use(Slot.STT, "whisper"); use(Slot.CLEAN_UP, "s1")
        assertEquals(setOf(Slot.STT), use(Slot.ASR, "v3"))
        assertEquals(setOf(Slot.ASR, Slot.CLEAN_UP), r.resident())
        assertEquals(setOf(Slot.ASR), use(Slot.STT, "whisper"))
        assertEquals(setOf(Slot.STT, Slot.CLEAN_UP), r.resident())
    }

    @Test fun `another speech file in the same engine replaces the old one`() {
        use(Slot.ASR, "v3")
        assertEquals(setOf(Slot.ASR), use(Slot.ASR, "110m"))
    }

    @Test fun `a leaked voice detector is forgotten when the service says so`() {
        use(Slot.ASR, "v3"); use(Slot.VAD, "vad")
        r.freed(Slot.VAD)
        assertTrue(Slot.VAD !in r.resident())
        assertEquals(emptySet(), use(Slot.VAD, "vad")) // loads again, nothing else is freed
    }

    @Test fun `the whole walk of a session never holds more than one use`() {
        val sizes = ArrayList<Set<Slot>>()
        voiceWithClean(); sizes += r.resident()
        use(Slot.GRAMMAR, "qwen"); sizes += r.resident()
        voiceWithClean(); sizes += r.resident()
        use(Slot.GRAMMAR, "a"); sizes += r.resident()
        use(Slot.GRAMMAR, "b"); sizes += r.resident()
        use(Slot.GRAMMAR, "qwen"); sizes += r.resident()
        assertEquals(listOf(setOf(Slot.ASR, Slot.VAD, Slot.CLEAN_UP), setOf(Slot.GRAMMAR), setOf(Slot.ASR, Slot.VAD, Slot.CLEAN_UP), setOf(Slot.GRAMMAR), setOf(Slot.GRAMMAR), setOf(Slot.GRAMMAR)), sizes)
        r.clear()
        assertTrue(r.resident().isEmpty())
    }
}
