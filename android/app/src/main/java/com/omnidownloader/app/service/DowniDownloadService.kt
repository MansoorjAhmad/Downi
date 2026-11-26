package com.omnidownloader.app.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.omnidownloader.app.DowniNativeUtils
import com.omnidownloader.app.EngineError
import com.omnidownloader.app.MainActivity
import com.omnidownloader.app.MediaStoreSaver
import com.omnidownloader.app.R
import com.omnidownloader.app.data.DownloadJobEntity
import com.omnidownloader.app.engine.DownloadCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class DowniDownloadService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val secondaryIds = ConcurrentHashMap.newKeySet<Int>()

    override fun onCreate() {
        super.onCreate()
        ensureChannels(this)
        acquireLocks()
        startForegroundSafe(buildJobNotification("Preparing download…", "", 0, null))

        serviceScope.launch {
            DownloadCoordinator.observeActiveJobs(applicationContext).collectLatest { active ->
                if (active.isEmpty()) {
                    sweepSecondaryNotifications()
                    releaseLocks()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    val nm = NotificationManagerCompat.from(this@DowniDownloadService)
                    val canPost = canPostNotifications(this@DowniDownloadService)
                    val primary = active.first()
                    val primaryTitle = buildNotificationTitle(primary, active.size)
                    val primarySub = buildNotificationSubtext(primary)
                    val primaryNotif = buildJobNotification(primaryTitle, primarySub, primary.progress, primary.id)
                    startForegroundSafe(primaryNotif)

                    // Show individual progress notifications for additional concurrent jobs
                    val activeSecondary = mutableSetOf<Int>()
                    if (canPost && active.size > 1) {
                        active.drop(1).forEach { job ->
                            val nid = secondaryNotificationId(job.id)
                            activeSecondary.add(nid)
                            secondaryIds.add(nid)
                            val sub = buildNotificationSubtext(job)
                            val n = buildJobNotification(
                                title = job.title.ifBlank { job.statusText }.take(48),
                                subtext = sub,
                                progress = job.progress,
                                cancelJobId = job.id
                            )
                            nm.notify(nid, n)
                        }
                    }
                    // Clear secondary notifications for jobs that completed/cancelled
                    val toRemove = secondaryIds.filter { it !in activeSecondary }
                    toRemove.forEach { nid ->
                        nm.cancel(nid)
                        secondaryIds.remove(nid)
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL_JOB -> {
                val jobId = intent.getStringExtra(EXTRA_JOB_ID)
                if (!jobId.isNullOrBlank()) {
                    DownloadCoordinator.cancel(applicationContext, jobId)
                }
            }
            ACTION_RETRY_JOB -> {
                val url = intent.getStringExtra(EXTRA_URL)
                val platform = intent.getStringExtra(EXTRA_PLATFORM) ?: "other"
                val notifId = intent.getIntExtra(EXTRA_NOTIF_ID, -1)
                if (notifId > 0) {
                    NotificationManagerCompat.from(this).cancel(notifId)
                }
                if (!url.isNullOrBlank() && !DownloadCoordinator.isUrlActive(url)) {
                    DownloadCoordinator.enqueue(
                        context = applicationContext,
                        url = url,
                        platform = platform,
                        isDrop = true
                    )
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        sweepSecondaryNotifications()
        serviceScope.cancel()
        releaseLocks()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun sweepSecondaryNotifications() {
        val nm = NotificationManagerCompat.from(this)
        secondaryIds.forEach { nm.cancel(it) }
        secondaryIds.clear()
    }

    private fun startForegroundSafe(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(FG_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(FG_NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotificationTitle(job: DownloadJobEntity, totalActive: Int): String {
        val base = job.title.ifBlank { job.statusText }.take(46)
        return if (totalActive > 1) "$base (+${totalActive - 1})" else base
    }

    private fun buildNotificationSubtext(job: DownloadJobEntity): String {
        val parts = mutableListOf<String>()
        if (job.progress > 0) parts.add("${job.progress}%")
        if (job.totalBytes > 0) {
            parts.add("${DowniNativeUtils.formatBytes(job.downloadedBytes)} / ${DowniNativeUtils.formatBytes(job.totalBytes)}")
        } else if (job.statusText.isNotBlank()) {
            parts.add(job.statusText)
        }
        val speed = DowniNativeUtils.formatSpeed(job.speedBps)
        if (speed.isNotBlank()) parts.add(speed)
        val eta = DowniNativeUtils.formatEta(job.etaSec)
        if (eta.isNotBlank()) parts.add(eta)
        return parts.joinToString(" · ")
    }

    private fun buildJobNotification(
        title: String,
        subtext: String,
        progress: Int,
        cancelJobId: String?
    ): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("openQueue", true)
        }
        val openPending = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_downi)
            .setColor(ACCENT_COLOR)
            .setContentTitle(title)
            .setContentText(subtext.ifBlank { "Working…" })
            .setContentIntent(openPending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setProgress(100, progress.coerceIn(0, 100), progress <= 0)
            .apply {
                if (progress > 0) setSubText("$progress%")
            }

        if (!cancelJobId.isNullOrBlank()) {
            val cancelIntent = Intent(this, DowniDownloadService::class.java).apply {
                action = ACTION_CANCEL_JOB
                putExtra(EXTRA_JOB_ID, cancelJobId)
            }
            val cancelPending = PendingIntent.getService(
                this,
                cancelJobId.hashCode(),
                cancelIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(0, "Cancel", cancelPending)
        }

        return builder.build()
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(POWER_SERVICE) as? PowerManager
                wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DOWNI::DownloadWakeLock")?.apply {
                    setReferenceCounted(false)
                }
            }
            if (wakeLock?.isHeld == false) wakeLock?.acquire(30 * 60 * 1000L)
        } catch (_: Exception) {
        }
        try {
            if (wifiLock == null) {
                val wm = applicationContext.getSystemService(WIFI_SERVICE) as? WifiManager
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wm?.createWifiLock(mode, "DOWNI::DownloadWifiLock")?.apply {
                    setReferenceCounted(false)
                }
            }
            if (wifiLock?.isHeld == false) wifiLock?.acquire()
        } catch (_: Exception) {
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        }
        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val CHANNEL_PROGRESS = "downi_progress"
        private const val CHANNEL_ALERTS = "downi_alerts"
        private const val FG_NOTIFICATION_ID = 4811
        private const val ACTION_CANCEL_JOB = "com.omnidownloader.app.ACTION_CANCEL_JOB"
        private const val ACTION_RETRY_JOB = "com.omnidownloader.app.ACTION_RETRY_JOB"
        private const val EXTRA_JOB_ID = "jobId"
        private const val EXTRA_URL = "url"
        private const val EXTRA_PLATFORM = "platform"
        private const val EXTRA_NOTIF_ID = "notifId"
        private const val ACCENT_COLOR = 0xFFC8FF00.toInt()
        @Volatile
        private var alertSeq = 6000

        private fun secondaryNotificationId(jobId: String): Int =
            4812 + ((jobId.hashCode() and 0x7FFFFFFF) % 900)

        fun ensureRunning(context: Context, jobId: String, title: String) {
            ensureChannels(context)
            val intent = Intent(context, DowniDownloadService::class.java).apply {
                putExtra(EXTRA_JOB_ID, jobId)
                putExtra("title", title)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun postDropCompletion(
            context: Context,
            title: String,
            saveOutcome: MediaStoreSaver.SaveOutcome,
            fileBytes: Long = 0L,
            shieldMode: String = ""
        ) {
            if (!canPostNotifications(context)) return
            ensureChannels(context)
            val openIntent = if (saveOutcome.mediaUri.isNotBlank()) {
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(Uri.parse(saveOutcome.mediaUri), saveOutcome.mimeType)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            } else {
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra("openQueue", true)
                }
            }
            val notifId = ++alertSeq
            val pi = PendingIntent.getActivity(
                context,
                notifId,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val details = buildList {
                if (fileBytes > 0L) add(DowniNativeUtils.formatBytes(fileBytes))
                add(saveOutcome.destinationLabel)
                if (shieldMode.isNotBlank() && shieldMode != "skipped") {
                    add("Shield verified")
                }
            }.joinToString(" · ")

            val builder = NotificationCompat.Builder(context, CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_stat_downi_done)
                .setColor(ACCENT_COLOR)
                .setContentTitle(title.ifBlank { "Saved" })
                .setContentText(details)
                .setAutoCancel(true)
                .setContentIntent(pi)

            if (saveOutcome.mediaUri.isNotBlank()) {
                val shareIntent = Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = saveOutcome.mimeType
                        putExtra(Intent.EXTRA_STREAM, Uri.parse(saveOutcome.mediaUri))
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },
                    "Share media"
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val sharePi = PendingIntent.getActivity(
                    context,
                    notifId + 10000,
                    shareIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                builder.addAction(0, "Share", sharePi)
            }

            NotificationManagerCompat.from(context).notify(notifId, builder.build())
        }

        fun postDropFailure(
            context: Context,
            title: String,
            cause: String,
            url: String = "",
            platform: String = "other"
        ) {
            if (!canPostNotifications(context)) return
            ensureChannels(context)
            val notifId = ++alertSeq
            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("openQueue", true)
            }
            val pi = PendingIntent.getActivity(
                context,
                notifId,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val builder = NotificationCompat.Builder(context, CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_stat_downi_alert)
                .setColor(0xFFFB7185.toInt())
                .setContentTitle(title.ifBlank { "DowniDrop failed" })
                .setContentText(EngineError.phrase(cause))
                .setAutoCancel(true)
                .setContentIntent(pi)

            if (url.isNotBlank()) {
                val retryIntent = Intent(context, DowniDownloadService::class.java).apply {
                    action = ACTION_RETRY_JOB
                    putExtra(EXTRA_URL, url)
                    putExtra(EXTRA_PLATFORM, platform)
                    putExtra(EXTRA_NOTIF_ID, notifId)
                }
                val retryPi = PendingIntent.getService(
                    context,
                    notifId + 20000,
                    retryIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                builder.addAction(0, "Retry", retryPi)
            }

            NotificationManagerCompat.from(context).notify(notifId, builder.build())
        }

        fun postDropCancelled(context: Context, title: String) {
            if (!canPostNotifications(context)) return
            ensureChannels(context)
            val notif = NotificationCompat.Builder(context, CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_stat_downi_alert)
                .setContentTitle(title.ifBlank { "DowniDrop" })
                .setContentText("Download cancelled.")
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(++alertSeq, notif)
        }

        private fun canPostNotifications(context: Context): Boolean {
            return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        }

        private fun ensureChannels(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_PROGRESS) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_PROGRESS, "Active downloads", NotificationManager.IMPORTANCE_LOW).apply {
                        setShowBadge(false)
                    }
                )
            }
            if (nm.getNotificationChannel(CHANNEL_ALERTS) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ALERTS, "Download alerts", NotificationManager.IMPORTANCE_DEFAULT)
                )
            }
        }
    }
}
