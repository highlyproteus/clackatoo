// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo.assist

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
)

object ModelCatalog {
    /**
     * Reference download, pinned to a revision:
     * https://huggingface.co/mudler/parakeet-cpp-gguf/resolve/c77726e46ceaab96b5a5a3328915edb44c73209d/tdt-0.6b-v3-q4_k.gguf
     */
    val PARAKEET_TDT_0_6B_V3 = ModelSpec(
        ModelKind.SPEECH, "tdt-0.6b-v3-q4_k.gguf", "Parakeet TDT 0.6B v3 (live text, 4-bit)", "CC-BY-4.0 (NVIDIA)", 675_200_864L,
        "993d73feb4206dadda865ab25bd64b50c48dc4d013c3bf6126a721f28b1d5ee8", engine = SpeechEngine.PARAKEET,
    )
    val SPEECH_SMALL = ModelSpec(
        ModelKind.SPEECH, "ggml-small-q5_1.bin", "Whisper small (multilingual, 5-bit)", "MIT", 190_085_487L,
        "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb", engine = SpeechEngine.WHISPER,
    )
    val S1_MINI = ModelSpec(
        ModelKind.CLEAN_UP, "s1-mini-q4_k_m.gguf", "S1-mini by Superwhisper (English)",
        "Apache-2.0 with the S1-mini naming term", 484_219_808L,
        "3b41ebe2502cbd03e811d5d16b022f5ab551eda58d62597d152f89535003c634", PromptFormat.CHATML_NO_THINK,
    )
    val GRAMMAR_QWEN3_5_0_8B = ModelSpec(
        ModelKind.GRAMMAR, "Qwen3.5-0.8B-Q4_K_M.gguf", "Qwen3.5 0.8B (4-bit)", "Apache-2.0", 532_517_120L,
        "bd258782e35f7f458f8aced1adc053e6e92e89bc735ba3be89d38a06121dc517", PromptFormat.CHATML_NO_THINK,
    )

    /** Silero voice-activity detector that cuts the live text into utterances. Ships inside the APK. */
    val SILERO_VAD = ModelSpec(
        ModelKind.VAD, "ggml-silero-v6.2.0.bin", "Silero VAD v6.2.0", "MIT", 885_098L,
        "2aa269b785eeb53a82983a20501ddf7c1d9c48e33ab63a41391ac6c9f7fb6987", bundledAsset = "models/ggml-silero-v6.2.0.bin",
    )

    /** In order of preference: the first installed file of a kind is used (Parakeet before Whisper). */
    val all = listOf(PARAKEET_TDT_0_6B_V3, SPEECH_SMALL, S1_MINI, GRAMMAR_QWEN3_5_0_8B, SILERO_VAD)

    fun forKind(kind: ModelKind) = all.filter { it.kind == kind }
}

/** What is known about a model on this phone. */
sealed interface ModelStatus {
    data object Missing : ModelStatus
    data class Installed(val file: File, val sizeBytes: Long) : ModelStatus
    /** The file exists but the model process crashed while loading it (or it was rejected). */
    data class Broken(val file: File) : ModelStatus
}
