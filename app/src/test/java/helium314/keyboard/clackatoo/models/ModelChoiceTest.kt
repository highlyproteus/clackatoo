// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo.models

import helium314.keyboard.clackatoo.assist.ModelCatalog
import helium314.keyboard.clackatoo.assist.ModelKind
import helium314.keyboard.clackatoo.catalog.Catalog
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelChoiceTest {
    @Test fun `only a downloadable model of the same role is a valid choice`() {
        assertTrue(ModelChoice.isValidChoice(ModelKind.SPEECH, Catalog.WHISPER_TINY.id))
        assertTrue(ModelChoice.isValidChoice(ModelKind.GRAMMAR, Catalog.QWEN3_5_2B.id))
        assertFalse(ModelChoice.isValidChoice(ModelKind.GRAMMAR, Catalog.WHISPER_TINY.id))
        assertFalse(ModelChoice.isValidChoice(ModelKind.SPEECH, ModelCatalog.SILERO_VAD.id))
        assertFalse(ModelChoice.isValidChoice(ModelKind.VAD, ModelCatalog.SILERO_VAD.id))
        assertFalse(ModelChoice.isValidChoice(ModelKind.SPEECH, "nope"))
    }

    @Test fun `Parakeet runs only on 64-bit ARM and x86_64, Whisper and the text models everywhere`() {
        val parakeet = ModelCatalog.byId(Catalog.PARAKEET_TDT_V3.id)!!
        val whisper = ModelCatalog.byId(Catalog.WHISPER_SMALL.id)!!
        val qwen = ModelCatalog.byId(Catalog.QWEN3_5_0_8B.id)!!
        for (abi in listOf("arm64-v8a", "x86_64")) assertTrue(ModelChoice.runsOn(parakeet, abi), abi)
        for (abi in listOf("armeabi-v7a", "x86", null)) assertFalse(ModelChoice.runsOn(parakeet, abi), "$abi")
        for (abi in listOf("armeabi-v7a", "x86", null)) {
            assertTrue(ModelChoice.runsOn(whisper, abi))
            assertTrue(ModelChoice.runsOn(qwen, abi))
        }
    }
}
