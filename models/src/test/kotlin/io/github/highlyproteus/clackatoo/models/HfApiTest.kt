// SPDX-License-Identifier: GPL-3.0-only

package io.github.highlyproteus.clackatoo.models

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The API calls with a fake [HttpSource]: allowed redirects, refused ones, the 2 MB cap, odd statuses. */
@RunWith(RobolectricTestRunner::class)
class HfApiTest {
    private val commit = "0123456789abcdef0123456789abcdef01234567"
    private val requested = ArrayList<String>()
    private val ranges = ArrayList<Long>()
    private val routes = HashMap<String, () -> HttpAnswer>()
    private val api = HfApi { url, range ->
        requested += url
        ranges += range
        (routes[url] ?: { HttpAnswer(404, null, 0, null) })()
    }

    private val searchUrl = HuggingFace.searchUrl("whisper")
    private val repo = RepoId("owner", "name")
    private val repoUrl = HuggingFace.repoUrl(repo)
    private val cdn = "https://us.aws.cdn.hf.co/api/answer?x=1"

    private fun ok(text: String) = ok(text.toByteArray())
    private fun ok(bytes: ByteArray, length: Long = bytes.size.toLong()) = HttpAnswer(200, null, length, ByteArrayInputStream(bytes))
    private fun redirect(to: String, status: Int = 302) = HttpAnswer(status, to, 0, null)

    private val searchAnswer = """[{"id":"ggerganov/whisper.cpp","downloads":10}]"""
    private val repoAnswer = """{"sha":"$commit","siblings":[{"rfilename":"m.gguf","size":10,"lfs":{"sha256":"${"b".repeat(64)}","size":10}},{"rfilename":"README.md","size":3}]}"""

    @Test fun `a search is one plain GET of the search address`() {
        routes[searchUrl] = { ok(searchAnswer) }
        assertEquals(ApiResult.Ok(listOf(RepoHit(RepoId("ggerganov", "whisper.cpp"), 10))), api.search("whisper"))
        assertEquals(listOf(searchUrl), requested)
        assertEquals(listOf(0L), ranges)
    }

    @Test fun `the search term is trimmed, cut and must not be empty`() {
        assertEquals(ApiResult.Failed(ApiError.UNREADABLE), api.search("   "))
        assertTrue(requested.isEmpty())
        routes[HuggingFace.searchUrl("a".repeat(HuggingFace.MAX_QUERY_LENGTH))] = { ok("[]") }
        assertEquals(ApiResult.Ok(emptyList()), api.search("  " + "a".repeat(500)))
        assertEquals(listOf(HuggingFace.searchUrl("a".repeat(HuggingFace.MAX_QUERY_LENGTH))), requested)
    }

    @Test fun `the files of a repository come from the blobs address`() {
        routes[repoUrl] = { ok(repoAnswer) }
        val result = api.files(repo)
        val files = (result as ApiResult.Ok).value
        assertEquals(commit, files.sha)
        assertEquals(listOf(RepoFile("m.gguf", 10, "b".repeat(64))), files.files)
        assertEquals(listOf(repoUrl), requested)
    }

    @Test fun `a redirect to a hf co host is followed`() {
        routes[searchUrl] = { redirect(cdn, 307) }
        routes[cdn] = { ok(searchAnswer) }
        assertTrue(api.search("whisper") is ApiResult.Ok)
        assertEquals(listOf(searchUrl, cdn), requested)
    }

    @Test fun `a relative redirect stays on huggingface co`() {
        routes[searchUrl] = { redirect("/api/other") }
        routes["https://huggingface.co/api/other"] = { ok(searchAnswer) }
        assertTrue(api.search("whisper") is ApiResult.Ok)
    }

    @Test fun `a redirect anywhere else is refused before it is requested`() {
        for (to in listOf("https://evil.example/x", "http://us.aws.cdn.hf.co/x", "https://hf.co.evil.example/x", "https://huggingface.co@evil.example/x", "ftp://huggingface.co/x", "https://huggingface.co:8443/x")) {
            requested.clear()
            routes[searchUrl] = { redirect(to) }
            assertEquals(ApiResult.Failed(ApiError.UNTRUSTED_HOST), api.search("whisper"), to)
            assertEquals(listOf(searchUrl), requested, to)
        }
    }

    @Test fun `a redirect without a location, or in a loop, is refused`() {
        routes[searchUrl] = { HttpAnswer(302, null, 0, null) }
        assertEquals(ApiResult.Failed(ApiError.REFUSED), api.search("whisper"))
        requested.clear()
        routes[searchUrl] = { redirect(searchUrl) }
        assertEquals(ApiResult.Failed(ApiError.REFUSED), api.search("whisper"))
        assertEquals(Downloader.MAX_REDIRECTS + 1, requested.size)
    }

    @Test fun `an answer of more than 2 MB is refused while reading`() {
        routes[searchUrl] = { ok(ByteArray(HfApi.MAX_ANSWER_BYTES + 1) { ' '.code.toByte() }, length = -1) }
        assertEquals(ApiResult.Failed(ApiError.TOO_BIG), api.search("whisper"))
    }

    @Test fun `an answer that declares more than 2 MB is refused without reading it`() {
        var read = false
        routes[searchUrl] = {
            HttpAnswer(200, null, HfApi.MAX_ANSWER_BYTES + 1L, object : InputStream() {
                override fun read(): Int { read = true; return -1 }
            })
        }
        assertEquals(ApiResult.Failed(ApiError.TOO_BIG), api.search("whisper"))
        assertTrue(!read)
    }

    @Test fun `an answer of exactly 2 MB is read`() {
        val bytes = ByteArray(HfApi.MAX_ANSWER_BYTES) { ' '.code.toByte() }
        bytes[0] = '['.code.toByte()
        bytes[bytes.size - 1] = ']'.code.toByte()
        routes[searchUrl] = { ok(bytes) }
        assertEquals(ApiResult.Ok(emptyList()), api.search("whisper"))
    }

    @Test fun `a status other than 200 is an error, a 404 is not found`() {
        routes[repoUrl] = { HttpAnswer(404, null, 0, null) }
        assertEquals(ApiResult.Failed(ApiError.NOT_FOUND), api.files(repo))
        for (status in listOf(204, 206, 304, 400, 401, 403, 429, 500, 503)) {
            routes[repoUrl] = { HttpAnswer(status, null, 0, null) }
            assertEquals(ApiResult.Failed(ApiError.REFUSED), api.files(repo), status.toString())
        }
    }

    @Test fun `a 200 without a body is a network error`() {
        routes[repoUrl] = { HttpAnswer(200, null, 0, null) }
        assertEquals(ApiResult.Failed(ApiError.NETWORK), api.files(repo))
    }

    @Test fun `a broken connection is a network error`() {
        routes[repoUrl] = { throw IOException("down") }
        assertEquals(ApiResult.Failed(ApiError.NETWORK), api.files(repo))
        routes[repoUrl] = { HttpAnswer(200, null, -1, object : InputStream() { override fun read(): Int = throw IOException("cut") }) }
        assertEquals(ApiResult.Failed(ApiError.NETWORK), api.files(repo))
    }

    @Test fun `an answer that cannot be parsed is unreadable, and no file is offered from a bad commit`() {
        routes[searchUrl] = { ok("<html>") }
        assertEquals(ApiResult.Failed(ApiError.UNREADABLE), api.search("whisper"))
        routes[repoUrl] = { ok("""{"sha":"main","siblings":[{"rfilename":"m.gguf","size":10}]}""") }
        assertEquals(ApiResult.Failed(ApiError.UNREADABLE), api.files(repo))
    }

    @Test fun `every request is allowed by the policy`() {
        routes[searchUrl] = { ok(searchAnswer) }
        routes[repoUrl] = { ok(repoAnswer) }
        api.search("whisper")
        api.files(repo)
        assertTrue(requested.isNotEmpty() && requested.all { DownloadPolicy.isAllowed(it) })
    }
}
