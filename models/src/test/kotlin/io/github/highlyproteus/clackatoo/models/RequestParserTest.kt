// SPDX-License-Identifier: GPL-3.0-only

package io.github.highlyproteus.clackatoo.models

import helium314.keyboard.clackatoo.catalog.Catalog
import helium314.keyboard.clackatoo.catalog.ModelsProtocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The companion accepts a catalog id and one of two actions from the keyboard, and nothing else. */
class RequestParserTest {
    @Test fun `a catalog id is a download or a cancel`() {
        assertEquals(Request.Download(Catalog.PARAKEET_TDT_V3), RequestParser.check(ModelsProtocol.ACTION_DOWNLOAD, Catalog.PARAKEET_TDT_V3.id))
        assertEquals(Request.Cancel(Catalog.S1_MINI), RequestParser.check(ModelsProtocol.ACTION_CANCEL, Catalog.S1_MINI.id))
    }

    @Test fun `an unknown id is refused`() {
        for (id in listOf("", "nope", "PARAKEET-TDT-0.6B-V3", " whisper-tiny", "whisper-tiny ", "../whisper-tiny", "x".repeat(10_000), null)) {
            assertNull(RequestParser.check(ModelsProtocol.ACTION_DOWNLOAD, id), id?.take(40))
        }
    }

    @Test fun `a URL, a size or a hash in place of an id is refused`() {
        for (id in listOf(Catalog.WHISPER_TINY.url, "https://evil.example/model.bin", Catalog.WHISPER_TINY.sha256, Catalog.WHISPER_TINY.sizeBytes.toString(), Catalog.WHISPER_TINY.fileName)) {
            assertNull(RequestParser.check(ModelsProtocol.ACTION_DOWNLOAD, id), id)
        }
    }

    @Test fun `another action, or none, is refused`() {
        for (action in listOf(null, "", "download", "android.intent.action.VIEW", ModelsProtocol.ACTION_DOWNLOAD.lowercase().replace("DOWNLOAD", "download"))) {
            assertNull(RequestParser.check(action, Catalog.S1_MINI.id), "$action")
        }
    }
}
