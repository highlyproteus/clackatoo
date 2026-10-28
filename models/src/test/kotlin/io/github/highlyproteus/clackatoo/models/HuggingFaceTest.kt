// SPDX-License-Identifier: GPL-3.0-only

package io.github.highlyproteus.clackatoo.models

import helium314.keyboard.clackatoo.catalog.ModelsProtocol
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the app takes from the Hugging Face API, and what it refuses. (org.json needs Robolectric.) */
@RunWith(RobolectricTestRunner::class)
class HuggingFaceTest {
    private val commit = "0123456789abcdef0123456789abcdef01234567"
    private val digest = "a".repeat(64)

    private fun repoJson(sha: String? = commit, siblings: String): String {
        val shaField = if (sha != null) ",\"sha\":\"$sha\"" else ""
        return "{\"id\":\"o/n\"$shaField,\"siblings\":[$siblings]}"
    }

    private fun file(name: String, size: String? = "100", lfs: String? = null): String {
        val sizeField = if (size != null) ",\"size\":$size" else ""
        val lfsField = if (lfs != null) ",\"lfs\":$lfs" else ""
        return "{\"rfilename\":\"$name\"$sizeField$lfsField}"
    }

    // ---------------------------------------------------------------- repository ids

    @Test fun `repository ids of the usual shapes parse`() {
        for (text in listOf("owner/name", "ggerganov/whisper.cpp", "a/b", "Org-1/Model_v2.0-gguf", "  owner/name  ", "0/0")) {
            val id = assertNotNull(RepoId.parse(text), text)
            assertEquals(text.trim(), id.toString())
        }
        assertEquals(RepoId("owner", "name"), RepoId.parse("owner/name"))
    }

    @Test fun `anything else is not a repository id`() {
        for (text in listOf(
            "", "owner", "owner/", "/name", "owner/name/extra", "../name", "owner/..", "owner/../x", "./x", "owner/.", "owner/.hidden", ".owner/name",
            "-owner/name", "owner/-name", "owner/na me", "own er/name", "owner\\name", "owner/name?x=1", "owner/name#frag", "owner/name%2e", "own%er/name",
            "https://huggingface.co/owner/name", "owner/näme", "a".repeat(97) + "/name", "owner/" + "b".repeat(97), "owner:x/name",
        )) {
            assertNull(RepoId.parse(text), text)
        }
    }

    @Test fun `only surrounding white space is forgiven`() {
        assertEquals(RepoId("owner", "name"), RepoId.parse(" owner/name\n"))
        assertNull(RepoId.parse("owner\n/name"))
    }

    @Test fun `the longest allowed parts parse`() {
        assertNotNull(RepoId.parse("a".repeat(96) + "/" + "b".repeat(96)))
    }

    // ---------------------------------------------------------------- file paths

    @Test fun `plain relative paths are valid`() {
        for (path in listOf("model.gguf", "a/b/c.bin", "Q4_K_M/model-00001-of-00002.gguf", "x.y.z", "a".repeat(200))) assertTrue(HuggingFace.validateFilePath(path), path)
    }

    @Test fun `paths with traversal, empty segments, odd characters or the wrong length are not`() {
        for (path in listOf(
            "", "..", "../x.gguf", "a/../b.gguf", "a/..", "./a.gguf", "a/./b.gguf", "/a.gguf", "a//b.gguf", "a/", "a b.gguf", "a\\b.gguf", "a%2e%2e/b",
            "a?b.gguf", "a#b.gguf", "ä.gguf", "a\nb.gguf", "a".repeat(201), "a:b.gguf", "a\u0000.gguf",
        )) assertFalse(HuggingFace.validateFilePath(path), path)
    }

    // ---------------------------------------------------------------- search

    @Test fun `a search answer gives ids and downloads`() {
        val hits = assertNotNull(HuggingFace.parseSearch("""[{"id":"a/b","downloads":1234,"likes":5},{"id":"c/d"},{"id":"e/f","downloads":-3}]"""))
        assertEquals(listOf(RepoHit(RepoId("a", "b"), 1234), RepoHit(RepoId("c", "d"), null), RepoHit(RepoId("e", "f"), null)), hits)
    }

    @Test fun `bad search entries are ignored and the rest kept`() {
        val json = """[1,"x",null,{},{"id":5},{"id":"no-slash"},{"id":"../x"},{"id":"a/b c"},{"id":"ok/one","downloads":"many"},{"modelId":"ok/two"},{"id":"ok/one"},[{"id":"in/array"}]]"""
        assertEquals(listOf(RepoHit(RepoId("ok", "one"), null)), HuggingFace.parseSearch(json))
    }

    @Test fun `a search answer is capped`() {
        val json = (1..50).joinToString(",", "[", "]") { """{"id":"o/n$it","downloads":$it}""" }
        val hits = assertNotNull(HuggingFace.parseSearch(json))
        assertEquals(HuggingFace.MAX_HITS, hits.size)
        assertEquals(RepoId("o", "n1"), hits.first().id)
    }

    @Test fun `an answer that is not an array is not a search answer`() {
        for (json in listOf("", "{}", """{"id":"a/b"}""", "[", "null", "<html>", "\"a/b\"")) assertNull(HuggingFace.parseSearch(json), json)
        assertEquals(emptyList(), HuggingFace.parseSearch("[]"))
    }

    @Test fun `the search address is encoded and sorted by downloads`() {
        val url = HuggingFace.searchUrl("whisper tiny&x=1#y/é")
        assertEquals("https://huggingface.co/api/models?search=whisper+tiny%26x%3D1%23y%2F%C3%A9&limit=20&sort=downloads&direction=-1", url)
        assertTrue(DownloadPolicy.isAllowed(url))
    }

    @Test fun `the repository address asks for sizes and is allowed`() {
        val url = HuggingFace.repoUrl(RepoId("a", "b.c"))
        assertEquals("https://huggingface.co/api/models/a/b.c?blobs=true", url)
        assertTrue(DownloadPolicy.isAllowed(url))
    }

    // ---------------------------------------------------------------- repository answer

    @Test fun `only gguf and bin files of a valid path and size are kept, in order`() {
        val json = repoJson(siblings = listOf(
            file("README.md"), file("config.json"), file("model.safetensors"), file("a.gguf", "5"),
            file("sub/dir/B.GGUF", "6"), file("c.Bin", "7"), file("d.bin.txt"), file("gguf"), file("../e.gguf"),
            file("sp ace.gguf"), file("zero.gguf", "0"), file("neg.gguf", "-5"), file("none.gguf", null),
            file("huge.gguf", (ModelsProtocol.MAX_CUSTOM_BYTES + 1).toString()), file("max.gguf", ModelsProtocol.MAX_CUSTOM_BYTES.toString()),
            file("str.gguf", "\"7\""), file("frac.gguf", "7.5"), file("a//b.gguf"), file("/abs.gguf"),
        ).joinToString(","))
        val repo = assertNotNull(HuggingFace.parseRepo(json))
        assertEquals(commit, repo.sha)
        assertEquals(
            listOf(RepoFile("a.gguf", 5, null), RepoFile("sub/dir/B.GGUF", 6, null), RepoFile("c.Bin", 7, null), RepoFile("max.gguf", ModelsProtocol.MAX_CUSTOM_BYTES, null)),
            repo.files,
        )
        assertEquals("B.GGUF", repo.files[1].name)
    }

    @Test fun `entries that are not objects, or have no name, are ignored`() {
        val repo = assertNotNull(HuggingFace.parseRepo(repoJson(siblings = """1,"a.gguf",null,[],{"size":5},{"rfilename":7,"size":5},${file("ok.gguf")}""")))
        assertEquals(listOf("ok.gguf"), repo.files.map { it.path })
    }

    @Test fun `the file list is capped`() {
        val repo = assertNotNull(HuggingFace.parseRepo(repoJson(siblings = (1..300).joinToString(",") { file("m$it.gguf") })))
        assertEquals(HuggingFace.MAX_FILES, repo.files.size)
    }

    @Test fun `a commit that is not 40 lower-case hex digits is refused`() {
        for (sha in listOf("main", "", "0123456789ABCDEF0123456789ABCDEF01234567", "0123456789abcdef0123456789abcdef0123456", "0123456789abcdef0123456789abcdef012345678", "g".repeat(40), "../" + "a".repeat(37))) {
            assertNull(HuggingFace.parseRepo(repoJson(sha = sha, siblings = file("a.gguf"))), sha)
        }
        assertNull(HuggingFace.parseRepo(repoJson(sha = null, siblings = file("a.gguf"))))
        assertNull(HuggingFace.parseRepo("""{"sha":123,"siblings":[]}"""))
    }

    @Test fun `an answer without siblings or that is not an object is refused`() {
        assertNull(HuggingFace.parseRepo("""{"sha":"$commit"}"""))
        assertNull(HuggingFace.parseRepo("""{"sha":"$commit","siblings":"x"}"""))
        for (json in listOf("", "[]", "null", "<html>", "{")) assertNull(HuggingFace.parseRepo(json), json)
        assertEquals(emptyList(), assertNotNull(HuggingFace.parseRepo("""{"sha":"$commit","siblings":[]}""")).files)
    }

    @Test fun `an LFS digest of 64 lower-case hex digits is kept, anything else is dropped`() {
        fun digestOf(lfs: String?) = assertNotNull(HuggingFace.parseRepo(repoJson(siblings = file("a.gguf", "100", lfs)))).files.single().sha256
        assertEquals(digest, digestOf("""{"sha256":"$digest","size":100,"pointerSize":134}"""))
        assertNull(digestOf(null))
        assertNull(digestOf("""{"size":100}"""))
        assertNull(digestOf("""{"sha256":"${"A".repeat(64)}","size":100}"""))
        assertNull(digestOf("""{"sha256":"${"a".repeat(63)}","size":100}"""))
        assertNull(digestOf("""{"sha256":"${"a".repeat(65)}","size":100}"""))
        assertNull(digestOf("""{"sha256":"${"g".repeat(64)}","size":100}"""))
        assertNull(digestOf("""{"sha256":5,"size":100}"""))
    }

    @Test fun `the size comes from the entry, or from lfs when the entry has none, and two sizes that differ refuse the file`() {
        fun sizeOf(size: String?, lfs: String?) = HuggingFace.parseRepo(repoJson(siblings = file("a.gguf", size, lfs)))!!.files.singleOrNull()?.sizeBytes
        assertEquals(100L, sizeOf("100", """{"sha256":"$digest","size":100}"""))
        assertEquals(200L, sizeOf(null, """{"sha256":"$digest","size":200}"""))
        assertNull(sizeOf("100", """{"sha256":"$digest","size":101}"""))
        assertNull(sizeOf(null, null))
    }

    // ---------------------------------------------------------------- download address

    @Test fun `the address is pinned to the commit and the path segments are encoded`() {
        val repo = RepoId("owner", "name")
        assertEquals("https://huggingface.co/owner/name/resolve/$commit/model.gguf", HuggingFace.buildResolveUrl(repo, commit, "model.gguf"))
        assertEquals("https://huggingface.co/owner/name/resolve/$commit/a/b/c-1_2.x.bin", HuggingFace.buildResolveUrl(repo, commit, "a/b/c-1_2.x.bin"))
    }

    @Test fun `segments are percent-encoded byte by byte`() {
        assertEquals("a%20b%2Fc%3F%23%25%C3%A9.gguf-_.~", HuggingFace.encodeSegment("a b/c?#%é.gguf-_.~"))
        assertEquals("plain-1_2.3", HuggingFace.encodeSegment("plain-1_2.3"))
    }

    @Test fun `no address is built for a bad commit or path`() {
        val repo = RepoId("owner", "name")
        for (sha in listOf("main", "", commit.uppercase(), commit.dropLast(1))) assertNull(HuggingFace.buildResolveUrl(repo, sha, "m.gguf"), sha)
        for (path in listOf("", "..", "../m.gguf", "a/../m.gguf", "a b.gguf", "/m.gguf", "a//m.gguf", "m.gguf?x=1", "m.gguf#x")) assertNull(HuggingFace.buildResolveUrl(repo, commit, path), path)
    }

    @Test fun `built addresses are on huggingface co and pass the policy, whatever the repository`() {
        for (id in listOf("owner/name", "a/b", "x.y/z-w_1", "huggingface.co/evil", "hf.co/evil", "evil.example/x")) {
            val repo = assertNotNull(RepoId.parse(id))
            val url = assertNotNull(HuggingFace.buildResolveUrl(repo, commit, "dir/m.gguf"))
            assertTrue(url.startsWith("https://huggingface.co/$id/resolve/$commit/"), url)
            assertTrue(DownloadPolicy.isAllowed(url), url)
        }
    }

    @Test fun `the policy refuses other hosts for the same paths`() {
        for (host in listOf("evil.example", "huggingface.co.evil.example", "hf.co", "example.com@huggingface.co.evil.example")) {
            assertFalse(DownloadPolicy.isAllowed("https://$host/owner/name/resolve/$commit/m.gguf"), host)
        }
        assertFalse(DownloadPolicy.isAllowed("http://huggingface.co/owner/name/resolve/$commit/m.gguf"))
    }
}
