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

    /** The phone of the tests: suggestions as for a big phone, or a given map. */
    private var suggestions: Map<ModelKind, String?> = mapOf(
        ModelKind.SPEECH to "parakeet-tdt-0.6b-v3", ModelKind.CLEAN_UP to "s1-mini", ModelKind.GRAMMAR to "qwen3.5-0.8b",
    )
    private val suggest = { kind: ModelKind -> suggestions[kind] }

    private fun plan(kind: ModelKind, cleanUp: Boolean, runs: (ModelSpec) -> Boolean) = MissingModels.plan(kind, cleanUp, status, runs, suggest)

    private fun ids(plan: MissingModels.Plan) = (plan as MissingModels.Plan.Offer).specs.map { it.id }

    @Test fun `voice typing without any model offers live text and the English clean-up`() {
        assertEquals(listOf("parakeet-tdt-0.6b-v3", "s1-mini"), ids(plan(ModelKind.SPEECH, true, everywhere)))
    }

    @Test fun `a language without the clean-up is not offered it`() {
        assertEquals(listOf("parakeet-tdt-0.6b-v3"), ids(plan(ModelKind.SPEECH, false, everywhere)))
    }

    @Test fun `where the suggested model cannot run, nothing that would fail is offered in its place`() {
        assertEquals(listOf("s1-mini"), ids(plan(ModelKind.SPEECH, true, noParakeet)))
        suggestions = suggestions + (ModelKind.SPEECH to "whisper-small")
        assertEquals(listOf("whisper-small", "s1-mini"), ids(plan(ModelKind.SPEECH, true, noParakeet)))
        assertEquals(listOf("whisper-small"), ids(plan(ModelKind.SPEECH, false, noParakeet)))
    }

    @Test fun `the sheet offers what is suggested for this phone, whatever it is`() {
        suggestions = mapOf(ModelKind.SPEECH to "parakeet-tdt-ctc-110m", ModelKind.CLEAN_UP to "s1-mini", ModelKind.GRAMMAR to "qwen3.5-0.8b")
        assertEquals(listOf("parakeet-tdt-ctc-110m", "s1-mini"), ids(plan(ModelKind.SPEECH, true, everywhere)))
        // no clean-up suggested (it would not fit): none offered
        suggestions = suggestions + (ModelKind.CLEAN_UP to null)
        assertEquals(listOf("parakeet-tdt-ctc-110m"), ids(plan(ModelKind.SPEECH, true, everywhere)))
        suggestions = suggestions + (ModelKind.SPEECH to "whisper-tiny")
        assertEquals(listOf("whisper-tiny"), ids(plan(ModelKind.SPEECH, true, everywhere)))
    }

    @Test fun `models that are installed are not offered again`() {
        installed += "s1-mini"
        assertEquals(listOf("parakeet-tdt-0.6b-v3"), ids(plan(ModelKind.SPEECH, true, everywhere)))
        installed += "parakeet-tdt-0.6b-v3"
        assertEquals(MissingModels.Plan.Nothing, plan(ModelKind.SPEECH, true, everywhere))
    }

    @Test fun `the grammar check offers Qwen3_5 0_8B`() {
        assertEquals(listOf("qwen3.5-0.8b"), ids(plan(ModelKind.GRAMMAR, false, everywhere)))
    }

    @Test fun `a model of the role that failed to load means the user is told to import it again, not offered a download`() {
        broken += "whisper-tiny"
        assertEquals(MissingModels.Plan.Broken, plan(ModelKind.SPEECH, true, everywhere))
        // a broken model of another role does not matter
        assertEquals(listOf("qwen3.5-0.8b"), ids(plan(ModelKind.GRAMMAR, false, everywhere)))
    }

    @Test fun `other roles have nothing to offer`() {
        assertEquals(MissingModels.Plan.Nothing, plan(ModelKind.CLEAN_UP, true, everywhere))
        assertEquals(MissingModels.Plan.Nothing, plan(ModelKind.VAD, true, everywhere))
    }

    @Test fun `on a phone that runs no model nothing is offered and the user is told why`() {
        val nowhere = { _: ModelSpec -> false }
        assertEquals(MissingModels.Plan.Unsupported, plan(ModelKind.SPEECH, true, nowhere))
        assertEquals(MissingModels.Plan.Unsupported, plan(ModelKind.GRAMMAR, false, nowhere))
    }

    @Test fun `a recommended model that cannot run here is never offered`() {
        val noCleanUp = { spec: ModelSpec -> spec.id != "s1-mini" }
        assertEquals(listOf("parakeet-tdt-0.6b-v3"), ids(plan(ModelKind.SPEECH, true, noCleanUp)))
    }
}
