// SPDX-License-Identifier: GPL-3.0-only

package io.github.highlyproteus.clackatoo.models

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.text.format.Formatter
import android.annotation.TargetApi
import helium314.keyboard.clackatoo.catalog.CatalogModel
import helium314.keyboard.clackatoo.catalog.ModelsProtocol
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Downloads catalog models one after another as a foreground service (a notification with progress and a Cancel action),
 * then hands each file to the keyboard and deletes its own copy. The keyboard asks over a bound Binder ([DownloadBinder]):
 * an action and one catalog id, from a caller whose package and signature are checked on every call ([RequestParser]
 * refuses everything else, [RequestLimiter] limits how often). The service is also guarded by the signature permission.
 * Start intents carry nothing this service reads: they only make it a foreground service, which the download needs.
 */
class DownloadService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    private var link: KeyboardLink? = null
    private val active = AtomicInteger()
    @Volatile private var lastStartId = 0
    @Volatile private var notifiedResult = false
    private val downloader = Downloader()
    private var lastNotified = 0L

    private val binder by lazy {
        DownloadBinder(
            isTrustedCaller = { isKeyboard(Binder.getCallingUid()) },
            onRequest = { request -> handle(request) },
        )
    }

    override fun onBind(intent: Intent?): IBinder = binder

    /** The caller is the Clackatoo keyboard of this build type, signed like this app. */
    private fun isKeyboard(uid: Int): Boolean {
        val pm = packageManager
        val keyboard = BuildConfig.KEYBOARD_APPLICATION_ID
        return pm.getPackagesForUid(uid).orEmpty().contains(keyboard) &&
            pm.checkSignatures(uid, applicationInfo.uid) == PackageManager.SIGNATURE_MATCH
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // a service started with startForegroundService() must go to the foreground at once, whoever started it; what the
        // Intent says is never read (requests come over the Binder), so a stranger can only make it flash a notification
        lastStartId = startId
        showForeground(getString(R.string.notification_title, "…"), 0, 0)
        if (active.get() == 0) finishIfIdle()
        return START_NOT_STICKY
    }

    /** Runs a request that [DownloadBinder] has checked for caller and shape. Returns a `REQUEST_*` code. */
    private fun handle(request: Request): Int = when (request) {
        is Request.Cancel -> { cancelFlags[request.model.id]?.set(true); ModelsProtocol.REQUEST_OK }
        is Request.Download -> when (limiter.tryStart(request.model.id)) {
            RequestLimiter.Verdict.ALREADY -> ModelsProtocol.REQUEST_OK
            RequestLimiter.Verdict.LATER -> ModelsProtocol.REQUEST_LATER
            RequestLimiter.Verdict.START -> {
                // Started (not yet foreground) so that it survives the keyboard unbinding; the keyboard, which is on
                // screen and so may, then starts it in the foreground (this app may not go to the foreground by itself)
                enqueue(request.model)
                startService(Intent(this, DownloadService::class.java))
                ModelsProtocol.REQUEST_OK
            }
        }
    }

    private fun enqueue(model: CatalogModel) {
        val flag = AtomicBoolean(false)
        cancelFlags[model.id] = flag
        active.incrementAndGet()
        DownloadTracker.update(Progress(model.id, Progress.Phase.QUEUED, 0, model.sizeBytes))
        worker.execute {
            try {
                run(model, flag)
            } finally {
                cancelFlags.remove(model.id)
                limiter.released(model.id) // whatever happened, it is no longer running
                active.decrementAndGet()
                finishIfIdle()
            }
        }
    }

    private fun run(model: CatalogModel, cancelled: AtomicBoolean) {
        val keyboard = link ?: KeyboardLink(this, BuildConfig.KEYBOARD_APPLICATION_ID).also { link = it }
        val total = model.sizeBytes
        if (cancelled.get()) {
            limiter.failed(model.id)
            return report(model, Progress.Phase.CANCELLED, 0, ModelsProtocol.FAIL_CANCELLED, keyboard)
        }
        keyboard.connect() // best effort: the download can run without progress reports
        val dir = File(filesDir, "downloads").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // leftovers of an earlier run: a download resumes only within one run
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
                part.delete() // no partial file outlives a run that did not succeed
                limiter.failed(model.id)
                report(model, if (outcome.reason == ModelsProtocol.FAIL_CANCELLED) Progress.Phase.CANCELLED else Progress.Phase.FAILED, 0, outcome.reason, keyboard)
            }
            DownloadOutcome.Done -> {
                val file = File(dir, model.fileName)
                if (!part.renameTo(file)) {
                    part.delete()
                    limiter.failed(model.id)
                    return report(model, Progress.Phase.FAILED, 0, ModelsProtocol.FAIL_OTHER, keyboard)
                }
                DownloadTracker.update(Progress(model.id, Progress.Phase.HANDING_OVER, total, total))
                try {
                    val result = keyboard.handover(model.id, file)
                    when (result) {
                        ModelsProtocol.HANDOVER_OK -> {
                            limiter.handedOver(model.id)
                            DownloadTracker.update(Progress(model.id, Progress.Phase.DONE, total, total))
                            notifyDone(model, true)
                        }
                        else -> {
                            limiter.failed(model.id)
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
        val intent = Intent(this, CancelReceiver::class.java).putExtra(ModelsProtocol.EXTRA_ID, model.id)
        val pending = PendingIntent.getBroadcast(this, 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
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

    companion object {
        /** Cancel flags of the queued and running downloads, by id; process-wide so the notification and the screen can reach them. */
        internal val cancelFlags = java.util.concurrent.ConcurrentHashMap<String, AtomicBoolean>()
        internal val limiter = RequestLimiter()

        /** Cancels the download of [id], from this app's own screen or notification (never from outside: those are not exported). */
        internal fun cancel(id: String) {
            cancelFlags[id]?.set(true)
        }

        private const val CHANNEL = "downloads"
        private const val MAX_ATTEMPTS = 6
        private const val NOTIFICATION_ID = 1
    }
}
