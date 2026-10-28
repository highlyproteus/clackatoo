// SPDX-License-Identifier: GPL-3.0-only

package io.github.highlyproteus.clackatoo.models

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.text.format.Formatter
import android.annotation.TargetApi
import helium314.keyboard.clackatoo.catalog.CatalogModel
import helium314.keyboard.clackatoo.catalog.ModelsProtocol
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Downloads catalog models one after another as a foreground service (a notification with progress and a Cancel action),
 * then hands each file to the keyboard and deletes its own copy. Started by the keyboard with one Intent that holds a
 * catalog id ([RequestParser] refuses everything else); the service is guarded by the signature permission.
 */
class DownloadService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    private val cancelFlags = ConcurrentHashMap<String, AtomicBoolean>()
    private var link: KeyboardLink? = null
    private val active = AtomicInteger()
    @Volatile private var lastStartId = 0
    @Volatile private var notifiedResult = false
    private val downloader = Downloader()
    private var lastNotified = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // a service started with startForegroundService() must go to the foreground at once, whatever the request says
        lastStartId = startId
        showForeground(getString(R.string.notification_title, "…"), 0, 0)
        when (val request = RequestParser.parse(intent)) {
            is Request.Download -> enqueue(request.model)
            is Request.Cancel -> cancel(request.model)
            null -> Unit // refused: nothing is started and nothing is said back
        }
        if (active.get() == 0) finishIfIdle()
        return START_NOT_STICKY
    }

    private fun enqueue(model: CatalogModel) {
        if (cancelFlags.containsKey(model.id)) return // already queued or running
        val flag = AtomicBoolean(false)
        cancelFlags[model.id] = flag
        active.incrementAndGet()
        DownloadTracker.update(Progress(model.id, Progress.Phase.QUEUED, 0, model.sizeBytes))
        worker.execute {
            try {
                run(model, flag)
            } finally {
                cancelFlags.remove(model.id)
                active.decrementAndGet()
                finishIfIdle()
            }
        }
    }

    private fun cancel(model: CatalogModel) {
        cancelFlags[model.id]?.set(true)
    }

    private fun run(model: CatalogModel, cancelled: AtomicBoolean) {
        val keyboard = link ?: KeyboardLink(this, BuildConfig.KEYBOARD_APPLICATION_ID).also { link = it }
        val total = model.sizeBytes
        if (cancelled.get()) return report(model, Progress.Phase.CANCELLED, 0, ModelsProtocol.FAIL_CANCELLED, keyboard)
        keyboard.connect() // best effort: the download can run without progress reports
        val dir = File(filesDir, "downloads").apply { mkdirs() }
        dir.listFiles()?.forEach { if (it.name != model.fileName + ".part") it.delete() } // leftovers of a crash
        val part = File(dir, model.fileName + ".part")
        var lastReport = 0L
        DownloadTracker.update(Progress(model.id, Progress.Phase.DOWNLOADING, 0, total))
        keyboard.progress(model.id, 0, total)
        // a broken connection keeps the partial file and is retried (resuming with a Range request) a few times
        var attempt = 0
        var outcome: DownloadOutcome
        while (true) {
            outcome = downloader.download(model, part, cancelled::get) { bytes ->
                val now = System.nanoTime()
                if (now - lastReport > 250_000_000L) {
                    lastReport = now
                    DownloadTracker.update(Progress(model.id, Progress.Phase.DOWNLOADING, bytes, total))
                    keyboard.progress(model.id, bytes, total)
                    notifyProgress(model, bytes)
                }
            }
            val retry = outcome is DownloadOutcome.Failed && outcome.reason == ModelsProtocol.FAIL_NETWORK && !cancelled.get() && ++attempt < MAX_ATTEMPTS
            if (!retry) break
            for (second in 0 until attempt * 3) { // wait a little longer each time, and stop waiting when cancelled
                if (cancelled.get()) break
                Thread.sleep(1_000)
            }
        }
        when (outcome) {
            is DownloadOutcome.Failed -> {
                report(model, if (outcome.reason == ModelsProtocol.FAIL_CANCELLED) Progress.Phase.CANCELLED else Progress.Phase.FAILED, 0, outcome.reason, keyboard)
            }
            DownloadOutcome.Done -> {
                val file = File(dir, model.fileName)
                if (!part.renameTo(file)) {
                    part.delete()
                    return report(model, Progress.Phase.FAILED, 0, ModelsProtocol.FAIL_OTHER, keyboard)
                }
                DownloadTracker.update(Progress(model.id, Progress.Phase.HANDING_OVER, total, total))
                try {
                    val result = keyboard.handover(model.id, file)
                    when (result) {
                        ModelsProtocol.HANDOVER_OK -> {
                            DownloadTracker.update(Progress(model.id, Progress.Phase.DONE, total, total))
                            notifyDone(model, true)
                        }
                        else -> {
                            DownloadTracker.update(Progress(model.id, Progress.Phase.FAILED, total, total, result ?: -1))
                            notifyDone(model, false)
                        }
                    }
                } finally {
                    file.delete() // the keyboard has its own checked copy now (or refused it); this one is not kept
                }
            }
        }
    }

    private fun report(model: CatalogModel, phase: Progress.Phase, bytes: Long, reason: Int, keyboard: KeyboardLink) {
        DownloadTracker.update(Progress(model.id, phase, bytes, model.sizeBytes, reason))
        keyboard.failed(model.id, reason)
        if (phase == Progress.Phase.FAILED) notifyDone(model, false)
    }

    private fun finishIfIdle() {
        if (active.get() != 0) return
        link?.close()
        link = null
        // keep the last notification (ready / failed) when there was a result; remove the placeholder otherwise
        stopForeground(!notifiedResult)
        // only ends the service if no newer request has arrived meanwhile
        stopSelf(lastStartId)
    }

    @TargetApi(35)
    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android limits a data-sync foreground service to 6 hours a day: stop what is running and end the service
        cancelFlags.values.forEach { it.set(true) }
        stopSelf()
    }

    override fun onDestroy() {
        cancelFlags.values.forEach { it.set(true) }
        worker.shutdown()
        link?.close()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- notification

    private fun manager() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            manager().createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun builder(): Notification.Builder {
        ensureChannel()
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return b.setSmallIcon(R.drawable.ic_stat_download).setContentIntent(open).setOnlyAlertOnce(true)
    }

    private fun cancelAction(model: CatalogModel): Notification.Action {
        val intent = Intent(this, DownloadService::class.java).setAction(ModelsProtocol.ACTION_CANCEL).putExtra(ModelsProtocol.EXTRA_ID, model.id)
        val pending = PendingIntent.getService(this, 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Action.Builder(null, getString(R.string.cancel), pending).build()
    }

    private fun showForeground(title: String, bytes: Long, total: Long) {
        val n = builder().setContentTitle(title).setOngoing(true).apply {
            if (total > 0) setProgress(100, (bytes * 100 / total).toInt(), false) else setProgress(0, 0, true)
        }.build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(NOTIFICATION_ID, n)
    }

    private fun notifyProgress(model: CatalogModel, bytes: Long) {
        val now = System.nanoTime()
        if (now - lastNotified < 500_000_000L) return
        lastNotified = now
        val n = builder().setContentTitle(getString(R.string.notification_title, model.displayName))
            .setContentText(getString(R.string.downloading, Formatter.formatShortFileSize(this, bytes), Formatter.formatShortFileSize(this, model.sizeBytes)))
            .setOngoing(true).setProgress(100, (bytes * 100 / model.sizeBytes).toInt(), false)
            .addAction(cancelAction(model)).build()
        manager().notify(NOTIFICATION_ID, n)
    }

    private fun notifyDone(model: CatalogModel, ok: Boolean) {
        notifiedResult = true
        val text = getString(if (ok) R.string.notification_done else R.string.notification_failed, model.displayName)
        manager().notify(NOTIFICATION_ID, builder().setContentTitle(text).setOngoing(false).setAutoCancel(true).build())
    }

    private companion object {
        const val CHANNEL = "downloads"
        const val MAX_ATTEMPTS = 6
        const val NOTIFICATION_ID = 1
    }
}
