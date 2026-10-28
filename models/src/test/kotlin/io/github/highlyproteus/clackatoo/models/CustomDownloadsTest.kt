// SPDX-License-Identifier: GPL-3.0-only

package io.github.highlyproteus.clackatoo.models

import helium314.keyboard.clackatoo.catalog.ModelsProtocol
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The in-process queue the service takes custom downloads from, and how they show up in [DownloadTracker]. */
class CustomDownloadsTest {
    private val url = "https://huggingface.co/owner/name/resolve/${"0".repeat(40)}/m.gguf"
    private fun job(size: Long = 1000, sha: String? = null) = DownloadJob(url, "m.gguf", size, sha)

    @BeforeTest fun clear() {
        CustomDownloads.takePending().forEach { CustomDownloads.finished(it) }
    }

    @Test fun `a queued download has an own id, shows as waiting under its name and role, and is handed over once`() {
        val a = assertNotNull(CustomDownloads.enqueue(job(), ModelsProtocol.CUSTOM_ROLE_SPEECH, "a.gguf"))
        val b = assertNotNull(CustomDownloads.enqueue(job(2000, "f".repeat(64)), ModelsProtocol.CUSTOM_ROLE_GRAMMAR, "b.gguf"))
        assertTrue(a.id.startsWith("custom:") && b.id.startsWith("custom:"))
        assertNotEquals(a.id, b.id)
        assertTrue(CustomDownloads.isCustomId(a.id))
        assertTrue(CustomDownloads.isKnown(a.id))
        assertEquals(Progress(a.id, Progress.Phase.QUEUED, 0, 1000, 0, "a.gguf", ModelsProtocol.CUSTOM_ROLE_SPEECH), DownloadTracker.get(a.id))
        assertEquals(listOf(a, b), CustomDownloads.takePending())
        assertEquals(emptyList(), CustomDownloads.takePending())
        assertEquals(ModelsProtocol.CUSTOM_ROLE_GRAMMAR, b.role)
        assertEquals("f".repeat(64), b.job.sha256)
        assertTrue(CustomDownloads.isKnown(a.id)) // still known (running) until it ends
        CustomDownloads.finished(a)
        assertFalse(CustomDownloads.isKnown(a.id))
    }

    @Test fun `the name is cut to the length the keyboard accepts`() {
        val custom = assertNotNull(CustomDownloads.enqueue(job(), ModelsProtocol.CUSTOM_ROLE_CLEAN_UP, "x".repeat(500)))
        assertEquals(ModelsProtocol.MAX_CUSTOM_NAME_LENGTH, custom.displayName.length)
        assertEquals(custom.displayName, DownloadTracker.get(custom.id)?.title)
    }

    @Test fun `a role that is not one of the three, or a size that is not allowed, queues nothing`() {
        for (role in listOf(0, 4, -1, 99)) assertNull(CustomDownloads.enqueue(job(), role, "m.gguf"), role.toString())
        for (size in listOf(0L, -1L, ModelsProtocol.MAX_CUSTOM_BYTES + 1)) assertNull(CustomDownloads.enqueue(job(size), ModelsProtocol.CUSTOM_ROLE_SPEECH, "m.gguf"), size.toString())
        assertNotNull(CustomDownloads.enqueue(job(ModelsProtocol.MAX_CUSTOM_BYTES), ModelsProtocol.CUSTOM_ROLE_SPEECH, "m.gguf"))
        assertEquals(1, CustomDownloads.takePending().size)
    }

    @Test fun `cancelling sets the flag of that download only, and only for a known id`() {
        val a = assertNotNull(CustomDownloads.enqueue(job(), ModelsProtocol.CUSTOM_ROLE_SPEECH, "a.gguf"))
        val b = assertNotNull(CustomDownloads.enqueue(job(), ModelsProtocol.CUSTOM_ROLE_SPEECH, "b.gguf"))
        CustomDownloads.cancel("custom:999999")
        CustomDownloads.cancel("whisper-tiny")
        assertFalse(a.cancelled.get() || b.cancelled.get())
        CustomDownloads.cancel(a.id)
        assertTrue(a.cancelled.get())
        assertFalse(b.cancelled.get())
        CustomDownloads.cancelAll()
        assertTrue(b.cancelled.get())
    }

    @Test fun `a download that was never started is withdrawn and shows as cancelled`() {
        val a = assertNotNull(CustomDownloads.enqueue(job(), ModelsProtocol.CUSTOM_ROLE_SPEECH, "a.gguf"))
        CustomDownloads.withdraw(a)
        assertEquals(emptyList(), CustomDownloads.takePending())
        assertFalse(CustomDownloads.isKnown(a.id))
        assertTrue(a.cancelled.get())
        assertEquals(Progress.Phase.CANCELLED, DownloadTracker.get(a.id)?.phase)
        assertEquals("a.gguf", DownloadTracker.get(a.id)?.title)
    }

    @Test fun `withdrawing a download the service already took only cancels it`() {
        val a = assertNotNull(CustomDownloads.enqueue(job(), ModelsProtocol.CUSTOM_ROLE_SPEECH, "a.gguf"))
        CustomDownloads.takePending()
        CustomDownloads.withdraw(a)
        assertTrue(a.cancelled.get())
        assertEquals(Progress.Phase.QUEUED, DownloadTracker.get(a.id)?.phase) // the service reports the end itself
    }

    @Test fun `withdrawing everything empties the queue and cancels each`() {
        val a = assertNotNull(CustomDownloads.enqueue(job(), ModelsProtocol.CUSTOM_ROLE_SPEECH, "a.gguf"))
        val b = assertNotNull(CustomDownloads.enqueue(job(), ModelsProtocol.CUSTOM_ROLE_GRAMMAR, "b.gguf"))
        CustomDownloads.withdrawAll()
        assertEquals(emptyList(), CustomDownloads.takePending())
        for (c in listOf(a, b)) assertEquals(Progress.Phase.CANCELLED, DownloadTracker.get(c.id)?.phase)
    }

    @Test fun `only custom ids are custom, and catalog ids are not known to the queue`() {
        assertTrue(CustomDownloads.isCustomId("custom:7"))
        assertFalse(CustomDownloads.isCustomId("whisper-tiny"))
        assertFalse(CustomDownloads.isCustomId(null))
        assertFalse(CustomDownloads.isKnown(null))
        assertFalse(CustomDownloads.isKnown("custom:0"))
    }

    @Test fun `progress of a catalog download has no title and no role`() {
        val p = Progress("whisper-tiny", Progress.Phase.DOWNLOADING, 5, 10)
        assertNull(p.title)
        assertEquals(0, p.role)
        assertEquals(0, p.reason)
    }

    @Test fun `a title and a role stay with the progress when it is updated`() {
        val a = assertNotNull(CustomDownloads.enqueue(job(), ModelsProtocol.CUSTOM_ROLE_CLEAN_UP, "a.gguf"))
        DownloadTracker.update(Progress(a.id, Progress.Phase.FAILED, 10, 10, ModelsProtocol.HANDOVER_WRONG_ROLE, a.displayName, a.role))
        val p = assertNotNull(DownloadTracker.get(a.id))
        assertEquals(ModelsProtocol.HANDOVER_WRONG_ROLE, p.reason)
        assertEquals(ModelsProtocol.CUSTOM_ROLE_CLEAN_UP, p.role)
        assertEquals("a.gguf", p.title)
    }
}
