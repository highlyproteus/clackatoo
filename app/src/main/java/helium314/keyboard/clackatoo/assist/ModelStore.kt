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

enum class CustomReject { TOO_LARGE, NOT_A_MODEL, WRONG_ROLE, NOT_ENOUGH_SPACE, READ_ERROR, LOAD_FAILED, TOO_MANY }

sealed interface CustomImportResult {
    data class Imported(val spec: ModelSpec) : CustomImportResult
    data class Rejected(val reason: CustomReject) : CustomImportResult
}

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
    /** The size cap of custom models; only tests use another value. */
    private val maxCustomBytes: Long = CustomPolicy.MAX_BYTES,
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
        // an experimental custom model is used only when the user chose it, never as a fallback
        customSpec(preferredId)?.takeIf {
            it.kind == kind && status(it) is ModelStatus.Installed && usable(it) && (languageCode == null || it.supports(languageCode))
        }?.let { return it }
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
        val stale = { f: File -> f.name.endsWith(PART_SUFFIX) && now - f.lastModified() >= maxAgeMs }
        dir.listFiles(stale)?.forEach { it.delete() }
        ModelKind.entries.mapNotNull { CustomPolicy.roleDir(it) }.forEach { role ->
            File(File(dir, CustomPolicy.DIR_NAME), role).listFiles(stale)?.forEach { it.delete() }
        }
    }

    // ------------------------------------------------------------------ experimental custom models

    private val formats = HashMap<String, ModelFormat>()

    private fun customFolder(kind: ModelKind) = File(File(dir, CustomPolicy.DIR_NAME), CustomPolicy.roleDir(kind) ?: "none")

    /** The format of an installed custom file, read from its start once per file version. */
    private fun formatOf(file: File): ModelFormat {
        val key = "${file.path}:${file.length()}:${file.lastModified()}"
        return synchronized(formats) { formats.getOrPut(key) { ModelFormats.detect(file) } }
    }

    private fun customSpec(kind: ModelKind, name: String, format: ModelFormat, size: Long) = ModelSpec(
        kind = kind,
        fileName = "${CustomPolicy.DIR_NAME}/${CustomPolicy.roleDir(kind)}/$name",
        displayName = CustomNames.title(name), licence = "", sizeBytes = size, sha256 = "",
        engine = if (kind == ModelKind.SPEECH) (if (format is ModelFormat.Parakeet) SpeechEngine.PARAKEET else SpeechEngine.WHISPER) else null,
        id = CustomPolicy.id(kind, name),
        languages = if (format is ModelFormat.Whisper && format.englishOnly) listOf("en") else emptyList(),
        shortName = CustomNames.title(name), custom = true,
    )

    /** The custom models on the phone, for [kind] or all roles, sorted by name. Files that are no longer what their role needs are left out. */
    fun customSpecs(kind: ModelKind? = null): List<ModelSpec> =
        (if (kind == null) ModelKind.entries else listOf(kind)).filter { CustomPolicy.roleDir(it) != null }.flatMap { k ->
            customFolder(k).listFiles { f -> f.isFile && !f.name.endsWith(PART_SUFFIX) && CustomNames.isSanitised(f.name) }.orEmpty()
                .sortedBy { it.name }
                .mapNotNull { f -> formatOf(f).takeIf { CustomPolicy.fits(k, it) }?.let { customSpec(k, f.name, it, f.length()) } }
        }

    /** The installed custom model with id [id], or null (also for a catalog id, a malformed id or a file that is gone). */
    fun customSpec(id: String?): ModelSpec? {
        val (kind, name) = CustomPolicy.parseId(id) ?: return null
        val file = File(customFolder(kind), name)
        if (!file.isFile) return null
        return formatOf(file).takeIf { CustomPolicy.fits(kind, it) }?.let { customSpec(kind, name, it, file.length()) }
    }

    /**
     * Copies [input] into the custom model folder of [kind], under a sanitised version of [rawName]. The file is checked
     * before it is kept: at most [CustomPolicy.MAX_BYTES] ([sizeHint], when known, is checked before anything is copied, and
     * the free space with it), a format found from its header that fits the role, at most [CustomPolicy.MAX_MODELS] custom
     * models, and [probe] (one test load of the temporary file in the model process) must succeed. The copy is a temporary
     * file of its own and is moved into place atomically; on any failure nothing is left behind.
     */
    fun importCustom(kind: ModelKind, rawName: String?, input: InputStream, sizeHint: Long, probe: (File, ModelSpec) -> Boolean): CustomImportResult {
        if (CustomPolicy.roleDir(kind) == null) return CustomImportResult.Rejected(CustomReject.NOT_A_MODEL)
        if (sizeHint > maxCustomBytes) return CustomImportResult.Rejected(CustomReject.TOO_LARGE)
        return synchronized(INSTALL_LOCK) { installCustom(kind, rawName, input, sizeHint, probe) }
    }

    private fun customCount() = ModelKind.entries.filter { CustomPolicy.roleDir(it) != null }.sumOf { k ->
        customFolder(k).listFiles { f -> f.isFile && !f.name.endsWith(PART_SUFFIX) }?.size ?: 0
    }

    private fun installCustom(kind: ModelKind, rawName: String?, input: InputStream, sizeHint: Long, probe: (File, ModelSpec) -> Boolean): CustomImportResult {
        val folder = customFolder(kind)
        folder.mkdirs()
        val baseName = CustomNames.sanitize(rawName)
        if (customCount() >= CustomPolicy.MAX_MODELS && !File(folder, baseName).exists()) return CustomImportResult.Rejected(CustomReject.TOO_MANY)
        if (usableSpace() < maxOf(sizeHint, 0L) + CustomPolicy.SPARE_BYTES) return CustomImportResult.Rejected(CustomReject.NOT_ENOUGH_SPACE)
        var part: File? = null
        try {
            val temp = File.createTempFile("import.", PART_SUFFIX, folder)
            part = temp
            val header = ByteArray(CustomPolicy.HEADER_WINDOW)
            var copied = 0L
            var checkedAt = 0L
            FileOutputStream(temp).use { out ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (copied < header.size) buffer.copyInto(header, copied.toInt(), 0, minOf(n.toLong(), header.size - copied).toInt())
                    copied += n
                    if (copied > maxCustomBytes) return customReject(temp, CustomReject.TOO_LARGE)
                    if (copied - checkedAt >= SPACE_CHECK_BYTES) {
                        checkedAt = copied
                        if (usableSpace() < CustomPolicy.SPARE_BYTES / 4) return customReject(temp, CustomReject.NOT_ENOUGH_SPACE)
                    }
                    out.write(buffer, 0, n)
                }
                out.flush()
                out.fd.sync()
            }
            val format = ModelFormats.detect(header, minOf(copied, header.size.toLong()).toInt())
            if (format is ModelFormat.Unknown || format is ModelFormat.Unsupported) return customReject(temp, CustomReject.NOT_A_MODEL)
            if (!CustomPolicy.fits(kind, format)) return customReject(temp, CustomReject.WRONG_ROLE)
            val name = CustomNames.withExtension(baseName, format)
            val spec = customSpec(kind, name, format, copied)
            val loaded = try { probe(temp, spec) } catch (_: Exception) { false }
            if (!loaded) return customReject(temp, CustomReject.LOAD_FAILED)
            val target = File(folder, name)
            if (customCount() >= CustomPolicy.MAX_MODELS && !target.exists()) return customReject(temp, CustomReject.TOO_MANY)
            // rename(2) replaces an older custom model of the same name atomically
            if (!temp.renameTo(target)) throw IOException("cannot move ${temp.name}")
            part = null
            marks.set(spec.fileName, null)
            return CustomImportResult.Imported(spec)
        } catch (e: IOException) {
            part?.delete()
            return CustomImportResult.Rejected(if (e.message?.contains("ENOSPC") == true || e.message?.contains("No space") == true) CustomReject.NOT_ENOUGH_SPACE else CustomReject.READ_ERROR)
        }
    }

    private fun customReject(part: File, reason: CustomReject): CustomImportResult {
        part.delete()
        return CustomImportResult.Rejected(reason)
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    companion object {
        /** One install at a time in the whole process: several hand-overs at once would each write a temporary file of the model's size. */
        private val INSTALL_LOCK = Any()
        private const val SPACE_CHECK_BYTES = 64L * 1024 * 1024
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
