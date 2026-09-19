package com.omnidownloader.app;

import android.app.DownloadManager;
import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Build;
import android.provider.MediaStore;
import android.content.ContentValues;
import android.content.Intent;
import android.provider.DocumentsContract;
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

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.Locale;

/**
 * Native download bridge for OmniDownloader V2.0.
 * Passes real-time download progress, file size, speed, and ETA to WebView.
 * Provides in-app APK installer and device info methods.
 */
@CapacitorPlugin(name = "OmniEngine", permissions = {
    @Permission(alias = "notifications", strings = { Manifest.permission.POST_NOTIFICATIONS })
})
public class OmniEnginePlugin extends Plugin {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private DownloadManager downloadManager;
    private long activeDownloadId = -1;
    private PluginCall activeCall;
    private Runnable progressWatcher;
    private final ExecutorService engineExecutor = Executors.newSingleThreadExecutor();
    private volatile boolean engineActive = false;
    private volatile boolean cancelRequested = false;

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
            result.put("engine", "Chaquopy 3.11 + yt-dlp");
            call.resolve(result);
        } catch (Exception e) {
            result.put("versionName", "2.0.0");
            result.put("versionCode", 20);
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
        if (activeDownloadId != -1 || engineActive) {
            call.reject("A download is already active. Wait for it to finish or cancel it first.");
            return;
        }
        downloadPublicPage(rawUrl, call, call.getString("formatId", "best"));
    }

    @PluginMethod
    public void cancelDownload(PluginCall call) {
        cancelRequested = true;
        if (engineActive) OmniDownloadService.finish(getContext(), false);
        if (activeDownloadId != -1) {
            downloadManager.remove(activeDownloadId);
            activeDownloadId = -1;
        }
        if (activeCall != null) {
            activeCall.reject("Download cancelled.");
            activeCall = null;
        }
        if (engineActive) {
            JSObject progress = new JSObject();
            progress.put("failed", true);
            progress.put("error", "Download cancelled.");
            notifyListeners("onProgress", progress);
        }
        stopWatcher();
        call.resolve();
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
        engineExecutor.execute(() -> {
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

                org.json.JSONArray fmts = info.optJSONArray("formats");
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

    private void downloadPublicPage(String url, PluginCall call, String formatId) {
        call.setKeepAlive(true);
        activeCall = call;
        engineActive = true;
        cancelRequested = false;

        try {
            OmniDownloadService.start(getContext(), "Connecting to video server…");
        } catch (Exception ignored) {}

        JSObject started = new JSObject();
        started.put("percent", 1);
        started.put("status", "Connecting to video server…");
        notifyListeners("onProgress", started);

        engineExecutor.execute(() -> {
            try {
                if (!Python.isStarted()) Python.start(new AndroidPlatform(getContext()));
                File workDir = new File(getContext().getCacheDir(), "OmniEngine");

                // Progress listener that streams progress directly to web UI
                DownloadProgressListener progressListener = new DownloadProgressListener() {
                    @Override
                    public void onProgress(double percent, long downloadedBytes, long totalBytes, double speedBytesPerSec, long etaSeconds) {
                        if (cancelRequested) return;
                        JSObject progress = new JSObject();
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
                        OmniDownloadService.update(progress.getString("sizeFormatted") + String.format(Locale.US, " (%.0f%%)", percent), (int) percent);
                    }
                };

                PyObject response = Python.getInstance().getModule("downloader").callAttr("download", url, workDir.getAbsolutePath(), formatId, progressListener);
                if (cancelRequested) return;

                JSONObject file = new JSONObject(response.toString());
                JSObject saving = new JSObject();
                saving.put("percent", 98);
                saving.put("status", "Saving to gallery…");
                notifyListeners("onProgress", saving);
                OmniDownloadService.update("Saving video to your gallery…", 98);

                String destination = copyToGallery(new File(file.getString("path")), file.getString("title"), file.getString("ext"));
                if (cancelRequested) return;

                JSObject progress = new JSObject();
                progress.put("percent", 100);
                progress.put("status", "Saved to your gallery");
                progress.put("complete", true);
                progress.put("title", file.optString("title", "Video"));
                progress.put("destination", destination);
                notifyListeners("onProgress", progress);

                OmniDownloadService.finish(getContext(), true);
                if (activeCall != null) {
                    JSObject result = new JSObject();
                    result.put("destination", destination);
                    result.put("title", file.optString("title", "Video"));
                    activeCall.resolve(result);
                    activeCall = null;
                }
            } catch (Exception error) {
                if (!cancelRequested) {
                    JSObject progress = new JSObject();
                    progress.put("failed", true);
                    String detail = error.getMessage() == null ? "Unknown download error" : error.getMessage();
                    progress.put("error", friendlyError(detail));
                    notifyListeners("onProgress", progress);
                    if (activeCall != null) {
                        activeCall.reject(friendlyError(detail), error);
                        activeCall = null;
                    }
                    OmniDownloadService.finish(getContext(), false);
                }
            } finally {
                engineActive = false;
                cancelRequested = false;
            }
        });
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

    private void stopWatcher() {
        if (progressWatcher != null) handler.removeCallbacks(progressWatcher);
        progressWatcher = null;
    }

    private String safeFileName(String requested, Uri uri) {
        String base = requested.replaceAll("[^a-zA-Z0-9._ -]", " ").trim();
        if (base.isEmpty()) base = "video";
        String path = uri.getPath() == null ? "" : uri.getPath();
        String ext = path.contains(".") ? path.substring(path.lastIndexOf('.')).toLowerCase(Locale.US) : ".mp4";
        if (!base.toLowerCase(Locale.US).endsWith(ext)) base += ext;
        return base.length() > 100 ? base.substring(0, 100 - ext.length()) + ext : base;
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
