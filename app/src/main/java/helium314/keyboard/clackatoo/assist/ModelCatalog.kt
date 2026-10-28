// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo.assist

import helium314.keyboard.clackatoo.catalog.Catalog
import helium314.keyboard.clackatoo.catalog.CatalogEngine
import helium314.keyboard.clackatoo.catalog.CatalogKind
import helium314.keyboard.clackatoo.catalog.CatalogModel
import helium314.keyboard.clackatoo.catalog.MULTILINGUAL
import java.io.File

enum class ModelKind { SPEECH, CLEAN_UP, GRAMMAR, VAD }

/** The speech recognition engine a [ModelKind.SPEECH] model runs on. */
enum class SpeechEngine { WHISPER, PARAKEET }

/** How the prompt of a text model is laid out (the chat template its weights were trained with). */
enum class PromptFormat { CHATML_NO_THINK }

/**
 * A model file the keyboard knows how to use. Only files with one of these names are accepted by "Import model
 * file", and only if they have exactly [sizeBytes] bytes and the SHA-256 digest [sha256] of the reference download:
 * a model file is parsed by native code, so no other file may reach it. Changing a model means changing both here.
 * Every entry must also be listed in NOTICE with its licence.
 *
 * A spec with a [bundledAsset] ships inside the APK instead: it is installed from there ([ModelStore.ensureBundled]),
 * never imported, and still checked against its size and digest.
 */
data class ModelSpec(
    val kind: ModelKind,
    val fileName: String,
    val displayName: String,
    val licence: String,
    val sizeBytes: Long,
    /** SHA-256 of the file, lower-case hex. */
    val sha256: String,
    val format: PromptFormat? = null,
    /** Context window to load the model with (text models). */
    val contextTokens: Int = 2048,
    /** Speech models only: which engine runs the file. */
    val engine: SpeechEngine? = null,
    /** Path in the APK's assets when the file ships with the app. */
    val bundledAsset: String? = null,
    /** The id of the [Catalog] entry this comes from (the file name for the bundled voice detector). */
    val id: String = fileName,
    /** Language codes the model supports; [MULTILINGUAL] when the model card only gives a count; empty when unknown. */
    val languages: List<String> = emptyList(),
    val maker: String = "",
    val recommended: Boolean = false,
) {
    /** True when the model is meant for [languageCode]. Models without language data are not excluded. */
    fun supports(languageCode: String) = languages.isEmpty() || MULTILINGUAL in languages || languageCode in languages
}

object ModelCatalog {
    private fun CatalogModel.toSpec() = ModelSpec(
        kind = when (kind) {
            CatalogKind.SPEECH -> ModelKind.SPEECH
            CatalogKind.CLEAN_UP -> ModelKind.CLEAN_UP
            CatalogKind.GRAMMAR -> ModelKind.GRAMMAR
        },
        fileName = fileName, displayName = displayName, licence = licence, sizeBytes = sizeBytes, sha256 = sha256,
        format = if (engine == CatalogEngine.LLAMA) PromptFormat.CHATML_NO_THINK else null,
        engine = when (engine) {
            CatalogEngine.WHISPER -> SpeechEngine.WHISPER
            CatalogEngine.PARAKEET -> SpeechEngine.PARAKEET
            CatalogEngine.LLAMA -> null
        },
        id = id, languages = languages, maker = maker, recommended = recommended,
    )

    /** Silero voice-activity detector that cuts the live text into utterances. Ships inside the APK, is not downloadable. */
    val SILERO_VAD = ModelSpec(
        ModelKind.VAD, "ggml-silero-v6.2.0.bin", "Silero VAD v6.2.0", "MIT", 885_098L,
        "2aa269b785eeb53a82983a20501ddf7c1d9c48e33ab63a41391ac6c9f7fb6987", bundledAsset = "models/ggml-silero-v6.2.0.bin",
    )

    /**
     * Every downloadable model of the shared [Catalog], in its order of preference (Parakeet before Whisper: with no
     * choice made, the first installed file of a kind is used), then the bundled voice detector.
     */
    val all: List<ModelSpec> = Catalog.all.map { it.toSpec() } + SILERO_VAD

    fun forKind(kind: ModelKind) = all.filter { it.kind == kind }

    /** The spec with catalog id [id], or null (also for the voice detector, which cannot be downloaded or chosen). */
    fun byId(id: String?): ModelSpec? = all.firstOrNull { it.id == id && it.bundledAsset == null }

    /** The models offered when a feature has none: the recommended ones of a role that are not installed yet. */
    fun recommendedFor(kind: ModelKind): List<ModelSpec> = when (kind) {
        // live text with the best speech model, and the English clean-up that goes with it (concept: "Voice typing needs a model")
        ModelKind.SPEECH -> listOf(Catalog.PARAKEET_TDT_V3.id, Catalog.S1_MINI.id)
        ModelKind.GRAMMAR -> listOf(Catalog.QWEN3_5_0_8B.id)
        else -> emptyList()
    }.mapNotNull { byId(it) }
}

/** What is known about a model on this phone. */
sealed interface ModelStatus {
    data object Missing : ModelStatus
    data class Installed(val file: File, val sizeBytes: Long) : ModelStatus
    /** The file exists but the model process crashed while loading it (or it was rejected). */
    data class Broken(val file: File) : ModelStatus
}
