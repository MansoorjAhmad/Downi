package com.omnidownloader.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.provider.MediaStore;
import android.webkit.MimeTypeMap;
import android.database.Cursor;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.chaquo.python.android.AndroidPlatform;
import com.chaquo.python.Python;
import com.chaquo.python.PyObject;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

/**
 * DOWNI foreground service — V2.5 ELITE.
 * Per-download notifications for the parallel queue, live progress,
 * and a Cancel button on DowniDrop background notifications.
 */
public class OmniDownloadService extends Service implements DownloadProgressListener {
    private static final String CHANNEL_ID = "omni_downloads";
    private static final int FG_NOTIFICATION_ID = 4811;
    private static volatile OmniDownloadService instance;
    private final ExecutorService shareExecutor = Executors.newSingleThreadExecutor();
    private long lastProgressNotify = 0;
    private String currentDownloadTitle = "Downi video";
    private static volatile boolean sharedCancelRequested = false;

    /** Active in-app job ids (per-job notifications derive from these). */
    private static final Set<String> activeJobs = ConcurrentHashMap.newKeySet();

    // ---------- Public static API (called from OmniEnginePlugin) ----------

    public static void startJob(Context context, String jobId, String status) {
        activeJobs.add(jobId);
        Intent intent = new Intent(context, OmniDownloadService.class);
        intent.setAction("job_start");
        intent.putExtra("jobId", jobId);
        intent.putExtra("status", status);
        ContextCompat.startForegroundService(context, intent);
    }

    public static void updateJob(Context context, String jobId, String status, int percent) {
        OmniDownloadService service = instance;
        if (service != null) service.showJobStatus(jobId, status, percent);
    }

    public static void finishJob(Context context, String jobId) {
        activeJobs.remove(jobId);
        Intent intent = new Intent(context, OmniDownloadService.class);
        intent.setAction("job_finish");
        intent.putExtra("jobId", jobId);
        try { context.startService(intent); } catch (Exception ignored) {}
    }

    // ---------- Shared (DowniDrop) API ----------

    public static void startShared(Context context, String url) {
        sharedCancelRequested = false;
        Intent intent = new Intent(context, OmniDownloadService.class);
        intent.setAction("shared_download");
        intent.putExtra("url", url);
        ContextCompat.startForegroundService(context, intent);
    }

    /** Called from the app UI (or the notification Cancel action) to abort an OmniDrop transfer. */
    public static void cancelShared() {
        sharedCancelRequested = true;
    }

    // ---------- Lifecycle ----------

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        ensureChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? "job_start" : intent.getAction();
        lastAction = action;
        try {
            if ("cancel_shared".equals(action)) {
                sharedCancelRequested = true;
                dismissNotification(FG_NOTIFICATION_ID);
                if (activeJobs.isEmpty()) {
                    stopForeground(STOP_FOREGROUND_REMOVE);
                    stopSelf();
                }
            } else if ("shared_download".equals(action)) {
                try {
                    startForeground(FG_NOTIFICATION_ID, buildProgressNotification("DowniDrop — Starting…", "Connecting to media server…", 0, true));
                } catch (Exception ignored) {}
                String url = intent == null ? "" : intent.getStringExtra("url");
                shareExecutor.execute(() -> runSharedDownload(url));
            } else if ("job_finish".equals(action)) {
                String jobId = intent == null ? "" : intent.getStringExtra("jobId");
                dismissNotification(jobNotificationId(jobId));
                refreshForeground();
                if (activeJobs.isEmpty()) {
                    stopForeground(STOP_FOREGROUND_REMOVE);
                    stopSelf();
                }
            } else { // job_start
                String jobId = intent == null ? "dl" : intent.getStringExtra("jobId");
                String status = intent == null ? "Queued" : intent.getStringExtra("status");
                refreshForeground();
                showJobStatus(jobId, status, 0);
            }
        } catch (Exception ignored) {}
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        instance = null;
        shareExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    // ---------- Notifications ----------

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Downi Downloads", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Real-time progress and notifications for DOWNI");
            channel.setShowBadge(false);
            channel.enableVibration(false);
            channel.enableLights(false);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private static int jobNotificationId(String jobId) {
        return 6000 + (Math.abs((jobId == null ? "dl" : jobId).hashCode()) % 1000);
    }

    private void refreshForeground() {
        int count = activeJobs.size();
        if (count > 0) {
            try {
                startForeground(FG_NOTIFICATION_ID, buildProgressNotification(
                    "DOWNI", count + (count == 1 ? " download in progress" : " downloads in progress"), 0, true));
            } catch (Exception ignored) {}
        }
    }

    private void dismissNotification(int id) {
        getSystemService(NotificationManager.class).cancel(id);
    }

    private void showJobStatus(String jobId, String status, int percent) {
        if (jobId == null || !activeJobs.contains(jobId)) return;
        Notification notif = buildJobNotification("DOWNI", status, percent, percent <= 0 || percent >= 100);
        getSystemService(NotificationManager.class).notify(jobNotificationId(jobId), notif);
    }

    private Notification buildJobNotification(String title, String content, int percent, boolean indeterminate) {
        Intent openIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (openIntent == null) openIntent = new Intent(this, MainActivity.class);
        openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPending = PendingIntent.getActivity(
            this, 100, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(content)
            .setSubText("DOWNI")
            .setContentIntent(openPending)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW);

        if (indeterminate) {
            builder.setProgress(0, 0, true);
        } else {
            builder.setProgress(100, Math.max(0, Math.min(100, percent)), false);
        }
        return builder.build();
    }

    private Notification buildProgressNotification(String title, String content, int percent, boolean indeterminate) {
        // Tapping the progress notification opens the app
        Intent openIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (openIntent == null) openIntent = new Intent(this, MainActivity.class);
        openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPending = PendingIntent.getActivity(
            this, 100, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(content)
            .setSubText("DowniDrop")
            .setContentIntent(openPending)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW);

        // Cancel action for background shared downloads
        if ("shared_download".equals(lastAction)) {
            Intent cancelIntent = new Intent(this, OmniDownloadService.class).setAction("cancel_shared");
            PendingIntent cancelPending = PendingIntent.getService(
                this, 103, cancelIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
            builder.addAction(0, "✕ Cancel", cancelPending);
        }

        if (indeterminate) {
            builder.setProgress(0, 0, true);
        } else {
            builder.setProgress(100, Math.max(0, Math.min(100, percent)), false);
        }
        return builder.build();
    }

    private volatile String lastAction = "";

    @Override
    public void onProgress(double percent, long downloadedBytes, long totalBytes, double speedBytesPerSec, long etaSeconds) {
        long now = System.currentTimeMillis();
        // Throttle updates to Android notification manager to avoid UI lag
        if (now - lastProgressNotify < 300 && percent < 99.0) {
            return;
        }
        lastProgressNotify = now;

        String sizeStr = formatBytes(downloadedBytes) + (totalBytes > 0 ? " / " + formatBytes(totalBytes) : "");
        String speedStr = formatSpeed(speedBytesPerSec);
        String etaStr = etaSeconds > 0 ? (etaSeconds + "s left") : "";

        StringBuilder info = new StringBuilder();
        info.append(sizeStr).append(String.format(Locale.US, " (%.0f%%)", percent));
        if (!speedStr.isEmpty()) info.append(" • ").append(speedStr);
        if (!etaStr.isEmpty()) info.append(" • ").append(etaStr);

        Notification notif = buildProgressNotification("DowniDrop — Downloading…", info.toString(), (int) percent, false);
        getSystemService(NotificationManager.class).notify(FG_NOTIFICATION_ID, notif);
    }

    @Override
    public boolean isCancelled() {
        return sharedCancelRequested;
    }

    private void showStatus(String status, int percent) {
        Notification notif = buildProgressNotification("DOWNI", status, percent, percent <= 0 || percent >= 100);
        getSystemService(NotificationManager.class).notify(FG_NOTIFICATION_ID, notif);
    }

    private void showSuccess(Uri videoUri, String mime, String title) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("DowniDrop — Download Complete! 🎉")
            .setContentText(title != null ? title : "Video saved to your gallery")
            .setSubText("Ready in Gallery")
            .setOnlyAlertOnce(false)
            .setOngoing(false)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT);

        if (videoUri != null && mime != null) {
            // Action 1: Play Video
            Intent playIntent = new Intent(Intent.ACTION_VIEW);
            playIntent.setDataAndType(videoUri, mime);
            playIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent playPending = PendingIntent.getActivity(
                this, 101, playIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
            builder.setContentIntent(playPending);
            builder.addAction(android.R.drawable.ic_media_play, "▶ Play", playPending);

            // Action 2: Share Video
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType(mime);
            shareIntent.putExtra(Intent.EXTRA_STREAM, videoUri);
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            PendingIntent sharePending = PendingIntent.getActivity(
                this, 102, Intent.createChooser(shareIntent, "Share Video"),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
            builder.addAction(android.R.drawable.ic_menu_share, "↗ Share", sharePending);
        }

        getSystemService(NotificationManager.class).notify(FG_NOTIFICATION_ID, builder.build());
    }

    private void runSharedDownload(String rawUrl) {
        File work = new File(getCacheDir(), "OmniDrop");
        try {
            String url = extractUrl(rawUrl);
            if (url.isEmpty()) throw new IllegalArgumentException("Empty or invalid link");
            if (!Python.isStarted()) Python.start(new AndroidPlatform(getApplicationContext()));

            showStatus("Finding best quality…", 5);
            String igSession = getSharedPreferences("omni_settings", MODE_PRIVATE).getString("ig_session", "");
            PyObject response;
            try {
                // Pass 'this' as DownloadProgressListener to Python.
                response = Python.getInstance().getModule("downloader")
                    .callAttr("download", url.trim(), work.getAbsolutePath(), "best", this, igSession);
            } catch (Exception localError) {
                // DowniDrop must follow the same Cloud Boost path as the in-app
                // queue. Previously Share → DOWNI always stopped here, even when
                // the user had enabled a working relay in Settings.
                if (sharedCancelRequested) throw localError;
                String relay = getSharedPreferences("omni_settings", MODE_PRIVATE).getString("relay_url", "");
                if (relay == null || relay.trim().isEmpty()) throw localError;
                response = downloadSharedFromCloud(url, work, relay.trim(), igSession);
            }

            org.json.JSONObject file = new org.json.JSONObject(response.toString());
            String title = file.optString("title", "Downi video");
            String ext = file.optString("ext", "mp4");
            currentDownloadTitle = title;

            showStatus("Saving to your gallery…", 98);
            Uri savedUri = saveToGallery(new File(file.getString("path")), title, ext);
            String mime = ext.equalsIgnoreCase("mp3") || ext.equalsIgnoreCase("m4a") ? "audio/" + ext : "video/mp4";

            showSuccess(savedUri, mime, title);
            stopForeground(STOP_FOREGROUND_DETACH);
            stopSelf();
        } catch (Exception error) {
            String detail = error.getMessage();
            if (detail != null && detail.toLowerCase(Locale.US).contains("cancel")) {
                showCancelled();
            } else {
                showFailure(detail == null || detail.trim().isEmpty() ? "Could not download this link. Video may be private or protected." : detail);
            }
            stopForeground(STOP_FOREGROUND_DETACH);
            stopSelf();
        } finally {
            // Remove engine temp files so cancelled/partial downloads never linger
            try {
                File[] files = work.listFiles();
                if (files != null) for (File f : files) f.delete();
            } catch (Exception ignored) {}
        }
    }

    /** Resolve a shared link through the user's Cloud Boost relay, then download
     * the returned CDN URL directly to the device. This mirrors the in-app queue
     * fallback and deliberately sends an Instagram session only to that user's
     * configured relay and only for Instagram links. */
    private PyObject downloadSharedFromCloud(String sourceUrl, File work, String relayUrl, String igSession) throws Exception {
        Uri relay = Uri.parse(relayUrl);
        if (!("https".equalsIgnoreCase(relay.getScheme()) || "http".equalsIgnoreCase(relay.getScheme()))
                || relay.getHost() == null || relay.getHost().isEmpty()) {
            throw new IllegalArgumentException("Cloud Boost URL is invalid.");
        }
        String baseUrl = relayUrl.endsWith("/") ? relayUrl.substring(0, relayUrl.length() - 1) : relayUrl;
        org.json.JSONObject request = new org.json.JSONObject()
            .put("url", sourceUrl)
            .put("format", "best");
        if (sourceUrl.toLowerCase(Locale.US).contains("instagram.com") && igSession != null && !igSession.trim().isEmpty()) {
            request.put("instagramSession", igSession.trim());
        }

        showStatus("Cloud Boost — resolving…", 8);
        HttpURLConnection conn = (HttpURLConnection) new URL(baseUrl + "/api/extract").openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("User-Agent", "DOWNI/DowniDrop");
        try (OutputStream output = conn.getOutputStream()) {
            output.write(request.toString().getBytes("UTF-8"));
        }
        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            conn.disconnect();
            throw new IllegalStateException("Cloud Boost could not resolve this link (HTTP " + code + ").");
        }
        StringBuilder raw = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
            for (String line; (line = reader.readLine()) != null;) raw.append(line);
        } finally {
            conn.disconnect();
        }
        org.json.JSONObject data = new org.json.JSONObject(raw.toString());
        String streamUrl = data.optString("url", "");
        if (!data.optBoolean("ok", false) || !(streamUrl.startsWith("https://") || streamUrl.startsWith("http://"))) {
            throw new IllegalStateException("Cloud Boost did not return a playable media link.");
        }
        showStatus("Cloud Boost — downloading…", 12);
        return Python.getInstance().getModule("downloader").callAttr(
            "download_direct", streamUrl, work.getAbsolutePath(),
            data.optString("title", "Downi video"), data.optString("ext", "mp4"), this
        );
    }

    private void showCancelled() {
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_close_clear_cancel)
            .setContentTitle("DowniDrop cancelled")
            .setContentText("The background download was stopped.")
            .setOnlyAlertOnce(true)
            .setOngoing(false)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build();
        getSystemService(NotificationManager.class).notify(FG_NOTIFICATION_ID, notification);
    }

    private void showFailure(String status) {
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("DowniDrop download failed")
            .setContentText(status)
            .setOnlyAlertOnce(true)
            .setOngoing(false)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build();
        getSystemService(NotificationManager.class).notify(FG_NOTIFICATION_ID, notification);
    }

    private String extractUrl(String value) {
        if (value == null) return "";
        Matcher matcher = Pattern.compile("https?://[^\\s<>\\\"']+", Pattern.CASE_INSENSITIVE).matcher(value);
        if (!matcher.find()) return "";
        return matcher.group().replaceAll("[.,;:!?)\\]]+$", "");
    }

    private Uri saveToGallery(File source, String title, String extension) throws Exception {
        String ext = extension == null ? "mp4" : extension.toLowerCase();
        String base = (title == null || title.trim().isEmpty() ? "Downi video" : title).replaceAll("[\\\\/:*?\\\"<>|]+", " ").trim();
        String name = nextGalleryName(base, ext);
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        if (mime == null) mime = ext.startsWith("m4") || ext.equals("mp3") ? "audio/" + ext : "video/mp4";

        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, mime.startsWith("audio/") ? Environment.DIRECTORY_MUSIC : Environment.DIRECTORY_MOVIES);
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        }

        Uri collection = mime.startsWith("audio/") ? MediaStore.Audio.Media.EXTERNAL_CONTENT_URI : MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        Uri destination = null;
        try {
            destination = getContentResolver().insert(collection, values);
        } catch (Exception ignored) {}

        if (destination != null) {
            try (InputStream input = new FileInputStream(source); OutputStream output = getContentResolver().openOutputStream(destination)) {
                if (output == null) throw new IllegalStateException("Gallery stream unavailable");
                byte[] buffer = new byte[65536];
                for (int read; (read = input.read(buffer)) != -1;) output.write(buffer, 0, read);
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues ready = new ContentValues();
                ready.put(MediaStore.MediaColumns.IS_PENDING, 0);
                getContentResolver().update(destination, ready, null, null);
            }
        } else {
            File publicDir = Environment.getExternalStoragePublicDirectory(mime.startsWith("audio/") ? Environment.DIRECTORY_MUSIC : Environment.DIRECTORY_MOVIES);
            if (!publicDir.exists()) publicDir.mkdirs();
            File destFile = new File(publicDir, name);
            try (InputStream input = new FileInputStream(source); OutputStream output = new java.io.FileOutputStream(destFile)) {
                byte[] buffer = new byte[65536];
                for (int read; (read = input.read(buffer)) != -1;) output.write(buffer, 0, read);
            }
            android.media.MediaScannerConnection.scanFile(this, new String[]{destFile.getAbsolutePath()}, new String[]{mime}, null);
            destination = Uri.fromFile(destFile);
        }
        source.delete();
        return destination;
    }

    private synchronized String nextGalleryName(String base, String ext) {
        String key = (base + "." + ext).toLowerCase();
        android.content.SharedPreferences names = getSharedPreferences("omni_saved_names", MODE_PRIVATE);
        int number = names.getInt(key, 0);
        String candidate = number == 0 ? base + "." + ext : base + " (" + number + ")." + ext;
        while (galleryNameExists(candidate)) {
            number++;
            candidate = base + " (" + number + ")." + ext;
        }
        names.edit().putInt(key, number + 1).apply();
        return candidate;
    }

    private boolean galleryNameExists(String displayName) {
        String[] projection = {MediaStore.MediaColumns._ID};
        try (Cursor cursor = getContentResolver().query(MediaStore.Files.getContentUri("external"), projection,
                MediaStore.MediaColumns.DISPLAY_NAME + "=?", new String[]{displayName}, null)) {
            return cursor != null && cursor.moveToFirst();
        } catch (Exception ignored) {
            return false;
        }
    }

    private String formatBytes(long bytes) {
        if (bytes <= 0) return "0 MB";
        double mb = bytes / (1024.0 * 1024.0);
        if (mb >= 1024.0) {
            return String.format(Locale.US, "%.2f GB", mb / 1024.0);
        }
        return String.format(Locale.US, "%.1f MB", mb);
    }

    private String formatSpeed(double bytesPerSec) {
        if (bytesPerSec <= 0) return "";
        double mbPerSec = bytesPerSec / (1024.0 * 1024.0);
        if (mbPerSec >= 1.0) {
            return String.format(Locale.US, "%.1f MB/s", mbPerSec);
        }
        double kbPerSec = bytesPerSec / 1024.0;
        return String.format(Locale.US, "%.0f KB/s", kbPerSec);
    }
}
