package io.github.ianrastall.offlinemaps

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager

/** User-started data transfer. It never restarts or resumes a job on its own. */
class DownloadService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val downloads get() = (application as OfflineMapsApplication).session.downloads
    private val notifications get() = getSystemService(NotificationManager::class.java)
    private var wakeLock: PowerManager.WakeLock? = null
    private var attachedRun = 0L
    private var ending = false
    private var lastProgress = ""
    private val limit = Runnable { timedOut() }
    private val tick = object : Runnable {
        override fun run() {
            if (ending || attachedRun != downloads.runId) return
            if (!downloads.active) { finish(); return }
            val key = "${downloads.message}:${downloads.fraction}"
            if (key != lastProgress) {
                lastProgress = key
                runCatching { notifications.notify(NOTIFICATION, notification(true)) }
            }
            main.postDelayed(this, 1_000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Map downloads", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Progress, pause controls and results for map downloads."
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == PAUSE) {
            if (intent.getLongExtra(RUN, -1) == downloads.runId) downloads.pause()
            if (attachedRun == 0L) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent?.action != START || !downloads.active || intent.getLongExtra(RUN, -1) != downloads.runId) {
            if (attachedRun == 0L) stopSelf(startId)
            return START_NOT_STICKY
        }
        attachedRun = downloads.runId
        ending = false
        runCatching {
            startForeground(NOTIFICATION, notification(true), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            if (wakeLock?.isHeld != true) {
                wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OfflineMaps:download").apply {
                    setReferenceCounted(false)
                    acquire(MAX_RUN_MS)
                }
            }
            main.removeCallbacks(limit)
            main.postDelayed(limit, MAX_RUN_MS)
            downloads.begin()
            main.removeCallbacks(tick)
            main.post(tick)
        }.onFailure {
            downloads.serviceFailed(it)
            finish()
        }
        return START_NOT_STICKY
    }

    private fun notification(active: Boolean, body: String? = null): Notification {
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val text = body ?: if (active) downloads.message else downloads.error ?: downloads.notice ?: "Open Offline Maps for download status."
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(downloads.title).setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open).setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setOngoing(active).setAutoCancel(!active)
        if (active) {
            val fraction = downloads.fraction
            builder.setProgress(100, ((fraction ?: 0f) * 100).toInt(), fraction == null)
            val pause = PendingIntent.getService(this, attachedRun.toInt(),
                Intent(this, DownloadService::class.java).setAction(PAUSE).putExtra(RUN, downloads.runId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(Notification.Action.Builder(null, "Pause", pause).build())
        }
        return builder.build()
    }

    private fun finish(body: String? = null) {
        if (ending) return
        ending = true
        main.removeCallbacks(tick); main.removeCallbacks(limit)
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        runCatching { notifications.notify(NOTIFICATION, notification(false, body)) }
        stopSelf()
    }

    private fun timedOut() {
        if (attachedRun == downloads.runId && downloads.active) {
            val reason = "Download paused at the background time limit. Open Offline Maps and choose Resume."
            downloads.pause(reason)
            // Android requires the service to stop promptly, without waiting
            // for native cancellation/atomic activation to finish.
            finish(reason)
        } else finish()
    }

    override fun onTimeout(startId: Int, fgsType: Int) { timedOut() }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        if (!ending && attachedRun == downloads.runId && downloads.active) {
            downloads.pause("Download paused because its background service stopped. Choose Resume to continue.")
        }
        releaseWakeLock()
        super.onDestroy()
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    companion object {
        const val START = "io.github.ianrastall.offlinemaps.DOWNLOAD"
        const val PAUSE = "io.github.ianrastall.offlinemaps.PAUSE_DOWNLOAD"
        const val RUN = "downloadRun"
        const val CHANNEL = "downloads"
        private const val NOTIFICATION = 40
        private const val MAX_RUN_MS = 6 * 60 * 60 * 1_000L

        fun notificationsEnabled(context: Context): Boolean {
            val manager = context.getSystemService(NotificationManager::class.java)
            return manager.areNotificationsEnabled() && manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
        }
    }
}
