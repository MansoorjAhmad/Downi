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
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * DOWNI native bridge.
 * Parallel download queue (3 simultaneous, more auto-queued), per-job progress
 * and cancel, true in-app updater, engine health check, Media Vault.
 */
@CapacitorPlugin(name = "DowniEngine", permissions = {
    @Permission(alias = "notifications", strings = { Manifest.permission.POST_NOTIFICATIONS }),
    @Permission(alias = "mediaModern", strings = { Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO }),
    @Permission(alias = "mediaLegacy", strings = { Manifest.permission.READ_EXTERNAL_STORAGE })
})
public class DowniEnginePlugin extends Plugin {
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
    public void deleteMediaBatch(PluginCall call) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            call.reject("Bulk delete needs Android 11 or newer.");
            return;
        }
        try {
            com.getcapacitor.JSArray videoIds = call.getArray("videoIds");
            com.getcapacitor.JSArray audioIds = call.getArray("audioIds");
            java.util.List<Uri> uris = new java.util.ArrayList<>();
            if (videoIds != null) for (int i = 0; i < videoIds.length(); i++) {
                uris.add(Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, String.valueOf(videoIds.getLong(i))));
            }
            if (audioIds != null) for (int i = 0; i < audioIds.length(); i++) {
                uris.add(Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, String.valueOf(audioIds.getLong(i))));
            }
            if (uris.isEmpty()) {
                call.reject("Nothing selected.");
                return;
            }
            android.app.PendingIntent pi = MediaStore.createDeleteRequest(getContext().getContentResolver(), uris);
            getActivity().startIntentSenderForResult(pi.getIntentSender(), 10292, null, 0, 0, 0);
            JSObject result = new JSObject();
            result.put("requested", true);
            result.put("count", uris.size());
            call.resolve(result);
        } catch (Exception e) {
            call.reject("Storage refused the delete request.", e);
        }
    }

    @PluginMethod
    public void shareMediaBatch(PluginCall call) {
        try {
            com.getcapacitor.JSArray videoIds = call.getArray("videoIds");
            com.getcapacitor.JSArray audioIds = call.getArray("audioIds");
            java.util.ArrayList<Uri> uris = new java.util.ArrayList<>();
            boolean anyVideo = false;
            if (videoIds != null) for (int i = 0; i < videoIds.length(); i++) {
                uris.add(Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, String.valueOf(videoIds.getLong(i))));
                anyVideo = true;
            }
            if (audioIds != null) for (int i = 0; i < audioIds.length(); i++) {
                uris.add(Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, String.valueOf(audioIds.getLong(i))));
            }
            if (uris.isEmpty()) {
                call.reject("Nothing selected.");
                return;
            }
            Intent intent;
            if (uris.size() == 1) {
                intent = new Intent(Intent.ACTION_SEND);
                intent.putExtra(Intent.EXTRA_STREAM, uris.get(0));
            } else {
                intent = new Intent(Intent.ACTION_SEND_MULTIPLE);
                intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
            }
            intent.setType(anyVideo ? "video/*" : "audio/*");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Intent chooser = Intent.createChooser(intent, "Share media");
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(chooser);
            call.resolve();
        } catch (Exception e) {
            call.reject("Could not share these files.", e);
        }
    }

    @PluginMethod
    public void clearCache(PluginCall call) {
        miscExecutor.execute(() -> {
            long freed = 0;
            try {
                freed += deleteTree(new File(getContext().getCacheDir(), "DowniEngine"));
                freed += deleteTree(new File(getContext().getCacheDir(), "OmniEngine")); // legacy era
                freed += deleteTree(new File(getContext().getCacheDir(), "OmniDrop"));   // legacy era
            } catch (Exception ignored) {}
            JSObject result = new JSObject();
            result.put("freed", freed);
            call.resolve(result);
        });
    }

    private long deleteTree(File file) {
        long freed = 0;
        try {
            if (file == null || !file.exists()) return 0;
            if (file.isDirectory()) {
                File[] children = file.listFiles();
                if (children != null) for (File child : children) freed += deleteTree(child);
            } else {
                freed += file.length();
                file.delete();
            }
            if (file.isDirectory()) file.delete();
        } catch (Exception ignored) {}
        return freed;
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
            getContext().getSharedPreferences("downi_settings", Context.MODE_PRIVATE).edit().putString("treeUri", tree.toString()).apply();
            JSObject response = new JSObject();
            response.put("folder", "Custom folder");
            response.put("uri", tree.toString());
            call.resolve(response);
        } catch (Exception error) {
            call.reject("Could not save the selected folder", error);
        }
    }

    @PluginMethod
    public void getSaveLocation(PluginCall call) {
        String treeUri = getContext().getSharedPreferences("downi_settings", Context.MODE_PRIVATE).getString("treeUri", "");
        boolean custom = treeUri != null && !treeUri.isEmpty();
        JSObject result = new JSObject();
        result.put("custom", custom);
        result.put("label", custom ? "Custom folder" : "Gallery — Movies / Music");
        call.resolve(result);
    }

    @Override
    public void load() {
        downloadManager = (DownloadManager) getContext().getSystemService(Context.DOWNLOAD_SERVICE);

        // One-time identity migration: settings lived under "omni_settings".
        try {
            android.content.SharedPreferences next = getContext().getSharedPreferences("downi_settings", Context.MODE_PRIVATE);
            android.content.SharedPreferences legacy = getContext().getSharedPreferences("omni_settings", Context.MODE_PRIVATE);
            String legacyTree = legacy.getString("treeUri", "");
            if (next.getString("treeUri", "").isEmpty() && legacyTree != null && !legacyTree.isEmpty()) {
                next.edit().putString("treeUri", legacyTree).apply();
                legacy.edit().clear().apply();
            }
        } catch (Exception ignored) {}

        // Never let a stale update APK eat storage (B14) — the installer keeps
        // no state, so last session's file (either naming era) is pure junk now.
        try {
            File updates = getContext().getExternalFilesDir("updates");
            if (updates != null) {
                new File(updates, "downi-update.apk").delete();
                new File(updates, "omni-update.apk").delete();
            }
        } catch (Exception ignored) {}
    }

    // ---------- Custom save folder (SAF) ----------
    // Files written to a user-picked folder are not MediaStore rows, so the
    // Vault (which reads MediaStore) never showed them. List them straight
    // from the SAF tree so every download is visible and manageable.

    @PluginMethod
    public void listCustomFiles(PluginCall call) {
        miscExecutor.execute(() -> {
            com.getcapacitor.JSArray items = new com.getcapacitor.JSArray();
            String treeUri = getContext().getSharedPreferences("downi_settings", Context.MODE_PRIVATE).getString("treeUri", "");
            if (treeUri != null && !treeUri.isEmpty()) {
                try {
                    Uri tree = Uri.parse(treeUri);
                    Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
                    String[] projection = {
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_SIZE,
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED
                    };
                    try (Cursor c = getContext().getContentResolver().query(children, projection, null, null, null)) {
                        if (c != null) {
                            while (c.moveToNext()) {
                                try {
                                    String docId = c.getString(0);
                                    String name = c.getString(1) != null ? c.getString(1) : "";
                                    String mime = c.getString(2);
                                    long size = c.getLong(3);
                                    long modified = c.getLong(4);
                                    String lower = name.toLowerCase(Locale.US);
                                    boolean isVideo = (mime != null && mime.startsWith("video/")) || lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".webm") || lower.endsWith(".mov");
                                    boolean isAudio = (mime != null && mime.startsWith("audio/")) || lower.endsWith(".mp3") || lower.endsWith(".m4a") || lower.endsWith(".wav") || lower.endsWith(".ogg");
                                    if (!isVideo && !isAudio) continue;
                                    Uri docUri = DocumentsContract.buildDocumentUriUsingTree(tree, docId);
                                    JSObject obj = new JSObject();
                                    obj.put("uri", docUri.toString());
                                    obj.put("name", name);
                                    obj.put("size", size);
                                    obj.put("modified", modified);
                                    obj.put("mime", mime != null ? mime : (isVideo ? "video/mp4" : "audio/mp4"));
                                    obj.put("isVideo", isVideo);
                                    items.put(obj);
                                } catch (Exception ignored) {}
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }
            JSObject result = new JSObject();
            result.put("files", items);
            call.resolve(result);
        });
    }

    @PluginMethod
    public void openCustomFile(PluginCall call) {
        String uri = call.getString("uri");
        String mime = call.getString("mime", "video/mp4");
        if (uri == null || uri.isEmpty()) {
            call.reject("File reference missing.");
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(Uri.parse(uri), mime);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(intent);
            call.resolve();
        } catch (Exception e) {
            call.reject("No app found to play this file.", e);
        }
    }

    @PluginMethod
    public void shareCustomFile(PluginCall call) {
        String uri = call.getString("uri");
        String mime = call.getString("mime", "video/mp4");
        if (uri == null || uri.isEmpty()) {
            call.reject("File reference missing.");
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType(mime);
            intent.putExtra(Intent.EXTRA_STREAM, Uri.parse(uri));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Intent chooser = Intent.createChooser(intent, "Share media");
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(chooser);
            call.resolve();
        } catch (Exception e) {
            call.reject("Could not share this file.", e);
        }
    }

    @PluginMethod
    public void deleteCustomFile(PluginCall call) {
        String uri = call.getString("uri");
        if (uri == null || uri.isEmpty()) {
            call.reject("File reference missing.");
            return;
        }
        miscExecutor.execute(() -> {
            try {
                boolean ok = DocumentsContract.deleteDocument(getContext().getContentResolver(), Uri.parse(uri));
                if (ok) {
                    JSObject result = new JSObject();
                    result.put("deleted", true);
                    call.resolve(result);
                } else {
                    call.reject("Storage refused the delete.");
                }
            } catch (Exception e) {
                call.reject("Storage refused the delete.", e);
            }
        });
    }

    @PluginMethod
    public void rescanStorage(PluginCall call) {
        miscExecutor.execute(() -> {
            try {
                String[] dirs = {
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES).getAbsolutePath(),
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC).getAbsolutePath(),
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getAbsolutePath()
                };
                android.media.MediaScannerConnection.scanFile(getContext(), dirs, null, null);
            } catch (Exception ignored) {}
            JSObject result = new JSObject();
            result.put("started", true);
            call.resolve(result);
        });
    }

    @PluginMethod
    public void playlistInspect(PluginCall call) {
        String url = call.getString("url", "").trim();
        if (url.isEmpty()) {
            call.reject("Paste a playlist link.");
            return;
        }
        call.setKeepAlive(true);
        miscExecutor.execute(() -> {
            try {
                if (!Python.isStarted()) Python.start(new AndroidPlatform(getContext()));
                PyObject response = Python.getInstance().getModule("downloader").callAttr("playlist_inspect", url);
                if (response == null || "None".equals(response.toString())) {
                    call.reject("No playlist found on that link.");
                    return;
                }
                JSONObject info = new JSONObject(response.toString());
                JSObject result = new JSObject();
                result.put("count", info.optInt("count", 0));
                com.getcapacitor.JSArray list = new com.getcapacitor.JSArray();
                JSONArray items = info.optJSONArray("items");
                if (items != null) {
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject obj = items.getJSONObject(i);
                        JSObject item = new JSObject();
                        item.put("url", obj.optString("url"));
                        item.put("title", obj.optString("title", "Video"));
                        item.put("duration", obj.optInt("duration", 0));
                        list.put(item);
                    }
                }
                result.put("items", list);
                call.resolve(result);
            } catch (Exception error) {
                call.reject("Could not read that playlist.", error);
            }
        });
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
            // Defect C (v3.1.1): THIS SPOT MUST MATCH build.gradle versionCode/versionName —
            // it is one of the three version spots bumped at release (see MISSION §J).
            result.put("versionName", "3.1.0");
            result.put("versionCode", 45L);
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !getContext().getPackageManager().canRequestPackageInstalls()) {
            call.reject("Install unknown apps permission required — allow it for DOWNI in system settings, then retry.");
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
            final File destFile = new File(destDir, "downi-update.apk");
            long lastEvent = 0;

            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(fileUrl).openConnection();
                conn.setInstanceFollowRedirects(true);
                conn.setConnectTimeout(20000);
                conn.setReadTimeout(30000);
                conn.setRequestProperty("User-Agent", "DOWNI-Android/" + getAppVersionName());
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
        // Kill the partial file too — a cancelled update must not leak storage (B14).
        try {
            File updates = getContext().getExternalFilesDir("updates");
            if (updates != null) new File(updates, "downi-update.apk").delete();
        } catch (Exception ignored) {}
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
        // RELATIVE_PATH exists only on API 29+, and ONLY when it is part of the
        // projection: getColumnIndexOrThrow() on a column that was not projected
        // throws, and the old per-row catch silently skipped every item — so the
        // Vault listed nothing on Android 10+ (found on-device: empty Vault).
        String[] projection;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            projection = new String[]{
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.RELATIVE_PATH,
            };
        } else {
            projection = new String[]{
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.MIME_TYPE,
            };
        }
        // The Vault shows ONLY what DOWNI downloaded — never the whole gallery.
        java.util.HashSet<Long> seen = new java.util.HashSet<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            String rootDir = video ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_MUSIC;
            // 1) Everything inside DOWNI's own folder (Movies/DOWNI/, Music/DOWNI/).
            collectMedia(items, collection, projection,
                MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?",
                new String[]{rootDir + "/DOWNI/%"}, video, seen, false);
            // 2) Legacy rows saved at the collection root (Movies/, Music/) before
            //    DOWNI had its own folder — matched against names this app saved,
            //    so existing users' downloads don't vanish after the update.
            collectMedia(items, collection, projection,
                MediaStore.MediaColumns.RELATIVE_PATH + " = ?",
                new String[]{rootDir + "/"}, video, seen, true);
        } else {
            // Pre-Android 10 has no RELATIVE_PATH: tracked names only.
            collectMedia(items, collection, projection, null, null, video, seen, true);
        }
        return items;
    }

    private void collectMedia(JSONArray items, Uri collection, String[] projection,
                              String selection, String[] args, boolean video,
                              java.util.Set<Long> seen, boolean trackedOnly) {
        try (Cursor cursor = getContext().getContentResolver().query(
                collection, projection, selection, args,
                MediaStore.MediaColumns.DATE_MODIFIED + " DESC")) {
            if (cursor == null) return;
            int count = 0;
            while (cursor.moveToNext() && count < 300) {
                try {
                    long id = cursor.getLong(0);
                    if (seen.contains(id)) continue;
                    String name = cursor.getString(1);
                    if (trackedOnly && !isTrackedDowniName(name)) continue;
                    JSONObject item = new JSONObject();
                    item.put("id", id);
                    item.put("name", name != null ? name : "Media");
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
                    seen.add(id);
                    items.put(item);
                    count++;
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    /**
     * True when displayName is one this app saved. nextGalleryName() records every
     * base name ("title.ext", lowercased) in the downi_saved_names prefs; actual
     * files may carry a " (N)" suffix before the extension, which we strip first.
     */
    private boolean isTrackedDowniName(String displayName) {
        if (displayName == null) return false;
        String name = displayName.toLowerCase(Locale.US)
            .replaceAll("\\s*\\(\\d+\\)(?=\\.[^.]+$)", "");
        if (getContext().getSharedPreferences("downi_saved_names", Context.MODE_PRIVATE).contains(name)) return true;
        // Identity migration: names saved while the app was still OmniDownloader.
        return getContext().getSharedPreferences("omni_saved_names", Context.MODE_PRIVATE).contains(name);
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
    public void setNativeTheme(PluginCall call) {
        // v3.1.1: dark/AMOLED only (light theme removed) — the system bars are always dark.
        Boolean amoledVal = call.getBoolean("amoled", true);
        final boolean amoled = amoledVal == null || amoledVal;
        getActivity().runOnUiThread(() -> {
            try {
                android.view.Window window = getActivity().getWindow();
                // AMOLED true-black must reach the system bars too, not just the page (B17).
                window.setStatusBarColor(android.graphics.Color.parseColor(amoled ? "#000000" : "#060A13"));
                window.setNavigationBarColor(android.graphics.Color.parseColor(amoled ? "#000000" : "#0D1421"));
                window.getDecorView().setSystemUiVisibility(0);
            } catch (Exception ignored) {}
            call.resolve();
        });
    }

    @PluginMethod
    public void getMediaStreamUrl(PluginCall call) {
        Double idVal = call.getDouble("id");
        long id = idVal == null ? 0L : idVal.longValue();
        boolean isVideo = call.getBoolean("isVideo", true);
        try {
            int port = MediaStreamServer.get(getContext()).start();
            JSObject result = new JSObject();
            result.put("url", "http://127.0.0.1:" + port + "/media/" + id + "?type=" + (isVideo ? "video" : "audio") + "&t=" + MediaStreamServer.get(getContext()).getToken());
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

        DowniDownloadService.startJob(getContext(), jobId, "Queued");
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
                DowniDownloadService.finishJob(getContext(), jobId, false);
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
                DowniDownloadService.finishJob(getContext(), job.id, false);
            }
            jobs.clear();
        }
        call.resolve();
    }

    private void runJob(DownloadJob job) {
        File workDir = new File(new File(getContext().getCacheDir(), "DowniEngine"), job.id);
        try {
            if (job.cancelled.get()) return;
            if (!Python.isStarted()) Python.start(new AndroidPlatform(getContext()));

            DowniDownloadService.updateJob(getContext(), job.id, "Starting engine…", 1);
            DownloadProgressListener listener = new DownloadProgressListener() {
                @Override
                public void onProgress(double percent, long downloadedBytes, long totalBytes, double speedBytesPerSec, long etaSeconds) {
                    if (job.cancelled.get()) return;
                    JSObject progress = new JSObject();
                    progress.put("jobId", job.id);
                    progress.put("url", job.url); // lets the web layer match pending cards to the right job (B5)
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
                    // v3.1.1 (defects N1-N3): the notification row is fed the same numbers the app
                    // shows — % · size/total · speed · ETA — via the service's shared formatter.
                    DowniDownloadService.updateJobProgress(getContext(), job.id, (int) percent,
                        downloadedBytes, totalBytes, speedBytesPerSec, etaSeconds);
                }

                @Override
                public boolean isCancelled() {
                    return job.cancelled.get();
                }
            };

            PyObject response = Python.getInstance().getModule("downloader").callAttr("download", job.url, workDir.getAbsolutePath(), job.formatId, listener);
            if (job.cancelled.get()) return;

            JSONObject file = new JSONObject(response.toString());
            DowniDownloadService.updateJob(getContext(), job.id, "Saving to your Vault…", 98);

            JSObject saving = new JSObject();
            saving.put("jobId", job.id);
            saving.put("percent", 98);
            saving.put("status", "Saving to your Vault…");
            notifyListeners("onProgress", saving);

            String destination;
            if (file.optBoolean("merge", false)) {
                // True 1080p: separate video + audio streams muxed on-device.
                DowniDownloadService.updateJob(getContext(), job.id, "Merging video + audio…", 96);
                JSObject merging = new JSObject();
                merging.put("jobId", job.id);
                merging.put("percent", 96);
                merging.put("status", "Merging video + audio…");
                notifyListeners("onProgress", merging);

                File videoPart = new File(file.getString("video_path"));
                File audioPart = new File(file.getString("audio_path"));
                File merged = new File(videoPart.getParentFile(), file.getString("title") + "-merged.mp4");
                if (Mp4Merger.merge(videoPart, audioPart, merged)) {
                    destination = copyToGallery(merged, file.getString("title"), "mp4");
                } else {
                    try { videoPart.delete(); } catch (Exception ignored) {}
                    try { audioPart.delete(); } catch (Exception ignored) {}
                    throw new IllegalStateException("Could not merge 1080p on this device. Try 720p HD or Best Available.");
                }
                try { videoPart.delete(); } catch (Exception ignored) {}
                try { audioPart.delete(); } catch (Exception ignored) {}
            } else {
                destination = copyToGallery(new File(file.getString("path")), file.getString("title"), file.getString("ext"));
            }
            if (job.cancelled.get()) return;

            JSObject progress = new JSObject();
            progress.put("jobId", job.id);
            progress.put("url", job.url);
            progress.put("percent", 100);
            progress.put("status", "Saved to your gallery");
            progress.put("complete", true);
            progress.put("title", file.optString("title", "Video"));
            progress.put("destination", destination);
            notifyListeners("onProgress", progress);

            DowniDownloadService.finishJob(getContext(), job.id, true);
            try { job.call.resolve(progress); } catch (Exception ignored) {}
        } catch (Exception error) {
            if (!job.cancelled.get()) {
                JSObject progress = new JSObject();
                progress.put("jobId", job.id);
                progress.put("failed", true);
                String detail = error.getMessage() == null ? "Unknown download error" : error.getMessage();
                progress.put("error", friendlyError(detail));
                notifyListeners("onProgress", progress);
                DowniDownloadService.finishJob(getContext(), job.id, false);
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
                result.put("note", info.optString("note", ""));

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
        if (intent != null) {
            String action = intent.getAction();
            if (android.content.Intent.ACTION_SEND.equals(action)) {
                String value = intent.getStringExtra(android.content.Intent.EXTRA_TEXT);
                if (value != null) shared = value;
            } else if (android.content.Intent.ACTION_PROCESS_TEXT.equals(action)) {
                // Text-selection share (overflow menu → DOWNI). API 23+.
                CharSequence value = intent.getCharSequenceExtra(android.content.Intent.EXTRA_PROCESS_TEXT);
                if (value == null) value = intent.getCharSequenceExtra(android.content.Intent.EXTRA_PROCESS_TEXT_READONLY);
                if (value != null) shared = value.toString();
            }
            if (!shared.isEmpty()) {
                // Consume the share so a second read (boot block + handleSharedIntent)
                // never re-opens the Inspector for the same intent.
                intent.setAction("__downi_consumed__");
            }
        }
        result.put("url", shared);
        call.resolve(result);
    }

    /**
     * DowniDrop 2.0 settings bridge: the web layer (localStorage) owns the share-behavior
     * toggle and the per-platform quality memory, but DropActivity and the headless
     * service path are native-only — so the values are mirrored into SharedPreferences.
     */
    @PluginMethod
    public void syncDropSettings(PluginCall call) {
        String mode = call.getString("mode", "instant");
        String qualities = call.getString("qualities", "");
        getContext().getSharedPreferences("downi_settings", Context.MODE_PRIVATE)
            .edit()
            .putString("dropMode", "ask".equals(mode) ? "ask" : "instant")
            .putString("dropQualities", qualities == null ? "" : qualities)
            .apply();
        call.resolve();
    }

    /**
     * v3.1.1 (defect N4): background DowniDrop jobs are headless, so the app reads this snapshot to
     * show them as live cards in Active downloads / Queue. Written by DowniDownloadService.
     */
    @PluginMethod
    public void getDropJobs(PluginCall call) {
        JSObject result = new JSObject();
        try {
            String raw = getContext().getSharedPreferences("downi_settings", Context.MODE_PRIVATE)
                .getString("dropLive", "[]");
            result.put("jobs", new JSONArray(raw == null || raw.isEmpty() ? "[]" : raw));
        } catch (Exception e) {
            result.put("jobs", new JSONArray());
        }
        call.resolve(result);
    }

    /** Cancel a background grab from inside the app — routes to the service's shared_cancel action. */
    @PluginMethod
    public void cancelDropJob(PluginCall call) {
        String jobId = call.getString("jobId");
        if (jobId != null && !jobId.trim().isEmpty()) {
            Intent intent = new Intent(getContext(), DowniDownloadService.class);
            intent.setAction("shared_cancel");
            intent.putExtra("jobId", jobId);
            try { getContext().startService(intent); } catch (Exception ignored) {}
        }
        call.resolve();
    }

    @PluginMethod
    public void requestMediaPermission(PluginCall call) {
        String alias = Build.VERSION.SDK_INT >= 33 ? "mediaModern" : "mediaLegacy";
        if (getPermissionState(alias) == PermissionState.GRANTED) {
            JSObject result = new JSObject();
            result.put("granted", true);
            call.resolve(result);
            return;
        }
        requestPermissionForAlias(alias, call, "mediaPermissionCallback");
    }

    @PermissionCallback
    private void mediaPermissionCallback(PluginCall call) {
        String alias = Build.VERSION.SDK_INT >= 33 ? "mediaModern" : "mediaLegacy";
        JSObject result = new JSObject();
        result.put("granted", getPermissionState(alias) == PermissionState.GRANTED);
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

    /**
     * SAF writes are not always indexed by MediaScanner, which left saved
     * videos invisible in the Vault (a MediaStore query) and in gallery apps.
     * Resolve the real path when the document lives on primary storage and
     * hand it to the media scanner.
     */
    private void scanSafDocument(Uri documentUri, String mime) {
        try {
            String docId = DocumentsContract.getDocumentId(documentUri);
            int colon = docId.indexOf(':');
            if (colon <= 0) return;
            String device = docId.substring(0, colon);
            String rel = docId.substring(colon + 1);
            if (!"primary".equals(device) || rel.isEmpty()) return;
            File f = new File(Environment.getExternalStorageDirectory(), rel);
            android.media.MediaScannerConnection.scanFile(getContext(), new String[]{f.getAbsolutePath()}, new String[]{mime}, null);
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

        String treeUri = getContext().getSharedPreferences("downi_settings", Context.MODE_PRIVATE).getString("treeUri", "");
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
                // DOWNI's own folders: Movies/DOWNI/ and Music/DOWNI/. This is what
                // lets the Vault scope itself to OUR downloads only (and gives users
                // a clean "DOWNI" album in their gallery app).
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, (mime.startsWith("audio/") ? Environment.DIRECTORY_MUSIC : Environment.DIRECTORY_MOVIES) + "/DOWNI/");
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
        android.content.SharedPreferences names = getContext().getSharedPreferences("downi_saved_names", Context.MODE_PRIVATE);
        // Identity migration: counters used to live under "omni_saved_names".
        android.content.SharedPreferences legacyNames = getContext().getSharedPreferences("omni_saved_names", Context.MODE_PRIVATE);
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
        if (lower.contains("requested format is not available")) return "That quality is not available for this link. Try Best Available or a lower quality.";
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
            return "DOWNI"; // UA fallback only — never a stale version number
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
