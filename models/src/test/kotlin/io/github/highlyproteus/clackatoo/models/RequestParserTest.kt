// SPDX-License-Identifier: GPL-3.0-only

package io.github.highlyproteus.clackatoo.models

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import helium314.keyboard.clackatoo.catalog.Catalog
import helium314.keyboard.clackatoo.catalog.ModelsProtocol
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The companion accepts a catalog id and nothing else from the keyboard (or from any other app that holds the permission). */
@RunWith(RobolectricTestRunner::class)
class RequestParserTest {
    private fun download(vararg extras: Pair<String, Any>) = Intent(ModelsProtocol.ACTION_DOWNLOAD).also { intent ->
        for ((key, value) in extras) when (value) {
            is String -> intent.putExtra(key, value)
            is Long -> intent.putExtra(key, value)
            is Int -> intent.putExtra(key, value)
            else -> error("unsupported")
        }
    }

    @Test fun `a catalog id is a download`() {
        val id = Catalog.PARAKEET_TDT_V3.id
        assertEquals(Request.Download(Catalog.PARAKEET_TDT_V3), RequestParser.parse(download(ModelsProtocol.EXTRA_ID to id)))
    }

    @Test fun `cancel is accepted with the same shape`() {
        val intent = Intent(ModelsProtocol.ACTION_CANCEL).putExtra(ModelsProtocol.EXTRA_ID, Catalog.S1_MINI.id)
        assertEquals(Request.Cancel(Catalog.S1_MINI), RequestParser.parse(intent))
    }

    @Test fun `an unknown id is refused`() {
        for (id in listOf("", "nope", "PARAKEET-TDT-0.6B-V3", " whisper-tiny", "whisper-tiny ", "../whisper-tiny", "x".repeat(10_000))) {
            assertNull(RequestParser.parse(download(ModelsProtocol.EXTRA_ID to id)), id.take(40))
        }
    }

    @Test fun `a URL in place of an id is refused`() {
        val url = Catalog.WHISPER_TINY.url
        assertNull(RequestParser.parse(download(ModelsProtocol.EXTRA_ID to url)))
        assertNull(RequestParser.parse(download(ModelsProtocol.EXTRA_ID to "https://evil.example/model.bin")))
    }

    @Test fun `any extra beside the id is refused, whatever it holds`() {
        val id = Catalog.WHISPER_TINY.id
        assertNull(RequestParser.parse(download(ModelsProtocol.EXTRA_ID to id, "url" to "https://evil.example/model.bin")))
        assertNull(RequestParser.parse(download(ModelsProtocol.EXTRA_ID to id, "size" to 5L)))
        assertNull(RequestParser.parse(download(ModelsProtocol.EXTRA_ID to id, "sha256" to "00".repeat(32))))
        assertNull(RequestParser.parse(download(ModelsProtocol.EXTRA_ID to id, "x" to 1)))
    }

    @Test fun `no id, or an id under another key, is refused`() {
        assertNull(RequestParser.parse(Intent(ModelsProtocol.ACTION_DOWNLOAD)))
        assertNull(RequestParser.parse(download("model" to Catalog.WHISPER_TINY.id)))
        assertNull(RequestParser.parse(download("ID" to Catalog.WHISPER_TINY.id)))
        assertNull(RequestParser.parse(null))
    }

    @Test fun `an id that is not a string is refused`() {
        assertNull(RequestParser.parse(Intent(ModelsProtocol.ACTION_DOWNLOAD).putExtra(ModelsProtocol.EXTRA_ID, 7)))
        assertNull(RequestParser.parse(Intent(ModelsProtocol.ACTION_DOWNLOAD).putExtra(ModelsProtocol.EXTRA_ID, arrayOf(Catalog.S1_MINI.id))))
    }

    @Test fun `another action, or none, is refused`() {
        for (action in listOf(null, "", Intent.ACTION_VIEW, Intent.ACTION_MAIN, "io.github.highlyproteus.clackatoo.models.action.download")) {
            val intent = Intent().putExtra(ModelsProtocol.EXTRA_ID, Catalog.S1_MINI.id)
            if (action != null) intent.action = action
            assertNull(RequestParser.parse(intent), "$action")
        }
    }

    @Test fun `a data URI, a type, clip data, a category or a selector is refused`() {
        val id = ModelsProtocol.EXTRA_ID to Catalog.S1_MINI.id
        assertNull(RequestParser.parse(download(id).setData(Uri.parse(Catalog.S1_MINI.url))))
        assertNull(RequestParser.parse(download(id).setType("application/octet-stream")))
        assertNull(RequestParser.parse(download(id).also { it.clipData = ClipData.newRawUri("x", Uri.parse("content://x/y")) }))
        assertNull(RequestParser.parse(download(id).addCategory(Intent.CATEGORY_DEFAULT)))
        assertNull(RequestParser.parse(download(id).also { it.selector = Intent(Intent.ACTION_VIEW) }))
    }

    @Test fun `the plain rules agree`() {
        val id = Catalog.QWEN3_5_0_8B.id
        assertEquals(Request.Download(Catalog.QWEN3_5_0_8B), RequestParser.check(ModelsProtocol.ACTION_DOWNLOAD, false, false, false, setOf("id"), id))
        assertNull(RequestParser.check(ModelsProtocol.ACTION_DOWNLOAD, false, false, false, setOf("id", "url"), id))
        assertNull(RequestParser.check(ModelsProtocol.ACTION_DOWNLOAD, false, false, false, emptySet(), id))
        assertNull(RequestParser.check(ModelsProtocol.ACTION_DOWNLOAD, true, false, false, setOf("id"), id))
        assertNull(RequestParser.check(ModelsProtocol.ACTION_DOWNLOAD, false, false, false, setOf("id"), null))
    }

    @Test fun `an extras bundle that cannot be read does not crash the app`() {
        val intent = Intent(ModelsProtocol.ACTION_DOWNLOAD)
        intent.putExtras(Bundle().also { it.putString(ModelsProtocol.EXTRA_ID, Catalog.S1_MINI.id); it.putString("a", "b") })
        assertNull(RequestParser.parse(intent))
    }
}
