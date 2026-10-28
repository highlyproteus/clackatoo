// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo.models

import helium314.keyboard.clackatoo.assist.ModelKind
import helium314.keyboard.clackatoo.catalog.Catalog
import helium314.keyboard.clackatoo.catalog.CatalogModel
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.locale

/**
 * The model suggested for each role on this phone ("Suggested for this phone"): shown first in Settings and offered by the
 * "needs a model" sheet. It is a suggestion only: every listed model can still be downloaded, chosen and used, and nothing is
 * forced.
 *
 * The rules come from measurements, see "Which model for which phone" in `ml/README.md`. The numbers in [PEAK_MB] are the
 * peak resident memory of the isolated model process (`VmHWM`, cold start) with that one model loaded and run on the test
 * clip, on an arm64 emulator with 4 cores. The model process keeps only the models of the current use resident
 * ([helium314.keyboard.clackatoo.assist.ModelResidency]): Voice typing holds the speech model, the voice detector and the
 * English clean-up model together, a grammar check holds the grammar model alone and frees the voice models first. The rules
 * therefore keep the voice pair within [BUDGET_PERCENT] of the phone's RAM (the rest is for Android and the app in front),
 * and the grammar model alone within the same share.
 * Live text (Parakeet) also needs the Arm dot-product instructions on arm64 and at least [LIVE_MIN_CORES] cores: it decodes the
 * growing utterance while you speak (4.5 s of recognition for the 5.2 s test clip on four cores).
 */
object ModelSuggestions {
    /** Measured peak memory of the model process per catalog id, in MB, rounded up (see ml/README.md). */
    val PEAK_MB: Map<String, Int> = mapOf(
        Catalog.PARAKEET_TDT_V3.id to 940,
        Catalog.PARAKEET_TDT_CTC_110M.id to 330,
        Catalog.WHISPER_SMALL.id to 500,
        Catalog.WHISPER_BASE.id to 300,
        Catalog.WHISPER_TINY.id to 250,
        Catalog.S1_MINI.id to 1010,
        Catalog.QWEN3_5_0_8B.id to 1320,
    )

    /** The share of the phone's RAM the models of one use may take together. */
    const val BUDGET_PERCENT = 45

    /** Parakeet decodes while you speak; with fewer cores it cannot keep up with the speech. */
    const val LIVE_MIN_CORES = 4

    private const val MB = 1024L * 1024L

    /** In order of preference: the live models, then Whisper from the most to the least accurate. */
    private val SPEECH_ORDER = listOf(
        Catalog.PARAKEET_TDT_V3, Catalog.PARAKEET_TDT_CTC_110M, Catalog.WHISPER_SMALL, Catalog.WHISPER_BASE, Catalog.WHISPER_TINY,
    )

    fun budgetMb(profile: DeviceProfile): Long = profile.totalMemBytes / MB * BUDGET_PERCENT / 100

    private fun live(model: CatalogModel) = model.engine == helium314.keyboard.clackatoo.catalog.CatalogEngine.PARAKEET

    private fun peak(id: String): Int = PEAK_MB[id] ?: 0

    /** [languages] are the language codes of the enabled keyboards; none means English. English clean-up is only used when English is one of them. */
    private fun usesEnglish(languages: Set<String>) = languages.isEmpty() || "en" in languages

    private fun canGoLive(profile: DeviceProfile) = profile.dotprod && profile.cores >= LIVE_MIN_CORES

    private fun speaks(model: CatalogModel, languages: Set<String>) = languages.all { model.supports(it) }

    /**
     * The catalog id suggested for [kind] on [profile] for a user whose keyboards are in [languages], or null when no model
     * of the role runs on this phone (an unsupported CPU architecture or Android version) or the role has no suggestion
     * (clean-up where it would not fit, or for a user without English).
     */
    fun suggestedId(profile: DeviceProfile, kind: ModelKind, languages: Set<String>): String? {
        if (!profile.runsModels) return null
        return when (kind) {
            ModelKind.SPEECH -> speech(profile, languages)?.id
            ModelKind.CLEAN_UP -> cleanUp(profile, languages)
            ModelKind.GRAMMAR -> Catalog.QWEN3_5_0_8B.id // the one grammar model; [mayBeSlow] tells when the phone is short of memory
            ModelKind.VAD -> null
        }
    }

    private fun speech(profile: DeviceProfile, languages: Set<String>): CatalogModel? {
        val budget = budgetMb(profile)
        val usable = SPEECH_ORDER.filter { speaks(it, languages) && (!live(it) || canGoLive(profile)) }
        val cleanUp = if (usesEnglish(languages)) peak(Catalog.S1_MINI.id) else 0
        // the best model that fits together with the English clean-up; else the smallest one there is, flagged by [mayBeSlow]
        return usable.firstOrNull { peak(it.id) + cleanUp <= budget } ?: usable.lastOrNull()
    }

    private fun cleanUp(profile: DeviceProfile, languages: Set<String>): String? {
        if (!usesEnglish(languages)) return null
        val speech = speech(profile, languages) ?: return null
        return Catalog.S1_MINI.id.takeIf { peak(speech.id) + peak(it) <= budgetMb(profile) }
    }

    /** True when the phone is short of what the model needs: no dot-product instructions, or the model alone is over the memory budget. */
    fun mayBeSlow(profile: DeviceProfile, id: String): Boolean {
        val peak = PEAK_MB[id] ?: return false
        return profile.runsModels && (!profile.dotprod || peak > budgetMb(profile))
    }

    /** The language codes of the enabled keyboards (empty when none can be read yet). */
    fun keyboardLanguages(): Set<String> = try {
        SubtypeSettings.getEnabledSubtypes(true).map { it.locale().language }.toSet()
    } catch (_: Throwable) { // the settings are not loaded yet: assume English
        emptySet()
    }
}
