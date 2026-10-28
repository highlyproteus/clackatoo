// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo.models

import helium314.keyboard.clackatoo.assist.ModelKind
import helium314.keyboard.clackatoo.assist.ModelSpec
import helium314.keyboard.clackatoo.assist.ModelStatus
import helium314.keyboard.clackatoo.assist.SpeechEngine
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** What the Voice and Check keys offer when there is no model. */
class MissingModelsTest {
    private val installed = HashSet<String>()
    private val broken = HashSet<String>()
    private val status = { spec: ModelSpec ->
        when (spec.id) {
            in installed -> ModelStatus.Installed(File("x"), 1)
            in broken -> ModelStatus.Broken(File("x"))
            else -> ModelStatus.Missing
        }
    }
    private val everywhere = { _: ModelSpec -> true }
    private val noParakeet = { spec: ModelSpec -> spec.engine != SpeechEngine.PARAKEET }

    private fun ids(plan: MissingModels.Plan) = (plan as MissingModels.Plan.Offer).specs.map { it.id }

    @Test fun `voice typing without any model offers live text and the English clean-up`() {
        assertEquals(listOf("parakeet-tdt-0.6b-v3", "s1-mini"), ids(MissingModels.plan(ModelKind.SPEECH, true, status, everywhere)))
    }

    @Test fun `a language without the clean-up is not offered it`() {
        assertEquals(listOf("parakeet-tdt-0.6b-v3"), ids(MissingModels.plan(ModelKind.SPEECH, false, status, everywhere)))
    }

    @Test fun `where Parakeet cannot run Whisper small is offered instead, never a model that would fail`() {
        assertEquals(listOf("whisper-small", "s1-mini"), ids(MissingModels.plan(ModelKind.SPEECH, true, status, noParakeet)))
        assertEquals(listOf("whisper-small"), ids(MissingModels.plan(ModelKind.SPEECH, false, status, noParakeet)))
    }

    @Test fun `models that are installed are not offered again`() {
        installed += "s1-mini"
        assertEquals(listOf("parakeet-tdt-0.6b-v3"), ids(MissingModels.plan(ModelKind.SPEECH, true, status, everywhere)))
        installed += "parakeet-tdt-0.6b-v3"
        assertEquals(MissingModels.Plan.Nothing, MissingModels.plan(ModelKind.SPEECH, true, status, everywhere))
    }

    @Test fun `the grammar check offers Qwen3_5 0_8B`() {
        assertEquals(listOf("qwen3.5-0.8b"), ids(MissingModels.plan(ModelKind.GRAMMAR, false, status, everywhere)))
    }

    @Test fun `a model of the role that failed to load means the user is told to import it again, not offered a download`() {
        broken += "whisper-tiny"
        assertEquals(MissingModels.Plan.Broken, MissingModels.plan(ModelKind.SPEECH, true, status, everywhere))
        // a broken model of another role does not matter
        assertEquals(listOf("qwen3.5-0.8b"), ids(MissingModels.plan(ModelKind.GRAMMAR, false, status, everywhere)))
    }

    @Test fun `other roles have nothing to offer`() {
        assertEquals(MissingModels.Plan.Nothing, MissingModels.plan(ModelKind.CLEAN_UP, true, status, everywhere))
        assertEquals(MissingModels.Plan.Nothing, MissingModels.plan(ModelKind.VAD, true, status, everywhere))
    }

    @Test fun `on a phone that runs no model nothing is offered and the user is told why`() {
        val nowhere = { _: ModelSpec -> false }
        assertEquals(MissingModels.Plan.Unsupported, MissingModels.plan(ModelKind.SPEECH, true, status, nowhere))
        assertEquals(MissingModels.Plan.Unsupported, MissingModels.plan(ModelKind.GRAMMAR, false, status, nowhere))
    }

    @Test fun `a recommended model that cannot run here is never offered`() {
        val noCleanUp = { spec: ModelSpec -> spec.id != "s1-mini" }
        assertEquals(listOf("parakeet-tdt-0.6b-v3"), ids(MissingModels.plan(ModelKind.SPEECH, true, status, noCleanUp)))
    }
}
