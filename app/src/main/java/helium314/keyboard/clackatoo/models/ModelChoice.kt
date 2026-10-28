// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo.models

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import helium314.keyboard.clackatoo.assist.AssistSettings
import helium314.keyboard.clackatoo.assist.ModelCatalog
import helium314.keyboard.clackatoo.assist.ModelKind
import helium314.keyboard.clackatoo.assist.ModelSpec
import helium314.keyboard.clackatoo.assist.SpeechEngine
import helium314.keyboard.latin.utils.prefs

/**
 * Which installed model each role uses. The choice is a catalog id per [ModelKind] (SPEECH, CLEAN_UP, GRAMMAR); a role with
 * no choice, or whose chosen model is not installed, uses the first installed model of the catalog order, as before.
 */
object ModelChoice {
    private fun key(kind: ModelKind) = "assist_model_choice_${kind.name.lowercase()}"

    /** The id the user chose for [kind] (it may not be installed), or null. */
    fun selectedId(context: Context, kind: ModelKind): String? = context.prefs().getString(key(kind), null)

    /** Chooses [id] for its role. False (and nothing changes) when [id] is not a downloadable model of [kind]. */
    fun select(context: Context, kind: ModelKind, id: String): Boolean {
        if (!isValidChoice(kind, id)) return false
        context.prefs().edit { putString(key(kind), id) }
        return true
    }

    /**
     * The model [kind] uses right now: the chosen one if installed and usable (and meant for [languageCode], when
     * given), else another installed one, else null.
     */
    fun active(context: Context, kind: ModelKind, languageCode: String? = null): ModelSpec? =
        AssistSettings.modelStore(context).best(kind, selectedId(context, kind), languageCode, ::runsHere)

    internal fun isValidChoice(kind: ModelKind, id: String): Boolean = ModelCatalog.byId(id)?.kind == kind

    /** False for models whose engine cannot run on this phone's CPU architecture (Parakeet needs arm64 or x86_64). */
    fun runsHere(spec: ModelSpec): Boolean = runsOn(spec, Build.SUPPORTED_ABIS.firstOrNull())

    internal fun runsOn(spec: ModelSpec, abi: String?): Boolean = when (spec.engine) {
        SpeechEngine.PARAKEET -> abi == "arm64-v8a" || abi == "x86_64"
        else -> true
    }
}
