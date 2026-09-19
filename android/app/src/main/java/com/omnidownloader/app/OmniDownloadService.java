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
import java.util.Locale;
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
 * OmniDrop 2.0 Foreground Service.
 * Implements real-time progress callbacks, determinate system progress bar,
 * live download speed & ETA, and 1-tap "Play" / "Share" notification actions.
 */
public class OmniDownloadService extends Service implements DownloadProgressListener {
    private static final String CHANNEL_ID = "omni_downloads";
    private static final int NOTIFICATION_ID = 4811;
    private static volatile OmniDownloadService instance;
    private final ExecutorService shareExecutor = Executors.newSingleThreadExecutor();
    private long lastProgressNotify = 0;
    private String currentDownloadTitle = "Downi video";
    private static volatile boolean sharedCancelRequested = false;

    public static void start(Context context, String status) {
        Intent intent = new Intent(context, OmniDownloadService.class);
        intent.setAction("start");
        intent.putExtra("status", status);
        ContextCompat.startForegroundService(context, intent);
    }

    public static void update(String status, int percent) {
        OmniDownloadService service = instance;
        if (service != null) service.showStatus(status, percent);
    }

    public static void finish(Context context, boolean success) {
        Intent intent = new Intent(context, OmniDownloadService.class);
        intent.setAction(success ? "complete" : "stop");
        context.startService(intent);
    }

    public static void startShared(Context context, String url) {
        sharedCancelRequested = false;
        Intent intent = new Intent(context, OmniDownloadService.class);
        intent.setAction("shared_download");
        intent.putExtra("url", url);
        ContextCompat.startForegroundService(context, intent);
    }

    /** Called from the app UI (or a future notification action) to abort an OmniDrop transfer. */
    public static void cancelShared() {
        sharedCancelRequested = true;
    }

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        ensureChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? "start" : intent.getAction();
        try {
            if ("complete".equals(action)) {
                showSuccess(null, null, "Video saved to your gallery");
                stopForeground(STOP_FOREGROUND_DETACH);
                stopSelf();
            } else if ("stop".equals(action)) {
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
            } else if ("shared_download".equals(action)) {
                try {
                    startForeground(NOTIFICATION_ID, buildProgressNotification("DowniDrop — Starting…", "Connecting to media server…", 0, true));
                } catch (Exception ignored) {}
                String url = intent == null ? "" : intent.getStringExtra("url");
                shareExecutor.execute(() -> runSharedDownload(url));
            } else {
                String status = intent != null ? intent.getStringExtra("status") : "Preparing download…";
                try {
                    startForeground(NOTIFICATION_ID, buildProgressNotification("DOWNI", status, 0, true));
                } catch (Exception ignored) {}
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
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notif);
    }

    @Override
    public boolean isCancelled() {
        return sharedCancelRequested;
    }

    private void showStatus(String status, int percent) {
        Notification notif = buildProgressNotification("DOWNI", status, percent, percent <= 0 || percent >= 100);
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notif);
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

        if (indeterminate) {
            builder.setProgress(0, 0, true);
        } else {
            builder.setProgress(100, Math.max(0, Math.min(100, percent)), false);
        }
        return builder.build();
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
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE : PendingIntent.FLAG_UPDATE_CURRENT
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
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE : PendingIntent.FLAG_UPDATE_CURRENT
            );
            builder.addAction(android.R.drawable.ic_menu_share, "↗ Share", sharePending);
        }

        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, builder.build());
    }

    private void runSharedDownload(String rawUrl) {
        try {
            String url = extractUrl(rawUrl);
            if (url.isEmpty()) throw new IllegalArgumentException("Empty or invalid link");
            if (!Python.isStarted()) Python.start(new AndroidPlatform(getApplicationContext()));

            showStatus("Finding best quality…", 5);

            File work = new File(getCacheDir(), "OmniDrop");
            // Pass 'this' as DownloadProgressListener to Python
            PyObject response = Python.getInstance().getModule("downloader").callAttr("download", url.trim(), work.getAbsolutePath(), "best", this);

            org.json.JSONObject file = new org.json.JSONObject(response.toString());
            String title = file.optString("title", "Omni video");
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
            File work = new File(getCacheDir(), "OmniDrop");
            try {
                File[] files = work.listFiles();
                if (files != null) for (File f : files) f.delete();
            } catch (Exception ignored) {}
        }
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
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification);
    }

    private String extractUrl(String value) {
        if (value == null) return "";
        Matcher matcher = Pattern.compile("https?://[^\\s<>\\\"']+", Pattern.CASE_INSENSITIVE).matcher(value);
        if (!matcher.find()) return "";
        return matcher.group().replaceAll("[.,;:!?)\\]]+$", "");
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
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification);
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
