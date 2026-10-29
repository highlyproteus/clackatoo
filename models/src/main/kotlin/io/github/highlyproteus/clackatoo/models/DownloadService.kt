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
import android.os.Handler
import android.os.Looper
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
 *
 * The only other work it does is the custom downloads the user chose in this app's own Hugging Face screen: those come from
 * the process-internal [CustomDownloads] queue (filled by [BrowseActivity] only, taken out when the service starts), never
 * from an Intent or a Binder call, and are not subject to [RequestLimiter].
 */
class DownloadService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    private val limiter get() = limiterFor(this)
    private var link: KeyboardLink? = null
    private val active = AtomicInteger()
    private val mainHandler = Handler(Looper.getMainLooper())
    // Admission, start ids and teardown share this service's monitor; transfers and handovers do not hold it.
    private var lastStartId = 0
    private var stopping = false
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

    @Synchronized
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // a service started with startForegroundService() must go to the foreground at once, whoever started it; what the
        // Intent says is never read (requests come over the Binder), so a stranger can only make it flash a notification
        lastStartId = startId
        try {
            showForeground(getString(R.string.notification_title, "…"), 0, 0)
        } catch (e: IllegalStateException) { // ForegroundServiceStartNotAllowedException: the phone does not let us be foreground now
            giveUp()
            return START_NOT_STICKY
        } catch (e: SecurityException) {
            giveUp()
            return START_NOT_STICKY
        }
        // the custom downloads the user chose in this app's own screen (a queue in this process, not the Intent) start now
        CustomDownloads.takePending().forEach { enqueueCustom(it) }
        if (active.get() == 0) finishIfIdle()
        return START_NOT_STICKY
    }

    /** The service cannot go to the foreground: no download may run (and none was handed anything); end quietly instead of crashing. */
    private fun giveUp() {
        stopping = true
        cancelFlags.values.forEach { it.set(true) }
        CustomDownloads.withdrawAll()
        stopSelf()
    }

    /** Runs a request that [DownloadBinder] has checked for caller and shape. Returns a `REQUEST_*` code. */
    @Synchronized
    private fun handle(request: Request): Int {
        if (stopping) return ModelsProtocol.REQUEST_REFUSED
        return when (request) {
            is Request.Cancel -> { cancelFlags[request.model.id]?.set(true); ModelsProtocol.REQUEST_OK }
            is Request.Download -> when (limiter.tryStart(request.model.id)) {
                RequestLimiter.Verdict.ALREADY -> ModelsProtocol.REQUEST_OK
                RequestLimiter.Verdict.LATER -> ModelsProtocol.REQUEST_LATER
                RequestLimiter.Verdict.START -> {
                    // The keyboard holds its binding until its foreground start is accepted. Do not self-start:
                    // our background start can be denied foreground promotion and tear down the keyboard's start.
                    if (enqueue(request.model)) ModelsProtocol.REQUEST_OK else ModelsProtocol.REQUEST_REFUSED
                }
            }
        }
    }

    /** Executor rejection must not leave a running limiter entry or a queued UI state. */
    private fun refund(model: CatalogModel) {
        limiter.refund(model.id)
        DownloadTracker.update(Progress(model.id, Progress.Phase.CANCELLED, 0, model.sizeBytes, ModelsProtocol.FAIL_CANCELLED))
    }

    private fun enqueue(model: CatalogModel): Boolean {
        val flag = AtomicBoolean(false)
        cancelFlags[model.id] = flag
        active.incrementAndGet()
        DownloadTracker.update(Progress(model.id, Progress.Phase.QUEUED, 0, model.sizeBytes))
        try {
            worker.submit {
                try {
                    run(model, flag)
                } finally {
                    synchronized(this) {
                        cancelFlags.remove(model.id, flag)
                        limiter.released(model.id) // whatever happened, it is no longer running
                        active.decrementAndGet()
                    }
                    finishIfIdle()
                }
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            cancelFlags.remove(model.id, flag)
            active.decrementAndGet()
            refund(model)
            return false
        }
        return true
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
        DownloadTracker.update(Progress(model.id, Progress.Phase.DOWNLOADING, 0, total))
        keyboard.progress(model.id, 0, total)
        val outcome = fetch(model.toJob(), part, cancelled) { bytes ->
            DownloadTracker.update(Progress(model.id, Progress.Phase.DOWNLOADING, bytes, total))
            keyboard.progress(model.id, bytes, total)
            notifyProgress(model.id, model.displayName, bytes, total)
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
                var answered = false
                try {
                    val result = keyboard.handover(model.id, file)
                    answered = result != null
                    when (result) {
                        ModelsProtocol.HANDOVER_OK -> {
                            limiter.handedOver(model.id)
                            DownloadTracker.update(Progress(model.id, Progress.Phase.DONE, total, total))
                            notifyDone(model.displayName, true)
                        }
                        else -> {
                            limiter.failed(model.id)
                            DownloadTracker.update(Progress(model.id, Progress.Phase.FAILED, total, total, result ?: -1))
                            notifyDone(model.displayName, false)
                        }
                    }
                } finally {
                    // the keyboard has its own checked copy now (or refused it); this one is not kept, unless the keyboard gave no answer
                    if (answered) file.delete()
                }
            }
        }
    }

    /**
     * Downloads [job] to [part]; a broken connection keeps the partial file and is retried (resuming with a Range request)
     * a few times. [onBytes] gets the byte count so far, at most four times a second.
     */
    private fun fetch(job: DownloadJob, part: File, cancelled: AtomicBoolean, onBytes: (Long) -> Unit): DownloadOutcome {
        var lastReport = 0L
        var attempt = 0
        var outcome: DownloadOutcome
        while (true) {
            outcome = downloader.download(job, part, cancelled::get) { bytes ->
                val now = System.nanoTime()
                if (now - lastReport > 250_000_000L) {
                    lastReport = now
                    onBytes(bytes)
                }
            }
            val retry = outcome is DownloadOutcome.Failed && outcome.reason == ModelsProtocol.FAIL_NETWORK && !cancelled.get() && ++attempt < MAX_ATTEMPTS
            if (!retry) return outcome
            for (second in 0 until attempt * 3) { // wait a little longer each time, and stop waiting when cancelled
                if (cancelled.get()) break
                Thread.sleep(1_000)
            }
        }
    }

    private fun enqueueCustom(custom: CustomJob) {
        active.incrementAndGet()
        try {
            worker.submit {
                try {
                    runCustom(custom)
                } finally {
                    CustomDownloads.finished(custom)
                    active.decrementAndGet()
                    finishIfIdle()
                }
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) { // the service is already being destroyed
            active.decrementAndGet()
            CustomDownloads.withdraw(custom)
        }
    }

    /**
     * A custom download (the user's own choice in the Hugging Face browser): to a generated file name in this app's private
     * directory, then to the keyboard with the user's role, then deleted here. Nothing but the file's base name goes to the
     * keyboard, and nothing the keyboard says is used except its reply code.
     */
    private fun runCustom(custom: CustomJob) {
        val total = custom.job.sizeBytes
        fun track(phase: Progress.Phase, bytes: Long, reason: Int = 0) =
            DownloadTracker.update(Progress(custom.id, phase, bytes, total, reason, custom.displayName, custom.role))
        if (custom.cancelled.get()) return track(Progress.Phase.CANCELLED, 0, ModelsProtocol.FAIL_CANCELLED)
        val dir = File(filesDir, "downloads").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // leftovers of an earlier run (downloads run one after another)
        val base = custom.id.replace(':', '-')
        val part = File(dir, "$base.part")
        track(Progress.Phase.DOWNLOADING, 0)
        val outcome = fetch(custom.job, part, custom.cancelled) { bytes ->
            track(Progress.Phase.DOWNLOADING, bytes)
            notifyProgress(custom.id, custom.displayName, bytes, total)
        }
        when (outcome) {
            is DownloadOutcome.Failed -> {
                part.delete() // no partial file outlives a run that did not succeed
                if (outcome.reason == ModelsProtocol.FAIL_CANCELLED) return track(Progress.Phase.CANCELLED, 0, outcome.reason)
                track(Progress.Phase.FAILED, 0, outcome.reason)
                notifyDone(custom.displayName, false)
            }
            DownloadOutcome.Done -> {
                val file = File(dir, "$base.model")
                if (!part.renameTo(file)) {
                    part.delete()
                    track(Progress.Phase.FAILED, 0, ModelsProtocol.FAIL_OTHER)
                    return notifyDone(custom.displayName, false)
                }
                track(Progress.Phase.HANDING_OVER, total)
                var answered = false
                try {
                    val keyboard = link ?: KeyboardLink(this, BuildConfig.KEYBOARD_APPLICATION_ID).also { link = it }
                    val result = keyboard.handoverCustom(custom.displayName, custom.role, file)
                    answered = result != null
                    if (result == ModelsProtocol.HANDOVER_OK) {
                        track(Progress.Phase.DONE, total)
                        notifyDone(custom.displayName, true)
                    } else {
                        track(Progress.Phase.FAILED, total, result ?: -1)
                        notifyDone(custom.displayName, false)
                    }
                } finally {
                    // the keyboard has its own copy now (or refused it); this one is not kept, unless the keyboard gave no answer
                    // (an older keyboard): then it stays until the next download clears the folder
                    if (answered) file.delete()
                }
            }
        }
    }

    private fun report(model: CatalogModel, phase: Progress.Phase, bytes: Long, reason: Int, keyboard: KeyboardLink) {
        DownloadTracker.update(Progress(model.id, phase, bytes, model.sizeBytes, reason))
        keyboard.failed(model.id, reason)
        if (phase == Progress.Phase.FAILED) notifyDone(model.displayName, false)
    }

    private fun finishIfIdle() {
        // The worker only schedules an idle check. Main-thread start callbacks cannot race foreground teardown;
        // Binder admission takes the same monitor, and the count is rechecked after either can accept more work.
        mainHandler.post {
            synchronized(this) {
                if (active.get() != 0) return@synchronized
                // Android may already know about a start whose onStartCommand has not run yet.
                if (!stopSelfResult(lastStartId)) return@synchronized
                stopping = true
                link?.close()
                link = null
                // keep the last notification (ready / failed); otherwise remove the placeholder
                stopForeground(!notifiedResult)
            }
        }
    }

    @TargetApi(35)
    @Synchronized
    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android limits a data-sync foreground service to 6 hours a day: stop what is running and end the service
        stopping = true
        cancelFlags.values.forEach { it.set(true) }
        CustomDownloads.cancelAll()
        stopSelf()
    }

    @Synchronized
    override fun onDestroy() {
        stopping = true
        mainHandler.removeCallbacksAndMessages(null)
        cancelFlags.values.forEach { it.set(true) }
        CustomDownloads.cancelAll()
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

    private fun cancelAction(id: String): Notification.Action {
        val intent = Intent(this, CancelReceiver::class.java).putExtra(ModelsProtocol.EXTRA_ID, id)
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

    private fun notifyProgress(id: String, name: String, bytes: Long, total: Long) {
        val now = System.nanoTime()
        if (now - lastNotified < 500_000_000L) return
        lastNotified = now
        val n = builder().setContentTitle(getString(R.string.notification_title, name))
            .setContentText(getString(R.string.downloading, Formatter.formatShortFileSize(this, bytes), Formatter.formatShortFileSize(this, total)))
            .setOngoing(true).setProgress(100, (bytes * 100 / total).toInt(), false)
            .addAction(cancelAction(id)).build()
        manager().notify(NOTIFICATION_ID, n)
    }

    private fun notifyDone(name: String, ok: Boolean) {
        notifiedResult = true
        val text = getString(if (ok) R.string.notification_done else R.string.notification_failed, name)
        manager().notify(NOTIFICATION_ID, builder().setContentTitle(text).setOngoing(false).setAutoCancel(true).build())
    }

    companion object {
        /** Cancel flags of the queued and running downloads, by id; process-wide so the notification and the screen can reach them. */
        internal val cancelFlags = java.util.concurrent.ConcurrentHashMap<String, AtomicBoolean>()
        private var limiterInstance: RequestLimiter? = null

        /** The limiter of this process, keeping its state in the app's preferences so that a restart does not reset it. */
        @Synchronized
        internal fun limiterFor(context: Context): RequestLimiter =
            limiterInstance ?: RequestLimiter(PrefsPersistence(context.applicationContext)).also { limiterInstance = it }

        /** Cancels the download of [id], from this app's own screen or notification (never from outside: those are not exported). */
        internal fun cancel(id: String) {
            cancelFlags[id]?.set(true)
            CustomDownloads.cancel(id)
        }

        private const val CHANNEL = "downloads"
        private const val MAX_ATTEMPTS = 6
        private const val NOTIFICATION_ID = 1
    }
}
