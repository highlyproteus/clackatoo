// SPDX-License-Identifier: GPL-3.0-only

package io.github.highlyproteus.clackatoo.models

import java.net.URI

/**
 * Which addresses a download may touch. The first request is the catalog's pinned `https://huggingface.co/<repo>/resolve/
 * <commit>/<file>`; Hugging Face answers with a redirect to its CDN, which has been seen at `us.aws.cdn.hf.co` (xet
 * storage; earlier at `cdn-lfs*.hf.co` and `cas-bridge.xethub.hf.co`). Only https, only `huggingface.co` and the
 * subdomains of `hf.co`, the default port, no user info: a redirect anywhere else stops the download.
 */
internal object DownloadPolicy {
    fun isAllowed(url: String): Boolean {
        val uri = try { URI(url) } catch (_: Exception) { return false }
        if (uri.scheme != "https" || uri.rawUserInfo != null || (uri.port != -1 && uri.port != 443)) return false
        val host = uri.host?.lowercase() ?: return false
        return host == "huggingface.co" || (host.endsWith(".hf.co") && host.length > ".hf.co".length)
    }

    /** Resolves a redirect `Location` (possibly relative) against the address it came from; null when it is not a valid address. */
    fun resolve(base: String, location: String): String? = try {
        URI(base).resolve(location).toString()
    } catch (_: Exception) {
        null
    }
}
