// SPDX-License-Identifier: GPL-3.0-only

package io.github.highlyproteus.clackatoo.models

import android.content.Intent
import android.os.Binder
import android.os.Parcel
import android.os.Looper
import helium314.keyboard.clackatoo.catalog.Catalog
import helium314.keyboard.clackatoo.catalog.ModelsProtocol
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.LooperMode
import java.io.File
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Force the worker/main-thread ordering without networking or widening the Binder entry point. */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class DownloadServiceTest {
    private lateinit var service: DownloadService
    private val model = Catalog.WHISPER_TINY
    private lateinit var worker: ExecutorService
    private lateinit var active: AtomicInteger
    private val releaseWorker = CountDownLatch(1)

    @Before fun setUp() {
        DownloadService.cancelFlags.clear()
        service = Robolectric.buildService(DownloadService::class.java).create().get()
        DownloadService::class.java.getDeclaredField("limiterInstance").apply { isAccessible = true }
            .set(null, RequestLimiter())
        worker = field("worker") as ExecutorService
        active = field("active") as AtomicInteger
        val waiting = CountDownLatch(1)
        worker.submit { waiting.countDown(); releaseWorker.await() }
        assertTrue(waiting.await(5, TimeUnit.SECONDS))
    }

    @After fun tearDown() {
        service.onDestroy()
        worker.shutdownNow()
        releaseWorker.countDown()
        assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS))
        DownloadService.cancelFlags.clear()
        DownloadService::class.java.getDeclaredField("limiterInstance").apply { isAccessible = true }.set(null, null)
    }

    @Test fun `admission never self starts before the visible keyboard can promote the service`() {
        assertEquals(ModelsProtocol.REQUEST_OK, request())
        assertEquals(1, active.get())
        assertNull(shadowOf(org.robolectric.RuntimeEnvironment.getApplication()).nextStartedService)
    }

    @Test fun `worker idle check cannot remove a newly started foreground notification`() {
        // An old job has just finished. Its idle decision must be deferred to main and rechecked there.
        active.set(1)
        service.onStartCommand(Intent(), 0, 1)
        shadowOf(Looper.getMainLooper()).idle()
        active.set(0)
        val finisher = Thread { invoke("finishIfIdle") }
        finisher.start()
        finisher.join(5_000)
        assertFalse(finisher.isAlive)
        assertFalse(shadowOf(service).isForegroundStopped)

        // Binder admission and onStartCommand for the next job beat the queued idle callback.
        assertEquals(ModelsProtocol.REQUEST_OK, request())
        service.onStartCommand(Intent(), 0, 2)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, active.get())
        assertFalse(shadowOf(service).isForegroundStopped)
        assertFalse(shadowOf(service).isStoppedBySelf)
    }

    @Test fun `executor rejection refunds admission and leaves no queued download`() {
        worker.shutdown()
        assertEquals(ModelsProtocol.REQUEST_REFUSED, request())
        assertEquals(0, active.get())
        assertNull(DownloadService.cancelFlags[model.id])
        assertEquals(Progress.Phase.CANCELLED, DownloadTracker.get(model.id)?.phase)
        assertEquals(RequestLimiter.Verdict.START, DownloadService.limiterFor(service).tryStart(model.id))
    }

    @Test fun `destroyed service refuses stale Binder requests without admission`() {
        service.onDestroy()
        assertEquals(ModelsProtocol.REQUEST_REFUSED, request())
        assertEquals(0, active.get())
        assertNull(DownloadService.cancelFlags[model.id])
        assertEquals(RequestLimiter.Verdict.START, DownloadService.limiterFor(service).tryStart(model.id))
    }

    @Test fun `cancel during retry sleep does not issue another network request`() {
        val requested = CountDownLatch(1)
        val requests = AtomicInteger()
        val source = HttpSource { _, _ ->
            requests.incrementAndGet()
            requested.countDown()
            HttpAnswer(503, null, 0, null)
        }
        DownloadService::class.java.getDeclaredField("downloader").apply { isAccessible = true }
            .set(service, Downloader(source) { Long.MAX_VALUE })
        val cancelled = AtomicBoolean()
        val result = AtomicReference<DownloadOutcome>()
        val part = File(service.cacheDir, "retry-test.part")
        val thread = Thread {
            val fetch = DownloadService::class.java.getDeclaredMethod(
                "fetch", DownloadJob::class.java, File::class.java, AtomicBoolean::class.java, Function1::class.java,
            ).apply { isAccessible = true }
            result.set(fetch.invoke(service, model.toJob(), part, cancelled, { _: Long -> }) as DownloadOutcome)
        }
        try {
            thread.start()
            assertTrue(requested.await(5, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (thread.state != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) Thread.yield()
            assertEquals(Thread.State.TIMED_WAITING, thread.state, "fetch reached its retry sleep")
            cancelled.set(true)
            thread.join(2_000)
            assertFalse(thread.isAlive, "cancellation leaves retry sleep within its one-second polling interval")
            assertEquals(DownloadOutcome.Failed(ModelsProtocol.FAIL_CANCELLED), result.get())
            assertEquals(1, requests.get())
            assertFalse(part.exists())
        } finally {
            cancelled.set(true)
            thread.interrupt()
            thread.join(5_000)
            part.delete()
        }
    }

    @Test fun `cancel during handover keeps service alive until the keyboard answers`() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val small = model.copy(sizeBytes = bytes.size.toLong(),
            sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
        val source = HttpSource { _, _ -> HttpAnswer(200, null, bytes.size.toLong(), ByteArrayInputStream(bytes)) }
        DownloadService::class.java.getDeclaredField("downloader").apply { isAccessible = true }
            .set(service, Downloader(source) { Long.MAX_VALUE })
        val entered = CountDownLatch(1)
        val answer = CountDownLatch(1)
        val keyboard = KeyboardLink(service, BuildConfig.KEYBOARD_APPLICATION_ID)
        val remote = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code == ModelsProtocol.TX_HANDOVER) {
                    entered.countDown()
                    assertTrue(answer.await(5, TimeUnit.SECONDS))
                    reply!!.writeNoException()
                    reply.writeInt(ModelsProtocol.HANDOVER_OK)
                }
                return true
            }
        }
        KeyboardLink::class.java.getDeclaredField("binder").apply { isAccessible = true }.set(keyboard, remote)
        DownloadService::class.java.getDeclaredField("link").apply { isAccessible = true }.set(service, keyboard)
        try {
            assertEquals(ModelsProtocol.REQUEST_OK, DownloadService::class.java.getDeclaredMethod("handle", Request::class.java).run {
                isAccessible = true
                invoke(service, Request.Download(small))
            })
            service.onStartCommand(Intent(), 0, 1)
            releaseWorker.countDown()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertEquals(Progress.Phase.HANDING_OVER, DownloadTracker.get(model.id)?.phase)
            DownloadService.cancel(model.id)
            invoke("finishIfIdle")
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, active.get())
            assertFalse(shadowOf(service).isForegroundStopped)
            answer.countDown()
            worker.submit { }.get(5, TimeUnit.SECONDS)
            // Once the keyboard accepted the file, a late cancel cannot undo its installation.
            assertEquals(Progress.Phase.DONE, DownloadTracker.get(model.id)?.phase)
            assertEquals(0, active.get())
            assertNull(DownloadService.cancelFlags[model.id])
        } finally {
            answer.countDown()
        }
    }

    private fun request(): Int = DownloadService::class.java.getDeclaredMethod("handle", Request::class.java).run {
        isAccessible = true
        invoke(service, Request.Download(model)) as Int
    }

    private fun field(name: String): Any = DownloadService::class.java.getDeclaredField(name).run {
        isAccessible = true
        get(service)
    }

    private fun invoke(name: String) = DownloadService::class.java.getDeclaredMethod(name).run {
        isAccessible = true
        invoke(service)
    }
}
