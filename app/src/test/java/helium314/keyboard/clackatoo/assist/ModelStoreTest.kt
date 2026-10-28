// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo.assist

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelStoreTest {
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** A file with a valid Whisper header (magic, mel filters of [bands] bands and [bins] FFT bins) and [fill] as content. */
    private fun whisperFile(size: Int = 1000, fill: Byte = 7, bands: Int = 80, bins: Int = 201) = ByteArray(size) { fill }.also {
        "lmgg".toByteArray().copyInto(it)
        for (i in 0 until 4) {
            it[48 + i] = (bands shr (8 * i)).toByte()
            it[52 + i] = (bins shr (8 * i)).toByte()
        }
    }

    private fun ggufFile(size: Int = 1000, version: Int = 3, fill: Byte = 7) = ByteArray(size) { fill }.also {
        "GGUF".toByteArray().copyInto(it)
        it[4] = version.toByte()
        it[5] = 0
        it[6] = 0
        it[7] = 0
    }

    private val speechBytes = whisperFile(fill = 7)
    private val speechBigBytes = whisperFile(fill = 8)
    private val grammarBytes = ggufFile()
    private val speech = ModelSpec(ModelKind.SPEECH, "speech.bin", "Speech", "MIT", 1000, sha(speechBytes))
    private val speechBig = ModelSpec(ModelKind.SPEECH, "speech-big.bin", "Speech big", "MIT", 1000, sha(speechBigBytes))
    private val grammar = ModelSpec(ModelKind.GRAMMAR, "grammar.gguf", "Grammar", "Apache-2.0", 1000, sha(grammarBytes), PromptFormat.CHATML_NO_THINK)
    private val catalog = listOf(speech, speechBig, grammar)

    private lateinit var dir: File
    private val marks = HashMap<String, String>()
    private var space = Long.MAX_VALUE
    private lateinit var store: ModelStore

    @BeforeTest fun setUp() {
        dir = kotlin.io.path.createTempDirectory("models").toFile()
        marks.clear()
        space = Long.MAX_VALUE
        store = ModelStore(dir, object : BrokenMarks {
            override fun get(fileName: String) = marks[fileName]
            override fun set(fileName: String, value: String?) { if (value == null) marks.remove(fileName) else marks[fileName] = value }
        }, catalog) { space }
    }

    @AfterTest fun tearDown() { dir.deleteRecursively() }

    private fun import(name: String?, bytes: ByteArray) = store.import(name, ByteArrayInputStream(bytes))
    private fun noPartFiles() = dir.listFiles().orEmpty().none { it.name.endsWith(".part") }

    @Test fun `the shipped catalog pins exact sizes and digests`() {
        for (spec in ModelCatalog.all) {
            assertTrue(spec.sizeBytes > 0)
            assertTrue(Regex("[0-9a-f]{64}").matches(spec.sha256), spec.fileName)
        }
        assertEquals(ModelCatalog.all.size, ModelCatalog.all.map { it.sha256 }.toSet().size)
        // the reference files, by name: a change here is a change of the model and must be made in NOTICE too
        val pinned = ModelCatalog.all.associate { it.fileName to Triple(it.kind, it.sizeBytes, it.sha256) }
        assertEquals(
            Triple(ModelKind.SPEECH, 675_200_864L, "993d73feb4206dadda865ab25bd64b50c48dc4d013c3bf6126a721f28b1d5ee8"),
            pinned["tdt-0.6b-v3-q4_k.gguf"],
        )
        assertEquals(
            Triple(ModelKind.SPEECH, 190_085_487L, "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb"),
            pinned["ggml-small-q5_1.bin"],
        )
        assertEquals(
            Triple(ModelKind.VAD, 885_098L, "2aa269b785eeb53a82983a20501ddf7c1d9c48e33ab63a41391ac6c9f7fb6987"),
            pinned["ggml-silero-v6.2.0.bin"],
        )
    }

    @Test fun `the catalog lists Parakeet before Whisper and only the voice detector ships in the APK`() {
        assertEquals(
            listOf("tdt-0.6b-v3-q4_k.gguf", "ggml-small-q5_1.bin", "s1-mini-q4_k_m.gguf", "Qwen3.5-0.8B-Q4_K_M.gguf", "ggml-silero-v6.2.0.bin"),
            ModelCatalog.all.map { it.fileName },
        )
        assertEquals(
            listOf(SpeechEngine.PARAKEET, SpeechEngine.WHISPER),
            ModelCatalog.forKind(ModelKind.SPEECH).map { it.engine },
        )
        assertEquals(listOf(ModelCatalog.SILERO_VAD), ModelCatalog.all.filter { it.bundledAsset != null })
        assertEquals(listOf(ModelCatalog.SILERO_VAD), ModelCatalog.forKind(ModelKind.VAD))
    }

    @Test fun `a valid speech model is imported and found`() {
        assertEquals(ImportResult.Imported(speech), import("speech.bin", speechBytes))
        assertEquals(speech, store.best(ModelKind.SPEECH))
        assertIs<ModelStatus.Installed>(store.status(speech))
        assertContentEquals(speechBytes, File(dir, "speech.bin").readBytes())
        assertTrue(noPartFiles())
    }

    @Test fun `a valid text model needs the gguf header and a supported version`() {
        assertIs<ImportResult.Imported>(import("grammar.gguf", grammarBytes))
        assertEquals(ImportResult.Rejected(RejectReason.NOT_A_MODEL), import("grammar.gguf", ggufFile(version = 9)))
    }

    @Test fun `a file with the right header and size but other content is rejected`() {
        // one byte differs from the reference file: only the digest can tell
        val tampered = speechBytes.copyOf().also { it[500] = 99 }
        assertEquals(ImportResult.Rejected(RejectReason.WRONG_CONTENT), import("speech.bin", tampered))
        // another valid-looking model under this name
        assertEquals(ImportResult.Rejected(RejectReason.WRONG_CONTENT), import("speech.bin", speechBigBytes))
        assertEquals(ImportResult.Rejected(RejectReason.WRONG_CONTENT), import("grammar.gguf", ggufFile(fill = 9)))
        assertTrue(dir.listFiles().isNullOrEmpty())
    }

    @Test fun `file names are matched without regard to case`() {
        assertIs<ImportResult.Imported>(import("Speech.BIN", speechBytes))
        assertTrue(File(dir, "speech.bin").isFile)
    }

    @Test fun `unknown names are rejected and nothing is written`() {
        assertEquals(ImportResult.Rejected(RejectReason.UNKNOWN_FILE_NAME), import("evil.so", speechBytes))
        assertEquals(ImportResult.Rejected(RejectReason.UNKNOWN_FILE_NAME), import(null, speechBytes))
        assertEquals(ImportResult.Rejected(RejectReason.UNKNOWN_FILE_NAME), import("../speech.bin", speechBytes))
        assertTrue(dir.listFiles().isNullOrEmpty())
    }

    @Test fun `a file with the wrong content is rejected and removed`() {
        val text = "this is not a model, just a text file pretending".toByteArray().copyOf(1000)
        assertEquals(ImportResult.Rejected(RejectReason.NOT_A_MODEL), import("speech.bin", text))
        // a text model file under a speech name
        assertEquals(ImportResult.Rejected(RejectReason.NOT_A_MODEL), import("speech.bin", ggufFile()))
        assertTrue(dir.listFiles().isNullOrEmpty())
    }

    @Test fun `a crafted speech header is rejected even with the right size`() {
        // the real whisper.cpp overflow: an FFT size far above 201
        assertEquals(ImportResult.Rejected(RejectReason.NOT_A_MODEL), import("speech.bin", whisperFile(bins = 4000)))
        assertEquals(ImportResult.Rejected(RejectReason.NOT_A_MODEL), import("speech.bin", whisperFile(bands = 1 shl 20)))
        assertTrue(dir.listFiles().isNullOrEmpty())
    }

    @Test fun `truncated and oversized files are rejected`() {
        assertEquals(ImportResult.Rejected(RejectReason.WRONG_SIZE), import("speech.bin", whisperFile(999)))
        assertEquals(ImportResult.Rejected(RejectReason.WRONG_SIZE), import("speech.bin", whisperFile(1001)))
        assertEquals(ImportResult.Rejected(RejectReason.WRONG_SIZE), import("speech.bin", whisperFile(5000)))
        assertTrue(dir.listFiles().isNullOrEmpty())
    }

    @Test fun `an endless stream stops at the exact size`() {
        val endless = object : InputStream() {
            override fun read() = 0x41
            override fun read(b: ByteArray, off: Int, len: Int): Int { b.fill(0x41, off, off + len); return len }
        }
        assertEquals(ImportResult.Rejected(RejectReason.WRONG_SIZE), store.import("speech.bin", endless))
        assertTrue(dir.listFiles().isNullOrEmpty())
    }

    @Test fun `not enough free space is reported before copying`() {
        space = 10
        assertEquals(ImportResult.Rejected(RejectReason.NOT_ENOUGH_SPACE), import("speech.bin", speechBytes))
        assertTrue(dir.listFiles().isNullOrEmpty())
    }

    @Test fun `a read error leaves nothing behind and keeps the old model`() {
        import("speech.bin", speechBytes)
        val failing = object : InputStream() {
            var first = true
            override fun read() = throw IOException()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (first) { first = false; return 10 }
                throw IOException("disk gone")
            }
        }
        assertEquals(ImportResult.Rejected(RejectReason.READ_ERROR), store.import("speech.bin", failing))
        assertContentEquals(speechBytes, File(dir, "speech.bin").readBytes())
        assertTrue(noPartFiles())
    }

    @Test fun `a rejected import does not replace a good model`() {
        import("speech.bin", speechBytes)
        import("speech.bin", ByteArray(1000))
        import("speech.bin", speechBytes.copyOf().also { it[900] = 1 })
        assertContentEquals(speechBytes, File(dir, "speech.bin").readBytes())
    }

    @Test fun `a good import replaces the old file in one step and clears the broken mark`() {
        File(dir, "speech.bin").writeBytes(ByteArray(10))
        store.markBroken(speech)
        assertIs<ModelStatus.Broken>(store.status(speech))
        assertIs<ImportResult.Imported>(import("speech.bin", speechBytes))
        assertContentEquals(speechBytes, File(dir, "speech.bin").readBytes())
        assertIs<ModelStatus.Installed>(store.status(speech))
        assertTrue(noPartFiles())
    }

    @Test fun `a model that crashed the model process is not used again until it changes`() {
        import("speech.bin", speechBytes)
        store.markBroken(speech)
        assertIs<ModelStatus.Broken>(store.status(speech))
        assertNull(store.best(ModelKind.SPEECH))
        // importing again (a new file) clears the mark
        import("speech.bin", speechBytes)
        assertIs<ModelStatus.Installed>(store.status(speech))
        assertEquals(speech, store.best(ModelKind.SPEECH))
    }

    @Test fun `a changed file is tried again even without a new import`() {
        import("speech.bin", speechBytes)
        store.markBroken(speech)
        File(dir, "speech.bin").writeBytes(whisperFile(1100))
        assertIs<ModelStatus.Installed>(store.status(speech))
    }

    @Test fun `the first installed model of a kind in catalog order is used`() {
        import("speech-big.bin", speechBigBytes)
        assertEquals(speechBig, store.best(ModelKind.SPEECH))
        import("speech.bin", speechBytes)
        assertEquals(speech, store.best(ModelKind.SPEECH))
        assertNull(store.best(ModelKind.GRAMMAR))
    }

    @Test fun `two imports of one model never share a temporary file`() {
        // the temporary name is unique, so one import cannot clobber another's file
        val seen = HashSet<String>()
        val recording = object : InputStream() {
            override fun read() = -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                dir.listFiles().orEmpty().filter { it.name.endsWith(".part") }.forEach { seen += it.name }
                return -1
            }
        }
        store.import("speech.bin", recording)
        store.import("speech.bin", recording)
        assertEquals(2, seen.size)
    }

    @Test fun `cleanup removes only stale leftovers, never an import that is running`() {
        val stale = File(dir, "speech.bin.1234.part").apply { writeText("half"); setLastModified(1_000) }
        val running = File(dir, "speech.bin.5678.part").apply { writeText("being written") }
        File(dir, "speech.bin").writeBytes(speechBytes)
        store.cleanUp(now = System.currentTimeMillis())
        assertFalse(stale.exists())
        assertTrue(running.exists())
        assertTrue(File(dir, "speech.bin").exists())
    }

    @Test fun `the catalog entries have distinct file names`() {
        assertEquals(ModelCatalog.all.size, ModelCatalog.all.map { it.fileName.lowercase() }.toSet().size)
    }

    // ---- Parakeet and the bundled voice detector

    private val parakeetBytes = ggufFile(fill = 5)
    private val parakeet = ModelSpec(ModelKind.SPEECH, "parakeet.gguf", "Parakeet", "CC-BY-4.0", 1000, sha(parakeetBytes), engine = SpeechEngine.PARAKEET)
    private val vadBytes = ByteArray(600) { (it * 7).toByte() }
    private val vad = ModelSpec(ModelKind.VAD, "vad.bin", "VAD", "MIT", 600, sha(vadBytes), bundledAsset = "models/vad.bin")

    private fun storeWith(assets: Map<String, ByteArray>, vararg specs: ModelSpec) = ModelStore(dir, object : BrokenMarks {
        override fun get(fileName: String) = marks[fileName]
        override fun set(fileName: String, value: String?) { if (value == null) marks.remove(fileName) else marks[fileName] = value }
    }, specs.toList(), openAsset = { name -> ByteArrayInputStream(assets[name] ?: throw IOException("no such asset")) }, usableSpace = { space })

    @Test fun `a Parakeet model is checked by its GGUF header`() {
        val s = storeWith(emptyMap(), parakeet)
        assertEquals(ImportResult.Imported(parakeet), s.import("parakeet.gguf", ByteArrayInputStream(parakeetBytes)))
        assertEquals(parakeet, s.best(ModelKind.SPEECH))
        // a Whisper file under the Parakeet name is not a GGUF file
        assertEquals(ImportResult.Rejected(RejectReason.NOT_A_MODEL), s.import("parakeet.gguf", ByteArrayInputStream(whisperFile())))
        assertEquals(ImportResult.Rejected(RejectReason.NOT_A_MODEL), s.import("parakeet.gguf", ByteArrayInputStream(ggufFile(version = 9))))
        // right header and size, other content
        assertEquals(ImportResult.Rejected(RejectReason.WRONG_CONTENT), s.import("parakeet.gguf", ByteArrayInputStream(ggufFile(fill = 6))))
    }

    @Test fun `a bundled model cannot be imported, whatever the file holds`() {
        val s = storeWith(mapOf("models/vad.bin" to vadBytes), vad)
        assertEquals(ImportResult.Rejected(RejectReason.UNKNOWN_FILE_NAME), s.import("vad.bin", ByteArrayInputStream(vadBytes)))
        assertEquals(ImportResult.Rejected(RejectReason.UNKNOWN_FILE_NAME), s.import("VAD.BIN", ByteArrayInputStream(vadBytes)))
        assertTrue(dir.listFiles().isNullOrEmpty())
    }

    @Test fun `the shipped voice detector name is refused by import`() {
        // the real catalog: the file is the reference one, and still it may not come in through the file picker
        val real = ModelStore(dir, marks = object : BrokenMarks {
            override fun get(fileName: String) = null
            override fun set(fileName: String, value: String?) {}
        })
        assertEquals(
            ImportResult.Rejected(RejectReason.UNKNOWN_FILE_NAME),
            real.import(ModelCatalog.SILERO_VAD.fileName, ByteArrayInputStream(ByteArray(8))),
        )
        assertTrue(dir.listFiles().isNullOrEmpty())
    }

    @Test fun `ensureBundled installs the asset through the same checks and then leaves it alone`() {
        var opened = 0
        val s = ModelStore(dir, object : BrokenMarks {
            override fun get(fileName: String) = marks[fileName]
            override fun set(fileName: String, value: String?) { if (value == null) marks.remove(fileName) else marks[fileName] = value }
        }, listOf(vad), openAsset = { opened++; ByteArrayInputStream(vadBytes) }, usableSpace = { space })
        assertTrue(s.ensureBundled(vad))
        assertContentEquals(vadBytes, File(dir, "vad.bin").readBytes())
        assertIs<ModelStatus.Installed>(s.status(vad))
        assertEquals(1, opened)
        assertTrue(s.ensureBundled(vad))
        assertEquals(1, opened, "an installed file is not copied again")
        assertTrue(noPartFiles())
    }

    @Test fun `ensureBundled replaces a file of the wrong size`() {
        File(dir, "vad.bin").writeBytes(ByteArray(10))
        val s = storeWith(mapOf("models/vad.bin" to vadBytes), vad)
        assertTrue(s.ensureBundled(vad))
        assertContentEquals(vadBytes, File(dir, "vad.bin").readBytes())
    }

    @Test fun `a corrupted asset installs nothing and an old file stays`() {
        val corrupted = vadBytes.copyOf().also { it[100] = (it[100] + 1).toByte() }
        val s = storeWith(mapOf("models/vad.bin" to corrupted), vad)
        assertFalse(s.ensureBundled(vad))
        assertTrue(dir.listFiles().isNullOrEmpty())
        // a truncated asset, and one that cannot be read at all
        assertFalse(storeWith(mapOf("models/vad.bin" to vadBytes.copyOf(500)), vad).ensureBundled(vad))
        assertFalse(storeWith(emptyMap(), vad).ensureBundled(vad))
        assertTrue(dir.listFiles().isNullOrEmpty())
    }

    @Test fun `ensureBundled does nothing for a model that does not ship in the APK`() {
        val s = storeWith(emptyMap(), speech)
        assertFalse(s.ensureBundled(speech))
        assertTrue(dir.listFiles().isNullOrEmpty())
    }

    @Test fun `ensureBundled reports a full disk and leaves nothing`() {
        space = 10
        val s = storeWith(mapOf("models/vad.bin" to vadBytes), vad)
        assertFalse(s.ensureBundled(vad))
        assertTrue(dir.listFiles().isNullOrEmpty())
    }
}

class ModelValidatorTest {
    private val speech = ModelSpec(ModelKind.SPEECH, "s.bin", "S", "MIT", 100, "00")

    private fun header(bands: Int, bins: Int, magic: String = "lmgg") = ByteArray(ModelValidator.HEADER_BYTES).also {
        magic.toByteArray().copyInto(it)
        for (i in 0 until 4) {
            it[48 + i] = (bands shr (8 * i)).toByte()
            it[52 + i] = (bins shr (8 * i)).toByte()
        }
    }

    @Test fun `whisper models have 80 or 128 mel bands and 201 FFT bins`() {
        assertTrue(ModelValidator.whisperFiltersOk(header(80, 201)))
        assertTrue(ModelValidator.whisperFiltersOk(header(128, 201)))
        assertFalse(ModelValidator.whisperFiltersOk(header(80, 200)))
        assertFalse(ModelValidator.whisperFiltersOk(header(80, 1600)))
        assertFalse(ModelValidator.whisperFiltersOk(header(80, 4000)))
        assertFalse(ModelValidator.whisperFiltersOk(header(81, 201)))
        assertFalse(ModelValidator.whisperFiltersOk(header(0, 0)))
        assertFalse(ModelValidator.whisperFiltersOk(header(-80, 201)))
        // a number that is also 201 or 80 only in its low bytes
        assertFalse(ModelValidator.whisperFiltersOk(header(80, 201 + (1 shl 16))))
        assertFalse(ModelValidator.whisperFiltersOk(ByteArray(ModelValidator.HEADER_BYTES - 1)))
    }

    @Test fun `the speech check looks at the magic and the filters, then the exact size`() {
        assertNull(ModelValidator.validate(speech, header(80, 201), 100))
        assertEquals(RejectReason.NOT_A_MODEL, ModelValidator.validate(speech, header(80, 4000), 100))
        assertEquals(RejectReason.NOT_A_MODEL, ModelValidator.validate(speech, header(80, 201, magic = "GGUF"), 100))
        assertEquals(RejectReason.WRONG_SIZE, ModelValidator.validate(speech, header(80, 201), 99))
        assertEquals(RejectReason.WRONG_SIZE, ModelValidator.validate(speech, header(80, 201), 101))
        assertEquals(RejectReason.NOT_A_MODEL, ModelValidator.validate(speech, ByteArray(4), 100))
    }
}
