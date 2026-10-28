// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo.assist

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/** Remembers which model files crashed the model process, keyed by file name; the value identifies the file version. */
interface BrokenMarks {
    fun get(fileName: String): String?
    fun set(fileName: String, value: String?)
}

enum class RejectReason { UNKNOWN_FILE_NAME, NOT_A_MODEL, WRONG_SIZE, WRONG_CONTENT, NOT_ENOUGH_SPACE, READ_ERROR }

sealed interface ImportResult {
    data class Imported(val spec: ModelSpec) : ImportResult
    data class Rejected(val reason: RejectReason) : ImportResult
}

/**
 * The model files in the app's private storage ([dir]; nothing else is ever loaded). Files get there only through
 * [import] (the file the user picked in the system file picker, validated first), [ensureBundled] (a file of the
 * APK's assets, validated the same way) or the documented adb route.
 */
class ModelStore(
    private val dir: File,
    private val marks: BrokenMarks,
    private val catalog: List<ModelSpec> = ModelCatalog.all,
    /** Opens a file of the APK's assets, for [ensureBundled]. */
    private val openAsset: (String) -> InputStream = { throw IOException("no assets") },
    private val usableSpace: () -> Long = { dir.usableSpace },
) {
    fun file(spec: ModelSpec) = File(dir, spec.fileName)

    private fun version(file: File) = "${file.length()}:${file.lastModified()}"

    fun status(spec: ModelSpec): ModelStatus {
        val file = file(spec)
        if (!file.isFile) return ModelStatus.Missing
        if (marks.get(spec.fileName) == version(file)) return ModelStatus.Broken(file)
        return ModelStatus.Installed(file, file.length())
    }

    /**
     * The model to use for [kind], or null when none fits: the one the user chose ([preferredId]) if it is installed,
     * usable on this phone and (when [languageCode] is given) meant for that language; otherwise the first such model of
     * the catalog order. A model for another language is never used: with a [languageCode] and no installed model meant
     * for it the answer is null, so the caller offers a fitting model instead. [usable] is for things only the caller
     * knows, such as the phone's CPU.
     */
    fun best(
        kind: ModelKind,
        preferredId: String? = null,
        languageCode: String? = null,
        usable: (ModelSpec) -> Boolean = { true },
    ): ModelSpec? {
        val installed = catalog.filter { it.kind == kind && status(it) is ModelStatus.Installed && usable(it) }
        val fitting = installed.filter { languageCode == null || it.supports(languageCode) }
        return fitting.firstOrNull { it.id == preferredId } ?: fitting.firstOrNull()
    }

    /** Removes the installed file of [spec] (and any broken mark). True when no file is left afterwards. */
    fun delete(spec: ModelSpec): Boolean {
        val file = file(spec)
        val gone = !file.exists() || file.delete()
        if (gone) marks.set(spec.fileName, null)
        return gone
    }

    /** Called when the model process died while loading [spec]: do not try this exact file again. */
    fun markBroken(spec: ModelSpec) {
        val file = file(spec)
        if (file.isFile) marks.set(spec.fileName, version(file))
    }

    /**
     * Copies [input] (the file the user picked, named [displayName]) into the model folder. The copy is checked
     * before it replaces anything: a known name, the model file header, exactly the size of the reference file and
     * its SHA-256 digest. Native code parses these files, so a file that is not byte for byte the reference
     * download is refused. The copy goes to a temporary file of its own and is moved over the old model in one atomic
     * step; on any failure nothing is left behind and the old model stays.
     */
    fun import(displayName: String?, input: InputStream): ImportResult {
        // a bundled model only ever comes from the APK: a file with its name is treated like any unknown file
        val spec = catalog.firstOrNull { it.fileName.equals(displayName, ignoreCase = true) && it.bundledAsset == null }
            ?: return ImportResult.Rejected(RejectReason.UNKNOWN_FILE_NAME)
        return synchronized(INSTALL_LOCK) { install(spec, input) }
    }

    /**
     * Makes sure the bundled model [spec] (one with a [ModelSpec.bundledAsset]) is in the model folder, copying it from
     * the APK's assets through the same checked path as [import]. True when it is installed afterwards; on any failure
     * false, and nothing is left behind.
     */
    fun ensureBundled(spec: ModelSpec): Boolean {
        val asset = spec.bundledAsset ?: return false
        val file = file(spec)
        if (status(spec) is ModelStatus.Installed && file.length() == spec.sizeBytes) return true
        return try {
            openAsset(asset).use { synchronized(INSTALL_LOCK) { install(spec, it) } } is ImportResult.Imported
        } catch (_: IOException) {
            false
        }
    }

    private fun install(spec: ModelSpec, input: InputStream): ImportResult {
        dir.mkdirs() // first: File.usableSpace of a folder that does not exist yet is 0, which refused every first model
        if (usableSpace() < spec.sizeBytes) return ImportResult.Rejected(RejectReason.NOT_ENOUGH_SPACE)
        var part: File? = null
        try {
            val temp = File.createTempFile(spec.fileName + ".", PART_SUFFIX, dir)
            part = temp
            val digest = MessageDigest.getInstance("SHA-256")
            val header = ByteArray(ModelValidator.HEADER_BYTES)
            var copied = 0L
            FileOutputStream(temp).use { out ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (copied < header.size) buffer.copyInto(header, copied.toInt(), 0, minOf(n.toLong(), header.size - copied).toInt())
                    copied += n
                    if (copied > spec.sizeBytes) return reject(temp, RejectReason.WRONG_SIZE)
                    digest.update(buffer, 0, n)
                    out.write(buffer, 0, n)
                }
                out.flush()
                out.fd.sync()
            }
            val problem = ModelValidator.validate(spec, header.copyOf(minOf(copied, header.size.toLong()).toInt()), copied)
            if (problem != null) return reject(temp, problem)
            if (!digest.digest().toHex().equals(spec.sha256, ignoreCase = true)) return reject(temp, RejectReason.WRONG_CONTENT)
            // rename(2) replaces the old file atomically: there is no moment without a model, and a crash leaves the old one
            if (!temp.renameTo(file(spec))) throw IOException("cannot move ${temp.name}")
            part = null
            marks.set(spec.fileName, null)
            return ImportResult.Imported(spec)
        } catch (_: IOException) {
            part?.delete()
            return ImportResult.Rejected(RejectReason.READ_ERROR)
        }
    }

    private fun reject(part: File, reason: RejectReason): ImportResult {
        part.delete()
        return ImportResult.Rejected(reason)
    }

    /**
     * Deletes leftovers of interrupted imports. Only temporary files that have not been written to for [maxAgeMs]
     * go: a younger one may belong to an import that is running right now.
     */
    fun cleanUp(now: Long = System.currentTimeMillis(), maxAgeMs: Long = STALE_PART_MS) {
        dir.listFiles { f -> f.name.endsWith(PART_SUFFIX) && now - f.lastModified() >= maxAgeMs }?.forEach { it.delete() }
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    companion object {
        /** One install at a time in the whole process: several hand-overs at once would each write a temporary file of the model's size. */
        private val INSTALL_LOCK = Any()
        const val PART_SUFFIX = ".part"
        const val STALE_PART_MS = 60 * 60 * 1000L
    }
}

object ModelValidator {
    /** Enough bytes to check every header field: Whisper's mel filter sizes are at offsets 48 and 52. */
    const val HEADER_BYTES = 56

    /** ggml file magic of Whisper models ("ggml" stored as a little-endian uint32, so the bytes read "lmgg"). */
    private val WHISPER_MAGIC = byteArrayOf('l'.code.toByte(), 'm'.code.toByte(), 'g'.code.toByte(), 'g'.code.toByte())
    private val GGUF_MAGIC = byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte())

    /**
     * Returns why the file is not usable, or null when its header and size look right for [spec]. This is the cheap
     * part of the check; the digest comparison is done by [ModelStore.import].
     */
    fun validate(spec: ModelSpec, header: ByteArray, size: Long): RejectReason? {
        if (header.size < 8) return RejectReason.NOT_A_MODEL
        val magicOk = when (spec.kind) {
            ModelKind.SPEECH -> when (spec.engine) {
                SpeechEngine.PARAKEET -> header.copyOfRange(0, 4).contentEquals(GGUF_MAGIC) && ggufVersionOk(header)
                else -> header.copyOfRange(0, 4).contentEquals(WHISPER_MAGIC) && whisperFiltersOk(header)
            }
            ModelKind.CLEAN_UP, ModelKind.GRAMMAR -> header.copyOfRange(0, 4).contentEquals(GGUF_MAGIC) && ggufVersionOk(header)
            // exact size and digest only; whisper.cpp checks the file itself when it loads it
            ModelKind.VAD -> true
        }
        if (!magicOk) return RejectReason.NOT_A_MODEL
        if (size != spec.sizeBytes) return RejectReason.WRONG_SIZE
        return null
    }

    /**
     * whisper.cpp sizes buffers by the mel filter band count and FFT size it reads from the file without checking
     * them, so a crafted header overflows a heap buffer. Real models have 80 or 128 bands and 201 FFT bins; the
     * model process checks the same numbers again before it hands a file to whisper.cpp (ml_core.cpp).
     */
    fun whisperFiltersOk(header: ByteArray): Boolean {
        if (header.size < HEADER_BYTES) return false
        val bands = le32(header, 48)
        val bins = le32(header, 52)
        return (bands == 80 || bands == 128) && bins == 201
    }

    private fun ggufVersionOk(header: ByteArray) = le32(header, 4) in 2..3

    private fun le32(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)
}
