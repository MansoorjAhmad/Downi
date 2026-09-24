package com.omnidownloader.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.util.Log;
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
import java.util.HashSet;
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
    /**
     * v3.1.1 (defect N8): how long a finished/failed DowniDrop card stays in the app snapshot.
     * Shared contract — DowniEnginePlugin.getDropJobs() prunes with the same window on read.
     */
    static final long DROP_LIVE_TERMINAL_TTL_MS = 20000L;
    private static volatile DowniDownloadService instance;
    private static volatile int completionSeq = 0;

    /** Active in-app job ids (per-job notifications derive from these). */
    private static final Set<String> activeJobs = ConcurrentHashMap.newKeySet();

    /** Cancel flags for DowniDrop headless jobs, keyed by job id. */
    private static final Map<String, AtomicBoolean> sharedJobs = new ConcurrentHashMap<>();

    /** Source url per DowniDrop job — the in-app snapshot needs it (v3.1.1, defect N4). */
    private static final Map<String, String> dropUrls = new ConcurrentHashMap<>();

    /** Serial queue for DowniDrop grabs — rapid shares run one after another. */
    private ExecutorService shareExecutor;

    // ---------- Public static API (called from DowniEnginePlugin / DropActivity) ----------

    /** Latest numbers per live job — the single source of truth for every notification row. */
    private static final Map<String, JobProgress> live = new ConcurrentHashMap<>();

    /**
     * v3.1.1 (defect N10): the job whose row currently occupies the foreground notification slot.
     * A grab must show exactly ONE row — the device pass showed "DOWNI is grabbing 54% · 59.9 MB /
     * 77.2 MB" twice, because every tick wrote the same content to the job's own id *and* mirrored
     * it into the foreground id. Now the owner writes to the foreground id, everyone else to their
     * own id, and the slot is handed over when the owner finishes.
     */
    private String fgRowOwner = null;

    /**
     * v3.1.1 (defect N11): the grab ids the service is actually working on, or null when no service
     * instance is alive at all. DowniEnginePlugin uses this to expire a "running" entry in the app
     * snapshot whose grab no longer exists — killing the app mid-grab (device pass: force-stop while
     * the card said "Warming up the engine…") froze that entry, so reopening DOWNI showed a live
     * card *and* ACTIVE 1 for a grab that was gone. Terminal entries expire on age (defect N8);
     * running entries must expire on liveness, and only the service can answer that.
     */
    static Set<String> liveJobIdsSnapshot() {
        if (instance == null) return null;
        return new HashSet<>(live.keySet());
    }

    /** Accent used to tint the notification header (matches the app's default cyan). */
    private static final int ACCENT_COLOR = 0xFF22D3EE;

    /** Everything a notification row needs to render one honest progress line. */
    private static final class JobProgress {
        volatile int percent;
        volatile long downloaded;
        volatile long total;
        volatile double speedBps;
        volatile long etaSec;
        volatile String status;   // phase text (Queued / Merging… / Saving…) or null during transfer
        volatile boolean numbers; // true once the engine reported real byte counts
        volatile long lastPostMs;
        volatile int lastPercent = -1;
    }

    public static void startJob(Context context, String jobId, String status) {
        activeJobs.add(jobId);
        Intent intent = new Intent(context, DowniDownloadService.class);
        intent.setAction("job_start");
        intent.putExtra("jobId", jobId);
        intent.putExtra("status", status);
        ContextCompat.startForegroundService(context, intent);
    }

    /**
     * Phase update with no byte numbers yet (Queued, Merging video + audio…, Saving to your Vault…).
     * The notification shows the phase text and the given percent.
     */
    public static void updateJob(Context context, String jobId, String status, int percent) {
        DowniDownloadService service = instance;
        if (service != null) service.applyPhase(jobId, status, percent);
    }

    /**
     * Live transfer tick (v3.1.1, defects N1-N3): percent, bytes, per-second speed and ETA all
     * reach the notification through one shared formatter — the same numbers the app shows.
     */
    public static void updateJobProgress(Context context, String jobId, int percent,
                                         long downloaded, long total, double speedBps, long etaSec) {
        DowniDownloadService service = instance;
        if (service != null) service.applyProgress(jobId, percent, downloaded, total, speedBps, etaSec);
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

    // ---------- DowniDrop ledger (v3.1.1, defect N9) ----------

    /** Lifetime bytes of every headless grab this device saved. */
    static final String DROP_BYTES_KEY = "dropBytesTotal";
    /** Rows for the headless grabs themselves — the app may never have been open to see them. */
    static final String DROP_HISTORY_KEY = "dropHistory";
    /** A small honest ledger, not a database. */
    private static final int DROP_HISTORY_MAX = 50;

    /**
     * v3.1.1 (defect N9): a DowniDrop grab usually finishes with no WebView alive, so the app's own
     * "MB grabbed"/history accounting never witnessed it — the Queue dashboard of the Truth Release
     * could read "6 completed · 0 MB". The service keeps its own ledger and the app reads it back
     * on every resume (downi_settings is the shared channel the plugin already uses).
     */
    static void addGrabBytes(Context context, long bytes) {
        if (bytes <= 0) return;
        try {
            SharedPreferences prefs = context.getSharedPreferences("downi_settings", Context.MODE_PRIVATE);
            prefs.edit().putLong(DROP_BYTES_KEY, prefs.getLong(DROP_BYTES_KEY, 0L) + bytes).apply();
        } catch (Exception ignored) {}
    }

    static long grabBytes(Context context) {
        try {
            return context.getSharedPreferences("downi_settings", Context.MODE_PRIVATE)
                .getLong(DROP_BYTES_KEY, 0L);
        } catch (Exception e) { return 0L; }
    }

    static String grabHistoryJson(Context context) {
        try {
            String raw = context.getSharedPreferences("downi_settings", Context.MODE_PRIVATE)
                .getString(DROP_HISTORY_KEY, "[]");
            return raw == null || raw.isEmpty() ? "[]" : raw;
        } catch (Exception e) { return "[]"; }
    }

    static void resetGrabLedger(Context context) {
        try {
            context.getSharedPreferences("downi_settings", Context.MODE_PRIVATE).edit()
                .putLong(DROP_BYTES_KEY, 0L).putString(DROP_HISTORY_KEY, "[]").apply();
        } catch (Exception ignored) {}
    }

    /** One row per successful headless grab — newest first, deduped by job id. */
    static void recordGrabHistory(Context context, String jobId, String title, String destination,
                                  String url, long bytes) {
        if (jobId == null || jobId.isEmpty()) return;
        try {
            SharedPreferences prefs = context.getSharedPreferences("downi_settings", Context.MODE_PRIVATE);
            JSONArray list = new JSONArray(prefs.getString(DROP_HISTORY_KEY, "[]"));
            String rowId = "drop_" + jobId;
            JSONObject row = new JSONObject();
            row.put("id", rowId);
            row.put("title", title == null || title.isEmpty() ? "Shared video" : title);
            row.put("dest", destination == null || destination.isEmpty() ? "Vault" : destination);
            row.put("url", url == null ? "" : url);
            row.put("bytes", Math.max(0L, bytes));
            row.put("ts", System.currentTimeMillis());
            JSONArray next = new JSONArray();
            next.put(row);
            for (int i = 0; i < list.length() && next.length() < DROP_HISTORY_MAX; i++) {
                JSONObject o = list.optJSONObject(i);
                if (o == null || rowId.equals(o.optString("id"))) continue;
                next.put(o);
            }
            prefs.edit().putString(DROP_HISTORY_KEY, next.toString()).apply();
        } catch (Exception ignored) {}
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
                JobProgress start = live.computeIfAbsent(jobId, k -> new JobProgress());
                start.status = status != null ? status : "Starting…";
                start.numbers = false;
                start.percent = 0;
                Notification n = buildJobNotification(jobId, start);
                // N10: exactly one row per grab. The first live job owns the foreground slot; a
                // second concurrent job gets its own row and the foreground row keeps showing the
                // owner (re-posted here so a startForegroundService() call is always answered).
                if (fgRowOwner == null || fgRowOwner.equals(jobId)) {
                    fgRowOwner = jobId;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        startForeground(FG_NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
                    } else {
                        startForeground(FG_NOTIFICATION_ID, n);
                    }
                } else {
                    JobProgress owner = live.get(fgRowOwner);
                    if (owner == null) {
                        // The recorded owner has no live numbers left — this job takes the slot over.
                        fgRowOwner = jobId;
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            startForeground(FG_NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
                        } else {
                            startForeground(FG_NOTIFICATION_ID, n);
                        }
                    } else {
                        try { NotificationManagerCompat.from(this).notify(jobNotificationId(jobId), n); } catch (Exception ignored) {}
                        Notification ownerRow = buildJobNotification(fgRowOwner, owner);
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            startForeground(FG_NOTIFICATION_ID, ownerRow, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
                        } else {
                            startForeground(FG_NOTIFICATION_ID, ownerRow);
                        }
                    }
                }
                break;
            }
            case "job_finish": {
                String jobId = intent.getStringExtra("jobId");
                boolean success = intent.getBooleanExtra("success", false);
                live.remove(jobId);
                releaseJobRow(jobId); // N10: drop this job's row and hand the slot to the next live one
                if (success) notifyCompletion();
                maybeStop();
                break;
            }
            case "shared_download": {
                String url = intent.getStringExtra("url");
                String jobId = "drop" + System.currentTimeMillis();
                activeJobs.add(jobId);
                sharedJobs.put(jobId, new AtomicBoolean(false));
                dropUrls.put(jobId, url == null ? "" : url);
                JobProgress start = live.computeIfAbsent(jobId, k -> new JobProgress());
                start.status = "Starting…";
                start.numbers = false;
                start.percent = 0;
                Notification n = buildJobNotification(jobId, start);
                // N10: a share owns the foreground row only if no other grab holds it — a rapid
                // burst then shows one row per grab instead of two copies of the newest one.
                if (fgRowOwner == null || live.get(fgRowOwner) == null) {
                    fgRowOwner = jobId;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        startForeground(FG_NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
                    } else {
                        startForeground(FG_NOTIFICATION_ID, n);
                    }
                } else {
                    try { NotificationManagerCompat.from(this).notify(jobNotificationId(jobId), n); } catch (Exception ignored) {}
                    Notification ownerRow = buildJobNotification(fgRowOwner, live.get(fgRowOwner));
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        startForeground(FG_NOTIFICATION_ID, ownerRow, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
                    } else {
                        startForeground(FG_NOTIFICATION_ID, ownerRow);
                    }
                }
                if (shareExecutor != null) shareExecutor.execute(() -> runSharedDownload(jobId, url));
                break;
            }
            case "shared_cancel": {
                String jobId = intent.getStringExtra("jobId");
                AtomicBoolean flag = sharedJobs.get(jobId);
                if (flag != null) flag.set(true);
                Log.i("DOWNI", "drop cancel requested " + jobId); // (D) logcat breadcrumb
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
            Log.i("DOWNI", "drop start " + jobId + " " + cleanUrl); // (D) logcat breadcrumb

            // Self-started engine — the v2.6.4 pattern. Do NOT assume MainActivity
            // warmed Python: on a cold share the whole process may have just started.
            applyPhase(jobId, "Warming up the engine…", 1);
            if (!Python.isStarted()) Python.start(new AndroidPlatform(getApplicationContext()));

            final AtomicBoolean cancelled = sharedJobs.get(jobId);
            String formatId = rememberedFormatFor(cleanUrl);
            DownloadProgressListener listener = new DownloadProgressListener() {
                @Override
                public void onProgress(double percent, long downloadedBytes, long totalBytes, double speedBytesPerSec, long etaSeconds) {
                    // v3.1.1 (defect N2): the row carries real numbers — % · size/total · speed · ETA.
                    applyProgress(jobId, (int) percent, downloadedBytes, totalBytes, speedBytesPerSec, etaSeconds);
                }
                @Override
                public boolean isCancelled() {
                    return cancelled != null && cancelled.get();
                }
            };

            PyObject response = Python.getInstance().getModule("downloader")
                .callAttr("download", cleanUrl, workDir.getAbsolutePath(), formatId, listener);


            if (cancelled != null && cancelled.get()) {
                writeDropSnapshot(jobId, "canceled", null, null);
                live.remove(jobId);
                releaseJobRow(jobId);
                Log.i("DOWNI", "drop canceled " + jobId);
                return;
            }

            JSONObject file = new JSONObject(response.toString());
            String title = file.optString("title", "Video");
            String destination;
            // v3.1.1 (defect N9): carry the real size of the saved file into the done snapshot —
            // the last live tick is a per-stream number (or 0 during a merge phase), so the app's
            // "MB grabbed" counter had nothing honest to add for DowniDrop grabs.
            long finalBytes = 0;
            applyPhase(jobId, "Saving to your Vault…", 98);
            if (file.optBoolean("merge", false)) {
                applyPhase(jobId, "Merging video + audio…", 96);
                File videoPart = new File(file.getString("video_path"));
                File audioPart = new File(file.getString("audio_path"));
                File merged = new File(videoPart.getParentFile(), title + "-merged.mp4");
                if (!Mp4Merger.merge(videoPart, audioPart, merged)) {
                    try { videoPart.delete(); } catch (Exception ignored) {}
                    try { audioPart.delete(); } catch (Exception ignored) {}
                    throw new IllegalStateException("Could not merge 1080p on this device. Try 720p HD or Best Available.");
                }
                finalBytes = merged.length();
                destination = saveToGallery(merged, title, "mp4");
                try { videoPart.delete(); } catch (Exception ignored) {}
                try { audioPart.delete(); } catch (Exception ignored) {}
            } else {
                File single = new File(file.getString("path"));
                finalBytes = single.length();
                destination = saveToGallery(single, title, file.getString("ext"));
            }
            if (cancelled != null && cancelled.get()) {
                writeDropSnapshot(jobId, "canceled", null, null);
                live.remove(jobId);
                releaseJobRow(jobId);
                Log.i("DOWNI", "drop canceled " + jobId);
                return;
            }

            writeDropSnapshot(jobId, "done", title, destination, finalBytes);
            // N9: the durable half of the accounting — recorded by the service, so the app sees the
            // grab even when it was closed the whole time.
            addGrabBytes(this, finalBytes);
            recordGrabHistory(this, jobId, title, destination, dropUrls.get(jobId), finalBytes);
            Log.i("DOWNI", "drop saved " + jobId + " -> " + destination); // (D) logcat breadcrumb
            live.remove(jobId);
            releaseJobRow(jobId);
            notifySharedCompletion(title, destination);
        } catch (Exception error) {
            String detail = error.getMessage() == null ? "Unknown download error" : error.getMessage();
            // The cancel flag lives in sharedJobs until finally runs, so it is still readable here.
            AtomicBoolean flag = sharedJobs.get(jobId);
            boolean userCanceled = (flag != null && flag.get()) || detail.toLowerCase(Locale.US).contains("cancel");
            live.remove(jobId);
            releaseJobRow(jobId);
            if (userCanceled) {
                // Cancel UX: the user asked for this stop — a neutral "Grab canceled"
                // confirmation, never the red "couldn't grab that / Download failed: …
                // cancelled" failure the user caused themselves.
                writeDropSnapshot(jobId, "canceled", null, null);
                notifySharedCanceled();
                Log.i("DOWNI", "drop canceled " + jobId + " (user)"); // (D) logcat breadcrumb
            } else {
                String friendly = friendlyError(detail);
                // DowniDrop self-diagnosis (09-24 "fails twice, third works" report): the
                // failed card carries the plain reason into the app, and the RAW engine
                // text is parked in dropLastError so getDropJobs() can hand it back —
                // no adb needed to name the culprit.
                writeDropSnapshot(jobId, "failed", null, null, 0, friendly);
                try {
                    getSharedPreferences("downi_settings", MODE_PRIVATE).edit()
                        .putString("dropLastError", new JSONObject()
                            .put("id", jobId)
                            .put("url", url == null ? "" : url)
                            .put("friendly", friendly)
                            .put("raw", detail)
                            .put("ts", System.currentTimeMillis()).toString())
                        .apply();
                } catch (Exception ignored) {}
                Log.i("DOWNI", "drop failed " + jobId + ": " + detail); // (D) logcat breadcrumb
                notifySharedFailure(url, friendly);
            }
        } finally {
            sharedJobs.remove(jobId);
            activeJobs.remove(jobId);
            live.remove(jobId);
            dropUrls.remove(jobId);
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
        if (source == null || !source.exists()) return "Downloaded file missing";
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
        // v3.1.1 (G): plain, honest reasons for the two rawest engine texts.
        if (lower.contains("url parsing") || lower.contains("unsupported url")) return "That link isn't a video.";
        if (lower.contains("getaddrinfo") || lower.contains("name or service not known") || lower.contains("name resolution")
                || lower.contains("no address associated") || lower.contains("transport") || lower.contains("connection refused")
                || lower.contains("network is unreachable") || lower.contains("connectionreset") || lower.contains("connection reset")
                || lower.contains("errno 7") || lower.contains("errno 101") || lower.contains("errno 111")) return "Can't reach the network — try again.";
        if (lower.contains("cancel")) return "Grab canceled."; // Cancel UX: never scary
        if (lower.contains("certificate") || lower.contains("ssl")) return "Secure connection failed. Check your internet, then retry.";
        if (lower.contains("private") || lower.contains("login") || lower.contains("sign in")) return "This video needs an account or is private. Try a public link.";
        if (lower.contains("requested format is not available")) return "That quality is not available for this link. Try Best Available or a lower quality.";
        if (lower.contains("unsupported") || lower.contains("no video formats")) return "This public link is not supported yet. Try another public video link.";
        return "Download failed: " + detail;
    }

    // ---------- Notifications ----------

    /** Unique id per completion — rapid grabs never overwrite each other (B11). */
    private void notifyCompletion() {
        try {
            Notification n = new NotificationCompat.Builder(this, CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_stat_downi_done)
                .setColor(ACCENT_COLOR)
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
                .setSmallIcon(R.drawable.ic_stat_downi_done)
                .setColor(ACCENT_COLOR)
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
                .setSmallIcon(R.drawable.ic_stat_downi_alert)
                .setColor(0xFFFB7185)
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


    /** User-initiated DowniDrop cancel (Cancel UX): a neutral "Grab canceled" alert —
     *  never the red failure notification for a stop the user themselves requested. */
    private void notifySharedCanceled() {
        try {
            Notification n = new NotificationCompat.Builder(this, CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_stat_downi)
                .setColor(ACCENT_COLOR)
                .setContentTitle("Grab canceled")
                .setContentText("Stopped before it saved — start it again any time.")
                .setContentIntent(openAppIntent())
                .setAutoCancel(true)
                .build();
            int id = FG_NOTIFICATION_ID + 1000 + (completionSeq++ % 4000);
            NotificationManagerCompat.from(this).notify(id, n);
        } catch (Exception ignored) {}
    }

    // ---------- Job progress rows (v3.1.1: one formatter, one live row) ----------

    /** Phase string from the engine (Queued / Merging… / Saving…) — no byte numbers yet. */
    private void applyPhase(String jobId, String status, int percent) {
        JobProgress p = live.computeIfAbsent(jobId, k -> new JobProgress());
        p.status = status != null ? status : "Starting…";
        p.numbers = false;
        p.percent = Math.max(0, Math.min(100, percent));
        postJobRow(jobId, true);
    }

    /** Live numbers tick from the engine listener — % · size/total · speed · ETA. */
    private void applyProgress(String jobId, int percent, long downloaded, long total, double speedBps, long etaSec) {
        JobProgress p = live.computeIfAbsent(jobId, k -> new JobProgress());
        p.numbers = true;
        p.status = null;
        p.percent = Math.max(0, Math.min(100, percent));
        p.downloaded = Math.max(0, downloaded);
        p.total = Math.max(0, total);
        p.speedBps = speedBps > 0 ? speedBps : 0;
        p.etaSec = etaSec > 0 ? etaSec : 0;
        postJobRow(jobId, false);
    }

    /**
     * Post one job's row AND mirror the same content into the foreground row.
     * Defect N1: the foreground row used to freeze at "Queued" / 0 % forever because every
     * later update went to a different id. Update floor: a phase change posts immediately,
     * otherwise at most one post per 800 ms (the engine ticks ~4x/s).
     */
    private void postJobRow(String jobId, boolean force) {
        JobProgress p = live.get(jobId);
        if (p == null) return;
        long now = SystemClock.elapsedRealtime();
        if (!force && p.lastPercent == p.percent && (now - p.lastPostMs) < 800) return;
        p.lastPercent = p.percent;
        p.lastPostMs = now;
        try {
            Notification n = buildJobNotification(jobId, p);
            // N10: one row per grab — the owner of the foreground slot writes there, every other
            // job writes to its own id. Posting to both ids was what doubled the row on device.
            int id = (jobId != null && jobId.equals(fgRowOwner)) ? FG_NOTIFICATION_ID : jobNotificationId(jobId);
            NotificationManagerCompat.from(this).notify(id, n);
        } catch (Exception ignored) {}
        if (jobId != null && jobId.startsWith("drop")) writeDropSnapshot(jobId, "running", null, null);
    }

    /**
     * v3.1.1 (defect N4): mirror DowniDrop jobs into `downi_settings/dropLive` so the app can show a
     * live card — and a just-finished one — even though the grab ran headless. Tiny JSON, written on
     * the same 800 ms floor as the notifications, pruned to the last 8 entries.
     *
     * v3.1.1 (defect N8): a finished/failed card is a short celebration, not a permanent list entry —
     * terminal entries expire after 20 s. The plugin's getDropJobs() re-applies this same window on
     * every read, because nothing writes this snapshot once the last grab ends (stale cards used to
     * linger in Active downloads until a new grab happened).
     */
    private void writeDropSnapshot(String jobId, String state, String title, String destination) {
        writeDropSnapshot(jobId, state, title, destination, 0, null);
    }

    private void writeDropSnapshot(String jobId, String state, String title, String destination, long finalBytes) {
        writeDropSnapshot(jobId, state, title, destination, finalBytes, null);
    }

    /**
     * v3.1.1 (defect N9): the terminal variant — `finalBytes` is the true size of the file that
     * landed (merged output included). When it is > 0 it overrides whatever the last progress tick
     * reported, so the app can add an honest "MB grabbed" number for a headless grab.
     * `error` (failures only) carries the plain reason onto the in-app failed card.
     */
    private void writeDropSnapshot(String jobId, String state, String title, String destination, long finalBytes, String error) {
        if (jobId == null || !jobId.startsWith("drop")) return;
        try {
            JobProgress p = live.get(jobId);
            SharedPreferences prefs = getSharedPreferences("downi_settings", MODE_PRIVATE);
            String raw = prefs.getString("dropLive", "");
            JSONArray list = (raw == null || raw.isEmpty()) ? new JSONArray() : new JSONArray(raw);
            long now = System.currentTimeMillis();
            JSONObject entry = null;
            for (int i = 0; i < list.length(); i++) {
                JSONObject o = list.optJSONObject(i);
                if (o != null && jobId.equals(o.optString("id"))) { entry = o; break; }
            }
            if (entry == null) {
                entry = new JSONObject();
                entry.put("id", jobId);
                list.put(entry);
            }
            String url = dropUrls.get(jobId);
            entry.put("url", url == null ? "" : url);
            entry.put("platform", platformKeyFor(url));
            entry.put("pct", p != null ? Math.max(0, Math.min(100, p.percent)) : 0);
            if (finalBytes > 0) {
                // N9: the real landed size wins over the last (per-stream) progress tick.
                entry.put("downloaded", finalBytes);
                entry.put("total", finalBytes);
                entry.put("pct", 100);
            } else {
                entry.put("downloaded", p != null ? p.downloaded : 0);
                entry.put("total", p != null ? p.total : 0);
            }
            entry.put("speed", p != null ? p.speedBps : 0);
            entry.put("status", p != null && p.status != null ? p.status : "");
            entry.put("state", state);
            if (title != null) entry.put("title", title);
            if (destination != null) entry.put("dest", destination);
            if (error != null && !error.isEmpty()) entry.put("error", error);
            entry.put("ts", now);

            JSONArray kept = new JSONArray();
            for (int i = 0; i < list.length(); i++) {
                JSONObject o = list.optJSONObject(i);
                if (o == null) continue;
                boolean running = "running".equals(o.optString("state"));
                if (!running && now - o.optLong("ts", now) > DROP_LIVE_TERMINAL_TTL_MS) continue;
                kept.put(o);
            }
            JSONArray capped = new JSONArray();
            int start = Math.max(0, kept.length() - 8);
            for (int i = start; i < kept.length(); i++) capped.put(kept.optJSONObject(i));
            prefs.edit().putString("dropLive", capped.toString()).apply();
        } catch (Exception ignored) {}
    }

    /**
     * v3.1.1 (defect N10): release one job's live row. If that job owned the foreground slot, the
     * next live grab inherits it (the foreground row must never go stale), otherwise just drop the
     * job's own row. Call this AFTER removing the job from {@link #live}.
     */
    private void releaseJobRow(String jobId) {
        if (jobId == null) return;
        try {
            if (jobId.equals(fgRowOwner)) {
                fgRowOwner = null;
                String next = null;
                for (String id : live.keySet()) { if (id != null) { next = id; break; } }
                if (next == null) {
                    NotificationManagerCompat.from(this).cancel(FG_NOTIFICATION_ID);
                } else {
                    adoptForegroundRow(next);
                }
            } else {
                NotificationManagerCompat.from(this).cancel(jobNotificationId(jobId));
            }
        } catch (Exception ignored) {}
    }

    /** Move `jobId`'s live row into the foreground slot and drop its temporary own-id row. */
    private void adoptForegroundRow(String jobId) {
        JobProgress p = live.get(jobId);
        if (p == null) return;
        try {
            fgRowOwner = jobId;
            NotificationManagerCompat.from(this).cancel(jobNotificationId(jobId));
            NotificationManagerCompat.from(this).notify(FG_NOTIFICATION_ID, buildJobNotification(jobId, p));
        } catch (Exception ignored) {}
    }

    private int jobNotificationId(String jobId) {
        return FG_NOTIFICATION_ID + 1 + Math.abs((jobId != null ? jobId.hashCode() : 0) % 1000);
    }

    /** Collapsed one-liner: "26% · 3.2 MB / 12.1 MB" — numbers whenever the engine has them. */
    private static String progressLine(JobProgress p) {
        if (p.numbers && p.total > 0) {
            return p.percent + "% · " + bytesShort(p.downloaded) + " / " + bytesShort(p.total);
        }
        if (p.numbers && p.downloaded > 0) return p.percent + "% · " + bytesShort(p.downloaded);
        if (p.status != null) return p.status;
        return "Starting…";
    }

    /** Expanded line: "⚡ DowniDrop · 26% done · 3.2 MB / 12.1 MB · 2.4 MB/s · 32s left". */
    private static String progressBigText(String jobId, JobProgress p) {
        StringBuilder sb = new StringBuilder();
        if (jobId != null && jobId.startsWith("drop")) sb.append("⚡ DowniDrop · ");
        sb.append(p.percent).append("% done");
        if (p.numbers && p.total > 0) {
            sb.append(" · ").append(bytesShort(p.downloaded)).append(" / ").append(bytesShort(p.total));
        } else if (p.numbers && p.downloaded > 0) {
            sb.append(" · ").append(bytesShort(p.downloaded));
        }
        String speed = speedShort(p.speedBps);
        if (!speed.isEmpty()) sb.append(" · ").append(speed);
        if (p.etaSec > 0) sb.append(" · ").append(p.etaSec).append("s left");
        return sb.toString();
    }

    /** 1024-based but labelled MB/s — the app's own formatBytes()/formatSpeed() convention (owner D3). */
    private static String bytesShort(long bytes) {
        if (bytes <= 0) return "0 MB";
        double mb = bytes / (1024.0 * 1024.0);
        if (mb >= 1024.0) return String.format(Locale.US, "%.2f GB", mb / 1024.0);
        if (mb >= 100.0) return String.format(Locale.US, "%.0f MB", mb);
        return String.format(Locale.US, "%.1f MB", mb);
    }

    private static String speedShort(double bytesPerSec) {
        if (bytesPerSec <= 0) return "";
        double mbPerSec = bytesPerSec / (1024.0 * 1024.0);
        if (mbPerSec >= 1.0) return String.format(Locale.US, "%.1f MB/s", mbPerSec);
        return String.format(Locale.US, "%.0f KB/s", bytesPerSec / 1024.0);
    }

    private Notification buildJobNotification(String jobId, JobProgress p) {
        boolean determinate = p.percent > 0 || (p.numbers && p.total > 0);
        String speed = speedShort(p.speedBps);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_downi)
            .setColor(ACCENT_COLOR)
            .setContentTitle("DOWNI is grabbing")
            .setContentText(progressLine(p))
            .setStyle(new NotificationCompat.BigTextStyle().bigText(progressBigText(jobId, p)))
            .setProgress(100, Math.max(0, Math.min(100, p.percent)), !determinate)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setShowWhen(false)
            // Defect N5: without IMMEDIATE, Android 12+ may hold the FGS notification back ~10 s —
            // short grabs then finished with no notification ever seen.
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openAppIntent());
        if (!speed.isEmpty()) builder.setSubText(speed);
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
        // v3.1.1 (defect N7): tapping a grab notification lands on the Queue, where the job lives.
        open.putExtra("openQueue", true);
        return PendingIntent.getActivity(this, 0, open,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private void maybeStop() {
        if (activeJobs.isEmpty()) {
            live.clear();
            fgRowOwner = null; // N10: the foreground slot dies with the service
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
            // v3.1.1 (U5): make the channel policy deliberate. A moving progress bar is not an
            // unread notification — no badge, no vibration, silent. Completions are the badge-worthy
            // ones (default sound/vibration from IMPORTANCE_DEFAULT). Note: Android locks a channel's
            // sound/vibration/badge policy at creation, so this shapes new installs.
            progress.setShowBadge(false);
            progress.enableVibration(false);
            progress.setSound(null, null);
            nm.createNotificationChannel(progress);
            NotificationChannel alerts = new NotificationChannel(
                CHANNEL_ALERTS, "DOWNI completions", NotificationManager.IMPORTANCE_DEFAULT);
            alerts.setDescription("Finished grabs land here with a sound");
            alerts.setShowBadge(true); // a real completion is worth an app-icon badge
            nm.createNotificationChannel(alerts);
        }
    }
}

