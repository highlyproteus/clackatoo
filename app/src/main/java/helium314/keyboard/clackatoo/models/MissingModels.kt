// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo.models

import helium314.keyboard.clackatoo.assist.ModelCatalog
import helium314.keyboard.clackatoo.assist.ModelKind
import helium314.keyboard.clackatoo.assist.ModelSpec
import helium314.keyboard.clackatoo.assist.ModelStatus

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
     * For [kind] (SPEECH or GRAMMAR): the model suggested for this phone ([suggest], see [ModelSuggestions]) if it is not
     * installed yet and can run here ([runs]); for Voice typing also the clean-up model suggested for this phone when the
     * language has its clean-up ([cleanUp]). The user can still choose any other model in Settings.
     */
    fun plan(
        kind: ModelKind, cleanUp: Boolean, status: (ModelSpec) -> ModelStatus, runs: (ModelSpec) -> Boolean,
        suggest: (ModelKind) -> String?,
    ): Plan {
        if (ModelCatalog.forKind(kind).any { status(it) is ModelStatus.Broken }) return Plan.Broken
        if ((kind == ModelKind.SPEECH || kind == ModelKind.GRAMMAR) && ModelCatalog.forKind(kind).none(runs)) return Plan.Unsupported
        val wanted = when (kind) {
            ModelKind.SPEECH -> listOfNotNull(
                ModelCatalog.byId(suggest(ModelKind.SPEECH))?.takeIf { runs(it) },
                if (cleanUp) ModelCatalog.byId(suggest(ModelKind.CLEAN_UP)) else null,
            )
            ModelKind.GRAMMAR -> listOfNotNull(ModelCatalog.byId(suggest(ModelKind.GRAMMAR)))
            else -> emptyList()
        }
        val offered = wanted.filter { status(it) is ModelStatus.Missing && runs(it) }
        return if (offered.isEmpty()) Plan.Nothing else Plan.Offer(offered)
    }
}
