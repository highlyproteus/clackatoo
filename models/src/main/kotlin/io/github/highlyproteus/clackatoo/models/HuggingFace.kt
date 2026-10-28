// SPDX-License-Identifier: GPL-3.0-only

package io.github.highlyproteus.clackatoo.models

import helium314.keyboard.clackatoo.catalog.ModelsProtocol
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URLEncoder

/** A Hugging Face repository id, `owner/name`, in the only shape this app ever puts into an address. */
internal data class RepoId(val owner: String, val name: String) {
    override fun toString() = "$owner/$name"

    companion object {
        private val pattern = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,95}/[A-Za-z0-9][A-Za-z0-9._-]{0,95}$")

        /** [text] (surrounding spaces ignored) as a repository id, or null when it is anything else. */
        fun parse(text: String): RepoId? {
            val t = text.trim()
            if (!pattern.matches(t)) return null
            val slash = t.indexOf('/')
            return RepoId(t.substring(0, slash), t.substring(slash + 1))
        }
    }
}

/** One repository of a search answer. */
internal data class RepoHit(val id: RepoId, val downloads: Long?)

/** One file the user may pick: its path inside the repository, its exact size and, when the API gave it, its SHA-256. */
internal data class RepoFile(val path: String, val sizeBytes: Long, val sha256: String?) {
    /** The last segment of [path]. */
    val name: String get() = path.substringAfterLast('/')
}

/** The files of a repository at one commit ([sha], 40 lower-case hex): the downloads are pinned to it. */
internal data class RepoFiles(val sha: String, val files: List<RepoFile>)

/**
 * What this app takes from the Hugging Face API, and nothing else: repository ids, download counts, a commit, and
 * the path, size and SHA-256 of model files that pass the checks below. Every text from the server goes through one of
 * these checks before it is shown or put into an address; unparseable answers give null.
 */
internal object HuggingFace {
    const val MAX_HITS = 20
    const val MAX_FILES = 200
    const val MAX_QUERY_LENGTH = 100

    private val filePathPattern = Regex("^[A-Za-z0-9._/-]{1,200}$")
    private val commitPattern = Regex("^[0-9a-f]{40}$")
    private val digestPattern = Regex("^[0-9a-f]{64}$")

    /** The model files the keyboard can use have these endings (compared without regard to case). */
    private val extensions = listOf(".gguf", ".bin")

    fun isCommit(text: String) = commitPattern.matches(text)

    /** True for a plain relative path of letters, digits, `.`, `_`, `-` and `/`, without an empty, `.` or `..` segment. */
    fun validateFilePath(path: String): Boolean =
        filePathPattern.matches(path) && path.split('/').none { it.isEmpty() || it == "." || it == ".." }

    /** A search answer: an array of objects with an `id`. Entries that are not objects or have no valid id are skipped. */
    fun parseSearch(json: String): List<RepoHit>? {
        val array = try { JSONArray(json) } catch (_: JSONException) { return null }
        val hits = ArrayList<RepoHit>()
        for (i in 0 until array.length()) {
            if (hits.size >= MAX_HITS) break
            val entry = array.optJSONObject(i) ?: continue
            val id = (entry.opt("id") as? String)?.let { RepoId.parse(it) } ?: continue
            if (hits.any { it.id == id }) continue
            hits += RepoHit(id, wholeNumber(entry.opt("downloads"))?.takeIf { it >= 0 })
        }
        return hits
    }

    /**
     * A repository answer (`?blobs=true`): the commit `sha` and the `siblings`. Only `.gguf` and `.bin` files with a
     * valid path and a size from 1 byte to [ModelsProtocol.MAX_CUSTOM_BYTES] are kept, at most [MAX_FILES]. Null when there is
     * no valid commit or no `siblings` array: then no address can be built.
     */
    fun parseRepo(json: String): RepoFiles? {
        val root = try { JSONObject(json) } catch (_: JSONException) { return null }
        val sha = root.opt("sha") as? String
        if (sha == null || !isCommit(sha)) return null
        val siblings = root.optJSONArray("siblings") ?: return null
        val files = ArrayList<RepoFile>()
        for (i in 0 until siblings.length()) {
            if (files.size >= MAX_FILES) break
            files += parseFile(siblings.optJSONObject(i) ?: continue) ?: continue
        }
        return RepoFiles(sha, files)
    }

    private fun parseFile(entry: JSONObject): RepoFile? {
        val path = entry.opt("rfilename") as? String ?: return null
        val lower = path.lowercase()
        if (extensions.none { lower.endsWith(it) } || !validateFilePath(path)) return null
        val lfs = entry.optJSONObject("lfs")
        val lfsSize = lfs?.let { wholeNumber(it.opt("size")) }
        val size = wholeNumber(entry.opt("size")) ?: lfsSize ?: return null
        if (lfsSize != null && lfsSize != size) return null // two answers about the size: believe neither
        if (size <= 0 || size > ModelsProtocol.MAX_CUSTOM_BYTES) return null
        val digest = (lfs?.opt("sha256") as? String)?.takeIf { digestPattern.matches(it) }
        return RepoFile(path, size, digest)
    }

    /** `https://huggingface.co/<repo>/resolve/<commit>/<path>`, or null when the commit or the path is not valid. */
    fun buildResolveUrl(repo: RepoId, commit: String, path: String): String? {
        if (!isCommit(commit) || !validateFilePath(path)) return null
        return "https://huggingface.co/$repo/resolve/$commit/${path.split('/').joinToString("/") { encodeSegment(it) }}"
    }

    /** `https://huggingface.co/api/models?search=...`, the 20 most downloaded repositories that match. */
    fun searchUrl(query: String): String =
        "https://huggingface.co/api/models?search=${URLEncoder.encode(query, "UTF-8")}&limit=$MAX_HITS&sort=downloads&direction=-1"

    fun repoUrl(repo: RepoId): String = "https://huggingface.co/api/models/$repo?blobs=true"

    /** Percent-encodes everything but the unreserved characters (RFC 3986), byte by byte as UTF-8. */
    fun encodeSegment(segment: String): String {
        val out = StringBuilder()
        for (b in segment.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xff
            if (c in 'A'.code..'Z'.code || c in 'a'.code..'z'.code || c in '0'.code..'9'.code || c == '-'.code || c == '.'.code || c == '_'.code || c == '~'.code) {
                out.append(c.toChar())
            } else {
                out.append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 15])
            }
        }
        return out.toString()
    }

    /** A JSON integer (not a string, not a fraction) as a Long. */
    private fun wholeNumber(value: Any?): Long? = when (value) {
        is Int -> value.toLong()
        is Long -> value
        else -> null
    }
}

/** Why a request to the Hugging Face API gave nothing. */
internal enum class ApiError { NETWORK, UNTRUSTED_HOST, TOO_BIG, NOT_FOUND, REFUSED, UNREADABLE }

internal sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>
    data class Failed(val error: ApiError) : ApiResult<Nothing>
}

/**
 * The Hugging Face API calls: plain GETs through [source] (no redirect is followed by it, so every redirect is checked by
 * [DownloadPolicy] here, at most [Downloader.MAX_REDIRECTS]), answers read up to [MAX_ANSWER_BYTES] and parsed by
 * [HuggingFace]. Blocking: call from a background thread.
 */
internal class HfApi(private val source: HttpSource = HttpUrlSource) {
    fun search(query: String): ApiResult<List<RepoHit>> {
        val q = query.trim().take(HuggingFace.MAX_QUERY_LENGTH)
        if (q.isEmpty()) return ApiResult.Failed(ApiError.UNREADABLE)
        return fetch(HuggingFace.searchUrl(q), HuggingFace::parseSearch)
    }

    fun files(repo: RepoId): ApiResult<RepoFiles> = fetch(HuggingFace.repoUrl(repo), HuggingFace::parseRepo)

    private fun <T : Any> fetch(url: String, parse: (String) -> T?): ApiResult<T> {
        val text = when (val answer = getText(url)) {
            is ApiResult.Failed -> return answer
            is ApiResult.Ok -> answer.value
        }
        return parse(text)?.let { ApiResult.Ok(it) } ?: ApiResult.Failed(ApiError.UNREADABLE)
    }

    /** The body of a 200 answer to a GET of [first] as text, following allowed redirects only. */
    internal fun getText(first: String): ApiResult<String> {
        var url = first
        var redirects = 0
        try {
            while (true) {
                if (!DownloadPolicy.isAllowed(url)) return ApiResult.Failed(ApiError.UNTRUSTED_HOST)
                source.get(url, 0).use { answer ->
                    when (answer.status) {
                        200 -> return readBody(answer)
                        301, 302, 303, 307, 308 -> {
                            val location = answer.location ?: return ApiResult.Failed(ApiError.REFUSED)
                            url = DownloadPolicy.resolve(url, location) ?: return ApiResult.Failed(ApiError.UNTRUSTED_HOST)
                            if (++redirects > Downloader.MAX_REDIRECTS) return ApiResult.Failed(ApiError.REFUSED)
                        }
                        404 -> return ApiResult.Failed(ApiError.NOT_FOUND)
                        else -> return ApiResult.Failed(ApiError.REFUSED)
                    }
                }
            }
        } catch (_: IOException) {
            return ApiResult.Failed(ApiError.NETWORK)
        } catch (_: RuntimeException) { // a malformed address or the like: refuse, do not crash the screen
            return ApiResult.Failed(ApiError.REFUSED)
        }
    }

    private fun readBody(answer: HttpAnswer): ApiResult<String> {
        val body = answer.body ?: return ApiResult.Failed(ApiError.NETWORK)
        if (answer.contentLength > MAX_ANSWER_BYTES) return ApiResult.Failed(ApiError.TOO_BIG)
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = body.read(buffer)
            if (n < 0) break
            if (out.size() + n > MAX_ANSWER_BYTES) return ApiResult.Failed(ApiError.TOO_BIG)
            out.write(buffer, 0, n)
        }
        return ApiResult.Ok(out.toString(Charsets.UTF_8.name()))
    }

    companion object {
        const val MAX_ANSWER_BYTES = 2 * 1024 * 1024
    }
}
