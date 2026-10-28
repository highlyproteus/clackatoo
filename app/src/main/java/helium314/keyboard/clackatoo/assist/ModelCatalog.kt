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
    /** The short name cards show; [displayName] stays the full name (accessibility description, licence screen). */
    val shortName: String = displayName,
    /** An experimental custom model (see [CustomPolicy]): chosen by the user, not pinned by size and SHA-256, never from the catalog. */
    val custom: Boolean = false,
) {
    /** Custom text models are prompted with the chat template inside the model file (see [MlClient.chat]); catalog models have a fixed [format]. */
    val ownTemplate: Boolean get() = custom && (kind == ModelKind.CLEAN_UP || kind == ModelKind.GRAMMAR)

    /** True when the model is meant for [languageCode]. Models without language data are not excluded. */
    fun supports(languageCode: String) = languages.isEmpty() || MULTILINGUAL in languages || languageCode in languages
}

object ModelCatalog {
    /** Short names for the cards of the settings, by catalog id. */
    private val SHORT_NAMES = mapOf(
        "parakeet-tdt-0.6b-v3" to "Parakeet v3",
        "parakeet-tdt-ctc-110m" to "Parakeet 110M",
        "whisper-tiny" to "Whisper tiny",
        "whisper-base" to "Whisper base",
        "whisper-small" to "Whisper small",
        "s1-mini" to "S1-mini",
        "qwen3.5-0.8b" to "Qwen3.5 0.8B",
    )

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
        id = id, languages = languages, maker = maker, recommended = recommended, shortName = SHORT_NAMES[id] ?: displayName,
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
}

/** What is known about a model on this phone. */
sealed interface ModelStatus {
    data object Missing : ModelStatus
    data class Installed(val file: File, val sizeBytes: Long) : ModelStatus
    /** The file exists but the model process crashed while loading it (or it was rejected). */
    data class Broken(val file: File) : ModelStatus
}
