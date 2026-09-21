package com.omnidownloader.app;

import android.Manifest;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.content.ContentValues;
import android.content.Intent;
import android.provider.DocumentsContract;
import android.util.Base64;
import android.webkit.MimeTypeMap;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import com.getcapacitor.PermissionState;
import androidx.activity.result.ActivityResult;
import androidx.core.content.FileProvider;
import com.getcapacitor.annotation.ActivityCallback;
import com.chaquo.python.android.AndroidPlatform;
import com.chaquo.python.Python;
import com.chaquo.python.PyObject;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * DOWNI native bridge — V2.5 ELITE.
 * Parallel download queue (3 simultaneous, more auto-queued), per-job progress
 * and cancel, true in-app updater, engine health check, Media Vault.
 */
@CapacitorPlugin(name = "OmniEngine", permissions = {
    @Permission(alias = "notifications", strings = { Manifest.permission.POST_NOTIFICATIONS })
})
public class OmniEnginePlugin extends Plugin {
    private static final int MAX_ACTIVE = 3;
    private static final int MAX_QUEUED = 6;

    private DownloadManager downloadManager;
    private final ExecutorService enginePool = Executors.newFixedThreadPool(MAX_ACTIVE);
    private final ExecutorService miscExecutor = Executors.newFixedThreadPool(3);
    private final Map<String, DownloadJob> jobs = new ConcurrentHashMap<>();
    private volatile boolean updateCancelled = false;

    /** One queued/active download. */
    private static class DownloadJob {
        final String id;
        final PluginCall call;
        final String url;
        final String formatId;
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        DownloadJob(String id, PluginCall call, String url, String formatId) {
            this.id = id;
            this.call = call;
            this.url = url;
            this.formatId = formatId;
        }
    }

    @PluginMethod
    public void chooseFolder(PluginCall call) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(call, intent, "folderPickerCallback");
    }

    @ActivityCallback
    private void folderPickerCallback(PluginCall call, ActivityResult result) {
        if (result.getResultCode() != android.app.Activity.RESULT_OK || result.getData() == null || result.getData().getData() == null) {
            call.reject("Folder selection cancelled");
            return;
        }
        Uri tree = result.getData().getData();
        try {
            getContext().getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            getContext().getSharedPreferences("omni_settings", Context.MODE_PRIVATE).edit().putString("treeUri", tree.toString()).apply();
            JSObject response = new JSObject();
            response.put("folder", "Custom folder");
            response.put("uri", tree.toString());
            call.resolve(response);
        } catch (Exception error) {
            call.reject("Could not save the selected folder", error);
        }
    }

    @Override
    public void load() {
        downloadManager = (DownloadManager) getContext().getSystemService(Context.DOWNLOAD_SERVICE);
    }

    @PluginMethod
    public void getClipboardText(PluginCall call) {
        ClipboardManager clipboard = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        String text = "";
        if (clipboard != null && clipboard.hasPrimaryClip()) {
            ClipData clip = clipboard.getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0) {
                CharSequence value = clip.getItemAt(0).coerceToText(getContext());
                if (value != null) text = value.toString();
            }
        }
        JSObject result = new JSObject();
        result.put("text", text);
        call.resolve(result);
    }

    @PluginMethod
    public void getAppInfo(PluginCall call) {
        JSObject result = new JSObject();
        try {
            PackageInfo pInfo = getContext().getPackageManager().getPackageInfo(getContext().getPackageName(), 0);
            result.put("versionName", pInfo.versionName);
            result.put("versionCode", Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? pInfo.getLongVersionCode() : pInfo.versionCode);
            result.put("packageName", getContext().getPackageName());
            result.put("engine", "DOWNI Engine (Chaquopy 3.11 + yt-dlp)");
            call.resolve(result);
        } catch (Exception e) {
            result.put("versionName", "2.5.0");
            result.put("versionCode", 25);
            call.resolve(result);
        }
    }

    @PluginMethod
    public void installApk(PluginCall call) {
        String filePath = call.getString("path");
        if (filePath == null || filePath.trim().isEmpty()) {
            call.reject("APK file path cannot be empty.");
            return;
        }
        File apkFile = new File(filePath);
        if (!apkFile.exists()) {
            call.reject("APK file does not exist at " + filePath);
            return;
        }

        try {
            Uri apkUri = FileProvider.getUriForFile(getContext(), getContext().getPackageName() + ".fileprovider", apkFile);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(intent);
            call.resolve();
        } catch (Exception e) {
            call.reject("Could not launch package installer: " + e.getMessage(), e);
        }
    }

    // ---------- In-App Updater: download APK with live progress ----------

    @PluginMethod
    public void downloadUpdate(PluginCall call) {
        final String fileUrl = call.getString("url", "");
        if (fileUrl == null || !fileUrl.startsWith("http")) {
            call.reject("Invalid update URL.");
            return;
        }
        if (updateCancelled) updateCancelled = false;
        call.setKeepAlive(true);

        miscExecutor.execute(() -> {
            File destDir = getContext().getExternalFilesDir("updates");
            if (destDir == null) destDir = getContext().getFilesDir();
            if (!destDir.exists()) destDir.mkdirs();
            final File destFile = new File(destDir, "omni-update.apk");
            long lastEvent = 0;

            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(fileUrl).openConnection();
                conn.setInstanceFollowRedirects(true);
                conn.setConnectTimeout(20000);
                conn.setReadTimeout(30000);
                conn.setRequestProperty("User-Agent", "DOWNI/" + getAppVersionName());
                conn.connect();

                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) {
                    call.reject("Update server returned HTTP " + code);
                    return;
                }

                long total = conn.getContentLength();
                InputStream input = conn.getInputStream();
                OutputStream output = new java.io.FileOutputStream(destFile);
                byte[] buffer = new byte[64 * 1024];
                long downloaded = 0;
                long start = System.currentTimeMillis();

                try {
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        if (updateCancelled) {
                            call.reject("Update download cancelled.");
                            return;
                        }
                        output.write(buffer, 0, read);
                        downloaded += read;
                        long now = System.currentTimeMillis();
                        if (now - lastEvent > 250) {
                            lastEvent = now;
                            long elapsed = Math.max(1, now - start);
                            double speed = downloaded / (elapsed / 1000.0);
                            int percent = total > 0 ? (int) (downloaded * 100 / total) : 0;
                            JSObject progress = new JSObject();
                            progress.put("percent", percent);
                            progress.put("downloadedBytes", downloaded);
                            progress.put("totalBytes", total);
                            progress.put("indeterminate", total <= 0);
                            progress.put("sizeFormatted", formatBytes(downloaded) + (total > 0 ? " / " + formatBytes(total) : ""));
                            progress.put("speedFormatted", formatSpeed(speed));
                            notifyListeners("onUpdateProgress", progress);
                        }
                    }
                } finally {
                    try { input.close(); } catch (Exception ignored) {}
                    try { output.close(); } catch (Exception ignored) {}
                    conn.disconnect();
                }

                if (destFile.length() <= 0) {
                    call.reject("Downloaded update file is empty.");
                    return;
                }

                JSObject result = new JSObject();
                result.put("path", destFile.getAbsolutePath());
                result.put("size", destFile.length());
                call.resolve(result);
            } catch (Exception error) {
                if (updateCancelled) {
                    call.reject("Update download cancelled.");
                } else {
                    call.reject("Update download failed: " + (error.getMessage() != null ? error.getMessage() : "network error"), error);
                }
            }
        });
    }

    @PluginMethod
    public void cancelUpdateDownload(PluginCall call) {
        updateCancelled = true;
        call.resolve();
    }

    // ---------- Engine health check ----------

    @PluginMethod
    public void engineInfo(PluginCall call) {
        miscExecutor.execute(() -> {
            try {
                if (!Python.isStarted()) Python.start(new AndroidPlatform(getContext()));
                PyObject response = Python.getInstance().getModule("downloader").callAttr("engine_info");
                JSONObject info = new JSONObject(response.toString());
                JSObject result = new JSObject();
                result.put("engine", info.optString("engine", "DOWNI Engine"));
                result.put("ytDlpVersion", info.optString("yt_dlp_version", ""));
                result.put("pythonVersion", info.optString("python", ""));
                result.put("healthy", true);
                call.resolve(result);
            } catch (Exception error) {
                call.reject("Engine check failed: " + error.getMessage(), error);
            }
        });
    }

    // ---------- Media Vault (real MediaStore library) ----------

    @PluginMethod
    public void listMedia(PluginCall call) {
        miscExecutor.execute(() -> {
            JSObject result = new JSObject();
            result.put("videos", queryMedia(true));
            result.put("audios", queryMedia(false));
            call.resolve(result);
        });
    }

    private JSONArray queryMedia(boolean video) {
        JSONArray items = new JSONArray();
        Uri collection = video ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        String[] projection = {
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.MIME_TYPE
        };
        try (Cursor cursor = getContext().getContentResolver().query(
                collection, projection, null, null,
                MediaStore.MediaColumns.DATE_MODIFIED + " DESC")) {
            if (cursor == null) return items;
            int count = 0;
            while (cursor.moveToNext() && count < 300) {
                try {
                    JSONObject item = new JSONObject();
                    item.put("id", cursor.getLong(0));
                    item.put("name", cursor.getString(1) != null ? cursor.getString(1) : "Media");
                    item.put("size", cursor.getLong(2));
                    item.put("dateModified", cursor.getLong(3));
                    String mime = cursor.getString(4);
                    item.put("mime", mime != null ? mime : (video ? "video/mp4" : "audio/mp4"));
                    item.put("isVideo", video);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        String rel = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH));
                        item.put("folder", rel != null ? rel.replace("/", " ").trim() : "");
                    } else {
                        item.put("folder", video ? "Movies" : "Music");
                    }
                    items.put(item);
                    count++;
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return items;
    }

    @PluginMethod
    public void getThumbnail(PluginCall call) {
        Double idVal = call.getDouble("id");
        long id = idVal == null ? 0L : idVal.longValue();
        boolean isVideo = call.getBoolean("isVideo", true);
        miscExecutor.execute(() -> {
            JSObject result = new JSObject();
            result.put("data", thumbnailBase64(id, isVideo));
            call.resolve(result);
        });
    }

    private String thumbnailBase64(long id, boolean isVideo) {
        try {
            Uri uri = Uri.withAppendedPath(
                isVideo ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                String.valueOf(id));
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                retriever.setDataSource(getContext(), uri);
                Bitmap frame = null;
                if (isVideo) {
                    frame = retriever.getFrameAtTime(500_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                    if (frame == null) frame = retriever.getFrameAtTime(0);
                } else {
                    byte[] art = retriever.getEmbeddedPicture();
                    if (art != null) frame = BitmapFactory.decodeByteArray(art, 0, art.length);
                }
                if (frame == null) return "";
                if (frame.getWidth() > 512) {
                    float scale = 512f / frame.getWidth();
                    frame = Bitmap.createScaledBitmap(frame, 512, Math.max(1, (int) (frame.getHeight() * scale)), true);
                }
                java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                frame.compress(Bitmap.CompressFormat.JPEG, 70, bytes);
                frame.recycle();
                return Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP);
            } finally {
                try { retriever.release(); } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            return "";
        }
    }

    @PluginMethod
    public void getMediaStreamUrl(PluginCall call) {
        Double idVal = call.getDouble("id");
        long id = idVal == null ? 0L : idVal.longValue();
        boolean isVideo = call.getBoolean("isVideo", true);
        try {
            int port = MediaStreamServer.get(getContext()).start();
            JSObject result = new JSObject();
            result.put("url", "http://127.0.0.1:" + port + "/media/" + id + "?type=" + (isVideo ? "video" : "audio"));
            call.resolve(result);
        } catch (Exception e) {
            call.reject("Could not start the in-app player service.", e);
        }
    }

    @PluginMethod
    public void openMedia(PluginCall call) {
        Double idVal = call.getDouble("id");
        long id = idVal == null ? 0L : idVal.longValue();
        boolean isVideo = call.getBoolean("isVideo", true);
        try {
            Uri uri = Uri.withAppendedPath(
                isVideo ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                String.valueOf(id));
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, isVideo ? "video/*" : "audio/*");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(intent);
            call.resolve();
        } catch (Exception e) {
            call.reject("No app found to play this media.", e);
        }
    }

    @PluginMethod
    public void shareMedia(PluginCall call) {
        Double idVal = call.getDouble("id");
        long id = idVal == null ? 0L : idVal.longValue();
        boolean isVideo = call.getBoolean("isVideo", true);
        try {
            Uri uri = Uri.withAppendedPath(
                isVideo ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                String.valueOf(id));
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType(isVideo ? "video/*" : "audio/*");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Intent chooser = Intent.createChooser(intent, "Share Media");
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(chooser);
            call.resolve();
        } catch (Exception e) {
            call.reject("Could not share this media.", e);
        }
    }

    @PluginMethod
    public void deleteMedia(PluginCall call) {
        Double idVal = call.getDouble("id");
        long id = idVal == null ? 0L : idVal.longValue();
        boolean isVideo = call.getBoolean("isVideo", true);
        try {
            Uri uri = Uri.withAppendedPath(
                isVideo ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                String.valueOf(id));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.app.PendingIntent pi = MediaStore.createDeleteRequest(getContext().getContentResolver(), java.util.Collections.singletonList(uri));
                getActivity().startIntentSenderForResult(pi.getIntentSender(), 10291, null, 0, 0, 0);
                JSObject result = new JSObject();
                result.put("requested", true);
                call.resolve(result);
            } else {
                int rows = getContext().getContentResolver().delete(uri, null, null);
                JSObject result = new JSObject();
                result.put("deleted", rows > 0);
                call.resolve(result);
            }
        } catch (Exception e) {
            call.reject("Could not delete this media from storage.", e);
        }
    }

    // ---------- Vault file listing ----------
    @PluginMethod
    public void listVaultFiles(PluginCall call) {
        miscExecutor.execute(() -> {
            File vaultDir = getContext().getExternalFilesDir(null);
            JSObject result = new JSObject();
            JSONArray items = new JSONArray();
            if (vaultDir != null && vaultDir.isDirectory()) {
                File[] files = vaultDir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        try {
                            JSONObject obj = new JSONObject();
                            obj.put("path", f.getAbsolutePath());
                            obj.put("name", f.getName());
                            obj.put("size", f.length());
                            String lower = f.getName().toLowerCase();
                            obj.put("isVideo", lower.endsWith(".mp4") || lower.endsWith(".webm") || lower.endsWith(".mkv"));
                            items.put(obj);
                        } catch (Exception ignored) {}
                    }
                }
            }
            result.put("files", items);
            call.resolve(result);
        });
    }

    // ---------- Vault file deletion ----------
    @PluginMethod
    public void removeVaultFile(PluginCall call) {
        String path = call.getString("path");
        if (path == null) {
            call.reject("Path is required");
            return;
        }
        miscExecutor.execute(() -> {
            File f = new File(path);
            JSObject result = new JSObject();
            if (f.exists() && f.delete()) {
                result.put("deleted", true);
                call.resolve(result);
            } else {
                result.put("deleted", false);
                call.reject("Failed to delete file");
            }
        });
    }

    @PluginMethod
    public void requestNotificationPermission(PluginCall call) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || getPermissionState("notifications") == PermissionState.GRANTED) {
            JSObject result = new JSObject();
            result.put("granted", true);
            call.resolve(result);
            return;
        }
        requestPermissionForAlias("notifications", call, "notificationPermissionCallback");
    }

    @PermissionCallback
    private void notificationPermissionCallback(PluginCall call) {
        JSObject result = new JSObject();
        result.put("granted", getPermissionState("notifications") == PermissionState.GRANTED);
        call.resolve(result);
    }

    // ---------- Parallel download queue ----------

    @PluginMethod
    public void download(PluginCall call) {
        String rawUrl = call.getString("directUrl", "");
        if (rawUrl == null || rawUrl.trim().isEmpty()) rawUrl = call.getString("url", "");
        rawUrl = rawUrl == null ? "" : rawUrl.trim();
        Uri uri = Uri.parse(rawUrl);
        if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))) {
            call.reject("Paste a valid http or https media link.");
            return;
        }
        if (jobs.size() >= MAX_QUEUED) {
            call.reject("Queue is full. Wait for a download to finish or cancel one.");
            return;
        }
        String jobId = "dl" + System.currentTimeMillis() + (int) (Math.random() * 1000);
        DownloadJob job = new DownloadJob(jobId, call, rawUrl, call.getString("formatId", "best"));
        jobs.put(jobId, job);

        JSObject started = new JSObject();
        started.put("jobId", jobId);
        started.put("percent", 0);
        started.put("status", "Queued");
        started.put("queued", true);
        notifyListeners("onProgress", started);

        OmniDownloadService.startJob(getContext(), jobId, "Queued");
        call.setKeepAlive(true);
        enginePool.execute(() -> runJob(job));
    }

    @PluginMethod
    public void cancelDownload(PluginCall call) {
        String jobId = call.getString("jobId");
        if (jobId != null && !jobId.trim().isEmpty()) {
            DownloadJob job = jobs.remove(jobId);
            if (job != null) {
                job.cancelled.set(true);
                try { job.call.reject("Download cancelled."); } catch (Exception ignored) {}
                OmniDownloadService.finishJob(getContext(), jobId);
                JSObject progress = new JSObject();
                progress.put("jobId", jobId);
                progress.put("failed", true);
                progress.put("error", "Download cancelled.");
                notifyListeners("onProgress", progress);
            }
        } else {
            for (DownloadJob job : jobs.values()) {
                job.cancelled.set(true);
                try { job.call.reject("Download cancelled."); } catch (Exception ignored) {}
                OmniDownloadService.finishJob(getContext(), job.id);
            }
            jobs.clear();
        }
        call.resolve();
    }

    private void runJob(DownloadJob job) {
        File workDir = new File(new File(getContext().getCacheDir(), "OmniEngine"), job.id);
        try {
            if (job.cancelled.get()) return;
            if (!Python.isStarted()) Python.start(new AndroidPlatform(getContext()));

            OmniDownloadService.updateJob(getContext(), job.id, "Starting engine…", 1);
            DownloadProgressListener listener = new DownloadProgressListener() {
                @Override
                public void onProgress(double percent, long downloadedBytes, long totalBytes, double speedBytesPerSec, long etaSeconds) {
                    if (job.cancelled.get()) return;
                    JSObject progress = new JSObject();
                    progress.put("jobId", job.id);
                    progress.put("percent", (int) percent);
                    progress.put("downloadedBytes", downloadedBytes);
                    progress.put("totalBytes", totalBytes);
                    progress.put("speed", speedBytesPerSec);
                    progress.put("speedFormatted", formatSpeed(speedBytesPerSec));
                    progress.put("sizeFormatted", formatBytes(downloadedBytes) + (totalBytes > 0 ? " / " + formatBytes(totalBytes) : ""));
                    progress.put("eta", etaSeconds);
                    progress.put("etaFormatted", etaSeconds > 0 ? (etaSeconds + "s left") : "");
                    progress.put("status", "Downloading…");
                    notifyListeners("onProgress", progress);
                    OmniDownloadService.updateJob(getContext(), job.id,
                        progress.getString("sizeFormatted") + String.format(Locale.US, " (%.0f%%)", percent), (int) percent);
                }

                @Override
                public boolean isCancelled() {
                    return job.cancelled.get();
                }
            };

            PyObject response = Python.getInstance().getModule("downloader").callAttr("download", job.url, workDir.getAbsolutePath(), job.formatId, listener);
            if (job.cancelled.get()) return;

            JSONObject file = new JSONObject(response.toString());
            OmniDownloadService.updateJob(getContext(), job.id, "Saving to gallery…", 98);

            JSObject saving = new JSObject();
            saving.put("jobId", job.id);
            saving.put("percent", 98);
            saving.put("status", "Saving to gallery…");
            notifyListeners("onProgress", saving);

            String destination = copyToGallery(new File(file.getString("path")), file.getString("title"), file.getString("ext"));
            if (job.cancelled.get()) return;

            JSObject progress = new JSObject();
            progress.put("jobId", job.id);
            progress.put("percent", 100);
            progress.put("status", "Saved to your gallery");
            progress.put("complete", true);
            progress.put("title", file.optString("title", "Video"));
            progress.put("destination", destination);
            notifyListeners("onProgress", progress);

            OmniDownloadService.finishJob(getContext(), job.id);
            try { job.call.resolve(progress); } catch (Exception ignored) {}
        } catch (Exception error) {
            if (!job.cancelled.get()) {
                JSObject progress = new JSObject();
                progress.put("jobId", job.id);
                progress.put("failed", true);
                String detail = error.getMessage() == null ? "Unknown download error" : error.getMessage();
                progress.put("error", friendlyError(detail));
                notifyListeners("onProgress", progress);
                OmniDownloadService.finishJob(getContext(), job.id);
                try { job.call.reject(friendlyError(detail), error); } catch (Exception ignored) {}
            }
        } finally {
            jobs.remove(job.id);
            cleanDir(workDir);
        }
    }



    @PluginMethod
    public void extract(PluginCall call) {
        String url = call.getString("url", "").trim();
        Uri uri = Uri.parse(url);
        if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))) {
            call.reject("Paste a valid public video link.");
            return;
        }
        call.setKeepAlive(true);
        miscExecutor.execute(() -> {
            try {
                if (!Python.isStarted()) Python.start(new AndroidPlatform(getContext()));
                PyObject response = Python.getInstance().getModule("downloader").callAttr("inspect", url);
                JSONObject info = new JSONObject(response.toString());
                JSObject result = new JSObject();
                result.put("title", info.optString("title", "Video"));
                result.put("uploader", info.optString("uploader", ""));
                result.put("duration", info.optInt("duration", 0));
                result.put("thumbnail", info.optString("thumbnail", ""));
                result.put("url", info.optString("webpage_url", url));
                result.put("platform", info.optString("platform", "other"));

                JSONArray fmts = info.optJSONArray("formats");
                if (fmts != null) {
                    com.getcapacitor.JSArray jsFmts = new com.getcapacitor.JSArray();
                    for (int i = 0; i < fmts.length(); i++) {
                        JSONObject obj = fmts.getJSONObject(i);
                        JSObject item = new JSObject();
                        item.put("id", obj.optString("id", "best"));
                        item.put("label", obj.optString("label", "Standard"));
                        item.put("ext", obj.optString("ext", "mp4"));
                        item.put("badge", obj.optString("badge", "HD"));
                        jsFmts.put(item);
                    }
                    result.put("formats", jsFmts);
                }
                call.resolve(result);
            } catch (Exception error) {
                call.reject("This public link could not be inspected. It may be private, protected, or temporarily unsupported.", error);
            }
        });
    }

    @PluginMethod
    public void diagnose(PluginCall call) {
        call.setKeepAlive(true);
        miscExecutor.execute(() -> {
            try {
                if (!Python.isStarted()) Python.start(new AndroidPlatform(getContext()));
                PyObject response = Python.getInstance().getModule("downloader").callAttr("diagnose");
                JSONObject info = new JSONObject(response.toString());
                JSObject result = new JSObject();
                result.put("results", com.getcapacitor.JSArray.from(info.optJSONArray("results") != null ? info.optJSONArray("results") : new JSONArray()));
                result.put("ytDlp", info.optString("yt_dlp", ""));
                call.resolve(result);
            } catch (Exception error) {
                call.reject("Diagnostics failed: " + error.getMessage(), error);
            }
        });
    }

    @PluginMethod
    public void openGallery(PluginCall call) {
        android.content.Intent intent = new android.content.Intent(DownloadManager.ACTION_VIEW_DOWNLOADS);
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
        getContext().startActivity(intent);
        call.resolve();
    }

    @PluginMethod
    public void getSharedUrl(PluginCall call) {
        JSObject result = new JSObject();
        android.content.Intent intent = getActivity().getIntent();
        String shared = "";
        if (intent != null && android.content.Intent.ACTION_SEND.equals(intent.getAction())) {
            String value = intent.getStringExtra(android.content.Intent.EXTRA_TEXT);
            if (value != null) shared = value;
        }
        result.put("url", shared);
        call.resolve(result);
    }

    private void cleanDir(File dir) {
        try {
            if (dir != null && dir.exists()) {
                File[] files = dir.listFiles();
                if (files != null) for (File f : files) f.delete();
            }
        } catch (Exception ignored) {}
    }

    private String copyToGallery(File source, String title, String extension) {
        if (source == null || !source.exists()) {
            return "Downloaded file missing";
        }
        String displayName = nextGalleryName(title, extension, source);
        String ext = (extension == null || extension.trim().isEmpty()) ? "mp4" : extension.toLowerCase(Locale.US);
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        if (mime == null) mime = ext.startsWith("m4") || ext.equals("mp3") ? "audio/" + ext : "video/mp4";

        boolean saved = false;

        String treeUri = getContext().getSharedPreferences("omni_settings", Context.MODE_PRIVATE).getString("treeUri", "");
        if (treeUri != null && !treeUri.isEmpty()) {
            try {
                Uri tree = Uri.parse(treeUri);
                Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
                Uri destination = DocumentsContract.createDocument(getContext().getContentResolver(), parent, mime, displayName);
                if (destination != null) {
                    try (InputStream input = new FileInputStream(source); OutputStream output = getContext().getContentResolver().openOutputStream(destination)) {
                        if (output != null) {
                            byte[] buffer = new byte[64 * 1024];
                            for (int read; (read = input.read(buffer)) != -1;) output.write(buffer, 0, read);
                            saved = true;
                        }
                    }
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
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, mime.startsWith("audio/") ? Environment.DIRECTORY_MUSIC : Environment.DIRECTORY_MOVIES);
                values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            }

            Uri collection = mime.startsWith("audio/") ? MediaStore.Audio.Media.EXTERNAL_CONTENT_URI : MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
            Uri destination = getContext().getContentResolver().insert(collection, values);
            if (destination != null) {
                try (InputStream input = new FileInputStream(source); OutputStream output = getContext().getContentResolver().openOutputStream(destination)) {
                    if (output != null) {
                        byte[] buffer = new byte[64 * 1024];
                        for (int read; (read = input.read(buffer)) != -1;) output.write(buffer, 0, read);
                        saved = true;
                    }
                }
                if (saved && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentValues ready = new ContentValues();
                    ready.put(MediaStore.MediaColumns.IS_PENDING, 0);
                    getContext().getContentResolver().update(destination, ready, null, null);
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
                android.media.MediaScannerConnection.scanFile(getContext(), new String[]{destFile.getAbsolutePath()}, new String[]{mime}, null);
            } catch (Exception ignored) {
                saved = false;
            }
        }

        // Method 3: App External Files
        if (!saved) {
            try {
                File appDir = getContext().getExternalFilesDir(mime.startsWith("audio/") ? Environment.DIRECTORY_MUSIC : Environment.DIRECTORY_MOVIES);
                if (appDir != null) {
                    if (!appDir.exists()) appDir.mkdirs();
                    File destFile = new File(appDir, displayName);
                    try (InputStream input = new FileInputStream(source); OutputStream output = new java.io.FileOutputStream(destFile)) {
                        byte[] buffer = new byte[64 * 1024];
                        for (int read; (read = input.read(buffer)) != -1;) output.write(buffer, 0, read);
                        saved = true;
                    }
                    android.media.MediaScannerConnection.scanFile(getContext(), new String[]{destFile.getAbsolutePath()}, new String[]{mime}, null);
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
        android.content.SharedPreferences names = getContext().getSharedPreferences("omni_saved_names", Context.MODE_PRIVATE);
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
        String selection = MediaStore.MediaColumns.DISPLAY_NAME + "=?";
        try (Cursor cursor = getContext().getContentResolver().query(MediaStore.Files.getContentUri("external"), projection, selection, new String[]{displayName}, null)) {
            return cursor != null && cursor.moveToFirst();
        } catch (Exception ignored) {
            return false;
        }
    }

    private String friendlyError(String detail) {
        String lower = detail.toLowerCase(Locale.US);
        if (lower.contains("certificate") || lower.contains("ssl")) return "Secure connection failed. Check your internet, then retry.";
        if (lower.contains("private") || lower.contains("login") || lower.contains("sign in")) return "This video needs an account or is private. Try a public link.";
        if (lower.contains("unsupported") || lower.contains("no video formats")) return "This public link is not supported yet. Try another public video link.";
        return "Download failed: " + detail;
    }

    private String safeFileName(String requested, Uri uri) {
        String base = requested.replaceAll("[^a-zA-Z0-9._ -]", " ").trim();
        if (base.isEmpty()) base = "video";
        String path = uri.getPath() == null ? "" : uri.getPath();
        String ext = path.contains(".") ? path.substring(path.lastIndexOf('.')).toLowerCase(Locale.US) : ".mp4";
        if (!base.toLowerCase(Locale.US).endsWith(ext)) base += ext;
        return base.length() > 100 ? base.substring(0, 100 - ext.length()) + ext : base;
    }

    private String getAppVersionName() {
        try {
            PackageInfo pInfo = getContext().getPackageManager().getPackageInfo(getContext().getPackageName(), 0);
            return pInfo.versionName;
        } catch (Exception e) {
            return "2.5.0";
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
