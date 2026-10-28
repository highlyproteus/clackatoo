// SPDX-License-Identifier: GPL-3.0-only

package io.github.highlyproteus.clackatoo.models

import helium314.keyboard.clackatoo.catalog.CatalogModel
import helium314.keyboard.clackatoo.catalog.ModelsProtocol
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * One HTTP answer: status, the `Location` of a redirect and, for a 200 or 206, the body. For a 206 [rangeStart] and
 * [rangeTotal] are the numbers of its `Content-Range: bytes <start>-<end>/<total>` (or -1 when it has none).
 */
internal class HttpAnswer(
    val status: Int,
    val location: String?,
    val contentLength: Long,
    val body: InputStream?,
    val rangeStart: Long = -1,
    val rangeTotal: Long = -1,
    private val onClose: () -> Unit = {},
) : Closeable {
    override fun close() {
        try { body?.close() } finally { onClose() }
    }
}

internal fun interface HttpSource {
    /** One plain GET of [url]; redirects are NOT followed. With [rangeStart] > 0 it asks for the rest of the file (`Range: bytes=<n>-`). */
    fun get(url: String, rangeStart: Long): HttpAnswer
}

/** The real thing: `HttpURLConnection`, no redirects, no cookies, no body encoding. */
internal const val USER_AGENT = "ClackatooModels"

internal object HttpUrlSource : HttpSource {
    private val contentRange = Regex("bytes (\\d+)-(\\d+)/(\\d+)")

    override fun get(url: String, rangeStart: Long): HttpAnswer {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        connection.connectTimeout = 20_000
        connection.readTimeout = 30_000
        // a fixed User-Agent: the default one carries the Android version and the device model
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.setRequestProperty("Accept-Encoding", "identity") // so Content-Length is the file's size
        if (rangeStart > 0) connection.setRequestProperty("Range", "bytes=$rangeStart-")
        val status = connection.responseCode
        val range = connection.getHeaderField("Content-Range")?.let { contentRange.matchEntire(it) }
        return HttpAnswer(
            status, connection.getHeaderField("Location"), connection.contentLengthLong,
            if (status == 200 || status == 206) connection.inputStream else null,
            range?.groupValues?.get(1)?.toLongOrNull() ?: -1, range?.groupValues?.get(3)?.toLongOrNull() ?: -1,
            onClose = { connection.disconnect() },
        )
    }
}

internal sealed interface DownloadOutcome {
    data object Done : DownloadOutcome
    /** One of the `ModelsProtocol.FAIL_*` codes. */
    data class Failed(val reason: Int) : DownloadOutcome
}

/**
 * What [Downloader] fetches: an address, the name of the file, its exact size and, when known, its SHA-256 (lower-case
 * hex). A catalog model has all of them ([toJob]); a custom model from the Hugging Face browser has the digest only when
 * the API gave one (see [HfApi]).
 */
internal data class DownloadJob(val url: String, val fileName: String, val sizeBytes: Long, val sha256: String?)

internal fun CatalogModel.toJob() = DownloadJob(url, fileName, sizeBytes, sha256)

/**
 * Downloads one file: HTTPS GET of its pinned URL, redirects followed only to the hosts [DownloadPolicy] allows,
 * the bytes counted and hashed while they are written, and the result checked against the job's exact size and
 * SHA-256 (when it has one). Nothing about the file comes from the server except its bytes.
 *
 * A download that breaks off (network error) keeps its partial file at [target]; the next call resumes it with a
 * `Range` request if the server answers 206 with the matching `Content-Range`, and starts again if it answers 200.
 * Every other failure and a cancel delete the partial file.
 */
internal class Downloader(
    private val source: HttpSource = HttpUrlSource,
    private val usableSpace: (File) -> Long = { it.usableSpace },
) {
    fun download(model: CatalogModel, target: File, isCancelled: () -> Boolean, onProgress: (Long) -> Unit): DownloadOutcome =
        download(model.toJob(), target, isCancelled, onProgress)

    fun download(job: DownloadJob, target: File, isCancelled: () -> Boolean, onProgress: (Long) -> Unit): DownloadOutcome {
        val dir = target.absoluteFile.parentFile ?: return DownloadOutcome.Failed(ModelsProtocol.FAIL_OTHER)
        dir.mkdirs()
        // a partial file of this model is continued; anything longer than the model is garbage
        var have = if (target.isFile) target.length() else 0L
        if (have > job.sizeBytes) {
            target.delete()
            have = 0
        }
        if (usableSpace(dir) < job.sizeBytes - have + SPARE_BYTES) return DownloadOutcome.Failed(ModelsProtocol.FAIL_NO_SPACE)
        var url = job.url
        var redirects = 0
        try {
            while (true) {
                if (!DownloadPolicy.isAllowed(url)) return fail(target, ModelsProtocol.FAIL_UNTRUSTED_HOST)
                if (isCancelled()) return fail(target, ModelsProtocol.FAIL_CANCELLED)
                val answer = source.get(url, have)
                answer.use {
                    when (it.status) {
                        200 -> return copy(job, it, target, 0, isCancelled, onProgress)
                        206 -> {
                            // only the bytes after the ones we have, of a file of the catalog's size
                            if (have <= 0 || it.rangeStart != have || it.rangeTotal != job.sizeBytes ||
                                (it.contentLength >= 0 && it.contentLength != job.sizeBytes - have)) return fail(target, ModelsProtocol.FAIL_SIZE)
                            return copy(job, it, target, have, isCancelled, onProgress)
                        }
                        301, 302, 303, 307, 308 -> {
                            val location = it.location ?: return keepPartial(ModelsProtocol.FAIL_NETWORK)
                            url = DownloadPolicy.resolve(url, location) ?: return fail(target, ModelsProtocol.FAIL_UNTRUSTED_HOST)
                            if (++redirects > MAX_REDIRECTS) return keepPartial(ModelsProtocol.FAIL_NETWORK)
                        }
                        416 -> return fail(target, ModelsProtocol.FAIL_SIZE) // our partial file is not a prefix of the file: start over next time
                        else -> return keepPartial(ModelsProtocol.FAIL_NETWORK)
                    }
                }
            }
        } catch (_: IOException) {
            return networkFailure(target, isCancelled)
        }
    }

    /** A broken connection keeps the partial file for a retry, unless the user cancelled meanwhile. */
    private fun networkFailure(target: File, isCancelled: () -> Boolean): DownloadOutcome =
        if (isCancelled()) fail(target, ModelsProtocol.FAIL_CANCELLED) else keepPartial(ModelsProtocol.FAIL_NETWORK)

    private fun fail(target: File, reason: Int): DownloadOutcome {
        target.delete()
        return DownloadOutcome.Failed(reason)
    }

    private fun keepPartial(reason: Int) = DownloadOutcome.Failed(reason)

    /** Copies the body to [target], after the first [skip] bytes of it that are already there (hashed again, not fetched again). */
    private fun copy(job: DownloadJob, answer: HttpAnswer, target: File, skip: Long, isCancelled: () -> Boolean, onProgress: (Long) -> Unit): DownloadOutcome {
        val body = answer.body ?: return keepPartial(ModelsProtocol.FAIL_NETWORK)
        if (skip == 0L && answer.contentLength >= 0 && answer.contentLength != job.sizeBytes) return fail(target, ModelsProtocol.FAIL_SIZE)
        val digest = if (job.sha256 != null) MessageDigest.getInstance("SHA-256") else null
        var received = 0L
        var finished = false
        var keep = false
        try {
            val buffer = ByteArray(1 shl 16)
            if (skip > 0) {
                FileInputStream(target).use { old ->
                    while (received < skip) {
                        val n = old.read(buffer, 0, minOf(buffer.size.toLong(), skip - received).toInt())
                        if (n < 0) return fail(target, ModelsProtocol.FAIL_SIZE)
                        digest?.update(buffer, 0, n)
                        received += n
                    }
                }
            }
            FileOutputStream(target, skip > 0).use { out ->
                while (true) {
                    if (isCancelled()) return fail(target, ModelsProtocol.FAIL_CANCELLED)
                    val n = try { body.read(buffer) } catch (e: IOException) { keep = !isCancelled(); out.flush(); throw e }
                    if (n < 0) break
                    received += n
                    if (received > job.sizeBytes) return fail(target, ModelsProtocol.FAIL_SIZE)
                    digest?.update(buffer, 0, n)
                    out.write(buffer, 0, n)
                    onProgress(received)
                }
                out.flush()
                out.fd.sync()
            }
            // a body that ended early is a broken connection: the partial file is the start of the right file
            if (received < job.sizeBytes) { keep = true; return networkFailure(target, isCancelled) }
            if (digest != null && !digest.digest().joinToString("") { "%02x".format(it) }.equals(job.sha256, ignoreCase = true)) {
                return fail(target, ModelsProtocol.FAIL_DIGEST)
            }
            finished = true
            return DownloadOutcome.Done
        } finally {
            if (!finished && !keep) target.delete()
        }
    }

    companion object {
        const val MAX_REDIRECTS = 5
        /** Room to keep free on top of the file. */
        const val SPARE_BYTES = 64L * 1024 * 1024
    }
}
