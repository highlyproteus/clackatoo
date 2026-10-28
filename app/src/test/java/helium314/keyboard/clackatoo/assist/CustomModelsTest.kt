// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo.assist

import helium314.keyboard.clackatoo.catalog.Catalog
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Header builders for the formats custom models are recognised by. */
internal object ModelBytes {
    /** A Whisper ggml header: magic, vocabulary size at offset 4, mel filters of [bands] bands and [bins] FFT bins at 48 and 52. */
    fun whisper(size: Int = 1000, vocab: Int = 51865, bands: Int = 80, bins: Int = 201, fill: Byte = 7): ByteArray = ByteArray(size) { fill }.also {
        "lmgg".toByteArray().copyInto(it)
        for (i in 0 until 4) {
            it[4 + i] = (vocab shr (8 * i)).toByte()
            it[48 + i] = (bands shr (8 * i)).toByte()
            it[52 + i] = (bins shr (8 * i)).toByte()
        }
    }

    private fun ByteArrayOutputStream.u32(v: Int) { for (i in 0 until 4) write(v shr (8 * i)) }
    private fun ByteArrayOutputStream.u64(v: Long) { for (i in 0 until 8) write((v shr (8 * i)).toInt()) }
    private fun ByteArrayOutputStream.str(s: String) { u64(s.length.toLong()); write(s.toByteArray()) }

    /**
     * A GGUF header with [keys] first (string values, or [Skipped] values of other types) and then `general.architecture`
     * = [architecture] (when not null), padded with [fill] bytes to [size].
     */
    fun gguf(architecture: String?, keys: List<Pair<String, Any>> = emptyList(), version: Int = 3, size: Int = 4096, fill: Byte = 0): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("GGUF".toByteArray())
        out.u32(version)
        out.u64(0) // tensors
        out.u64((keys.size + if (architecture != null) 1 else 0).toLong())
        for ((k, v) in keys) {
            out.str(k)
            when (v) {
                is String -> { out.u32(8); out.str(v) }
                is Int -> { out.u32(4); out.u32(v) }
                is List<*> -> { out.u32(9); out.u32(8); out.u64(v.size.toLong()); v.forEach { out.str(it as String) } } // array of strings
                is LongArray -> { out.u32(9); out.u32(10); out.u64(v.size.toLong()); v.forEach { out.u64(it) } } // array of u64
                else -> error("unsupported test value")
            }
        }
        if (architecture != null) { out.str("general.architecture"); out.u32(8); out.str(architecture) }
        val bytes = out.toByteArray()
        return ByteArray(maxOf(size, bytes.size)) { fill }.also { bytes.copyInto(it) }
    }
}

class ModelFormatTest {
    @Test fun `a Whisper file is recognised and English-only models are told apart by the vocabulary size`() {
        assertEquals(ModelFormat.Whisper(englishOnly = false), ModelFormats.detect(ModelBytes.whisper()))
        assertEquals(ModelFormat.Whisper(englishOnly = true), ModelFormats.detect(ModelBytes.whisper(vocab = 51864)))
        assertEquals(ModelFormat.Whisper(englishOnly = false), ModelFormats.detect(ModelBytes.whisper(bands = 128)))
    }

    @Test fun `a Whisper header with other mel filter sizes is not accepted, because whisper_cpp sizes buffers by them`() {
        assertEquals(ModelFormat.Unknown, ModelFormats.detect(ModelBytes.whisper(bands = 100)))
        assertEquals(ModelFormat.Unknown, ModelFormats.detect(ModelBytes.whisper(bins = 1 shl 30)))
        assertEquals(ModelFormat.Unknown, ModelFormats.detect(ModelBytes.whisper().copyOf(40))) // truncated before the filters
    }

    @Test fun `a Parakeet GGUF is recognised by its architecture, wherever the key is among the first keys`() {
        assertEquals(ModelFormat.Parakeet, ModelFormats.detect(ModelBytes.gguf("parakeet")))
        val others = listOf("general.name" to "x", "general.file_type" to 7, "general.tags" to listOf("a", "b", "c"), "general.sizes" to longArrayOf(1, 2, 3))
        assertEquals(ModelFormat.Parakeet, ModelFormats.detect(ModelBytes.gguf("parakeet", keys = others)))
    }

    @Test fun `any other GGUF is a possible text model and keeps its architecture name`() {
        assertEquals(ModelFormat.Gguf("qwen3"), ModelFormats.detect(ModelBytes.gguf("qwen3")))
        assertEquals(ModelFormat.Gguf("llama"), ModelFormats.detect(ModelBytes.gguf("llama", version = 2)))
    }

    @Test fun `GGUF files of other tools are recognised but not usable`() {
        for (arch in listOf("parakeet-bundle", "silero_vad", "ced", "voicedetect", "clip", "mmproj"))
            assertEquals(ModelFormat.Unsupported(arch), ModelFormats.detect(ModelBytes.gguf(arch)), arch)
    }

    @Test fun `garbage, an empty file and other formats are unknown`() {
        assertEquals(ModelFormat.Unknown, ModelFormats.detect(ByteArray(0)))
        assertEquals(ModelFormat.Unknown, ModelFormats.detect("hello, this is just a text file".toByteArray()))
        assertEquals(ModelFormat.Unknown, ModelFormats.detect(ByteArray(5000) { it.toByte() }))
        assertEquals(ModelFormat.Unknown, ModelFormats.detect("PK\u0003\u0004".toByteArray() + ByteArray(100)))
        assertEquals(ModelFormat.Unknown, ModelFormats.detect("GGUF".toByteArray() + ByteArray(4))) // too short
    }

    @Test fun `GGUF versions other than 2 and 3 are unknown`() {
        assertEquals(ModelFormat.Unknown, ModelFormats.detect(ModelBytes.gguf("llama", version = 1)))
        assertEquals(ModelFormat.Unknown, ModelFormats.detect(ModelBytes.gguf("llama", version = 4)))
    }

    @Test fun `a GGUF without an architecture, with a non-string one or a malformed name is unknown`() {
        assertEquals(ModelFormat.Unknown, ModelFormats.detect(ModelBytes.gguf(null, keys = listOf("general.name" to "x"))))
        assertEquals(ModelFormat.Unknown, ModelFormats.detect(ModelBytes.gguf(null, keys = listOf("general.architecture" to 5))))
        assertEquals(ModelFormat.Unknown, ModelFormats.detect(ModelBytes.gguf("Llama 3\u0000 evil")))
        assertEquals(ModelFormat.Unknown, ModelFormats.detect(ModelBytes.gguf("../../etc")))
        assertEquals(ModelFormat.Unknown, ModelFormats.detect(ModelBytes.gguf("x".repeat(200))))
    }

    @Test fun `a hostile header cannot make the reader run past the bytes it has or overflow`() {
        // key count huge, but the data ends: unknown, no exception
        val b = ModelBytes.gguf("llama", size = 64)
        for (i in 16 until 24) b[i] = 0x7f
        assertEquals(ModelFormat.Unknown, ModelFormats.detect(b))
        // a string length of 2^63-1 and of -1 (as unsigned 2^64-1)
        for (len in listOf(Long.MAX_VALUE, -1L)) {
            val h = ByteArray(200)
            "GGUF".toByteArray().copyInto(h); h[4] = 3
            h[16] = 1 // one key
            for (i in 0 until 8) h[24 + i] = (len shr (8 * i)).toByte()
            assertEquals(ModelFormat.Unknown, ModelFormats.detect(h))
        }
        // an array of u64 with a count that overflows when multiplied by the element size
        val out = ByteArrayOutputStream()
        out.write("GGUF".toByteArray()); out.write(byteArrayOf(3, 0, 0, 0)); out.write(ByteArray(8)); out.write(byteArrayOf(2, 0, 0, 0, 0, 0, 0, 0))
        out.write(byteArrayOf(1, 0, 0, 0, 0, 0, 0, 0, 'k'.code.toByte())); out.write(byteArrayOf(9, 0, 0, 0)); out.write(byteArrayOf(10, 0, 0, 0))
        out.write(ByteArray(7) { -1 }); out.write(byteArrayOf(0x7f)); out.write(ByteArray(100))
        assertEquals(ModelFormat.Unknown, ModelFormats.detect(out.toByteArray()))
    }

    @Test fun `an architecture that is past the first keys of a huge header is not searched for`() {
        val many = (0 until 600).map { "k$it" to "v" }
        assertEquals(ModelFormat.Unknown, ModelFormats.detect(ModelBytes.gguf("llama", keys = many, size = 64 * 1024)))
    }

    @Test fun `roles accept only their formats`() {
        assertTrue(CustomPolicy.fits(ModelKind.SPEECH, ModelFormat.Whisper(false)))
        assertTrue(CustomPolicy.fits(ModelKind.SPEECH, ModelFormat.Parakeet))
        assertFalse(CustomPolicy.fits(ModelKind.SPEECH, ModelFormat.Gguf("llama")))
        for (kind in listOf(ModelKind.CLEAN_UP, ModelKind.GRAMMAR)) {
            assertTrue(CustomPolicy.fits(kind, ModelFormat.Gguf("llama")))
            assertFalse(CustomPolicy.fits(kind, ModelFormat.Whisper(false)))
            assertFalse(CustomPolicy.fits(kind, ModelFormat.Parakeet))
        }
        for (format in listOf(ModelFormat.Unknown, ModelFormat.Unsupported("clip"))) {
            for (kind in ModelKind.entries) assertFalse(CustomPolicy.fits(kind, format))
        }
    }
}

class CustomNamesTest {
    @Test fun `plain names are kept`() {
        assertEquals("ggml-tiny.en-q5_1.bin", CustomNames.sanitize("ggml-tiny.en-q5_1.bin"))
        assertEquals("Qwen3-0.6B-Q4_K_M.gguf", CustomNames.sanitize("Qwen3-0.6B-Q4_K_M.gguf"))
    }

    @Test fun `paths, spaces and odd characters are reduced to a short plain name`() {
        assertEquals("evil.gguf", CustomNames.sanitize("../../etc/evil.gguf"))
        assertEquals("evil.gguf", CustomNames.sanitize("C:\\Users\\x\\evil.gguf"))
        assertEquals("my_model_v2.gguf", CustomNames.sanitize("my model (v2).gguf"))
        assertEquals("b.bin", CustomNames.sanitize("a\u0000\n\t/\u202e b.bin")) // only the last path segment
        assertEquals("a_b.bin", CustomNames.sanitize("a\u0000\n\u202e b.bin"))
        assertEquals("model.gguf", CustomNames.sanitize("\u00e9\u00e8.gguf")) // nothing plain left of the name: the fallback, with the extension
    }

    @Test fun `nothing usable becomes the fallback name, never empty, hidden or a dot name`() {
        for (raw in listOf(null, "", "   ", ".", "..", "...", "/", "../", ".hidden", "-rf", "_", "\u0000", "\u202e\u202e")) {
            val s = CustomNames.sanitize(raw)
            assertTrue(s.isNotEmpty(), "$raw")
            assertFalse(s.startsWith("."), "$raw -> $s")
            assertFalse(s.startsWith("-"), "$raw -> $s")
            assertTrue(CustomNames.isSanitised(s), "$raw -> $s")
        }
        assertEquals("model", CustomNames.sanitize(".."))
        assertEquals("model.hidden", CustomNames.sanitize(".hidden"))
        assertEquals("rf", CustomNames.sanitize("-rf"))
    }

    @Test fun `long names are shortened and keep their extension, and the result is at most 80 characters`() {
        val s = CustomNames.sanitize("x".repeat(500) + ".gguf")
        assertEquals(CustomNames.MAX_LENGTH, s.length)
        assertTrue(s.endsWith(".gguf"))
        assertTrue(CustomNames.sanitize("y".repeat(500)).length <= CustomNames.MAX_LENGTH)
    }

    @Test fun `no double dots, and the temporary-file suffix is never the extension`() {
        assertFalse(".." in CustomNames.sanitize("a..b...gguf"))
        assertNotEquals(".part", CustomNames.sanitize("model.part").takeLast(5))
        assertFalse(CustomNames.sanitize("model.PART").endsWith(".part", ignoreCase = true))
    }

    @Test fun `sanitising is idempotent and recognises its own output`() {
        for (raw in listOf("Qwen3 0.6B (Q4).gguf", "../x", "a".repeat(300), "m.bin", "\u00e4\u00f6\u00fc")) {
            val once = CustomNames.sanitize(raw)
            assertEquals(once, CustomNames.sanitize(once))
            assertTrue(CustomNames.isSanitised(once))
        }
        assertFalse(CustomNames.isSanitised("../x"))
        assertFalse(CustomNames.isSanitised("a b"))
        assertFalse(CustomNames.isSanitised(""))
        assertFalse(CustomNames.isSanitised(".hidden"))
    }

    @Test fun `a catalog file name can never be the name of a custom file's folder entry, because the folders differ and ids are prefixed`() {
        for (m in Catalog.all) {
            assertFalse(CustomPolicy.isCustomId(m.id))
            assertFalse(m.fileName.startsWith(CustomPolicy.DIR_NAME + "/"))
        }
    }

    @Test fun `the extension of the detected format is added when missing`() {
        assertEquals("tiny.bin", CustomNames.withExtension("tiny", ModelFormat.Whisper(false)))
        assertEquals("tiny.bin", CustomNames.withExtension("tiny.bin", ModelFormat.Whisper(false)))
        assertEquals("m.gguf", CustomNames.withExtension("m.bin", ModelFormat.Gguf("llama"))) // a wrong extension is replaced
        assertEquals("model.gguf", CustomNames.withExtension("model", ModelFormat.Parakeet))
        assertEquals("Qwen3.GGUF", CustomNames.withExtension("Qwen3.GGUF", ModelFormat.Gguf("qwen3"))) // any case is fine
    }

    @Test fun `ids round trip and only sanitised ones parse`() {
        val id = CustomPolicy.id(ModelKind.GRAMMAR, "Qwen3-0.6B.gguf")
        assertEquals("custom:grammar:Qwen3-0.6B.gguf", id)
        assertEquals(ModelKind.GRAMMAR to "Qwen3-0.6B.gguf", CustomPolicy.parseId(id))
        assertEquals(ModelKind.SPEECH to "a.bin", CustomPolicy.parseId("custom:speech:a.bin"))
        assertEquals(ModelKind.CLEAN_UP to "a.gguf", CustomPolicy.parseId("custom:clean_up:a.gguf"))
        for (bad in listOf(null, "", "whisper-tiny", "custom:", "custom:grammar:", "custom:vad:x.bin", "custom:grammar:../x", "custom:grammar:a/b", "custom:grammar: x", "custom:speech", "Custom:speech:a.bin", "custom:grammar:" + "a".repeat(300)))
            assertNull(CustomPolicy.parseId(bad), "$bad")
    }

    @Test fun `protocol roles map to kinds and nothing else`() {
        assertEquals(ModelKind.SPEECH, CustomPolicy.roleOfProtocol(1))
        assertEquals(ModelKind.CLEAN_UP, CustomPolicy.roleOfProtocol(2))
        assertEquals(ModelKind.GRAMMAR, CustomPolicy.roleOfProtocol(3))
        for (n in listOf(0, 4, -1, 99, Int.MIN_VALUE)) assertNull(CustomPolicy.roleOfProtocol(n))
    }
}

/** Importing custom models: the size cap, format and role checks, the test load, the limit on how many there are. */
class CustomImportTest {
    private lateinit var dir: File
    private val marks = HashMap<String, String>()
    private var space = Long.MAX_VALUE
    private var cap = CustomPolicy.MAX_BYTES
    private val probed = ArrayList<Pair<String, ModelSpec>>()
    private var probeResult = true
    private val store get() = ModelStore(dir, object : BrokenMarks {
        override fun get(fileName: String) = marks[fileName]
        override fun set(fileName: String, value: String?) { if (value == null) marks.remove(fileName) else marks[fileName] = value }
    }, emptyList(), { throw java.io.IOException() }, cap, { space })

    @BeforeTest fun setUp() {
        dir = kotlin.io.path.createTempDirectory("custom").toFile()
        marks.clear(); space = Long.MAX_VALUE; cap = CustomPolicy.MAX_BYTES; probed.clear(); probeResult = true
    }

    @AfterTest fun tearDown() { dir.deleteRecursively() }

    private fun import(kind: ModelKind, name: String?, bytes: ByteArray, size: Long = bytes.size.toLong()) =
        store.importCustom(kind, name, ByteArrayInputStream(bytes), size) { f, spec ->
            assertTrue(f.isFile && f.length() == bytes.size.toLong())
            probed += f.name to spec
            probeResult
        }

    private fun leftovers() = dir.walkTopDown().filter { it.isFile && it.name.endsWith(".part") }.toList()

    @Test fun `a Whisper file is imported as a custom speech model with its own spec`() {
        val result = import(ModelKind.SPEECH, "ggml-tiny.en-q5_1.bin", ModelBytes.whisper(vocab = 51864))
        val spec = assertIs<CustomImportResult.Imported>(result).spec
        assertEquals("custom:speech:ggml-tiny.en-q5_1.bin", spec.id)
        assertTrue(spec.custom)
        assertEquals(SpeechEngine.WHISPER, spec.engine)
        assertEquals(listOf("en"), spec.languages)
        assertEquals("", spec.sha256)
        assertEquals("custom/speech/ggml-tiny.en-q5_1.bin", spec.fileName)
        assertTrue(store.file(spec).isFile)
        assertEquals(1, probed.size)
        assertTrue(leftovers().isEmpty())
        assertEquals(listOf(spec.id), store.customSpecs(ModelKind.SPEECH).map { it.id })
        assertEquals(spec.id, store.customSpec(spec.id)?.id)
        assertTrue(store.status(spec) is ModelStatus.Installed)
    }

    @Test fun `a Parakeet GGUF becomes a Parakeet speech model, a text GGUF a clean-up or grammar model`() {
        val parakeet = assertIs<CustomImportResult.Imported>(import(ModelKind.SPEECH, "my-parakeet", ModelBytes.gguf("parakeet"))).spec
        assertEquals(SpeechEngine.PARAKEET, parakeet.engine)
        assertEquals("my-parakeet.gguf", parakeet.fileName.substringAfterLast('/'))
        val text = assertIs<CustomImportResult.Imported>(import(ModelKind.GRAMMAR, "Qwen3-0.6B-Q4_K_M.gguf", ModelBytes.gguf("qwen3"))).spec
        assertTrue(text.ownTemplate)
        assertNull(text.engine)
        assertEquals(ModelKind.GRAMMAR, text.kind)
        val cleanUp = assertIs<CustomImportResult.Imported>(import(ModelKind.CLEAN_UP, "Qwen3-0.6B-Q4_K_M.gguf", ModelBytes.gguf("qwen3"))).spec
        assertTrue(cleanUp.ownTemplate)
        assertNotEquals(text.id, cleanUp.id) // the same file name in two roles is two models
    }

    @Test fun `a garbage file is rejected as not a model, the test load is never run and nothing is left`() {
        for (bytes in listOf(ByteArray(0), "just text, not a model".toByteArray(), ByteArray(100_000) { (it * 31).toByte() })) {
            val r = import(ModelKind.GRAMMAR, "notes.txt", bytes)
            assertEquals(CustomImportResult.Rejected(CustomReject.NOT_A_MODEL), r)
        }
        assertTrue(probed.isEmpty())
        assertTrue(leftovers().isEmpty())
        assertTrue(store.customSpecs().isEmpty())
    }

    @Test fun `a file of the wrong kind for the role is refused without a test load`() {
        assertEquals(CustomImportResult.Rejected(CustomReject.WRONG_ROLE), import(ModelKind.SPEECH, "q.gguf", ModelBytes.gguf("qwen3")))
        assertEquals(CustomImportResult.Rejected(CustomReject.WRONG_ROLE), import(ModelKind.GRAMMAR, "w.bin", ModelBytes.whisper()))
        assertEquals(CustomImportResult.Rejected(CustomReject.WRONG_ROLE), import(ModelKind.CLEAN_UP, "p.gguf", ModelBytes.gguf("parakeet")))
        assertEquals(CustomImportResult.Rejected(CustomReject.NOT_A_MODEL), import(ModelKind.SPEECH, "c.gguf", ModelBytes.gguf("clip")))
        assertTrue(probed.isEmpty() && leftovers().isEmpty())
    }

    @Test fun `the size cap applies to the announced size and to the bytes actually read`() {
        cap = 5_000
        assertEquals(CustomImportResult.Rejected(CustomReject.TOO_LARGE), import(ModelKind.GRAMMAR, "a.gguf", ModelBytes.gguf("llama", size = 4096), size = 6_000))
        // a source that claims less (or nothing) and delivers more is stopped at the cap
        assertEquals(CustomImportResult.Rejected(CustomReject.TOO_LARGE), import(ModelKind.GRAMMAR, "a.gguf", ModelBytes.gguf("llama", size = 9_000), size = 100))
        assertEquals(CustomImportResult.Rejected(CustomReject.TOO_LARGE), import(ModelKind.GRAMMAR, "a.gguf", ModelBytes.gguf("llama", size = 9_000), size = -1))
        assertTrue(assertIs<CustomImportResult>(import(ModelKind.GRAMMAR, "a.gguf", ModelBytes.gguf("llama", size = 5_000))) is CustomImportResult.Imported)
        assertTrue(probed.size == 1 && leftovers().isEmpty())
        // the real cap is 4 GiB
        assertEquals(4L * 1024 * 1024 * 1024, CustomPolicy.MAX_BYTES)
    }

    @Test fun `not enough free storage is refused before copying and while copying`() {
        space = 1_000
        assertEquals(CustomImportResult.Rejected(CustomReject.NOT_ENOUGH_SPACE), import(ModelKind.GRAMMAR, "a.gguf", ModelBytes.gguf("llama")))
        space = Long.MAX_VALUE
        // the room must cover the file and the spare
        space = 10_000 + CustomPolicy.SPARE_BYTES - 1
        assertEquals(CustomImportResult.Rejected(CustomReject.NOT_ENOUGH_SPACE), import(ModelKind.GRAMMAR, "a.gguf", ModelBytes.gguf("llama"), size = 10_000))
        assertTrue(probed.isEmpty() && leftovers().isEmpty())
    }

    @Test fun `a model the model process cannot load is removed again and reported`() {
        probeResult = false
        assertEquals(CustomImportResult.Rejected(CustomReject.LOAD_FAILED), import(ModelKind.GRAMMAR, "a.gguf", ModelBytes.gguf("llama")))
        assertTrue(store.customSpecs().isEmpty())
        assertTrue(dir.walkTopDown().none { it.isFile })
        // a probe that throws counts the same
        val r = store.importCustom(ModelKind.GRAMMAR, "b.gguf", ByteArrayInputStream(ModelBytes.gguf("llama")), -1) { _, _ -> error("boom") }
        assertEquals(CustomImportResult.Rejected(CustomReject.LOAD_FAILED), r)
        assertTrue(dir.walkTopDown().none { it.isFile })
    }

    @Test fun `a read error leaves nothing behind`() {
        val broken = object : InputStream() {
            var n = 0
            override fun read(): Int = throw java.io.IOException("gone")
            override fun read(b: ByteArray): Int { if (n++ == 0) { ModelBytes.gguf("llama").copyInto(b, 0, 0, 100); return 100 }; throw java.io.IOException("gone") }
        }
        assertEquals(CustomImportResult.Rejected(CustomReject.READ_ERROR), store.importCustom(ModelKind.GRAMMAR, "a.gguf", broken, -1) { _, _ -> true })
        assertTrue(dir.walkTopDown().none { it.isFile })
    }

    @Test fun `at most eight custom models, but one of them can be replaced`() {
        for (i in 1..CustomPolicy.MAX_MODELS)
            assertIs<CustomImportResult.Imported>(import(ModelKind.GRAMMAR, "m$i.gguf", ModelBytes.gguf("llama")))
        assertEquals(CustomImportResult.Rejected(CustomReject.TOO_MANY), import(ModelKind.GRAMMAR, "m9.gguf", ModelBytes.gguf("llama")))
        assertEquals(CustomImportResult.Rejected(CustomReject.TOO_MANY), import(ModelKind.SPEECH, "s.bin", ModelBytes.whisper()))
        assertIs<CustomImportResult.Imported>(import(ModelKind.GRAMMAR, "m3.gguf", ModelBytes.gguf("llama", fill = 1)))
        assertEquals(CustomPolicy.MAX_MODELS, store.customSpecs().size)
        assertTrue(leftovers().isEmpty())
    }

    @Test fun `importing the same name again replaces the file atomically and clears a broken mark`() {
        val first = assertIs<CustomImportResult.Imported>(import(ModelKind.GRAMMAR, "a.gguf", ModelBytes.gguf("llama", fill = 1))).spec
        store.markBroken(first)
        assertTrue(store.status(first) is ModelStatus.Broken)
        val second = assertIs<CustomImportResult.Imported>(import(ModelKind.GRAMMAR, "a.gguf", ModelBytes.gguf("llama", fill = 2))).spec
        assertEquals(first.id, second.id)
        assertTrue(store.status(second) is ModelStatus.Installed)
    }

    @Test fun `a hostile name stays inside the custom folder of its role`() {
        val r = assertIs<CustomImportResult.Imported>(import(ModelKind.GRAMMAR, "../../../models/evil\u0000.gguf", ModelBytes.gguf("llama")))
        val file = store.file(r.spec).canonicalFile
        assertEquals(File(dir, "custom/grammar").canonicalFile, file.parentFile)
        assertTrue(file.canonicalPath.startsWith(dir.canonicalPath))
        assertTrue(dir.walkTopDown().filter { it.isFile }.all { it.canonicalPath.startsWith(File(dir, "custom").canonicalPath) })
    }

    @Test fun `a custom model is used only when the user chose it, never as a fallback`() {
        val spec = assertIs<CustomImportResult.Imported>(import(ModelKind.GRAMMAR, "a.gguf", ModelBytes.gguf("llama"))).spec
        assertNull(store.best(ModelKind.GRAMMAR, null, null))
        assertNull(store.best(ModelKind.GRAMMAR, "qwen3.5-0.8b", null))
        assertEquals(spec.id, store.best(ModelKind.GRAMMAR, spec.id, "en")?.id)
        // chosen for another role: not used
        assertNull(store.best(ModelKind.CLEAN_UP, spec.id, null))
        // broken: not used
        store.markBroken(spec)
        assertNull(store.best(ModelKind.GRAMMAR, spec.id, null))
    }

    @Test fun `an English-only Whisper custom model is not used for another language`() {
        val spec = assertIs<CustomImportResult.Imported>(import(ModelKind.SPEECH, "tiny.en.bin", ModelBytes.whisper(vocab = 51864))).spec
        assertEquals(spec.id, store.best(ModelKind.SPEECH, spec.id, "en")?.id)
        assertNull(store.best(ModelKind.SPEECH, spec.id, "es"))
    }

    @Test fun `deleting a custom model removes the file and nothing else`() {
        val a = assertIs<CustomImportResult.Imported>(import(ModelKind.GRAMMAR, "a.gguf", ModelBytes.gguf("llama"))).spec
        val b = assertIs<CustomImportResult.Imported>(import(ModelKind.GRAMMAR, "b.gguf", ModelBytes.gguf("llama"))).spec
        assertTrue(store.delete(a))
        assertEquals(listOf(b.id), store.customSpecs().map { it.id })
        assertNull(store.customSpec(a.id))
    }

    @Test fun `files in the custom folder that are not what their role needs, or not plainly named, are not listed`() {
        val folder = File(dir, "custom/grammar").also { it.mkdirs() }
        File(folder, "ok.gguf").writeBytes(ModelBytes.gguf("llama"))
        File(folder, "whisper.bin").writeBytes(ModelBytes.whisper()) // wrong role
        File(folder, "junk.gguf").writeBytes(ByteArray(100))
        File(folder, "has space.gguf").writeBytes(ModelBytes.gguf("llama"))
        File(folder, "x.gguf.part").writeBytes(ModelBytes.gguf("llama"))
        assertEquals(listOf("custom:grammar:ok.gguf"), store.customSpecs(ModelKind.GRAMMAR).map { it.id })
    }

    @Test fun `old temporary files are cleaned up, also in the custom folders`() {
        val folder = File(dir, "custom/speech").also { it.mkdirs() }
        val old = File(folder, "import.1.part").also { it.writeBytes(ByteArray(3)); it.setLastModified(0) }
        val young = File(folder, "import.2.part").also { it.writeBytes(ByteArray(3)) }
        store.cleanUp()
        assertFalse(old.exists())
        assertTrue(young.exists())
    }
}
