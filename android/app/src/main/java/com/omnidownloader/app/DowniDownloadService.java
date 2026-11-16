package com.omnidownloader.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
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
 */
public class DowniDownloadService extends Service {
    private static final String CHANNEL_PROGRESS = "downi_progress";
    private static final String CHANNEL_ALERTS = "downi_alerts";
    private static final int FG_NOTIFICATION_ID = 4811;
    private static final long WAKELOCK_TIMEOUT_MS = 30 * 60 * 1000L;

    private static volatile DowniDownloadService instance;
    private static volatile int completionSeq = 0;
    private static final Object FG_LOCK = new Object();

    /** Active in-app + headless job ids. */
    private static final Set<String> activeJobs = ConcurrentHashMap.newKeySet();

    /** Cancel flags for DowniDrop headless jobs, keyed by job id. */
    private static final Map<String, AtomicBoolean> sharedJobs = new ConcurrentHashMap<>();

    /** Shared jobs that have actually entered runSharedDownload execution. */
    private static final Set<String> runningSharedJobs = ConcurrentHashMap.newKeySet();

    /** Shared jobs whose cancel was already finalized immediately while queued. */
    private static final Set<String> canceledEarlyJobs = ConcurrentHashMap.newKeySet();

    /** Registered DowniDrop grabs for interrupted-transfer sweep. */
    private static final String ACTIVE_KEY = "activeGrabs";

    /** Source url per DowniDrop job. */
    private static final Map<String, String> dropUrls = new ConcurrentHashMap<>();

    /** Latest numbers per live job — single source of truth for notification rows. */
    private static final Map<String, JobProgress> live = new ConcurrentHashMap<>();

    /** Secondary progress notification IDs ever posted in this service lifecycle, swept on stop. */
    private static final Set<Integer> postedSecondaryIds = ConcurrentHashMap.newKeySet();

    private static final int ACCENT_COLOR = 0xFF22D3EE;
    static final String DROP_BYTES_KEY = "dropBytesTotal";
    static final String DROP_HISTORY_KEY = "dropHistory";
    private static final int DROP_HISTORY_MAX = 50;

    private ExecutorService shareExecutor;
    private String fgRowOwner = null;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    private static final class JobProgress {
        volatile int percent;
        volatile long downloaded;
        volatile long total;
        volatile double speedBps;
        volatile long etaSec;
        volatile String title;
        volatile String status;
        volatile boolean numbers;
        volatile long lastPostMs;
        volatile int lastPercent = -1;
        volatile String shieldMode;
        volatile String shieldReason;
        volatile String shieldInputSha;
        volatile String shieldSha;
    }

    static Set<String> liveJobIdsSnapshot() {
        if (instance == null) return null;
        return new HashSet<>(live.keySet());
    }

    static boolean isAlive() {
        return instance != null;
    }

    /** Direct in-process cancel for DowniDrop jobs (avoids startService() FGS restrictions). */
    static boolean cancelSharedJob(Context context, String jobId) {
        if (jobId == null || jobId.trim().isEmpty()) return false;
        AtomicBoolean flag = sharedJobs.get(jobId);
        if (flag != null) {
            flag.set(true);
            DowniDownloadService inst = instance;
            if (inst != null) {
                inst.handleEarlyQueuedCancel(jobId);
            }
            return true;
        }
        return false;
    }

    public static void startJob(Context context, String jobId, String status) {
        startJob(context, jobId, status, null);
    }

    public static void startJob(Context context, String jobId, String status, String initialTitle) {
        activeJobs.add(jobId);
        Intent intent = new Intent(context, DowniDownloadService.class);
        intent.setAction("job_start");
        intent.putExtra("jobId", jobId);
        intent.putExtra("status", status);
        if (initialTitle != null && !initialTitle.trim().isEmpty()) {
            intent.putExtra("title", initialTitle.trim());
        }
        ContextCompat.startForegroundService(context, intent);
    }

    public static void updateJob(Context context, String jobId, String status, int percent) {
        DowniDownloadService service = instance;
        if (service != null) service.applyPhase(jobId, status, percent);
    }

    public static void updateJobProgress(Context context, String jobId, int percent,
                                         long downloaded, long total, double speedBps, long etaSec) {
        DowniDownloadService service = instance;
        if (service != null) service.applyProgress(jobId, percent, downloaded, total, speedBps, etaSec);
    }

    public static void finishJob(Context context, String jobId, boolean success) {
        finishJob(context, jobId, success, false);
    }

    public static void finishJob(Context context, String jobId, boolean success, boolean inForeground) {
        activeJobs.remove(jobId);
        DowniDownloadService service = instance;
        if (service != null) {
            Intent intent = new Intent(context, DowniDownloadService.class);
            intent.setAction("job_finish");
            intent.putExtra("jobId", jobId);
            intent.putExtra("success", success);
            intent.putExtra("inForeground", inForeground);
            try {
                context.startService(intent);
            } catch (Exception e) {
                service.finalizeInAppJob(jobId, success, inForeground);
            }
        }
    }

    public static void startShared(Context context, String url) {
        Intent intent = new Intent(context, DowniDownloadService.class);
        intent.setAction("shared_download");
        intent.putExtra("url", url);
        ContextCompat.startForegroundService(context, intent);
    }

    // ---------- DowniDrop ledger ----------

    static void addGrabBytes(Context context, long bytes) {
        DowniNativeUtils.addLongPref(context, DROP_BYTES_KEY, bytes);
    }

    static long grabBytes(Context context) {
        synchronized (DowniNativeUtils.PREFS_LOCK) {
            try {
                return context.getSharedPreferences(DowniNativeUtils.PREFS_SETTINGS, Context.MODE_PRIVATE)
                        .getLong(DROP_BYTES_KEY, 0L);
            } catch (Exception e) {
                return 0L;
            }
        }
    }

    static String grabHistoryJson(Context context) {
        synchronized (DowniNativeUtils.PREFS_LOCK) {
            try {
                String raw = context.getSharedPreferences(DowniNativeUtils.PREFS_SETTINGS, Context.MODE_PRIVATE)
                        .getString(DROP_HISTORY_KEY, "[]");
                return raw == null || raw.isEmpty() ? "[]" : raw;
            } catch (Exception e) {
                return "[]";
            }
        }
    }

    static void resetGrabLedger(Context context) {
        synchronized (DowniNativeUtils.PREFS_LOCK) {
            try {
                context.getSharedPreferences(DowniNativeUtils.PREFS_SETTINGS, Context.MODE_PRIVATE).edit()
                        .putLong(DROP_BYTES_KEY, 0L).putString(DROP_HISTORY_KEY, "[]").apply();
            } catch (Exception ignored) {}
        }
    }

    static void recordGrabHistory(Context context, String jobId, String title, String destination,
                                  String url, long bytes, VideoUniquifier.Result uRes,
                                  MediaStoreSaver.SaveOutcome saveResult) {
        if (jobId == null || jobId.isEmpty()) return;
        DowniNativeUtils.mutateJsonArrayPref(context, DROP_HISTORY_KEY, list -> {
            String rowId = "drop_" + jobId;
            JSONObject row = new JSONObject();
            row.put("id", rowId);
            row.put("title", title == null || title.isEmpty() ? "Shared video" : title);
            row.put("dest", destination == null || destination.isEmpty() ? "Gallery" : destination);
            row.put("url", url == null ? "" : url);
            row.put("bytes", Math.max(0L, bytes));
            row.put("sizeText", DowniNativeUtils.formatBytes(bytes));
            row.put("ts", System.currentTimeMillis());
            row.put("mediaUri", saveResult != null ? saveResult.mediaUri : "");
            row.put("mimeType", saveResult != null ? saveResult.mimeType : "");
            if (saveResult != null) {
                row.put("mediaId", saveResult.mediaId);
                row.put("isVideo", saveResult.video);
            }
            if (uRes != null) {
                row.put("shieldMode", uRes.mode);
                row.put("shieldReason", uRes.reason);
                if (uRes.inputSha256 != null && !uRes.inputSha256.isEmpty()) {
                    row.put("shieldInputSha", uRes.inputSha256);
                }
                if (uRes.outputSha256 != null && !uRes.outputSha256.isEmpty()) {
                    row.put("shieldSha", uRes.outputSha256);
                }
            }
            JSONArray next = new JSONArray();
            next.put(row);
            for (int i = 0; i < list.length() && next.length() < DROP_HISTORY_MAX; i++) {
                JSONObject o = list.optJSONObject(i);
                if (o == null || rowId.equals(o.optString("id"))) continue;
                next.put(o);
            }
            return next;
        });
    }

    // ---------- Service lifecycle ----------

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        shareExecutor = Executors.newSingleThreadExecutor();
        ensureChannels();
        sweepInterrupted(this);
    }

    private void registerActiveGrab(String jobId, String url) {
        DowniNativeUtils.mutateJsonObjectPref(this, ACTIVE_KEY, m -> {
            JSONObject j = new JSONObject();
            j.put("url", url == null ? "" : url);
            m.put(jobId, j);
        });
    }

    private void unregisterActiveGrab(String jobId) {
        DowniNativeUtils.mutateJsonObjectPref(this, ACTIVE_KEY, m -> m.remove(jobId));
    }

    static void sweepInterrupted(Context context) {
        synchronized (DowniNativeUtils.PREFS_LOCK) {
            try {
                SharedPreferences prefs = context.getSharedPreferences(DowniNativeUtils.PREFS_SETTINGS, Context.MODE_PRIVATE);
                JSONObject active = new JSONObject(prefs.getString(ACTIVE_KEY, "{}"));
                java.util.Iterator<String> it = active.keys();
                while (it.hasNext()) {
                    String jobId = it.next();
                    if (activeJobs.contains(jobId)) continue;
                    File dir = new File(new File(context.getCacheDir(), "DowniEngine"), jobId);
                    File[] leftovers = dir.listFiles();
                    long bytes = 0;
                    if (leftovers != null) for (File f : leftovers) bytes += f.length();
                    it.remove();
                    if (bytes <= 0) continue;
                    for (File f : leftovers) f.delete();
                    dir.delete();
                    DowniDownloadService inst = instance;
                    if (inst != null) inst.writeDropSnapshot(jobId, "failed", null, null);
                    Log.i("DOWNI", "interrupted grab discarded " + jobId + " (" + bytes + "B on disk)");
                }
                prefs.edit().putString(ACTIVE_KEY, active.toString()).apply();
            } catch (Exception ignored) {}
        }
    }

    @Override
    public void onDestroy() {
        instance = null;
        releaseLocks();
        if (shareExecutor != null) {
            shareExecutor.shutdownNow();
            shareExecutor = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (action == null) {
            // Guard against ForegroundServiceDidNotStartInTimeException if OS delivers null intent.
            Notification fallback = buildFallbackNotification();
            startForegroundWithType(fallback);
            maybeStop();
            return START_NOT_STICKY;
        }

        switch (action) {
            case "job_start": {
                String jobId = intent.getStringExtra("jobId");
                String status = intent.getStringExtra("status");
                String title = intent.getStringExtra("title");
                if (jobId == null) {
                    startForegroundWithType(buildFallbackNotification());
                    maybeStop();
                    return START_NOT_STICKY;
                }
                acquireLocks();
                JobProgress start = live.computeIfAbsent(jobId, k -> new JobProgress());
                if (title != null && !title.trim().isEmpty()) {
                    start.title = title.trim();
                }
                start.status = status != null ? status : "Starting…";
                start.numbers = false;
                start.percent = 0;
                Notification n = buildJobNotification(jobId, start);
                synchronized (FG_LOCK) {
                    int secId = jobNotificationId(jobId);
                    if (fgRowOwner == null || fgRowOwner.equals(jobId) || live.get(fgRowOwner) == null) {
                        fgRowOwner = jobId;
                        try { NotificationManagerCompat.from(this).cancel(secId); } catch (Exception ignored) {}
                        postedSecondaryIds.remove(secId);
                        startForegroundWithType(n);
                    } else {
                        postedSecondaryIds.add(secId);
                        try { NotificationManagerCompat.from(this).notify(secId, n); } catch (Exception ignored) {}
                        Notification ownerRow = buildJobNotification(fgRowOwner, live.get(fgRowOwner));
                        startForegroundWithType(ownerRow);
                    }
                }
                break;
            }
            case "job_finish": {
                String jobId = intent.getStringExtra("jobId");
                boolean success = intent.getBooleanExtra("success", false);
                boolean inForeground = intent.getBooleanExtra("inForeground", false);
                finalizeInAppJob(jobId, success, inForeground);
                break;
            }
            case "shared_download": {
                String url = intent.getStringExtra("url");
                String jobId = DowniNativeUtils.nextJobId("drop");
                acquireLocks();
                activeJobs.add(jobId);
                sharedJobs.put(jobId, new AtomicBoolean(false));
                dropUrls.put(jobId, url == null ? "" : url);
                JobProgress start = live.computeIfAbsent(jobId, k -> new JobProgress());
                boolean isAlreadyRunning;
                synchronized (FG_LOCK) {
                    isAlreadyRunning = (fgRowOwner != null && live.get(fgRowOwner) != null);
                }
                start.status = isAlreadyRunning ? "Queued…" : "Starting…";
                start.numbers = false;
                start.percent = 0;
                Notification n = buildJobNotification(jobId, start);
                synchronized (FG_LOCK) {
                    int secId = jobNotificationId(jobId);
                    if (fgRowOwner == null || live.get(fgRowOwner) == null) {
                        fgRowOwner = jobId;
                        try { NotificationManagerCompat.from(this).cancel(secId); } catch (Exception ignored) {}
                        postedSecondaryIds.remove(secId);
                        startForegroundWithType(n);
                    } else {
                        postedSecondaryIds.add(secId);
                        try { NotificationManagerCompat.from(this).notify(secId, n); } catch (Exception ignored) {}
                        Notification ownerRow = buildJobNotification(fgRowOwner, live.get(fgRowOwner));
                        startForegroundWithType(ownerRow);
                    }
                }
                registerActiveGrab(jobId, url == null ? "" : url);
                writeDropSnapshot(jobId, "running", null, null);
                if (shareExecutor != null) shareExecutor.execute(() -> runSharedDownload(jobId, url));
                break;
            }
            case "shared_cancel": {
                String jobId = intent.getStringExtra("jobId");
                if (jobId != null) {
                    cancelSharedJob(this, jobId);
                    Log.i("DOWNI", "drop cancel requested " + jobId);
                }
                break;
            }
            default: {
                startForegroundWithType(buildFallbackNotification());
                maybeStop();
                break;
            }
        }
        return START_NOT_STICKY;
    }

    private void startForegroundWithType(Notification n) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(FG_NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(FG_NOTIFICATION_ID, n);
        }
    }

    private void finalizeInAppJob(String jobId, boolean success, boolean inForeground) {
        if (jobId != null) {
            live.remove(jobId);
            releaseJobRow(jobId);
        }
        if (success && !inForeground) notifyCompletion();
        maybeStop();
    }

    /** Immediately clears notification & snapshot when a still-queued share job is cancelled. */
    private void handleEarlyQueuedCancel(String jobId) {
        if (runningSharedJobs.contains(jobId)) return;
        if (!canceledEarlyJobs.add(jobId)) return;
        writeDropSnapshot(jobId, "canceled", null, null);
        live.remove(jobId);
        releaseJobRow(jobId);
        activeJobs.remove(jobId);
        dropUrls.remove(jobId);
        unregisterActiveGrab(jobId);
        notifySharedCanceled();
        maybeStop();
    }

    private synchronized void acquireLocks() {
        try {
            if (wakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                if (pm != null) {
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DOWNI::DownloadWakeLock");
                    wakeLock.setReferenceCounted(false);
                }
            }
            if (wakeLock != null && !wakeLock.isHeld()) {
                wakeLock.acquire(WAKELOCK_TIMEOUT_MS);
            }
        } catch (Exception ignored) {}

        try {
            if (wifiLock == null) {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                if (wm != null) {
                    int mode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                            ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                            : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
                    wifiLock = wm.createWifiLock(mode, "DOWNI::DownloadWifiLock");
                    wifiLock.setReferenceCounted(false);
                }
            }
            if (wifiLock != null && !wifiLock.isHeld()) {
                wifiLock.acquire();
            }
        } catch (Exception ignored) {}
    }

    private synchronized void releaseLocks() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Exception ignored) {}
        try {
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        } catch (Exception ignored) {}
    }

    // ---------- DowniDrop 2.0: headless shared download ----------

    private void runSharedDownload(String jobId, String url) {
        final AtomicBoolean cancelled = sharedJobs.get(jobId);
        if ((cancelled != null && cancelled.get()) || canceledEarlyJobs.contains(jobId)) {
            if (!canceledEarlyJobs.contains(jobId)) {
                writeDropSnapshot(jobId, "canceled", null, null);
                live.remove(jobId);
                releaseJobRow(jobId);
                notifySharedCanceled();
            }
            sharedJobs.remove(jobId);
            activeJobs.remove(jobId);
            dropUrls.remove(jobId);
            canceledEarlyJobs.remove(jobId);
            unregisterActiveGrab(jobId);
            maybeStop();
            return;
        }

        runningSharedJobs.add(jobId);
        File workDir = new File(new File(getCacheDir(), "DowniEngine"), jobId);
        try {
            if (url == null || url.trim().isEmpty()) throw new IllegalArgumentException("[cause:link] No link in that share.");
            final String cleanUrl = url.trim();
            Log.i("DOWNI", "drop start " + jobId + " " + cleanUrl);

            applyPhase(jobId, "Warming up the engine…", 1);
            DowniNativeUtils.ensurePythonStarted(getApplicationContext());

            if (cancelled != null && cancelled.get()) {
                throw new RuntimeException("[cause:cancelled] Download cancelled.");
            }

            DownloadProgressListener listener = new DownloadProgressListener() {
                @Override
                public void onProgress(double percent, long downloadedBytes, long totalBytes, double speedBytesPerSec, long etaSeconds) {
                    applyProgress(jobId, (int) percent, downloadedBytes, totalBytes, speedBytesPerSec, etaSeconds);
                }

                @Override
                public boolean isCancelled() {
                    return cancelled != null && cancelled.get();
                }
            };

            String formatId = rememberedFormatFor(cleanUrl);
            PyObject response = Python.getInstance().getModule("downloader")
                    .callAttr("download", cleanUrl, workDir.getAbsolutePath(), formatId, listener, Build.VERSION.SDK_INT, "{}");

            if (cancelled != null && cancelled.get()) {
                throw new RuntimeException("[cause:cancelled] Download cancelled.");
            }

            JSONObject file = new JSONObject(response.toString());
            String title = file.optString("title", "Video");
            SharedPreferences settingsPrefs = getSharedPreferences(DowniNativeUtils.PREFS_SETTINGS, MODE_PRIVATE);
            boolean wantUnique = settingsPrefs.getBoolean("autoUnique", true);
            String shieldTier = DowniNativeUtils.normalizeShieldTier(settingsPrefs.getString("shieldTier", "standard"));

            File toSave;
            String ext;
            VideoUniquifier.Result uRes = null;

            if (file.optBoolean("merge", false)) {
                applyPhase(jobId, "Merging video + audio…", 96);
                File videoPart = new File(file.getString("video_path"));
                File audioPart = new File(file.getString("audio_path"));
                File merged = new File(videoPart.getParentFile(), TitleSanitizer.sanitize(title) + "-merged.mp4");
                boolean mergedOk = Mp4Merger.merge(videoPart, audioPart, merged,
                        () -> cancelled != null && cancelled.get());
                try { videoPart.delete(); } catch (Exception ignored) {}
                try { audioPart.delete(); } catch (Exception ignored) {}

                if (cancelled != null && cancelled.get()) {
                    try { merged.delete(); } catch (Exception ignored) {}
                    throw new RuntimeException("[cause:cancelled] Download cancelled.");
                }
                if (!mergedOk) {
                    throw new IllegalStateException("[cause:format] Could not merge video and audio on this device. Try 1080p Full HD or 720p HD.");
                }

                String mergedExt = IsoBmff.isWebm(merged) ? "webm" : "mp4";
                if (wantUnique) applyPhase(jobId, "Making video unique…", 97);
                MediaStoreSaver.ShieldOutcome shield = MediaStoreSaver.applyShieldIfEnabled(
                        merged, title, mergedExt, wantUnique, formatId, shieldTier,
                        () -> cancelled != null && cancelled.get());
                toSave = shield.fileToSave;
                ext = shield.ext;
                uRes = shield.result;
            } else {
                File single = new File(file.getString("path"));
                ext = file.getString("ext");
                if (wantUnique && !MediaStoreSaver.isAudioExtension(ext)
                        && !"audio".equalsIgnoreCase(formatId) && !"audio_opus".equalsIgnoreCase(formatId)) {
                    applyPhase(jobId, "Making video unique…", 97);
                }
                MediaStoreSaver.ShieldOutcome shield = MediaStoreSaver.applyShieldIfEnabled(
                        single, title, ext, wantUnique, formatId, shieldTier,
                        () -> cancelled != null && cancelled.get());
                toSave = shield.fileToSave;
                ext = shield.ext;
                uRes = shield.result;
            }

            if (cancelled != null && cancelled.get()) {
                throw new RuntimeException("[cause:cancelled] Download cancelled.");
            }

            JobProgress p = live.get(jobId);
            if (p != null && uRes != null) {
                p.shieldMode = uRes.mode;
                p.shieldReason = uRes.reason;
                p.shieldInputSha = uRes.inputSha256;
                p.shieldSha = uRes.outputSha256;
            }

            applyPhase(jobId, "Saving to your gallery.", 98);
            long finalBytes = toSave.length();
            MediaStoreSaver.SaveOutcome saveResult = MediaStoreSaver.saveToGallery(this, toSave, title, ext);
            String destination = saveResult.destinationLabel;

            unregisterActiveGrab(jobId);
            writeDropSnapshot(jobId, "done", title, destination, finalBytes, null, null, null, saveResult);
            addGrabBytes(this, finalBytes);
            recordGrabHistory(this, jobId, title, destination, dropUrls.get(jobId), finalBytes, uRes, saveResult);
            Log.i("DOWNI", "drop saved " + jobId + " -> " + destination);
            live.remove(jobId);
            releaseJobRow(jobId);
            notifySharedCompletion(title, destination, saveResult);
        } catch (Exception error) {
            String detail = error.getMessage() == null ? "Unknown download error" : error.getMessage();
            String causeCode = EngineError.cause(detail);
            boolean userCanceled = (cancelled != null && cancelled.get()) || "cancelled".equals(causeCode);
            live.remove(jobId);
            releaseJobRow(jobId);
            if (userCanceled) {
                writeDropSnapshot(jobId, "canceled", null, null);
                notifySharedCanceled();
                Log.i("DOWNI", "drop canceled " + jobId + " (user)");
            } else {
                String friendly = EngineError.phrase(causeCode);
                String rawReason = EngineError.raw(detail);
                writeDropSnapshot(jobId, "failed", null, null, 0, friendly, causeCode, rawReason);
                try {
                    JSONObject lastErrObj = new JSONObject()
                            .put("id", jobId)
                            .put("url", url == null ? "" : url)
                            .put("cause", causeCode)
                            .put("friendly", friendly)
                            .put("raw", rawReason)
                            .put("ts", System.currentTimeMillis());
                    synchronized (DowniNativeUtils.PREFS_LOCK) {
                        getSharedPreferences(DowniNativeUtils.PREFS_SETTINGS, MODE_PRIVATE).edit()
                                .putString("dropLastError", lastErrObj.toString())
                                .apply();
                    }
                } catch (Exception ignored) {}
                Log.i("DOWNI", "drop failed " + jobId + ": " + detail);
                notifySharedFailure(url, friendly);
            }
        } finally {
            runningSharedJobs.remove(jobId);
            canceledEarlyJobs.remove(jobId);
            sharedJobs.remove(jobId);
            activeJobs.remove(jobId);
            live.remove(jobId);
            dropUrls.remove(jobId);
            unregisterActiveGrab(jobId);
            cleanDir(workDir);
            maybeStop();
        }
    }

    private String rememberedFormatFor(String url) {
        try {
            String json = getSharedPreferences(DowniNativeUtils.PREFS_SETTINGS, MODE_PRIVATE)
                    .getString("dropQualities", "");
            if (json == null || json.isEmpty()) return "best";
            JSONObject qualities = new JSONObject(json);
            String fmt = qualities.optString(platformKeyFor(url), "");
            if (fmt.isEmpty()) fmt = qualities.optString("any", "");
            return fmt.isEmpty() ? "best" : fmt;
        } catch (Exception e) {
            return "best";
        }
    }

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

    private void cleanDir(File dir) {
        try {
            if (dir != null && dir.exists()) {
                File[] files = dir.listFiles();
                if (files != null) for (File f : files) f.delete();
                dir.delete();
            }
        } catch (Exception ignored) {}
    }

    // ---------- Notifications ----------

    private Notification buildFallbackNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_PROGRESS)
                .setSmallIcon(R.drawable.ic_stat_downi)
                .setColor(ACCENT_COLOR)
                .setContentTitle("DOWNI")
                .setContentText("Checking transfers…")
                .setOngoing(true)
                .setShowWhen(false)
                .build();
    }

    private void notifyCompletion() {
        try {
            Notification n = new NotificationCompat.Builder(this, CHANNEL_ALERTS)
                    .setSmallIcon(R.drawable.ic_stat_downi_done)
                    .setColor(ACCENT_COLOR)
                    .setContentTitle("Saved to your gallery")
                    .setContentText("Grab complete - tap to open DOWNI.")
                    .setContentIntent(openAppIntent())
                    .setAutoCancel(true)
                    .build();
            int id = FG_NOTIFICATION_ID + 1000 + (completionSeq++ % 4000);
            NotificationManagerCompat.from(this).notify(id, n);
        } catch (Exception ignored) {}
    }

    private void notifySharedCompletion(String title, String destination, MediaStoreSaver.SaveOutcome saveResult) {
        try {
            NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ALERTS)
                    .setSmallIcon(R.drawable.ic_stat_downi_done)
                    .setColor(ACCENT_COLOR)
                    .setContentTitle("Saved to your gallery")
                    .setContentText(title + " · " + destination)
                    .setContentIntent(openAppIntent())
                    .setAutoCancel(true);
            if (saveResult != null && saveResult.mediaUri != null && saveResult.mediaUri.startsWith("content://")) {
                try {
                    Uri contentUri = Uri.parse(saveResult.mediaUri);
                    String shareTitle = (title != null && !title.isEmpty()) ? title : "Share media";
                    Intent shareIntent = new Intent(Intent.ACTION_SEND);
                    shareIntent.setType(saveResult.mimeType != null && !saveResult.mimeType.isEmpty()
                            ? saveResult.mimeType : "video/mp4");
                    shareIntent.putExtra(Intent.EXTRA_STREAM, contentUri);
                    shareIntent.putExtra(Intent.EXTRA_SUBJECT, shareTitle);
                    shareIntent.setClipData(ClipData.newUri(getContentResolver(), shareTitle, contentUri));
                    shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    Intent chooser = Intent.createChooser(shareIntent, shareTitle);
                    chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    int reqCode = 9000 + (completionSeq % 4000);
                    PendingIntent sharePending = PendingIntent.getActivity(
                            this, reqCode, chooser,
                            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                    builder.addAction(0, "Share", sharePending);
                } catch (Exception ignored) {}
            }
            Notification n = builder.build();
            int id = FG_NOTIFICATION_ID + 1000 + (completionSeq++ % 4000);
            NotificationManagerCompat.from(this).notify(id, n);
        } catch (Exception ignored) {}
    }

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

    // ---------- Job progress rows ----------

    private void applyPhase(String jobId, String status, int percent) {
        JobProgress p = live.computeIfAbsent(jobId, k -> new JobProgress());
        p.status = status != null ? status : "Starting…";
        p.numbers = false;
        p.percent = Math.max(0, Math.min(100, percent));
        postJobRow(jobId, true);
    }

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

    private void postJobRow(String jobId, boolean force) {
        JobProgress p = live.get(jobId);
        if (p == null) return;
        long now = SystemClock.elapsedRealtime();
        if (!force && p.lastPercent == p.percent && (now - p.lastPostMs) < 800) return;
        p.lastPercent = p.percent;
        p.lastPostMs = now;
        try {
            Notification n = buildJobNotification(jobId, p);
            synchronized (FG_LOCK) {
                if (live.get(jobId) == null) return;
                if (jobId != null && jobId.equals(fgRowOwner)) {
                    int secId = jobNotificationId(jobId);
                    NotificationManagerCompat.from(this).cancel(secId);
                    postedSecondaryIds.remove(secId);
                    NotificationManagerCompat.from(this).notify(FG_NOTIFICATION_ID, n);
                } else {
                    int secId = jobNotificationId(jobId);
                    postedSecondaryIds.add(secId);
                    NotificationManagerCompat.from(this).notify(secId, n);
                }
            }
        } catch (Exception ignored) {}
        if (jobId != null && jobId.startsWith("drop")) writeDropSnapshot(jobId, "running", null, null);
    }

    private void writeDropSnapshot(String jobId, String state, String title, String destination) {
        writeDropSnapshot(jobId, state, title, destination, 0, null, null, null, null);
    }

    private void writeDropSnapshot(String jobId, String state, String title, String destination,
                                   long finalBytes, String error, String causeCode, String rawReason) {
        writeDropSnapshot(jobId, state, title, destination, finalBytes, error, causeCode, rawReason, null);
    }

    private void writeDropSnapshot(String jobId, String state, String title, String destination,
                                   long finalBytes, String error, String causeCode, String rawReason,
                                   MediaStoreSaver.SaveOutcome saveResult) {
        if (jobId == null || !jobId.startsWith("drop")) return;
        DowniNativeUtils.mutateJsonArrayPref(this, "dropLive", list -> {
            JobProgress p = live.get(jobId);
            long now = System.currentTimeMillis();
            JSONObject entry = null;
            for (int i = 0; i < list.length(); i++) {
                JSONObject o = list.optJSONObject(i);
                if (o != null && jobId.equals(o.optString("id"))) {
                    entry = o;
                    break;
                }
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

            long downloaded = p != null ? p.downloaded : 0;
            long total = p != null ? p.total : 0;
            double speedBps = p != null ? p.speedBps : 0;
            long etaSec = p != null ? p.etaSec : 0;

            if (finalBytes > 0) {
                downloaded = finalBytes;
                total = finalBytes;
                entry.put("pct", 100);
            }
            entry.put("downloaded", downloaded);
            entry.put("total", total);
            entry.put("speed", speedBps);
            entry.put("eta", etaSec);
            entry.put("sizeText", total > 0
                    ? DowniNativeUtils.formatBytes(downloaded) + " / " + DowniNativeUtils.formatBytes(total)
                    : (downloaded > 0 ? DowniNativeUtils.formatBytes(downloaded) : ""));
            entry.put("speedText", DowniNativeUtils.formatSpeed(speedBps));
            entry.put("etaText", DowniNativeUtils.formatEta(etaSec));
            entry.put("status", p != null && p.status != null ? p.status : "");
            entry.put("state", state);
            if (title != null) entry.put("title", title);
            if (destination != null) entry.put("dest", destination);
            if (saveResult != null) {
                entry.put("mediaId", saveResult.mediaId);
                entry.put("mediaUri", saveResult.mediaUri);
                entry.put("mimeType", saveResult.mimeType);
                entry.put("isVideo", saveResult.video);
            }
            if (error != null && !error.isEmpty()) {
                entry.put("error", error);
                JSONObject lastErr = new JSONObject();
                lastErr.put("cause", causeCode != null ? causeCode : "unknown");
                lastErr.put("friendly", error);
                lastErr.put("raw", rawReason != null ? rawReason : error);
                entry.put("lastError", lastErr);
            }
            if (p != null && p.shieldMode != null) {
                entry.put("shieldMode", p.shieldMode);
                entry.put("shield_mode", p.shieldMode);
            }
            if (p != null && p.shieldReason != null) {
                entry.put("shieldReason", p.shieldReason);
                entry.put("shield_reason", p.shieldReason);
            }
            if (p != null && p.shieldInputSha != null && !p.shieldInputSha.isEmpty()) {
                entry.put("shieldInputSha", p.shieldInputSha);
            }
            if (p != null && p.shieldSha != null && !p.shieldSha.isEmpty()) {
                entry.put("shieldSha", p.shieldSha);
                entry.put("shield_sha", p.shieldSha);
            }
            entry.put("ts", now);

            JSONArray kept = new JSONArray();
            for (int i = 0; i < list.length(); i++) {
                JSONObject o = list.optJSONObject(i);
                if (o == null) continue;
                boolean running = "running".equals(o.optString("state"));
                if (!running && now - o.optLong("ts", now) > JobSnapshot.TERMINAL_TTL_MS) continue;
                kept.put(o);
            }
            JSONArray capped = new JSONArray();
            int start = Math.max(0, kept.length() - JobSnapshot.MAX_ROWS);
            for (int i = start; i < kept.length(); i++) capped.put(kept.optJSONObject(i));
            return capped;
        });
    }

    private void releaseJobRow(String jobId) {
        if (jobId == null) return;
        try {
            synchronized (FG_LOCK) {
                int secId = jobNotificationId(jobId);
                NotificationManagerCompat.from(this).cancel(secId);
                postedSecondaryIds.remove(secId);
                if (jobId.equals(fgRowOwner)) {
                    fgRowOwner = null;
                    String next = null;
                    for (String id : live.keySet()) {
                        if (id != null) {
                            next = id;
                            break;
                        }
                    }
                    if (next == null) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            stopForeground(STOP_FOREGROUND_REMOVE);
                        } else {
                            stopForeground(true);
                        }
                        NotificationManagerCompat.from(this).cancel(FG_NOTIFICATION_ID);
                    } else {
                        adoptForegroundRowLocked(next);
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private void adoptForegroundRowLocked(String jobId) {
        JobProgress p = live.get(jobId);
        if (p == null) return;
        try {
            fgRowOwner = jobId;
            NotificationManagerCompat.from(this).cancel(jobNotificationId(jobId));
            NotificationManagerCompat.from(this).notify(FG_NOTIFICATION_ID, buildJobNotification(jobId, p));
        } catch (Exception ignored) {}
    }

    /** Maps to [FG_NOTIFICATION_ID+1 .. FG_NOTIFICATION_ID+999], avoiding FG_NOTIFICATION_ID+1000 completions. */
    static int jobNotificationId(String jobId) {
        int hash = (jobId != null ? jobId.hashCode() : 0) & 0x7FFFFFFF;
        return FG_NOTIFICATION_ID + 1 + (hash % 999);
    }

    private static String progressLine(JobProgress p) {
        if (p.numbers && p.total > 0) {
            return p.percent + "% · " + DowniNativeUtils.formatBytes(p.downloaded) + " / " + DowniNativeUtils.formatBytes(p.total);
        }
        if (p.numbers && p.downloaded > 0) return p.percent + "% · " + DowniNativeUtils.formatBytes(p.downloaded);
        if (p.status != null) return p.status;
        return "Starting…";
    }

    private static String progressBigText(String jobId, JobProgress p) {
        StringBuilder sb = new StringBuilder();
        if (jobId != null && jobId.startsWith("drop")) sb.append("⚡ DowniDrop · ");
        sb.append(p.percent).append("% done");
        if (p.numbers && p.total > 0) {
            sb.append(" · ").append(DowniNativeUtils.formatBytes(p.downloaded)).append(" / ").append(DowniNativeUtils.formatBytes(p.total));
        } else if (p.numbers && p.downloaded > 0) {
            sb.append(" · ").append(DowniNativeUtils.formatBytes(p.downloaded));
        }
        String speed = DowniNativeUtils.formatSpeed(p.speedBps);
        if (!speed.isEmpty()) sb.append(" · ").append(speed);
        if (p.etaSec > 0) sb.append(" · ").append(p.etaSec).append("s left");
        return sb.toString();
    }

    private Notification buildJobNotification(String jobId, JobProgress p) {
        boolean determinate = p.percent > 0 || (p.numbers && p.total > 0);
        String speed = DowniNativeUtils.formatSpeed(p.speedBps);
        String notifTitle = (p.title != null && !p.title.trim().isEmpty()) ? p.title.trim() : "DOWNI is grabbing";
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_PROGRESS)
                .setSmallIcon(R.drawable.ic_stat_downi)
                .setColor(ACCENT_COLOR)
                .setContentTitle(notifTitle)
                .setContentText(progressLine(p))
                .setStyle(new NotificationCompat.BigTextStyle().bigText(progressBigText(jobId, p)))
                .setProgress(100, Math.max(0, Math.min(100, p.percent)), !determinate)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setShowWhen(false)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setContentIntent(openAppIntent());
        if (!speed.isEmpty()) builder.setSubText(speed);
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
        open.putExtra("openQueue", true);
        return PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private void maybeStop() {
        if (activeJobs.isEmpty()) {
            live.clear();
            synchronized (FG_LOCK) {
                fgRowOwner = null;
            }
            releaseLocks();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
            try {
                NotificationManagerCompat nm = NotificationManagerCompat.from(this);
                nm.cancel(FG_NOTIFICATION_ID);
                for (Integer secId : postedSecondaryIds) {
                    if (secId != null) nm.cancel(secId);
                }
                postedSecondaryIds.clear();
            } catch (Exception ignored) {}
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
            progress.setShowBadge(false);
            progress.enableVibration(false);
            progress.setSound(null, null);
            nm.createNotificationChannel(progress);
            NotificationChannel alerts = new NotificationChannel(
                    CHANNEL_ALERTS, "DOWNI completions", NotificationManager.IMPORTANCE_DEFAULT);
            alerts.setDescription("Finished grabs land here with a sound");
            alerts.setShowBadge(true);
            nm.createNotificationChannel(alerts);
        }
    }
}
