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
        assertTrue(ModelChoice.isValidChoice(ModelKind.GRAMMAR, Catalog.QWEN3_5_0_8B.id))
        assertFalse(ModelChoice.isValidChoice(ModelKind.GRAMMAR, Catalog.WHISPER_TINY.id))
        assertFalse(ModelChoice.isValidChoice(ModelKind.SPEECH, ModelCatalog.SILERO_VAD.id))
        assertFalse(ModelChoice.isValidChoice(ModelKind.VAD, ModelCatalog.SILERO_VAD.id))
        assertFalse(ModelChoice.isValidChoice(ModelKind.SPEECH, "nope"))
    }

    @Test fun `a custom model is a valid choice only for its own role`() {
        assertTrue(ModelChoice.isValidChoice(ModelKind.GRAMMAR, "custom:grammar:Qwen3-0.6B.gguf"))
        assertTrue(ModelChoice.isValidChoice(ModelKind.SPEECH, "custom:speech:ggml-tiny.en-q5_1.bin"))
        assertFalse(ModelChoice.isValidChoice(ModelKind.SPEECH, "custom:grammar:Qwen3-0.6B.gguf"))
        assertFalse(ModelChoice.isValidChoice(ModelKind.VAD, "custom:vad:x.bin"))
        assertFalse(ModelChoice.isValidChoice(ModelKind.GRAMMAR, "custom:grammar:../x"))
        assertFalse(ModelChoice.isValidChoice(ModelKind.GRAMMAR, "custom:grammar:"))
    }

    @Test fun `every engine runs only where its native runtime ships, 64-bit ARM or x86_64 on Android 9 or newer`() {
        val models = listOf(Catalog.PARAKEET_TDT_V3, Catalog.WHISPER_SMALL, Catalog.S1_MINI, Catalog.QWEN3_5_0_8B)
            .map { ModelCatalog.byId(it.id)!! }
        for (spec in models) {
            for (abi in listOf("arm64-v8a", "x86_64")) {
                assertTrue(ModelChoice.runsOn(spec, abi, 28), "${spec.id} $abi")
                assertFalse(ModelChoice.runsOn(spec, abi, 27), "${spec.id} $abi on Android 8.1")
            }
            for (abi in listOf("armeabi-v7a", "x86", null)) assertFalse(ModelChoice.runsOn(spec, abi, 37), "${spec.id} $abi")
        }
    }
}
