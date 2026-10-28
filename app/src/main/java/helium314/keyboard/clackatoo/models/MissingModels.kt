// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo.models

import helium314.keyboard.clackatoo.assist.ModelCatalog
import helium314.keyboard.clackatoo.assist.ModelKind
import helium314.keyboard.clackatoo.assist.ModelSpec
import helium314.keyboard.clackatoo.assist.ModelStatus
import helium314.keyboard.clackatoo.catalog.Catalog

/** What the panel offers when the Voice or Check key finds no usable model. */
internal object MissingModels {
    sealed interface Plan {
        /** Offer these models for download (all of them are missing and can run on this phone). */
        data class Offer(val specs: List<ModelSpec>) : Plan
        /** A model of the role is installed but could not be loaded: tell the user to import it again, do not offer more. */
        data object Broken : Plan
        /** No model of the role can run on this phone (CPU architecture or Android version): say so, offer nothing. */
        data object Unsupported : Plan
        /** Nothing to offer. */
        data object Nothing : Plan
    }

    /**
     * For [kind] (SPEECH or GRAMMAR): the recommended models that are not installed yet and can run here ([runs]).
     * Voice typing recommends Parakeet (live text), or Whisper small where Parakeet cannot run, and with it S1-mini when
     * the language has its clean-up ([cleanUp]); the grammar check recommends Qwen3.5 0.8B.
     */
    fun plan(kind: ModelKind, cleanUp: Boolean, status: (ModelSpec) -> ModelStatus, runs: (ModelSpec) -> Boolean): Plan {
        if (ModelCatalog.forKind(kind).any { status(it) is ModelStatus.Broken }) return Plan.Broken
        if ((kind == ModelKind.SPEECH || kind == ModelKind.GRAMMAR) && ModelCatalog.forKind(kind).none(runs)) return Plan.Unsupported
        val wanted = when (kind) {
            ModelKind.SPEECH -> {
                val speech = listOf(Catalog.PARAKEET_TDT_V3.id, Catalog.WHISPER_SMALL.id).mapNotNull { ModelCatalog.byId(it) }
                    .firstOrNull { runs(it) }
                listOfNotNull(speech, if (cleanUp) ModelCatalog.byId(Catalog.S1_MINI.id) else null)
            }
            ModelKind.GRAMMAR -> listOfNotNull(ModelCatalog.byId(Catalog.QWEN3_5_0_8B.id))
            else -> emptyList()
        }
        val offered = wanted.filter { status(it) is ModelStatus.Missing && runs(it) }
        return if (offered.isEmpty()) Plan.Nothing else Plan.Offer(offered)
    }
}
