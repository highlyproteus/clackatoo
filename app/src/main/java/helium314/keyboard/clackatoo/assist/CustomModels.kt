// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo.assist

import helium314.keyboard.clackatoo.catalog.ModelsProtocol
import java.io.File
import java.io.FileInputStream
import java.io.IOException

/**
 * Experimental custom models: files the user chose that are not in the catalog and so are not pinned by size and SHA-256.
 * They are the one exception to "a model file is byte for byte a known file" (AGENTS.md rule 5) and are allowed only through
 * two paths, both started by the user: "Import file" in Settings, and the companion app's "Browse Hugging Face" screen. Either
 * way the keyboard checks the file itself (size cap, format from the header, one test load in the isolated model process),
 * keeps it apart from the catalog models ([CustomPolicy.DIR_NAME]) and shows it as experimental. Models always run in the
 * isolated process, custom or not.
 */
object CustomPolicy {
    /** Folder of the custom models inside the model folder; the catalog's file names never live here, so they cannot collide. */
    const val DIR_NAME = "custom"
    const val MAX_BYTES = ModelsProtocol.MAX_CUSTOM_BYTES
    const val MAX_MODELS = ModelsProtocol.MAX_CUSTOM_MODELS
    /** Free space that must remain after copying (the temporary copy and the test load need some room). */
    const val SPARE_BYTES = 128L * 1024 * 1024
    /** How much of the file's start is read to find its format; the GGUF key `general.architecture` is the first or one of the first keys. */
    const val HEADER_WINDOW = 256 * 1024
    const val ID_PREFIX = "custom:"

    /** The roles a custom model can have, with the folder name and the id part each uses. */
    fun roleDir(kind: ModelKind): String? = when (kind) {
        ModelKind.SPEECH -> "speech"
        ModelKind.CLEAN_UP -> "clean_up"
        ModelKind.GRAMMAR -> "grammar"
        ModelKind.VAD -> null
    }

    fun roleOfDir(dir: String): ModelKind? = ModelKind.entries.firstOrNull { roleDir(it) == dir }

    /** The `ModelsProtocol.CUSTOM_ROLE_*` of the companion's hand-over call, or null for a number that is not a role. */
    fun roleOfProtocol(role: Int): ModelKind? = when (role) {
        ModelsProtocol.CUSTOM_ROLE_SPEECH -> ModelKind.SPEECH
        ModelsProtocol.CUSTOM_ROLE_CLEAN_UP -> ModelKind.CLEAN_UP
        ModelsProtocol.CUSTOM_ROLE_GRAMMAR -> ModelKind.GRAMMAR
        else -> null
    }

    fun id(kind: ModelKind, fileName: String): String = "$ID_PREFIX${roleDir(kind)}:$fileName"

    /** The role and file name of a custom id, or null when [id] is not one (a catalog id, or a name that is not a sanitised file name). */
    fun parseId(id: String?): Pair<ModelKind, String>? {
        if (id == null || !id.startsWith(ID_PREFIX) || id.length > ID_PREFIX.length + 16 + CustomNames.MAX_LENGTH) return null
        val rest = id.substring(ID_PREFIX.length)
        val colon = rest.indexOf(':')
        if (colon <= 0) return null
        val kind = roleOfDir(rest.substring(0, colon)) ?: return null
        val name = rest.substring(colon + 1)
        return if (CustomNames.isSanitised(name)) kind to name else null
    }

    fun isCustomId(id: String?) = parseId(id) != null

    /** Whether a model of [format] can serve [kind]: speech models for speech, GGUF text models for clean-up and grammar. */
    fun fits(kind: ModelKind, format: ModelFormat): Boolean = when (kind) {
        ModelKind.SPEECH -> format is ModelFormat.Whisper || format is ModelFormat.Parakeet
        ModelKind.CLEAN_UP, ModelKind.GRAMMAR -> format is ModelFormat.Gguf
        ModelKind.VAD -> false
    }
}

/** File names of custom models: whatever the source called the file, only a short, plain name is ever used on disk. */
object CustomNames {
    const val MAX_LENGTH = 80
    private const val FALLBACK = "model"
    private val extension = Regex("\\.[A-Za-z0-9]{1,8}$")
    private val dots = Regex("\\.{2,}")
    private val allowed = Regex("[A-Za-z0-9._-]{1,$MAX_LENGTH}")

    /**
     * The name a custom file is stored under: the last path segment of [raw], every character outside `A-Z a-z 0-9 . _ -`
     * replaced by `_` (runs collapsed), no leading dot, dash or underscore, at most [MAX_LENGTH] characters (the extension is
     * kept), never empty, never `..`, and never the suffix of a temporary file. Two different raw names can give the same
     * result; importing one then replaces the other.
     */
    fun sanitize(raw: String?): String {
        val segment = (raw ?: "").substringAfterLast('/').substringAfterLast('\\')
        var ext = extension.find(segment)?.value.orEmpty()
        if (ext.equals(ModelStore.PART_SUFFIX, ignoreCase = true)) ext = "_part"
        val out = StringBuilder()
        for (c in segment.removeSuffix(extension.find(segment)?.value.orEmpty())) {
            val ok = c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '.' || c == '-' || c == '_'
            if (ok) out.append(c) else if (out.isEmpty() || out.last() != '_') out.append('_')
        }
        var base = dots.replace(out.toString(), ".").trim('.', '-', '_')
        if (base.isEmpty()) base = FALLBACK
        if (base.length + ext.length > MAX_LENGTH) base = base.take(MAX_LENGTH - ext.length).trimEnd('.', '_', '-').ifEmpty { FALLBACK }
        return base + ext
    }

    /** True for exactly what [sanitize] can produce. */
    fun isSanitised(name: String) = allowed.matches(name) && sanitize(name) == name

    /** [name] with the extension of its format (`.gguf`, `.bin`) when it has none of it. */
    fun withExtension(name: String, format: ModelFormat): String {
        val want = when (format) {
            is ModelFormat.Whisper -> ".bin"
            is ModelFormat.Parakeet, is ModelFormat.Gguf -> ".gguf"
            is ModelFormat.Unsupported, ModelFormat.Unknown -> return name
        }
        if (name.endsWith(want, ignoreCase = true)) return name
        return sanitize(name.removeSuffix(extension.find(name)?.value.orEmpty()) + want)
    }

    /** What a card calls the model: the file name without its extension. */
    fun title(name: String): String = name.removeSuffix(extension.find(name)?.value.orEmpty()).ifEmpty { name }
}

/** What a model file is, found from its first bytes only. */
sealed interface ModelFormat {
    /** A Whisper ggml file (the header passed [ModelValidator.whisperFiltersOk]); [englishOnly] for the `.en` models. */
    data class Whisper(val englishOnly: Boolean) : ModelFormat
    /** A parakeet.cpp GGUF with `general.architecture` "parakeet". */
    data object Parakeet : ModelFormat
    /** Any other GGUF that may be a text model; whether llama.cpp supports [architecture] is found by the test load. */
    data class Gguf(val architecture: String) : ModelFormat
    /** A file that is recognised but cannot serve any role (a GGUF of another tool, a bundle, a voice detector). */
    data class Unsupported(val architecture: String) : ModelFormat
    /** Not a model file this app can use. */
    data object Unknown : ModelFormat
}

object ModelFormats {
    private val GGUF_MAGIC = byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte())
    private val WHISPER_MAGIC = byteArrayOf('l'.code.toByte(), 'm'.code.toByte(), 'g'.code.toByte(), 'g'.code.toByte())
    private const val WHISPER_VOCAB_OFFSET = 4
    private const val WHISPER_ENGLISH_VOCAB = 51864
    private const val MAX_KEYS_SCANNED = 512
    private const val MAX_KEY_BYTES = 256
    private const val MAX_ARCH_BYTES = 64
    private val archName = Regex("[a-z0-9][a-z0-9._-]{0,63}")

    /** GGUF files of the parakeet.cpp family that are not speech recognisers, and files that are not language models. */
    private val NOT_A_TEXT_MODEL = setOf("parakeet-bundle", "silero_vad", "ced", "voicedetect", "clip", "mmproj")

    /** The format of a file whose first [length] bytes are in [header]. Reads nothing beyond them and never throws. */
    fun detect(header: ByteArray, length: Int = header.size): ModelFormat {
        val n = minOf(length, header.size)
        if (n < 8) return ModelFormat.Unknown
        if (header.copyOfRange(0, 4).contentEquals(WHISPER_MAGIC)) {
            if (n < ModelValidator.HEADER_BYTES || !ModelValidator.whisperFiltersOk(header)) return ModelFormat.Unknown
            return ModelFormat.Whisper(englishOnly = le32(header, WHISPER_VOCAB_OFFSET) == WHISPER_ENGLISH_VOCAB)
        }
        if (!header.copyOfRange(0, 4).contentEquals(GGUF_MAGIC)) return ModelFormat.Unknown
        if (le32(header, 4) !in 2..3) return ModelFormat.Unknown
        val arch = try { Gguf(header, n).findArchitecture() } catch (_: Truncated) { null } catch (_: ArithmeticException) { null } ?: return ModelFormat.Unknown
        if (!archName.matches(arch)) return ModelFormat.Unknown
        return when {
            arch == "parakeet" -> ModelFormat.Parakeet
            arch in NOT_A_TEXT_MODEL -> ModelFormat.Unsupported(arch)
            else -> ModelFormat.Gguf(arch)
        }
    }

    /** Reads the start of [file] and detects its format; [ModelFormat.Unknown] when it cannot be read. */
    fun detect(file: File): ModelFormat = try {
        FileInputStream(file).use { input ->
            val buffer = ByteArray(CustomPolicy.HEADER_WINDOW)
            var n = 0
            while (n < buffer.size) {
                val r = input.read(buffer, n, buffer.size - n)
                if (r < 0) break
                n += r
            }
            detect(buffer, n)
        }
    } catch (_: IOException) {
        ModelFormat.Unknown
    }

    private class Truncated : Exception()

    /** A bounds-checked walk over the key/value section of a GGUF v2/v3 header that is only partly in memory. */
    private class Gguf(val b: ByteArray, val n: Int) {
        var pos = 0L

        fun need(count: Long) {
            if (count < 0 || count > n - pos) throw Truncated()
        }

        fun u32(): Long { need(4); val v = le32(b, pos.toInt()).toLong() and 0xffffffffL; pos += 4; return v }

        fun u64(): Long {
            need(8)
            var v = 0L
            for (i in 7 downTo 0) v = (v shl 8) or (b[(pos + i).toInt()].toLong() and 0xff)
            pos += 8
            return v
        }

        fun skip(count: Long) { need(count); pos += count }

        fun string(max: Int): String {
            val len = u64()
            if (len < 0 || len > max) throw Truncated()
            need(len)
            val s = String(b, pos.toInt(), len.toInt(), Charsets.UTF_8)
            pos += len
            return s
        }

        fun skipString() { val len = u64(); if (len < 0) throw Truncated(); skip(len) }

        /** Skips a value of GGUF type [type]; arrays of strings are walked, every other array has a fixed element size. */
        fun skipValue(type: Long, depth: Int = 0) {
            when (type) {
                0L, 1L, 7L -> skip(1)
                2L, 3L -> skip(2)
                4L, 5L, 6L -> skip(4)
                10L, 11L, 12L -> skip(8)
                8L -> skipString()
                9L -> {
                    if (depth > 0) throw Truncated() // arrays of arrays: not worth supporting here
                    val elementType = u32()
                    val count = u64()
                    if (count < 0) throw Truncated()
                    when (elementType) {
                        0L, 1L, 7L -> skip(count)
                        2L, 3L -> skip(Math.multiplyExact(count, 2L))
                        4L, 5L, 6L -> skip(Math.multiplyExact(count, 4L))
                        10L, 11L, 12L -> skip(Math.multiplyExact(count, 8L))
                        8L -> { var i = 0L; while (i < count) { skipString(); i++ } }
                        else -> throw Truncated()
                    }
                }
                else -> throw Truncated()
            }
        }

        fun findArchitecture(): String? {
            pos = 8
            u64() // tensor count
            val keys = u64()
            if (keys < 0 || keys > 1_000_000) return null
            var i = 0L
            while (i < minOf(keys, MAX_KEYS_SCANNED.toLong())) {
                val key = string(MAX_KEY_BYTES)
                val type = u32()
                if (key == "general.architecture") return if (type == 8L) string(MAX_ARCH_BYTES) else null
                skipValue(type)
                i++
            }
            return null
        }
    }

    private fun le32(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)
}
