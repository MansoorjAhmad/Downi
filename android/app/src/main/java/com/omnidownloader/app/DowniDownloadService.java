package com.omnidownloader.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DOWNI foreground service — keeps the engine alive while downloads run and
 * shows one compact progress notification per active job.
 *
 * Channels: progress rides a silent channel (no sound spam while a bar moves);
 * completions ride a default-importance channel with a unique id per job, so a
 * "Saved" celebration never overwrites another one.
 */
public class DowniDownloadService extends Service {
    private static final String CHANNEL_PROGRESS = "downi_progress";
    private static final String CHANNEL_ALERTS = "downi_alerts";
    private static final int FG_NOTIFICATION_ID = 4811;
    private static volatile DowniDownloadService instance;
    private static volatile int completionSeq = 0;

    /** Active in-app job ids (per-job notifications derive from these). */
    private static final Set<String> activeJobs = ConcurrentHashMap.newKeySet();

    // ---------- Public static API (called from DowniEnginePlugin) ----------

    public static void startJob(Context context, String jobId, String status) {
        activeJobs.add(jobId);
        Intent intent = new Intent(context, DowniDownloadService.class);
        intent.setAction("job_start");
        intent.putExtra("jobId", jobId);
        intent.putExtra("status", status);
        ContextCompat.startForegroundService(context, intent);
    }

    public static void updateJob(Context context, String jobId, String status, int percent) {
        DowniDownloadService service = instance;
        if (service != null) service.showJobStatus(jobId, status, percent);
    }

    /** @param success true posts a completion celebration, false (cancel/fail) stays silent. */
    public static void finishJob(Context context, String jobId, boolean success) {
        activeJobs.remove(jobId);
        Intent intent = new Intent(context, DowniDownloadService.class);
        intent.setAction("job_finish");
        intent.putExtra("jobId", jobId);
        intent.putExtra("success", success);
        try { context.startService(intent); } catch (Exception ignored) {}
    }

    // ---------- Service lifecycle ----------

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        ensureChannels();
    }

    @Override public void onDestroy() { instance = null; super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (action == null) { maybeStop(); return START_NOT_STICKY; }

        switch (action) {
            case "job_start": {
                String jobId = intent.getStringExtra("jobId");
                String status = intent.getStringExtra("status");
                Notification n = buildJobNotification(jobId, status != null ? status : "Starting...", 0);
                if (Build.VERSION.SDK_INT >= 29) {
                    startForeground(FG_NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
                } else {
                    startForeground(FG_NOTIFICATION_ID, n);
                }
                break;
            }
            case "job_finish": {
                String jobId = intent.getStringExtra("jobId");
                boolean success = intent.getBooleanExtra("success", false);
                NotificationManagerCompat.from(this).cancel(jobNotificationId(jobId));
                if (success) notifyCompletion();
                maybeStop();
                break;
            }
        }
        return START_STICKY;
    }

    // ---------- Notifications ----------

    /** Unique id per completion — rapid grabs never overwrite each other (B11). */
    private void notifyCompletion() {
        try {
            Notification n = new NotificationCompat.Builder(this, CHANNEL_ALERTS)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Saved to your Vault")
                .setContentText("Grab complete - tap to open DOWNI.")
                .setContentIntent(openAppIntent())
                .setAutoCancel(true)
                .build();
            int id = FG_NOTIFICATION_ID + 1000 + (completionSeq++ % 4000);
            NotificationManagerCompat.from(this).notify(id, n);
        } catch (Exception ignored) {}
    }

    private void showJobStatus(String jobId, String status, int percent) {
        try {
            NotificationManagerCompat.from(this).notify(
                jobNotificationId(jobId), buildJobNotification(jobId, status, percent));
        } catch (Exception ignored) {}
    }

    private int jobNotificationId(String jobId) {
        return FG_NOTIFICATION_ID + 1 + Math.abs((jobId != null ? jobId.hashCode() : 0) % 1000);
    }

    private Notification buildJobNotification(String jobId, String status, int percent) {
        return new NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("DOWNI is grabbing")
            .setContentText(status)
            .setProgress(100, percent, percent <= 0)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(openAppIntent())
            .build();
    }

    private PendingIntent openAppIntent() {
        Intent open = new Intent(this, MainActivity.class);
        return PendingIntent.getActivity(this, 0, open,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private void maybeStop() {
        if (activeJobs.isEmpty()) {
            stopForeground(true);
            stopSelf();
        }
    }

    private void ensureChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm == null) return;
            NotificationChannel progress = new NotificationChannel(
                CHANNEL_PROGRESS, "DOWNI grabs", NotificationManager.IMPORTANCE_LOW);
            progress.setDescription("Live progress while videos download");
            nm.createNotificationChannel(progress);
            NotificationChannel alerts = new NotificationChannel(
                CHANNEL_ALERTS, "DOWNI completions", NotificationManager.IMPORTANCE_DEFAULT);
            alerts.setDescription("Finished grabs land here with a sound");
            nm.createNotificationChannel(alerts);
        }
    }
}
