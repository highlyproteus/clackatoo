// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo.models

import helium314.keyboard.clackatoo.assist.BrokenMarks
import helium314.keyboard.clackatoo.assist.ModelCatalog
import helium314.keyboard.clackatoo.assist.ModelKind
import helium314.keyboard.clackatoo.assist.ModelSpec
import helium314.keyboard.clackatoo.assist.ModelStatus
import helium314.keyboard.clackatoo.assist.ModelStore
import helium314.keyboard.clackatoo.catalog.ModelsProtocol
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The keyboard does not trust the companion app with content: a handed-over file must be exactly the catalog's file. */
class ModelHandoverTest {
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun ggufFile(size: Int, fill: Byte) = ByteArray(size) { fill }.also {
        "GGUF".toByteArray().copyInto(it)
        it[4] = 3
        it[5] = 0
        it[6] = 0
        it[7] = 0
    }

    private val good = ggufFile(1000, 7)
    private val spec = ModelSpec(ModelKind.GRAMMAR, "grammar.gguf", "Grammar", "Apache-2.0", 1000, sha(good), id = "grammar-test")
    private lateinit var dir: File
    private lateinit var store: ModelStore
    private val lookup = { id: String? -> if (id == spec.id) spec else null }

    @BeforeTest fun setUp() {
        dir = kotlin.io.path.createTempDirectory("handover").toFile()
        store = ModelStore(dir, object : BrokenMarks {
            override fun get(fileName: String): String? = null
            override fun set(fileName: String, value: String?) {}
        }, listOf(spec)) { Long.MAX_VALUE }
    }

    @AfterTest fun tearDown() { dir.deleteRecursively() }

    private fun accept(id: String?, input: InputStream) = ModelHandover.accept(store, id, input, lookup)
    private fun nothingLeft() = dir.listFiles().orEmpty().isEmpty()

    @Test fun `the exact file is installed`() {
        assertEquals(ModelsProtocol.HANDOVER_OK, accept(spec.id, ByteArrayInputStream(good)))
        assertIs<ModelStatus.Installed>(store.status(spec))
        assertTrue(File(dir, "grammar.gguf").readBytes().contentEquals(good))
    }

    @Test fun `a file with the wrong content but the right size and header is refused and removed`() {
        val other = ggufFile(1000, 9)
        assertEquals(ModelsProtocol.HANDOVER_WRONG_CONTENT, accept(spec.id, ByteArrayInputStream(other)))
        assertEquals(ModelStatus.Missing, store.status(spec))
        assertTrue(nothingLeft())
    }

    @Test fun `a file that is too short or too long is refused`() {
        assertEquals(ModelsProtocol.HANDOVER_WRONG_SIZE, accept(spec.id, ByteArrayInputStream(good.copyOf(999))))
        assertEquals(ModelsProtocol.HANDOVER_WRONG_SIZE, accept(spec.id, ByteArrayInputStream(good + byteArrayOf(1))))
        assertTrue(nothingLeft())
    }

    @Test fun `an endless stream is cut off at the catalog size`() {
        val endless = object : InputStream() {
            var served = 0L
            override fun read(): Int = 7.also { served++ }
            override fun read(b: ByteArray, off: Int, len: Int): Int { b.fill(7, off, off + len); served += len; return len }
        }
        assertEquals(ModelsProtocol.HANDOVER_WRONG_SIZE, accept(spec.id, endless))
        assertTrue(endless.served < 1_000_000, "read ${endless.served} bytes")
        assertTrue(nothingLeft())
    }

    @Test fun `something that is not a model file is refused`() {
        assertEquals(ModelsProtocol.HANDOVER_NOT_A_MODEL, accept(spec.id, ByteArrayInputStream(ByteArray(1000) { 1 })))
        assertTrue(nothingLeft())
    }

    @Test fun `an id that is not in the catalog is refused without reading the file`() {
        val input = object : InputStream() {
            var touched = false
            override fun read(): Int { touched = true; return -1 }
        }
        assertEquals(ModelsProtocol.HANDOVER_BAD_REQUEST, accept("evil", input))
        assertEquals(ModelsProtocol.HANDOVER_BAD_REQUEST, accept(null, input))
        assertEquals(ModelsProtocol.HANDOVER_BAD_REQUEST, accept("../grammar.gguf", input))
        assertTrue(!input.touched)
        assertTrue(nothingLeft())
    }

    @Test fun `a refused file never replaces an installed good model`() {
        assertEquals(ModelsProtocol.HANDOVER_OK, accept(spec.id, ByteArrayInputStream(good)))
        assertEquals(ModelsProtocol.HANDOVER_WRONG_CONTENT, accept(spec.id, ByteArrayInputStream(ggufFile(1000, 9))))
        assertTrue(File(dir, "grammar.gguf").readBytes().contentEquals(good))
    }

    @Test fun `the real catalog is used by default and the bundled voice detector cannot be handed over`() {
        assertEquals(
            ModelsProtocol.HANDOVER_BAD_REQUEST,
            ModelHandover.accept(store, ModelCatalog.SILERO_VAD.id, ByteArrayInputStream(ByteArray(8))),
        )
        assertEquals(
            ModelsProtocol.HANDOVER_BAD_REQUEST,
            ModelHandover.accept(store, ModelCatalog.SILERO_VAD.fileName, ByteArrayInputStream(ByteArray(8))),
        )
    }

    // ---- experimental custom models: the same untrusted treatment, whatever the companion says

    private fun whisper(size: Int = 1000) = helium314.keyboard.clackatoo.assist.ModelBytes.whisper(size)
    private fun text(arch: String = "qwen3") = helium314.keyboard.clackatoo.assist.ModelBytes.gguf(arch)

    private fun custom(kind: ModelKind, name: String, bytes: ByteArray, size: Long = bytes.size.toLong(), probe: (File, ModelSpec) -> Boolean = { _, _ -> true }) =
        ModelHandover.acceptCustom(store, kind, name, ByteArrayInputStream(bytes), size, probe)

    @Test fun `a custom file the model process can load is installed and reported with its spec`() {
        val handed = custom(ModelKind.GRAMMAR, "Qwen3-0.6B-Q4_K_M.gguf", text())
        assertEquals(ModelsProtocol.HANDOVER_OK, handed.code)
        assertEquals("custom:grammar:Qwen3-0.6B-Q4_K_M.gguf", handed.spec?.id)
        assertTrue(File(dir, "custom/grammar/Qwen3-0.6B-Q4_K_M.gguf").isFile)
    }

    @Test fun `each refusal has its own answer and leaves nothing behind`() {
        assertEquals(ModelsProtocol.HANDOVER_NOT_A_MODEL, custom(ModelKind.GRAMMAR, "x.gguf", ByteArray(5000) { 1 }).code)
        assertEquals(ModelsProtocol.HANDOVER_WRONG_ROLE, custom(ModelKind.SPEECH, "x.gguf", text()).code)
        assertEquals(ModelsProtocol.HANDOVER_WRONG_ROLE, custom(ModelKind.GRAMMAR, "x.bin", whisper()).code)
        assertEquals(ModelsProtocol.HANDOVER_TOO_LARGE, custom(ModelKind.GRAMMAR, "x.gguf", text(), size = ModelsProtocol.MAX_CUSTOM_BYTES + 1).code)
        assertEquals(ModelsProtocol.HANDOVER_LOAD_FAILED, custom(ModelKind.GRAMMAR, "x.gguf", text(), probe = { _, _ -> false }).code)
        assertTrue(dir.walkTopDown().none { it.isFile })
    }

    @Test fun `the name the companion sends decides nothing but the sanitised file name`() {
        val handed = custom(ModelKind.GRAMMAR, "../../../../data/evil\u0000/..\\x.gguf", text())
        assertEquals(ModelsProtocol.HANDOVER_OK, handed.code)
        val files = dir.walkTopDown().filter { it.isFile }.toList()
        assertEquals(1, files.size)
        assertEquals(File(dir, "custom/grammar").canonicalFile, files[0].parentFile.canonicalFile)
        // a catalog file name is not special: it lands in the custom folder, never over the catalog file
        assertEquals(ModelsProtocol.HANDOVER_OK, custom(ModelKind.GRAMMAR, "grammar.gguf", text()).code)
        assertTrue(!File(dir, "grammar.gguf").exists())
        assertEquals(ModelStatus.Missing, store.status(spec))
    }
}
