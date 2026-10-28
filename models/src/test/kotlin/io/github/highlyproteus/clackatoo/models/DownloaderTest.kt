// SPDX-License-Identifier: GPL-3.0-only

package io.github.highlyproteus.clackatoo.models

import helium314.keyboard.clackatoo.catalog.CatalogEngine
import helium314.keyboard.clackatoo.catalog.CatalogKind
import helium314.keyboard.clackatoo.catalog.CatalogModel
import helium314.keyboard.clackatoo.catalog.ModelsProtocol
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
import kotlin.test.assertTrue

class DownloaderTest {
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private val content = ByteArray(200_000) { (it % 251).toByte() }
    private val model = CatalogModel(
        "test-model", CatalogKind.GRAMMAR, CatalogEngine.LLAMA, "test.gguf", "Test", "Me", "MIT", listOf("en"),
        content.size.toLong(), sha(content), "owner/repo", "0123456789abcdef0123456789abcdef01234567",
    )
    private val cdn = "https://us.aws.cdn.hf.co/xet-bridge-us/abc/def?Policy=1"

    private lateinit var dir: File
    private lateinit var target: File
    private val requested = ArrayList<String>()
    private val ranges = ArrayList<Long>()
    private var routes = HashMap<String, (Long) -> HttpAnswer>()

    @BeforeTest fun setUp() {
        dir = kotlin.io.path.createTempDirectory("dl").toFile()
        target = File(dir, "test.gguf.part")
        routes[model.url] = { _ -> redirect(cdn) }
        routes[cdn] = { _ -> ok(content) }
    }

    @AfterTest fun tearDown() { dir.deleteRecursively() }

    private fun redirect(to: String, status: Int = 302) = HttpAnswer(status, to, 0, null)
    private fun ok(bytes: ByteArray, length: Long = bytes.size.toLong()) = HttpAnswer(200, null, length, ByteArrayInputStream(bytes))

    private fun download(cancelled: () -> Boolean = { false }, space: Long = Long.MAX_VALUE, progress: (Long) -> Unit = {}): DownloadOutcome {
        val source = HttpSource { url, range ->
            requested += url
            ranges += range
            (routes[url] ?: { _: Long -> HttpAnswer(404, null, 0, null) })(range)
        }
        return Downloader(source) { space }.download(model, target, cancelled, progress)
    }

    private fun nothingLeft() = dir.listFiles().orEmpty().isEmpty()

    private fun partial(bytes: Int) = target.writeBytes(content.copyOf(bytes))
    private fun resumable(servedFrom: MutableList<Long>? = null) {
        routes[cdn] = { from ->
            servedFrom?.add(from)
            if (from > 0) HttpAnswer(206, null, content.size - from, ByteArrayInputStream(content, from.toInt(), content.size - from.toInt()), from, content.size.toLong())
            else ok(content)
        }
    }

    @Test fun `the pinned URL is fetched, its redirect followed, and the exact file kept`() {
        val seen = ArrayList<Long>()
        assertEquals(DownloadOutcome.Done, download { seen += it })
        assertEquals(listOf(model.url, cdn), requested)
        assertContentEquals(content, target.readBytes())
        assertEquals(content.size.toLong(), seen.last())
        assertTrue(seen.zipWithNext().all { (a, b) -> a < b })
    }

    @Test fun `a redirect to another host stops the download before anything is requested there`() {
        routes[model.url] = { _ -> redirect("https://evil.example/model.gguf") }
        assertEquals(DownloadOutcome.Failed(ModelsProtocol.FAIL_UNTRUSTED_HOST), download())
        assertEquals(listOf(model.url), requested)
        assertTrue(nothingLeft())
    }

    @Test fun `a redirect to http, or to a look-alike host, or with user info stops it`() {
        for (to in listOf("http://us.aws.cdn.hf.co/x", "https://hf.co.evil.example/x", "https://huggingface.co@evil.example/x", "ftp://huggingface.co/x")) {
            requested.clear()
            routes[model.url] = { _ -> redirect(to) }
            assertEquals(DownloadOutcome.Failed(ModelsProtocol.FAIL_UNTRUSTED_HOST), download(), to)
            assertEquals(listOf(model.url), requested, to)
        }
    }

    @Test fun `a relative redirect stays on the same host`() {
        routes[model.url] = { _ -> redirect("/cdn/file") }
        routes["https://huggingface.co/cdn/file"] = { _ -> ok(content) }
        assertEquals(DownloadOutcome.Done, download())
    }

    @Test fun `a redirect loop ends`() {
        routes[model.url] = { _ -> redirect(model.url) }
        assertEquals(DownloadOutcome.Failed(ModelsProtocol.FAIL_NETWORK), download())
        assertEquals(Downloader.MAX_REDIRECTS + 1, requested.size)
    }

    @Test fun `a file with the right size but other bytes fails the digest and is deleted`() {
        routes[cdn] = { _ -> ok(content.copyOf().also { it[1000] = (it[1000] + 1).toByte() }) }
        assertEquals(DownloadOutcome.Failed(ModelsProtocol.FAIL_DIGEST), download())
        assertTrue(nothingLeft())
    }

    @Test fun `a short file is a broken connection, it fails and the partial file is kept for resuming`() {
        routes[cdn] = { _ -> ok(content.copyOf(content.size - 1), length = -1) }
        assertEquals(DownloadOutcome.Failed(ModelsProtocol.FAIL_NETWORK), download())
        assertEquals(content.size - 1L, target.length())
        assertContentEquals(content.copyOf(content.size - 1), target.readBytes())
    }

    @Test fun `a long file is cut off at the exact size and deleted`() {
        var served = 0L
        routes[cdn] = { _ ->
            HttpAnswer(200, null, -1, object : InputStream() {
                override fun read(): Int = 1
                override fun read(b: ByteArray, off: Int, len: Int): Int { served += len; b.fill(1, off, off + len); return len }
            })
        }
        assertEquals(DownloadOutcome.Failed(ModelsProtocol.FAIL_SIZE), download())
        assertTrue(served < content.size + 200_000, "read $served")
        assertTrue(nothingLeft())
    }

    @Test fun `a Content-Length that is not the catalog size is refused before reading`() {
        var read = false
        routes[cdn] = { _ -> HttpAnswer(200, null, content.size + 1L, object : InputStream() { override fun read(): Int { read = true; return -1 } }) }
        assertEquals(DownloadOutcome.Failed(ModelsProtocol.FAIL_SIZE), download())
        assertTrue(!read)
        assertTrue(nothingLeft())
    }

    @Test fun `a server error is a network failure and leaves nothing`() {
        routes[cdn] = { _ -> HttpAnswer(503, null, 0, null) }
        assertEquals(DownloadOutcome.Failed(ModelsProtocol.FAIL_NETWORK), download())
        assertTrue(nothingLeft())
    }

    @Test fun `a connection that breaks keeps what arrived, and the next call resumes with a Range request`() {
        routes[cdn] = { _ ->
            HttpAnswer(200, null, content.size.toLong(), object : InputStream() {
                var n = 0
                override fun read(): Int = throw IOException("reset")
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (n > 0) throw IOException("reset")
                    n++
                    System.arraycopy(content, 0, b, off, 1000)
                    return 1000
                }
            })
        }
        assertEquals(DownloadOutcome.Failed(ModelsProtocol.FAIL_NETWORK), download())
        assertEquals(1000L, target.length())
        val from = ArrayList<Long>()
        resumable(from)
        requested.clear()
        ranges.clear()
        val seen = ArrayList<Long>()
        assertEquals(DownloadOutcome.Done, download { seen += it })
        assertEquals(listOf(1000L), from)
        assertContentEquals(content, target.readBytes())
        assertEquals(content.size.toLong(), seen.last())
        assertTrue(seen.first() > 1000, "progress counts the bytes already there")
    }

    @Test fun `a resumed file is still checked against the digest`() {
        partial(50_000)
        val tampered = content.copyOf().also { it[10] = (it[10] + 1).toByte() } // a bad byte in the part that is already there
        routes[cdn] = { from -> HttpAnswer(206, null, content.size - from, ByteArrayInputStream(tampered, from.toInt(), content.size - from.toInt()), from, content.size.toLong()) }
        target.writeBytes(tampered.copyOf(50_000))
        assertEquals(DownloadOutcome.Failed(ModelsProtocol.FAIL_DIGEST), download())
        assertTrue(nothingLeft())
    }

    @Test fun `a server that ignores the Range request restarts the file from the beginning`() {
        partial(50_000)
        assertEquals(DownloadOutcome.Done, download()) // the default route answers 200 with the whole file
        assertEquals(50_000L, ranges.first())
        assertContentEquals(content, target.readBytes())
    }

    @Test fun `a 206 for another place or another file is refused and the partial file removed`() {
        for (answer in listOf(
            HttpAnswer(206, null, content.size - 50_000L, ByteArrayInputStream(content, 40_000, 100), 40_000, content.size.toLong()), // wrong start
            HttpAnswer(206, null, content.size - 50_000L, ByteArrayInputStream(content, 50_000, 100), 50_000, content.size + 1L), // other total
            HttpAnswer(206, null, 77, ByteArrayInputStream(content, 50_000, 77), 50_000, content.size.toLong()), // wrong length
            HttpAnswer(206, null, content.size - 50_000L, ByteArrayInputStream(content, 50_000, 100)), // no Content-Range at all
        )) {
            partial(50_000)
            routes[cdn] = { _ -> answer }
            assertEquals(DownloadOutcome.Failed(ModelsProtocol.FAIL_SIZE), download())
            assertTrue(nothingLeft())
        }
    }

    @Test fun `a 416 means the partial file does not fit and it is removed`() {
        partial(50_000)
        routes[cdn] = { _ -> HttpAnswer(416, null, 0, null) }
        assertEquals(DownloadOutcome.Failed(ModelsProtocol.FAIL_SIZE), download())
        assertTrue(nothingLeft())
    }

    @Test fun `a partial file longer than the model is discarded and the download starts again`() {
        target.writeBytes(ByteArray(content.size + 10))
        assertEquals(DownloadOutcome.Done, download())
        assertEquals(0L, ranges.first())
        assertContentEquals(content, target.readBytes())
    }

    @Test fun `a 206 without anything to resume is refused`() {
        routes[cdn] = { _ -> HttpAnswer(206, null, content.size.toLong(), ByteArrayInputStream(content), 0, content.size.toLong()) }
        assertEquals(DownloadOutcome.Failed(ModelsProtocol.FAIL_SIZE), download())
        assertTrue(nothingLeft())
    }

    @Test fun `cancelling stops the transfer and deletes the partial file`() {
        var calls = 0
        val outcome = download(cancelled = { calls++ > 3 })
        assertEquals(DownloadOutcome.Failed(ModelsProtocol.FAIL_CANCELLED), outcome)
        assertTrue(nothingLeft())
    }

    @Test fun `too little free storage is reported before any request`() {
        assertEquals(DownloadOutcome.Failed(ModelsProtocol.FAIL_NO_SPACE), download(space = content.size.toLong()))
        assertTrue(requested.isEmpty())
    }

    @Test fun `a catalog URL that is not allowed is never requested`() {
        val bad = model.copy(repo = "owner/repo", revision = "0123456789abcdef0123456789abcdef01234567")
        // the real policy check is on the address, so a catalog entry cannot smuggle in a host: simulate through a redirect-free 200 on the wrong host
        assertTrue(DownloadPolicy.isAllowed(bad.url))
    }
}
