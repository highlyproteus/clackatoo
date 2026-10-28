// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo.catalog

/** What a model is used for. */
enum class CatalogKind { SPEECH, CLEAN_UP, GRAMMAR }

/** The runtime that loads the file: whisper.cpp, parakeet.cpp or llama.cpp. */
enum class CatalogEngine { WHISPER, PARAKEET, LLAMA }

/**
 * One downloadable model. Everything the keyboard and the companion app need to know about it is here; the companion
 * is only ever told an [id] and resolves the rest from this list.
 *
 * [url] is the Hugging Face `resolve/<commit>/<file>` address of exactly the file that has [sizeBytes] bytes and the
 * SHA-256 digest [sha256]; the commit is a 40-hex revision, never a branch name, so the bytes cannot change.
 * [languages] are the codes the model card lists; `"multilingual"` stands for a card that gives only a count.
 */
data class CatalogModel(
    val id: String,
    val kind: CatalogKind,
    val engine: CatalogEngine,
    val fileName: String,
    val displayName: String,
    /** Who made the model, as plain text (no logos). */
    val maker: String,
    val licence: String,
    val languages: List<String>,
    val sizeBytes: Long,
    /** SHA-256 of the file, lower-case hex. */
    val sha256: String,
    /** Hugging Face repository, `owner/name`. */
    val repo: String,
    /** Commit of [repo] the file is pinned to. */
    val revision: String,
    val recommended: Boolean = false,
) {
    val url: String get() = "https://huggingface.co/$repo/resolve/$revision/$fileName"

    /** True when the model can transcribe or check text in [languageCode] (a two-letter code such as "en"). */
    fun supports(languageCode: String) = MULTILINGUAL in languages || languageCode in languages
}

const val MULTILINGUAL = "multilingual"

object Catalog {
    private const val PARAKEET_REPO = "mudler/parakeet-cpp-gguf"
    private const val PARAKEET_REV = "c77726e46ceaab96b5a5a3328915edb44c73209d"
    private const val WHISPER_REPO = "ggerganov/whisper.cpp"
    private const val WHISPER_REV = "5359861c739e955e79d9a303bcbc70fb988958b1"

    private val WHISPER_LANGUAGES = listOf(
        "en", "zh", "de", "es", "ru", "ko", "fr", "ja", "pt", "tr", "pl", "ca", "nl", "ar", "sv", "it", "id", "hi", "fi",
        "vi", "he", "uk", "el", "ms", "cs", "ro", "da", "hu", "ta", "no", "th", "ur", "hr", "bg", "lt", "la", "mi", "ml",
        "cy", "sk", "te", "fa", "lv", "bn", "sr", "az", "sl", "kn", "et", "mk", "br", "eu", "is", "hy", "ne", "mn", "bs",
        "kk", "sq", "sw", "gl", "mr", "pa", "si", "km", "sn", "yo", "so", "af", "oc", "ka", "be", "tg", "sd", "gu", "am",
        "yi", "lo", "uz", "fo", "ht", "ps", "tk", "nn", "mt", "sa", "lb", "my", "bo", "tl", "mg", "as", "tt", "haw", "ln",
        "ha", "ba", "jw", "su",
    )

    private fun parakeet(
        id: String, file: String, name: String, licence: String, languages: List<String>, size: Long, sha: String,
        recommended: Boolean = false,
    ) = CatalogModel(id, CatalogKind.SPEECH, CatalogEngine.PARAKEET, file, name, "NVIDIA", licence, languages, size, sha,
        PARAKEET_REPO, PARAKEET_REV, recommended)

    private fun whisper(id: String, file: String, name: String, size: Long, sha: String, recommended: Boolean = false) =
        CatalogModel(id, CatalogKind.SPEECH, CatalogEngine.WHISPER, file, name, "OpenAI", "MIT", WHISPER_LANGUAGES, size, sha,
            WHISPER_REPO, WHISPER_REV, recommended)

    /** NVIDIA's Parakeet TDT 0.6B v3 card (CC-BY-4.0) lists these 25 European languages. */
    private val PARAKEET_V3_LANGUAGES = listOf(
        "en", "es", "fr", "de", "bg", "hr", "cs", "da", "nl", "et", "fi", "el", "hu", "it", "lv", "lt", "mt", "pl", "pt",
        "ro", "sk", "sl", "sv", "ru", "uk",
    )

    val PARAKEET_TDT_V3 = parakeet(
        "parakeet-tdt-0.6b-v3", "tdt-0.6b-v3-q5_k.gguf", "Parakeet TDT 0.6B v3 (live text, 5-bit)", "CC-BY-4.0",
        PARAKEET_V3_LANGUAGES, 741_867_360L, "5ebd1d55609b5ad9dac1c457eeb87a9904f199d6fbbb738453182d010646c2e4", recommended = true,
    )
    val PARAKEET_TDT_CTC_110M = parakeet(
        "parakeet-tdt-ctc-110m", "tdt_ctc-110m-q5_k.gguf", "Parakeet TDT-CTC 110M (live text, English, fast, 5-bit)", "CC-BY-4.0",
        listOf("en"), 143_290_496L, "1316a7a708a083179ea133421dc437ceee6719a159489204a1bb6ff559793e5b",
    )

    val WHISPER_TINY = whisper(
        "whisper-tiny", "ggml-tiny-q5_1.bin", "Whisper tiny (multilingual, 5-bit)", 32_152_673L,
        "818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7",
    )
    val WHISPER_BASE = whisper(
        "whisper-base", "ggml-base-q5_1.bin", "Whisper base (multilingual, 5-bit)", 59_707_625L,
        "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898",
    )
    val WHISPER_SMALL = whisper(
        "whisper-small", "ggml-small-q5_1.bin", "Whisper small (multilingual, 5-bit)", 190_085_487L,
        "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb", recommended = true,
    )

    val S1_MINI = CatalogModel(
        "s1-mini", CatalogKind.CLEAN_UP, CatalogEngine.LLAMA, "s1-mini-q4_k_m.gguf", "S1-mini by Superwhisper (English)",
        "Superwhisper", "Apache-2.0 with the S1-mini naming term", listOf("en"), 484_219_808L,
        "3b41ebe2502cbd03e811d5d16b022f5ab551eda58d62597d152f89535003c634",
        "superwhisper/s1-mini-GGUF", "34add00a48a2e5d24e5a4ee5405a99620a3a240c", recommended = true,
    )

    val QWEN3_5_0_8B = CatalogModel(
        "qwen3.5-0.8b", CatalogKind.GRAMMAR, CatalogEngine.LLAMA, "Qwen3.5-0.8B-Q4_K_M.gguf", "Qwen3.5 0.8B (4-bit)",
        "Qwen team, Alibaba Cloud", "Apache-2.0", listOf(MULTILINGUAL), 532_517_120L,
        "bd258782e35f7f458f8aced1adc053e6e92e89bc735ba3be89d38a06121dc517",
        "unsloth/Qwen3.5-0.8B-GGUF", "6ab461498e2023f6e3c1baea90a8f0fe38ab64d0", recommended = true,
    )

    /** In order of preference within a kind: with no choice made, the first installed one is used. */
    val all: List<CatalogModel> = listOf(
        PARAKEET_TDT_V3, PARAKEET_TDT_CTC_110M,
        WHISPER_SMALL, WHISPER_TINY, WHISPER_BASE,
        S1_MINI,
        QWEN3_5_0_8B,
    )

    fun byId(id: String?): CatalogModel? = if (id == null) null else all.firstOrNull { it.id == id }

    fun forKind(kind: CatalogKind) = all.filter { it.kind == kind }
}
