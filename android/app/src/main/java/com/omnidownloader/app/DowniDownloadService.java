package com.omnidownloader.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.webkit.MimeTypeMap;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * DOWNI foreground service — keeps the engine alive while downloads run and
 * shows one compact progress notification per active job.
 *
 * Channels: progress rides a silent channel (no sound spam while a bar moves);
 * completions ride a default-importance channel with a unique id per job, so a
 * "Saved" celebration never overwrites another one.
 *
 * DowniDrop 2.0 (v3.1.0): the service is once again SELF-SUFFICIENT — it starts
 * the Chaquopy runtime itself when the app process was cold (Python.isStarted() →
 * Python.start()), exactly like the v2.6.4 OmniDownloadService whose instant
 * background grabs this restores. The save path below ports DowniEnginePlugin's
 * gallery logic so headless saves land in the same Movies/DOWNI + Music/DOWNI
 * folders (or the user's custom SAF folder) that the v3.0.4 scoped Vault reads.
 */
public class DowniDownloadService extends Service {
    private static final String CHANNEL_PROGRESS = "downi_progress";
    private static final String CHANNEL_ALERTS = "downi_alerts";
    private static final int FG_NOTIFICATION_ID = 4811;
    private static volatile DowniDownloadService instance;
    private static volatile int completionSeq = 0;

    /** Active in-app job ids (per-job notifications derive from these). */
    private static final Set<String> activeJobs = ConcurrentHashMap.newKeySet();

    /** Cancel flags for DowniDrop headless jobs, keyed by job id. */
    private static final Map<String, AtomicBoolean> sharedJobs = new ConcurrentHashMap<>();

    /** Serial queue for DowniDrop grabs — rapid shares run one after another. */
    private ExecutorService shareExecutor;

    // ---------- Public static API (called from DowniEnginePlugin / DropActivity) ----------

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

    /**
     * DowniDrop 2.0 entry point (called by DropActivity): grab a shared link fully
     * in the background — no UI, no WebView, no MainActivity warm-up assumed.
     */
    public static void startShared(Context context, String url) {
        Intent intent = new Intent(context, DowniDownloadService.class);
        intent.setAction("shared_download");
        intent.putExtra("url", url);
        ContextCompat.startForegroundService(context, intent);
    }

    // ---------- Service lifecycle ----------

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        shareExecutor = Executors.newSingleThreadExecutor();
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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
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
            case "shared_download": {
                String url = intent.getStringExtra("url");
                String jobId = "drop" + System.currentTimeMillis();
                activeJobs.add(jobId);
                sharedJobs.put(jobId, new AtomicBoolean(false));
                Notification n = buildJobNotification(jobId, "DowniDrop: starting…", 0);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(FG_NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
                } else {
                    startForeground(FG_NOTIFICATION_ID, n);
                }
                if (shareExecutor != null) shareExecutor.execute(() -> runSharedDownload(jobId, url));
                break;
            }
            case "shared_cancel": {
                String jobId = intent.getStringExtra("jobId");
                AtomicBoolean flag = sharedJobs.get(jobId);
                if (flag != null) flag.set(true);
                break;
            }
        }
        return START_STICKY;
    }

    // ---------- DowniDrop 2.0: headless shared download ----------

    private void runSharedDownload(String jobId, String url) {
        File workDir = new File(new File(getCacheDir(), "DowniEngine"), jobId);
        try {
            if (url == null || url.trim().isEmpty()) throw new IllegalArgumentException("No link in that share.");
            final String cleanUrl = url.trim();

            // Self-started engine — the v2.6.4 pattern. Do NOT assume MainActivity
            // warmed Python: on a cold share the whole process may have just started.
            showJobStatus(jobId, "DowniDrop: warming up the engine…", 1);
            if (!Python.isStarted()) Python.start(new AndroidPlatform(getApplicationContext()));

            final AtomicBoolean cancelled = sharedJobs.get(jobId);
            String formatId = rememberedFormatFor(cleanUrl);
            DownloadProgressListener listener = new DownloadProgressListener() {
                @Override
                public void onProgress(double percent, long downloadedBytes, long totalBytes, double speedBytesPerSec, long etaSeconds) {
                    showJobStatus(jobId, "DowniDrop: grabbing…", (int) percent);
                }
                @Override
                public boolean isCancelled() {
                    return cancelled != null && cancelled.get();
                }
            };

            PyObject response;
            try {
                response = Python.getInstance().getModule("downloader")
                    .callAttr("download", cleanUrl, workDir.getAbsolutePath(), formatId, listener);
            } catch (Exception firstError) {
                // Quality memory is a PREFERENCE, never a hard gate. A remembered lane
                // ('1080' etc.) can miss on videos whose formats carry no height metadata
                // or exceed the cap — DowniDrop must then grab the best available instead
                // of aborting (owner rule: it picks automatically). Cancels ("Download
                // cancelled.") and network errors must NOT trigger the retry.
                String m = String.valueOf(firstError.getMessage()).toLowerCase(Locale.US);
                boolean qualityMiss = m.contains("requested format is not available")
                    || m.contains("in that quality");
                if (qualityMiss && !"best".equalsIgnoreCase(formatId)) {
                    showJobStatus(jobId, "DowniDrop: that quality isn't here — grabbing best available…", 2);
                    response = Python.getInstance().getModule("downloader")
                        .callAttr("download", cleanUrl, workDir.getAbsolutePath(), "best", listener);
                } else {
                    throw firstError;
                }
            }

            if (cancelled != null && cancelled.get()) {
                NotificationManagerCompat.from(this).cancel(jobNotificationId(jobId));
                return;
            }

            JSONObject file = new JSONObject(response.toString());
            String title = file.optString("title", "Video");
            String destination;
            showJobStatus(jobId, "DowniDrop: saving to gallery…", 98);
            if (file.optBoolean("merge", false)) {
                showJobStatus(jobId, "DowniDrop: merging video + audio…", 96);
                File videoPart = new File(file.getString("video_path"));
                File audioPart = new File(file.getString("audio_path"));
                File merged = new File(videoPart.getParentFile(), title + "-merged.mp4");
                if (!Mp4Merger.merge(videoPart, audioPart, merged)) {
                    try { videoPart.delete(); } catch (Exception ignored) {}
                    try { audioPart.delete(); } catch (Exception ignored) {}
                    throw new IllegalStateException("Could not merge 1080p on this device. Try 720p HD or Best Available.");
                }
                destination = saveToGallery(merged, title, "mp4");
                try { videoPart.delete(); } catch (Exception ignored) {}
                try { audioPart.delete(); } catch (Exception ignored) {}
            } else {
                destination = saveToGallery(new File(file.getString("path")), title, file.getString("ext"));
            }
            if (cancelled != null && cancelled.get()) {
                NotificationManagerCompat.from(this).cancel(jobNotificationId(jobId));
                return;
            }

            NotificationManagerCompat.from(this).cancel(jobNotificationId(jobId));
            notifySharedCompletion(title, destination);
            recordDropCompletion(url, title, destination);
        } catch (Exception error) {
            NotificationManagerCompat.from(this).cancel(jobNotificationId(jobId));
            // A user-cancelled job raises "Download cancelled." out of the engine —
            // that is not a failure and must never scare the user with an error card.
            AtomicBoolean flag = sharedJobs.get(jobId);
            if (flag == null || !flag.get()) {
                String detail = error.getMessage() == null ? "Unknown download error" : error.getMessage();
                notifySharedFailure(url, friendlyError(detail));
            }
        } finally {
            sharedJobs.remove(jobId);
            activeJobs.remove(jobId);
            cleanDir(workDir);
            maybeStop();
        }
    }

    /** Per-platform quality memory, mirrored from the web layer (q_* localStorage keys). */
    private String rememberedFormatFor(String url) {
        try {
            String json = getSharedPreferences("downi_settings", MODE_PRIVATE).getString("dropQualities", "");
            if (json == null || json.isEmpty()) return "best";
            JSONObject qualities = new JSONObject(json);
            String fmt = qualities.optString(platformKeyFor(url), "");
            if (fmt.isEmpty()) fmt = qualities.optString("any", "");
            return fmt.isEmpty() ? "best" : fmt;
        } catch (Exception e) {
            return "best";
        }
    }

    /** Mirrors downloader.py _detect_platform() so remembered-quality keys line up. */
    private static String platformKeyFor(String url) {
        String u = url == null ? "" : url.toLowerCase(Locale.US);
        if (u.contains("youtube") || u.contains("youtu.be")) return "youtube";
        if (u.contains("tiktok")) return "tiktok";
        if (u.contains("instagram")) return "instagram";
        if (u.contains("facebook") || u.contains("fb.watch") || u.contains("fb.gg")) return "facebook";
        if (u.contains("twitter") || u.contains("x.com")) return "twitter";
        if (u.contains("reddit")) return "reddit";
        if (u.contains("pinterest")) return "pinterest";
        return "other";
    }


    // ---------- Gallery save (ported from DowniEnginePlugin — same behavior, headless) ----------

    private String saveToGallery(File source, String title, String extension) {
        if (source == null || !source.exists()) {
            // Returning the words "Downloaded file missing" as the DESTINATION made
            // a failed save look like a success (C9) — fail loudly instead.
            throw new IllegalStateException("Downloaded file went missing before it could be saved.");
        }
        String displayName = nextGalleryName(title, extension, source);
        String ext = (extension == null || extension.trim().isEmpty()) ? "mp4" : extension.toLowerCase(Locale.US);
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        if (mime == null) mime = ext.startsWith("m4") || ext.equals("mp3") ? "audio/" + ext : "video/mp4";

        boolean saved = false;

        String treeUri = getSharedPreferences("downi_settings", Context.MODE_PRIVATE).getString("treeUri", "");
        if (treeUri != null && !treeUri.isEmpty()) {
            try {
                Uri tree = Uri.parse(treeUri);
                Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
                Uri destination = DocumentsContract.createDocument(getContentResolver(), parent, mime, displayName);
                if (destination != null) {
                    try (InputStream input = new FileInputStream(source); OutputStream output = getContentResolver().openOutputStream(destination)) {
                        if (output != null) {
                            byte[] buffer = new byte[64 * 1024];
                            for (int read; (read = input.read(buffer)) != -1;) output.write(buffer, 0, read);
                            saved = true;
                        }
                    }
                    if (saved) scanSafDocument(destination, mime);
                }
            } catch (Exception ignored) {}
            if (saved) {
                try { source.delete(); } catch (Exception ignored) {}
                return "Custom folder";
            }
        }

        // Method 1: MediaStore
        Uri pendingRow = null;
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
            values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, (mime.startsWith("audio/") ? Environment.DIRECTORY_MUSIC : Environment.DIRECTORY_MOVIES) + "/DOWNI/");
                values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            }
            Uri collection = mime.startsWith("audio/") ? MediaStore.Audio.Media.EXTERNAL_CONTENT_URI : MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
            Uri destination = getContentResolver().insert(collection, values);
            pendingRow = destination;
            if (destination != null) {
                try (InputStream input = new FileInputStream(source); OutputStream output = getContentResolver().openOutputStream(destination)) {
                    if (output != null) {
                        byte[] buffer = new byte[64 * 1024];
                        for (int read; (read = input.read(buffer)) != -1;) output.write(buffer, 0, read);
                        saved = true;
                    }
                }
                if (saved && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentValues ready = new ContentValues();
                    ready.put(MediaStore.MediaColumns.IS_PENDING, 0);
                    getContentResolver().update(destination, ready, null, null);
                }
            }
        } catch (Exception ignored) {
            saved = false;
            // A row left with IS_PENDING=1 becomes a grey "ghost" video in gallery
            // apps (C3) — remove it when the copy blew up mid-write.
            if (pendingRow != null) {
                try { getContentResolver().delete(pendingRow, null, null); } catch (Exception ignored2) {}
            }
        }


        // Method 2: Public Media Directory
        if (!saved) {
            try {
                File publicDir = Environment.getExternalStoragePublicDirectory(mime.startsWith("audio/") ? Environment.DIRECTORY_MUSIC : Environment.DIRECTORY_MOVIES);
                if (!publicDir.exists()) publicDir.mkdirs();
                File destFile = new File(publicDir, displayName);
                try (InputStream input = new FileInputStream(source); OutputStream output = new java.io.FileOutputStream(destFile)) {
                    byte[] buffer = new byte[64 * 1024];
                    for (int read; (read = input.read(buffer)) != -1;) output.write(buffer, 0, read);
                    saved = true;
                }
                android.media.MediaScannerConnection.scanFile(this, new String[]{destFile.getAbsolutePath()}, new String[]{mime}, null);
            } catch (Exception ignored) {
                saved = false;
            }
        }

        // Method 3: App External Files
        if (!saved) {
            try {
                File appDir = getExternalFilesDir(mime.startsWith("audio/") ? Environment.DIRECTORY_MUSIC : Environment.DIRECTORY_MOVIES);
                if (appDir != null) {
                    if (!appDir.exists()) appDir.mkdirs();
                    File destFile = new File(appDir, displayName);
                    try (InputStream input = new FileInputStream(source); OutputStream output = new java.io.FileOutputStream(destFile)) {
                        byte[] buffer = new byte[64 * 1024];
                        for (int read; (read = input.read(buffer)) != -1;) output.write(buffer, 0, read);
                        saved = true;
                    }
                    android.media.MediaScannerConnection.scanFile(this, new String[]{destFile.getAbsolutePath()}, new String[]{mime}, null);
                }
            } catch (Exception ignored) {}
        }

        try { source.delete(); } catch (Exception ignored) {}
        return "Gallery / Movies";
    }

    private synchronized String nextGalleryName(String title, String extension, File source) {
        String ext = (extension == null || extension.trim().isEmpty()) ? "mp4" : extension.toLowerCase(Locale.US);
        String base = safeFileName(title, Uri.fromFile(source));
        if (base.toLowerCase(Locale.US).endsWith("." + ext)) base = base.substring(0, base.length() - ext.length() - 1);
        String key = (base + "." + ext).toLowerCase(Locale.US);
        android.content.SharedPreferences names = getSharedPreferences("downi_saved_names", Context.MODE_PRIVATE);
        // Identity migration: counters used to live under "omni_saved_names".
        android.content.SharedPreferences legacyNames = getSharedPreferences("omni_saved_names", Context.MODE_PRIVATE);
        int number = names.getInt(key, -1);
        if (number < 0) {
            number = legacyNames.getInt(key, 0);
            if (number > 0) names.edit().putInt(key, number).apply();
        }
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
        String selection = MediaStore.MediaColumns.DISPLAY_NAME + "=?";
        try (Cursor cursor = getContentResolver().query(MediaStore.Files.getContentUri("external"), projection, selection, new String[]{displayName}, null)) {
            return cursor != null && cursor.moveToFirst();
        } catch (Exception ignored) {
            return false;
        }
    }

    private String safeFileName(String requested, Uri uri) {
        String base = requested.replaceAll("[^a-zA-Z0-9._ -]", " ").trim();
        if (base.isEmpty()) base = "video";
        String path = uri.getPath() == null ? "" : uri.getPath();
        String ext = path.contains(".") ? path.substring(path.lastIndexOf('.')).toLowerCase(Locale.US) : ".mp4";
        if (!base.toLowerCase(Locale.US).endsWith(ext)) base += ext;
        return base.length() > 100 ? base.substring(0, 100 - ext.length()) + ext : base;
    }


    private void scanSafDocument(Uri documentUri, String mime) {
        try {
            String docId = DocumentsContract.getDocumentId(documentUri);
            int colon = docId.indexOf(':');
            if (colon <= 0) return;
            String device = docId.substring(0, colon);
            String rel = docId.substring(colon + 1);
            if (!"primary".equals(device) || rel.isEmpty()) return;
            File f = new File(Environment.getExternalStorageDirectory(), rel);
            android.media.MediaScannerConnection.scanFile(this, new String[]{f.getAbsolutePath()}, new String[]{mime}, null);
        } catch (Exception ignored) {}
    }

    private void cleanDir(File dir) {
        try {
            if (dir != null && dir.exists()) {
                File[] files = dir.listFiles();
                if (files != null) for (File f : files) f.delete();
            }
        } catch (Exception ignored) {}
    }

    private String friendlyError(String detail) {
        String lower = detail.toLowerCase(Locale.US);
        if (lower.contains("certificate") || lower.contains("ssl")) return "Secure connection failed. Check your internet, then retry.";
        if (lower.contains("private") || lower.contains("login") || lower.contains("sign in")) return "This video needs an account or is private. Try a public link.";
        if (lower.contains("requested format is not available")) return "That quality is not available for this link. Try Best Available or a lower quality.";
        if (lower.contains("unsupported") || lower.contains("no video formats")) return "This public link is not supported yet. Try another public video link.";
        if (lower.contains("tiktok") && (lower.contains("unexpected response") || lower.contains("throttled") || lower.contains("empty media stream")))
            return "TikTok throttled that grab. Wait a few seconds, then tap to retry.";
        // Never show yt-dlp's raw bug-report boilerplate to a user — keep the signal only.
        String cleaned = detail;
        int boiler = lower.indexOf("please report this issue");
        if (boiler > 0) cleaned = cleaned.substring(0, boiler).trim();
        cleaned = cleaned.replace("DownloadError: ERROR:", "").replace("ERROR:", "").trim();
        if (cleaned.isEmpty()) cleaned = "The media stream could not be downloaded. The video may be private, restricted, or the connection dropped.";
        if (cleaned.length() > 180) cleaned = cleaned.substring(0, 180).trim() + "…";
        return "Download failed: " + cleaned;
    }

    // ---------- Drop history handoff ----------

    /** Serializes appends (service thread) against drains (WebView thread) — C6. */
    public static final Object DROP_HISTORY_LOCK = new Object();

    /**
     * The headless path has no WebView, so it cannot write to the web layer's
     * localStorage history. Instead it appends each completed grab to a
     * SharedPreferences queue; DowniEnginePlugin.drainDropHistory hands the
     * batch to the web layer on next launch, which merges it into the Queue
     * history (v3.1.1 — fixes drops landing in the Vault with no history row).
     */
    private void recordDropCompletion(String url, String title, String destination) {
        try {
            synchronized (DROP_HISTORY_LOCK) {
                android.content.SharedPreferences prefs = getSharedPreferences("downi_settings", MODE_PRIVATE);
                JSONArray arr = new JSONArray(prefs.getString("pendingDropHistory", "[]"));
                JSONObject item = new JSONObject();
                String id = "drop" + System.currentTimeMillis();
                item.put("id", id);
                item.put("url", url == null ? "" : url);
                item.put("title", title == null ? "Video" : title);
                item.put("destination", destination == null ? "" : destination);
                item.put("ts", System.currentTimeMillis());
                arr.put(item);
                while (arr.length() > 50) arr.remove(0);
                prefs.edit().putString("pendingDropHistory", arr.toString()).commit();
            }
        } catch (Exception ignored) {}
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

    /** DowniDrop completion: names the video and where it landed. */
    private void notifySharedCompletion(String title, String destination) {
        try {
            Notification n = new NotificationCompat.Builder(this, CHANNEL_ALERTS)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Saved to your Vault")
                .setContentText(title + " · " + destination)
                .setContentIntent(openAppIntent())
                .setAutoCancel(true)
                .build();
            int id = FG_NOTIFICATION_ID + 1000 + (completionSeq++ % 4000);
            NotificationManagerCompat.from(this).notify(id, n);
        } catch (Exception ignored) {}
    }

    /** DowniDrop failure: honest reason, tap opens the Inspector with the link to retry. */
    private void notifySharedFailure(String url, String reason) {
        try {
            Intent open = new Intent(this, MainActivity.class);
            open.setAction(Intent.ACTION_SEND);
            open.setType("text/plain");
            open.putExtra(Intent.EXTRA_TEXT, url == null ? "" : url);
            PendingIntent retry = PendingIntent.getActivity(this, (int) (System.currentTimeMillis() % 100000), open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification n = new NotificationCompat.Builder(this, CHANNEL_ALERTS)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("DowniDrop couldn't grab that")
                .setContentText(reason)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(reason + " Tap to open it in the Inspector."))
                .setContentIntent(retry)
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
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("DOWNI is grabbing")
            .setContentText(status)
            .setProgress(100, percent, percent <= 0)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(openAppIntent());
        // DowniDrop jobs get a Cancel action (v2.6.4 parity) — in-app jobs already
        // have per-job cancel buttons in the Queue UI.
        if (jobId != null && jobId.startsWith("drop")) {
            Intent cancel = new Intent(this, DowniDownloadService.class);
            cancel.setAction("shared_cancel");
            cancel.putExtra("jobId", jobId);
            PendingIntent cancelPi = PendingIntent.getService(this, jobId.hashCode(), cancel,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            builder.addAction(0, "Cancel", cancelPi);
        }
        return builder.build();
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

