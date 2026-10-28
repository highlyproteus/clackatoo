// SPDX-License-Identifier: GPL-3.0-only

package io.github.highlyproteus.clackatoo.models

import helium314.keyboard.clackatoo.catalog.Catalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadPolicyTest {
    @Test fun `every catalog URL is allowed`() {
        for (m in Catalog.all) assertTrue(DownloadPolicy.isAllowed(m.url), m.url)
    }

    @Test fun `the hosts Hugging Face redirects to are allowed`() {
        for (host in listOf("us.aws.cdn.hf.co", "cdn-lfs.hf.co", "cdn-lfs-us-1.hf.co", "cas-bridge.xethub.hf.co", "huggingface.co")) {
            assertTrue(DownloadPolicy.isAllowed("https://$host/some/path?X-Amz=1&Policy=abc"), host)
        }
    }

    @Test fun `other hosts, look-alikes, other schemes, ports and user info are not`() {
        for (url in listOf(
            "http://huggingface.co/a/b/resolve/${"0".repeat(40)}/f.bin",
            "https://evil.example/f.bin",
            "https://huggingface.co.evil.example/f.bin",
            "https://hf.co.evil.example/f.bin",
            "https://evilhf.co/f.bin",
            "https://hf.co/f.bin",
            "https://nothuggingface.co/f.bin",
            "https://huggingface.co@evil.example/f.bin",
            "https://user:pw@huggingface.co/f.bin",
            "https://huggingface.co:8443/f.bin",
            "https://cdn.hf.co:80/f.bin",
            "ftp://huggingface.co/f.bin",
            "file:///data/data/x",
            "//huggingface.co/f.bin",
            "huggingface.co/f.bin",
            "",
            "https://",
            "https://huggingface.co\\@evil.example/",
        )) assertFalse(DownloadPolicy.isAllowed(url), url)
    }

    @Test fun `a relative redirect resolves against the current address`() {
        assertEquals("https://huggingface.co/x/y", DownloadPolicy.resolve("https://huggingface.co/a/b", "/x/y"))
        assertEquals("https://evil.example/z", DownloadPolicy.resolve("https://huggingface.co/a/b", "https://evil.example/z"))
        assertNull(DownloadPolicy.resolve("https://huggingface.co/a/b", "https://exa mple/"))
    }
}
