// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo.models

import helium314.keyboard.clackatoo.assist.ModelKind
import helium314.keyboard.clackatoo.catalog.Catalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The "Suggested for this phone" rules, on made-up phones. Memory is what `ActivityManager.MemoryInfo.totalMem` reports: a
 * little less than the number on the box (a 4 GB phone reports 3.6 to 3.9 GiB).
 */
class ModelSuggestionsTest {
    private fun gib(x: Double) = (x * 1024 * 1024 * 1024).toLong()
    private fun phone(gib: Double, abi: String? = "arm64-v8a", sdk: Int = 35, dotprod: Boolean = true, cores: Int = 8) =
        DeviceProfile(gib(gib), abi, sdk, dotprod, cores)

    private val english = setOf("en")
    private fun speech(p: DeviceProfile, l: Set<String> = english) = ModelSuggestions.suggestedId(p, ModelKind.SPEECH, l)
    private fun cleanUp(p: DeviceProfile, l: Set<String> = english) = ModelSuggestions.suggestedId(p, ModelKind.CLEAN_UP, l)
    private fun grammar(p: DeviceProfile, l: Set<String> = english) = ModelSuggestions.suggestedId(p, ModelKind.GRAMMAR, l)

    @Test fun `an 8 GB or bigger phone with dot-product gets live Parakeet v3, the English clean-up and the grammar model`() {
        for (ram in listOf(7.3, 11.2, 15.0)) {
            val p = phone(ram)
            assertEquals(Catalog.PARAKEET_TDT_V3.id, speech(p), "$ram")
            assertEquals(Catalog.S1_MINI.id, cleanUp(p), "$ram")
            assertEquals(Catalog.QWEN3_5_0_8B.id, grammar(p), "$ram")
        }
    }

    @Test fun `a 6 GB phone also runs Parakeet v3`() {
        assertEquals(Catalog.PARAKEET_TDT_V3.id, speech(phone(5.5)))
        assertEquals(Catalog.PARAKEET_TDT_V3.id, speech(phone(5.5), setOf("es")))
    }

    @Test fun `a 4 GB phone gets the small English Parakeet for English only, and v3 where there is no English clean-up to keep in memory`() {
        val p = phone(3.7)
        assertEquals(Catalog.PARAKEET_TDT_CTC_110M.id, speech(p))
        assertEquals(Catalog.S1_MINI.id, cleanUp(p))
        // Spanish only: no English clean-up, so v3 fits on its own
        assertEquals(Catalog.PARAKEET_TDT_V3.id, speech(p, setOf("es")))
        assertNull(cleanUp(p, setOf("es")))
        // English and Spanish: the English-only Parakeet cannot serve Spanish, and v3 with S1-mini would be too much, so Whisper
        assertEquals(Catalog.WHISPER_SMALL.id, speech(p, setOf("en", "es")))
    }

    @Test fun `the emulator profile of this project (arm64, 4 cores, 3_9 GiB) suggests Parakeet 110M, S1-mini and Qwen`() {
        val p = DeviceProfile(4_159_000_000L, "arm64-v8a", 37, true, 4)
        assertEquals(Catalog.PARAKEET_TDT_CTC_110M.id, speech(p))
        assertEquals(Catalog.S1_MINI.id, cleanUp(p))
        assertEquals(Catalog.QWEN3_5_0_8B.id, grammar(p))
        assertFalse(ModelSuggestions.mayBeSlow(p, Catalog.QWEN3_5_0_8B.id))
    }

    @Test fun `a small phone gets the live model only while it fits next to the English clean-up, then Whisper as large as the memory allows`() {
        // budget: 45 percent of the RAM; clean-up S1-mini needs 1010 MB next to the speech model
        assertEquals(Catalog.PARAKEET_TDT_CTC_110M.id, speech(phone(3.0))) // 1382 MB: 110M (330) + 1010 fits
        assertEquals(Catalog.WHISPER_TINY.id, speech(phone(2.8))) // 1290 MB: only tiny (250) fits next to it
        for (ram in listOf(2.8, 2.0, 1.5)) assertTrue(speech(phone(ram))!!.startsWith("whisper"), "$ram")
        assertEquals(Catalog.WHISPER_TINY.id, speech(phone(1.5))) // nothing fits: the smallest
        // without English there is no clean-up to keep in memory, so a 3 GiB phone has room for Whisper small, and v3 (940 MB) fits too
        assertEquals(Catalog.PARAKEET_TDT_V3.id, speech(phone(3.0), setOf("es")))
        assertEquals(Catalog.WHISPER_SMALL.id, speech(phone(1.2), setOf("es"))) // 553 MB budget: small (500) fits, v3 does not
    }

    @Test fun `a phone without dot-product never gets live Parakeet, and the models are flagged as possibly slow`() {
        val p = phone(11.2, dotprod = false)
        assertTrue(speech(p)!!.startsWith("whisper"))
        assertTrue(ModelSuggestions.mayBeSlow(p, Catalog.QWEN3_5_0_8B.id))
        assertTrue(ModelSuggestions.mayBeSlow(p, Catalog.WHISPER_SMALL.id))
    }

    @Test fun `a phone with fewer than four cores gets no live model either`() {
        assertTrue(speech(phone(11.2, cores = 2))!!.startsWith("whisper"))
        assertEquals(Catalog.PARAKEET_TDT_V3.id, speech(phone(11.2, cores = ModelSuggestions.LIVE_MIN_CORES)))
    }

    @Test fun `x86_64 phones (the emulator) need no dot-product`() {
        assertTrue(DeviceProfile.hasDotprod("x86_64", ""))
        assertEquals(Catalog.PARAKEET_TDT_V3.id, speech(phone(11.2, abi = "x86_64", cores = 4)))
    }

    @Test fun `a very small phone still gets the smallest speech model and the grammar model, flagged as slow`() {
        val p = phone(1.5)
        assertEquals(Catalog.WHISPER_TINY.id, speech(p))
        assertEquals(Catalog.QWEN3_5_0_8B.id, grammar(p))
        assertTrue(ModelSuggestions.mayBeSlow(p, Catalog.QWEN3_5_0_8B.id))
        assertNull(cleanUp(p)) // the clean-up would not fit next to the speech model
    }

    @Test fun `on an unsupported phone nothing is suggested`() {
        for (p in listOf(phone(11.2, abi = "armeabi-v7a"), phone(11.2, abi = "x86"), phone(11.2, abi = null), phone(11.2, sdk = 27))) {
            for (kind in ModelKind.entries) assertNull(ModelSuggestions.suggestedId(p, kind, english), "$p $kind")
            assertFalse(ModelSuggestions.mayBeSlow(p, Catalog.QWEN3_5_0_8B.id))
        }
    }

    @Test fun `only catalog models are ever suggested, one per role, of the right kind`() {
        val kinds = mapOf(ModelKind.SPEECH to "SPEECH", ModelKind.CLEAN_UP to "CLEAN_UP", ModelKind.GRAMMAR to "GRAMMAR")
        for (ram in listOf(1.5, 2.0, 3.0, 3.7, 5.5, 7.3, 11.2)) for (langs in listOf(english, setOf("es"), setOf("en", "es"), emptySet())) {
            for ((kind, name) in kinds) {
                val id = ModelSuggestions.suggestedId(phone(ram), kind, langs) ?: continue
                assertEquals(name, Catalog.byId(id)!!.kind.name, "$ram $langs $kind")
            }
        }
    }

    @Test fun `every catalog model has a measured memory footprint`() {
        for (m in Catalog.all) assertTrue((ModelSuggestions.PEAK_MB[m.id] ?: 0) > m.sizeBytes / (1024 * 1024) / 2, m.id)
    }

    @Test fun `the dot-product flag is read from the cpuinfo Features line`() {
        val with = "processor\t: 0\nFeatures\t: fp asimd evtstrm aes asimddp sha512\nCPU implementer\t: 0x41\n"
        val without = "processor\t: 0\nFeatures\t: fp asimd evtstrm aes sha512\nCPU implementer\t: 0x41\n"
        assertTrue(DeviceProfile.hasDotprod("arm64-v8a", with))
        assertFalse(DeviceProfile.hasDotprod("arm64-v8a", without))
        assertFalse(DeviceProfile.hasDotprod("arm64-v8a", ""))
        assertFalse(DeviceProfile.hasDotprod("arm64-v8a", "Features\t: asimddpx other\n")) // a longer name is not it
        assertTrue(DeviceProfile.hasDotprod("x86_64", without))
    }
}
