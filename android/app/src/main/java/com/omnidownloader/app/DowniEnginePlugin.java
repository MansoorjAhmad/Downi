package com.omnidownloader.app;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.webkit.WebView;

import androidx.activity.result.ActivityResult;
import androidx.core.content.FileProvider;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * DOWNI native bridge.
 * Parallel download queue (3 simultaneous, up to 6 queued), per-job progress
 * and cancel, verified in-app updater, engine health check, and MediaStore/SAF saver.
 */
@CapacitorPlugin(name = "DowniEngine", permissions = {
        @Permission(alias = "notifications", strings = { Manifest.permission.POST_NOTIFICATIONS }),
        @Permission(alias = "storageLegacy", strings = { Manifest.permission.WRITE_EXTERNAL_STORAGE })
})
public class DowniEnginePlugin extends Plugin {
    private static volatile boolean isAppInForeground = true;

    @Override
    protected void handleOnResume() {
        super.handleOnResume();
        isAppInForeground = true;
    }

    @Override
    protected void handleOnPause() {
        super.handleOnPause();
        isAppInForeground = false;
    }

    @Override
    protected void handleOnDestroy() {
        enginePool.shutdownNow();
        miscExecutor.shutdownNow();
        super.handleOnDestroy();
    }

    private static final int MAX_ACTIVE = 3;
    private static final int MAX_QUEUED = 6;
    private static final int MAX_UPDATE_REDIRECTS = 5;

    private final ExecutorService enginePool = Executors.newFixedThreadPool(MAX_ACTIVE);
    private final ExecutorService miscExecutor = Executors.newFixedThreadPool(3);
    private final Map<String, DownloadJob> jobs = new ConcurrentHashMap<>();
    private final AtomicBoolean updateCancelled = new AtomicBoolean(false);
    private final AtomicBoolean updateInProgress = new AtomicBoolean(false);

    private static class DownloadJob {
        final String id;
        final PluginCall call;
        final String url;
        final String formatId;
        final String initialTitle;
        final boolean subtitles;
        final String subLang;
        final String shieldTier;
        final AtomicBoolean cancelled = new AtomicBoolean(false);

        DownloadJob(String id, PluginCall call, String url, String formatId,
                    String initialTitle, boolean subtitles, String subLang, String shieldTier) {
            this.id = id;
            this.call = call;
            this.url = url;
            this.formatId = formatId;
            this.initialTitle = initialTitle != null ? initialTitle : "";
            this.subtitles = subtitles;
            this.subLang = subLang;
            this.shieldTier = shieldTier;
        }
    }

    @PluginMethod
    public void clearCache(PluginCall call) {
        miscExecutor.execute(() -> {
            long freed = 0;
            try {
                freed += deleteTree(new File(getContext().getCacheDir(), "DowniEngine"));
                freed += deleteTree(new File(getContext().getCacheDir(), "OmniEngine"));
                freed += deleteTree(new File(getContext().getCacheDir(), "OmniDrop"));
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
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(call, intent, "folderPickerCallback");
    }

    @ActivityCallback
    private void folderPickerCallback(PluginCall call, ActivityResult result) {
        if (result.getResultCode() != android.app.Activity.RESULT_OK
                || result.getData() == null
                || result.getData().getData() == null) {
            call.reject("Folder selection cancelled");
            return;
        }
        Uri tree = result.getData().getData();
        try {
            getContext().getContentResolver().takePersistableUriPermission(
                    tree, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            String treeStr = tree.toString();
            String folderName = MediaStoreSaver.getSafFolderDisplayName(getContext(), treeStr);
            String label = folderName.isEmpty() ? "Custom folder" : folderName;
            synchronized (DowniNativeUtils.PREFS_LOCK) {
                getContext().getSharedPreferences(DowniNativeUtils.PREFS_SETTINGS, Context.MODE_PRIVATE)
                        .edit()
                        .putString("treeUri", treeStr)
                        .putString("treeLabel", label)
                        .apply();
            }
            JSObject response = new JSObject();
            response.put("folder", label);
            response.put("label", label);
            response.put("uri", treeStr);
            response.put("custom", true);
            call.resolve(response);
        } catch (Exception error) {
            call.reject("Could not save the selected folder", error);
        }
    }

    @PluginMethod
    public void getSaveLocation(PluginCall call) {
        android.content.SharedPreferences prefs = getContext()
                .getSharedPreferences(DowniNativeUtils.PREFS_SETTINGS, Context.MODE_PRIVATE);
        String treeUri = prefs.getString("treeUri", "");
        boolean custom = MediaStoreSaver.isSafTreeAccessible(getContext(), treeUri);
        String label = "Gallery — Movies / Music";
        if (custom) {
            String stored = prefs.getString("treeLabel", "");
            String resolved = !stored.isEmpty() ? stored : MediaStoreSaver.getSafFolderDisplayName(getContext(), treeUri);
            if (!resolved.isEmpty() && stored.isEmpty()) {
                synchronized (DowniNativeUtils.PREFS_LOCK) {
                    prefs.edit().putString("treeLabel", resolved).apply();
                }
            }
            label = resolved.isEmpty() ? "Custom folder" : resolved;
        }
        JSObject result = new JSObject();
        result.put("custom", custom);
        result.put("uri", custom ? treeUri : "");
        result.put("label", label);
        call.resolve(result);
    }

    @PluginMethod
    public void clearSaveFolder(PluginCall call) {
        try {
            android.content.SharedPreferences prefs = getContext()
                    .getSharedPreferences(DowniNativeUtils.PREFS_SETTINGS, Context.MODE_PRIVATE);
            String treeUri = prefs.getString("treeUri", "");
            if (treeUri != null && !treeUri.isEmpty()) {
                try {
                    getContext().getContentResolver().releasePersistableUriPermission(
                            Uri.parse(treeUri),
                            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                } catch (Exception ignored) {}
            }
            synchronized (DowniNativeUtils.PREFS_LOCK) {
                prefs.edit().remove("treeUri").remove("treeLabel").apply();
            }
        } catch (Exception ignored) {}
        JSObject result = new JSObject();
        result.put("uri", "");
        result.put("label", "Gallery — Movies / Music");
        result.put("custom", false);
        call.resolve(result);
    }

    @Override
    public void load() {
        try {
            android.content.SharedPreferences next = getContext().getSharedPreferences(DowniNativeUtils.PREFS_SETTINGS, Context.MODE_PRIVATE);
            android.content.SharedPreferences legacy = getContext().getSharedPreferences("omni_settings", Context.MODE_PRIVATE);
            String legacyTree = legacy.getString("treeUri", "");
            if (next.getString("treeUri", "").isEmpty() && legacyTree != null && !legacyTree.isEmpty()) {
                next.edit().putString("treeUri", legacyTree).apply();
                legacy.edit().clear().apply();
            }
        } catch (Exception ignored) {}

        try {
            File updates = getContext().getExternalFilesDir("updates");
            if (updates != null) {
                new File(updates, "downi-update.apk").delete();
                new File(updates, "downi-update.apk.part").delete();
                new File(updates, "omni-update.apk").delete();
            }
        } catch (Exception ignored) {}

        // Discard leftover interrupted DowniDrop bytes even when reopening app directly.
        DowniDownloadService.sweepInterrupted(getContext());
    }

    @PluginMethod
    public void playlistInspect(PluginCall call) {
        String url = call.getString("url", "").trim();
        if (url.isEmpty()) {
            call.reject("Paste a playlist link.");
            return;
        }
        final int offset = Math.max(0, call.getInt("offset", 0));
        final int limit = Math.max(1, Math.min(100, call.getInt("limit", 50)));
        call.setKeepAlive(true);
        miscExecutor.execute(() -> {
            long t0 = android.os.SystemClock.elapsedRealtime();
            Log.i("DOWNI", "playlistInspect start off=" + offset + " lim=" + limit);
            try {
                DowniNativeUtils.ensurePythonStarted(getContext());
                PyObject response = Python.getInstance().getModule("downloader")
                        .callAttr("playlist_inspect", url, offset, limit);
                if (response == null || "None".equals(response.toString())) {
                    Log.i("DOWNI", "playlistInspect empty");
                    call.reject("No playlist found on that link.", "link", (Exception) null);
                    return;
                }
                String jsonStr = Python.getInstance().getModule("json")
                        .callAttr("dumps", response).toString();
                JSONObject info = new JSONObject(jsonStr);
                JSObject result = new JSObject();
                result.put("title", info.optString("title", "Playlist"));
                JSArray list = new JSArray();
                JSONArray items = info.optJSONArray("items");
                if (items != null) {
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject obj = items.getJSONObject(i);
                        JSObject item = new JSObject();
                        item.put("url", obj.optString("url"));
                        item.put("title", obj.optString("title", "Video"));
                        item.put("duration", obj.optInt("duration", 0));
                        item.put("thumbnail", obj.optString("thumbnail", ""));
                        list.put(item);
                    }
                }
                int totalCount = info.optInt("total_count", info.optInt("count", list.length()));
                boolean hasMore = info.optBoolean("has_more", false);
                int nextOffset = info.optInt("next_offset", offset + list.length());
                result.put("count", totalCount);
                result.put("totalCount", totalCount);
                result.put("hasMore", hasMore);
                result.put("nextOffset", nextOffset);
                result.put("items", list);
                Log.i("DOWNI", "playlistInspect ok items=" + list.length() + " ms=" + (android.os.SystemClock.elapsedRealtime() - t0));
                call.resolve(result);
            } catch (Exception error) {
                String detail = error.getMessage() == null ? "" : error.getMessage();
                Log.w("DOWNI", "playlistInspect err: " + detail);
                String cause = EngineError.cause(detail);
                String raw = EngineError.raw(detail);
                call.reject(raw.isEmpty() ? "Could not read that playlist." : raw, cause, error);
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
    public void getDeviceCapabilities(PluginCall call) {
        miscExecutor.execute(() -> {
            long availBytes = 0L;
            try {
                File dir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MOVIES);
                if (dir == null || !dir.exists()) dir = getContext().getFilesDir();
                if (dir != null) {
                    android.os.StatFs stat = new android.os.StatFs(dir.getAbsolutePath());
                    availBytes = stat.getAvailableBytes();
                }
            } catch (Exception ignored) {}
            int maxAvcHeight = DeepShieldProcessor.queryMaxAvcEncoderHeight();
            JSObject res = new JSObject();
            res.put("availBytes", availBytes);
            res.put("maxAvcEncodeHeight", maxAvcHeight);
            call.resolve(res);
        });
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
            result.put("versionName", BuildConfig.VERSION_NAME);
            result.put("versionCode", (long) BuildConfig.VERSION_CODE);
            result.put("packageName", getContext().getPackageName());
            result.put("engine", "DOWNI Engine (Chaquopy 3.11 + yt-dlp)");
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
            try {
                Intent settingsIntent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getContext().getPackageName()));
                settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(settingsIntent);
            } catch (Exception ignored) {}
            call.reject("Install unknown apps permission required — enable it in settings, then retry.");
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

    // ---------- In-App Updater: verified GitHub download with live progress ----------

    @PluginMethod
    public void downloadUpdate(PluginCall call) {
        final String fileUrl = call.getString("url", "");
        final String expectedSha256 = call.getString("sha256", "");
        if (!DowniNativeUtils.isAllowedUpdateUrl(fileUrl)) {
            call.reject("Update URL must be an HTTPS GitHub release URL.");
            return;
        }
        if (!updateInProgress.compareAndSet(false, true)) {
            call.reject("An update download is already in progress.");
            return;
        }
        updateCancelled.set(false);
        call.setKeepAlive(true);

        miscExecutor.execute(() -> {
            File destDir = getContext().getExternalFilesDir("updates");
            if (destDir == null) destDir = getContext().getFilesDir();
            if (!destDir.exists()) destDir.mkdirs();
            final File partFile = new File(destDir, "downi-update.apk.part");
            final File destFile = new File(destDir, "downi-update.apk");
            long lastEvent = 0;
            HttpURLConnection conn = null;

            try {
                conn = openAllowedUpdateConnection(fileUrl);
                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) {
                    call.reject("Update server returned HTTP " + code);
                    return;
                }

                long total = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                        ? conn.getContentLengthLong()
                        : conn.getContentLength();
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] buffer = new byte[64 * 1024];
                long downloaded = 0;
                long start = System.currentTimeMillis();

                try (InputStream input = conn.getInputStream();
                     OutputStream output = new FileOutputStream(partFile)) {
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        if (updateCancelled.get()) {
                            call.reject("Update download cancelled.");
                            return;
                        }
                        output.write(buffer, 0, read);
                        digest.update(buffer, 0, read);
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
                            progress.put("sizeFormatted", DowniNativeUtils.formatBytes(downloaded)
                                    + (total > 0 ? " / " + DowniNativeUtils.formatBytes(total) : ""));
                            progress.put("speedFormatted", DowniNativeUtils.formatSpeed(speed));
                            notifyListeners("onUpdateProgress", progress);
                        }
                    }
                    output.flush();
                }

                if (partFile.length() <= 0) {
                    call.reject("Downloaded update file is empty.");
                    return;
                }

                String actualSha = IsoBmff.bytesToHex(digest.digest());
                if (expectedSha256 != null && !expectedSha256.trim().isEmpty()) {
                    String normalizedExpected = expectedSha256.trim().toLowerCase(java.util.Locale.US)
                            .replaceFirst("^sha256:", "");
                    if (!actualSha.equalsIgnoreCase(normalizedExpected)) {
                        call.reject("Update integrity check failed (SHA-256 mismatch).");
                        return;
                    }
                }

                if (!BuildConfig.DEBUG && !verifyApkSignatureMatchesSelf(partFile)) {
                    call.reject("Update signature verification failed — APK signer does not match DOWNI.");
                    return;
                }

                if (destFile.exists()) destFile.delete();
                if (!partFile.renameTo(destFile)) {
                    call.reject("Could not finalize downloaded update APK.");
                    return;
                }

                JSObject result = new JSObject();
                result.put("path", destFile.getAbsolutePath());
                result.put("size", destFile.length());
                result.put("sha256", actualSha);
                call.resolve(result);
            } catch (Exception error) {
                if (updateCancelled.get()) {
                    call.reject("Update download cancelled.");
                } else {
                    call.reject("Update download failed: "
                            + (error.getMessage() != null ? error.getMessage() : "network error"), error);
                }
            } finally {
                if (conn != null) try { conn.disconnect(); } catch (Exception ignored) {}
                try { if (partFile.exists()) partFile.delete(); } catch (Exception ignored) {}
                updateInProgress.set(false);
            }
        });
    }

    private HttpURLConnection openAllowedUpdateConnection(String initialUrl) throws Exception {
        String current = initialUrl;
        for (int hops = 0; hops <= MAX_UPDATE_REDIRECTS; hops++) {
            if (!DowniNativeUtils.isAllowedUpdateUrl(current)) {
                throw new SecurityException("Blocked redirect to untrusted host.");
            }
            HttpURLConnection conn = (HttpURLConnection) new URL(current).openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("User-Agent", "DOWNI-Android/" + getAppVersionName());
            conn.connect();
            int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_MOVED_TEMP
                    || code == HttpURLConnection.HTTP_MOVED_PERM
                    || code == HttpURLConnection.HTTP_SEE_OTHER
                    || code == 307
                    || code == 308) {
                String location = conn.getHeaderField("Location");
                conn.disconnect();
                if (location == null || location.isEmpty()) {
                    throw new java.io.IOException("Redirect missing Location header.");
                }
                current = new URL(new URL(current), location).toString();
                continue;
            }
            return conn;
        }
        throw new java.io.IOException("Too many redirects downloading update.");
    }

    @SuppressWarnings("deprecation")
    private boolean verifyApkSignatureMatchesSelf(File apkFile) {
        try {
            PackageManager pm = getContext().getPackageManager();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageInfo selfInfo = pm.getPackageInfo(getContext().getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
                PackageInfo apkInfo = pm.getPackageArchiveInfo(apkFile.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES);
                if (selfInfo == null || apkInfo == null || selfInfo.signingInfo == null || apkInfo.signingInfo == null) {
                    return false;
                }
                Signature[] selfSigs = selfInfo.signingInfo.getApkContentsSigners();
                Signature[] apkSigs = apkInfo.signingInfo.getApkContentsSigners();
                if (selfSigs == null || apkSigs == null || selfSigs.length == 0 || apkSigs.length == 0) {
                    return false;
                }
                return Arrays.equals(selfSigs[0].toByteArray(), apkSigs[0].toByteArray());
            } else {
                PackageInfo selfInfo = pm.getPackageInfo(getContext().getPackageName(), PackageManager.GET_SIGNATURES);
                PackageInfo apkInfo = pm.getPackageArchiveInfo(apkFile.getAbsolutePath(), PackageManager.GET_SIGNATURES);
                if (selfInfo == null || apkInfo == null || selfInfo.signatures == null || apkInfo.signatures == null
                        || selfInfo.signatures.length == 0 || apkInfo.signatures.length == 0) {
                    return false;
                }
                return Arrays.equals(selfInfo.signatures[0].toByteArray(), apkInfo.signatures[0].toByteArray());
            }
        } catch (Exception e) {
            return false;
        }
    }

    @PluginMethod
    public void cancelUpdateDownload(PluginCall call) {
        updateCancelled.set(true);
        try {
            File updates = getContext().getExternalFilesDir("updates");
            if (updates != null) {
                new File(updates, "downi-update.apk").delete();
                new File(updates, "downi-update.apk.part").delete();
            }
        } catch (Exception ignored) {}
        call.resolve();
    }

    // ---------- Engine info ----------

    @PluginMethod
    public void engineInfo(PluginCall call) {
        miscExecutor.execute(() -> {
            try {
                DowniNativeUtils.ensurePythonStarted(getContext());
                PyObject response = Python.getInstance().getModule("downloader").callAttr("engine_info");
                JSONObject info = new JSONObject(response.toString());
                JSObject result = new JSObject();
                result.put("engine", info.optString("engine", "DOWNI Engine"));
                result.put("ytDlpVersion", info.optString("yt_dlp_version", ""));
                result.put("pythonVersion", info.optString("python", ""));
                call.resolve(result);
            } catch (Exception error) {
                call.reject("Engine info failed: " + error.getMessage(), error);
            }
        });
    }

    @PluginMethod
    public void setNativeTheme(PluginCall call) {
        getActivity().runOnUiThread(() -> {
            try {
                android.view.Window window = getActivity().getWindow();
                window.setStatusBarColor(android.graphics.Color.parseColor("#090B0D"));
                window.setNavigationBarColor(android.graphics.Color.parseColor("#090B0D"));
                window.getDecorView().setSystemUiVisibility(0);
            } catch (Exception ignored) {}
            call.resolve();
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
            call.reject("Paste a valid http or https media link.", "link", (Exception) null);
            return;
        }

        // Request WRITE_EXTERNAL_STORAGE on API 24-28 before starting a download so gallery writes succeed.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                && getPermissionState("storageLegacy") != PermissionState.GRANTED) {
            call.setKeepAlive(true);
            requestPermissionForAlias("storageLegacy", call, "storagePermissionCallback");
            return;
        }
        enqueueDownloadJob(call, rawUrl);
    }

    @PermissionCallback
    private void storagePermissionCallback(PluginCall call) {
        String rawUrl = call.getString("directUrl", "");
        if (rawUrl == null || rawUrl.trim().isEmpty()) rawUrl = call.getString("url", "");
        enqueueDownloadJob(call, rawUrl == null ? "" : rawUrl.trim());
    }

    private void enqueueDownloadJob(PluginCall call, String rawUrl) {
        if (jobs.size() >= MAX_QUEUED) {
            call.reject("Queue is full. Wait for a download to finish or cancel one.");
            return;
        }
        String jobId = DowniNativeUtils.nextJobId("dl");
        boolean subtitles = Boolean.TRUE.equals(call.getBoolean("subtitles", false));
        String subLang = call.getString("subLang", "");
        String storedTier = getContext().getSharedPreferences(DowniNativeUtils.PREFS_SETTINGS, Context.MODE_PRIVATE)
                .getString("shieldTier", "standard");
        String reqTier = call.getString("shieldTier", "");
        String shieldTier = DowniNativeUtils.normalizeShieldTier(
                (reqTier != null && !reqTier.trim().isEmpty()) ? reqTier : storedTier);
        String rawTitle = call.getString("title", "");
        String initialTitle = rawTitle == null ? "" : rawTitle.trim();
        DownloadJob job = new DownloadJob(jobId, call, rawUrl, call.getString("formatId", "best"),
                initialTitle, subtitles, subLang == null ? "" : subLang.trim(), shieldTier);
        jobs.put(jobId, job);
        Log.i("DOWNI", "job start " + jobId + " " + rawUrl);

        JSObject started = new JSObject();
        started.put("jobId", jobId);
        started.put("url", rawUrl);
        if (!job.initialTitle.isEmpty()) started.put("title", job.initialTitle);
        started.put("percent", 0);
        started.put("status", "Queued");
        started.put("queued", true);
        notifyListeners("onProgress", started);

        DowniDownloadService.startJob(getContext(), jobId, "Queued", job.initialTitle);
        call.setKeepAlive(true);
        enginePool.execute(() -> runJob(job));
    }

    @PluginMethod
    public void cancelDownload(PluginCall call) {
        String jobId = call.getString("jobId");
        if (jobId != null && !jobId.trim().isEmpty()) {
            DownloadJob job = jobs.get(jobId);
            if (job != null && job.cancelled.compareAndSet(false, true)) {
                Log.i("DOWNI", "job cancel " + jobId);
                try { job.call.reject("Download cancelled.", "cancelled", (Exception) null); } catch (Exception ignored) {}
                DowniDownloadService.finishJob(getContext(), jobId, false, isAppInForeground);
                JSObject progress = new JSObject();
                progress.put("jobId", jobId);
                progress.put("failed", true);
                progress.put("cause", "cancelled");
                progress.put("error", "Download cancelled.");
                notifyListeners("onProgress", progress);
            }
        } else {
            for (DownloadJob job : jobs.values()) {
                if (job.cancelled.compareAndSet(false, true)) {
                    Log.i("DOWNI", "job cancel all " + job.id);
                    try { job.call.reject("Download cancelled.", "cancelled", (Exception) null); } catch (Exception ignored) {}
                    DowniDownloadService.finishJob(getContext(), job.id, false, isAppInForeground);
                    JSObject progress = new JSObject();
                    progress.put("jobId", job.id);
                    progress.put("failed", true);
                    progress.put("cause", "cancelled");
                    progress.put("error", "Download cancelled.");
                    notifyListeners("onProgress", progress);
                }
            }
        }
        call.resolve();
    }

    private void runJob(DownloadJob job) {
        File workDir = new File(new File(getContext().getCacheDir(), "DowniEngine"), job.id);
        try {
            if (job.cancelled.get()) return;
            DowniNativeUtils.ensurePythonStarted(getContext());
            if (job.cancelled.get()) return;

            DowniDownloadService.updateJob(getContext(), job.id, "Starting engine…", 1);
            DownloadProgressListener listener = new DownloadProgressListener() {
                @Override
                public void onProgress(double percent, long downloadedBytes, long totalBytes, double speedBytesPerSec, long etaSeconds) {
                    if (job.cancelled.get()) return;
                    JSObject progress = new JSObject();
                    progress.put("jobId", job.id);
                    progress.put("url", job.url);
                    progress.put("percent", (int) percent);
                    progress.put("downloadedBytes", downloadedBytes);
                    progress.put("totalBytes", totalBytes);
                    progress.put("speed", speedBytesPerSec);
                    progress.put("speedFormatted", DowniNativeUtils.formatSpeed(speedBytesPerSec));
                    String sizeText = DowniNativeUtils.formatBytes(downloadedBytes)
                            + (totalBytes > 0 ? " / " + DowniNativeUtils.formatBytes(totalBytes) : "");
                    progress.put("sizeFormatted", sizeText);
                    progress.put("sizeText", sizeText);
                    progress.put("eta", etaSeconds);
                    progress.put("etaFormatted", DowniNativeUtils.formatEta(etaSeconds));
                    progress.put("status", "Downloading…");
                    notifyListeners("onProgress", progress);
                    DowniDownloadService.updateJobProgress(getContext(), job.id, (int) percent,
                            downloadedBytes, totalBytes, speedBytesPerSec, etaSeconds);
                }

                @Override
                public boolean isCancelled() {
                    return job.cancelled.get();
                }
            };

            JSONObject opts = new JSONObject();
            opts.put("subtitles", job.subtitles);
            if (!job.subLang.isEmpty()) opts.put("sub_lang", job.subLang);

            PyObject response = Python.getInstance().getModule("downloader")
                    .callAttr("download", job.url, workDir.getAbsolutePath(), job.formatId,
                            listener, Build.VERSION.SDK_INT, opts.toString());
            if (job.cancelled.get()) return;

            JSONObject file = new JSONObject(response.toString());
            String title = file.optString("title", "Video");
            boolean wantUnique = job.call.getBoolean("makeUnique", false)
                    || getContext().getSharedPreferences(DowniNativeUtils.PREFS_SETTINGS, Context.MODE_PRIVATE)
                    .getBoolean("autoUnique", true);

            File toSave;
            String ext;
            VideoUniquifier.Result uRes;

            if (file.optBoolean("merge", false)) {
                if (job.cancelled.get()) return;
                DowniDownloadService.updateJob(getContext(), job.id, "Merging video + audio…", 96);
                JSObject merging = new JSObject();
                merging.put("jobId", job.id);
                merging.put("percent", 96);
                merging.put("status", "Merging video + audio…");
                notifyListeners("onProgress", merging);

                File videoPart = new File(file.getString("video_path"));
                File audioPart = new File(file.getString("audio_path"));
                File merged = new File(videoPart.getParentFile(), TitleSanitizer.sanitize(title) + "-merged.mp4");
                boolean mergedOk = Mp4Merger.merge(videoPart, audioPart, merged, job.cancelled::get);
                try { videoPart.delete(); } catch (Exception ignored) {}
                try { audioPart.delete(); } catch (Exception ignored) {}

                if (job.cancelled.get()) {
                    try { merged.delete(); } catch (Exception ignored) {}
                    return;
                }
                if (!mergedOk) {
                    throw new IllegalStateException("[cause:format] Could not merge video and audio on this device. Try 1080p Full HD or 720p HD.");
                }

                String mergedExt = IsoBmff.isWebm(merged) ? "webm" : "mp4";
                if (wantUnique) {
                    DowniDownloadService.updateJob(getContext(), job.id, "Making video unique…", 97);
                    JSObject uProg = new JSObject();
                    uProg.put("jobId", job.id);
                    uProg.put("percent", 97);
                    uProg.put("status", "Making video unique…");
                    notifyListeners("onProgress", uProg);
                }
                MediaStoreSaver.ShieldOutcome shield = MediaStoreSaver.applyShieldIfEnabled(
                        merged, title, mergedExt, wantUnique, job.formatId, job.shieldTier, job.cancelled::get);
                toSave = shield.fileToSave;
                ext = shield.ext;
                uRes = shield.result;
            } else {
                if (job.cancelled.get()) return;
                File single = new File(file.getString("path"));
                ext = file.getString("ext");
                if (wantUnique && !MediaStoreSaver.isAudioExtension(ext)
                        && !"audio".equalsIgnoreCase(job.formatId) && !"audio_opus".equalsIgnoreCase(job.formatId)) {
                    DowniDownloadService.updateJob(getContext(), job.id, "Making video unique…", 97);
                    JSObject uProg = new JSObject();
                    uProg.put("jobId", job.id);
                    uProg.put("percent", 97);
                    uProg.put("status", "Making video unique…");
                    notifyListeners("onProgress", uProg);
                }
                MediaStoreSaver.ShieldOutcome shield = MediaStoreSaver.applyShieldIfEnabled(
                        single, title, ext, wantUnique, job.formatId, job.shieldTier, job.cancelled::get);
                toSave = shield.fileToSave;
                ext = shield.ext;
                uRes = shield.result;
            }

            if (job.cancelled.get()) return;

            DowniDownloadService.updateJob(getContext(), job.id, "Saving to your gallery.", 98);
            JSObject saving = new JSObject();
            saving.put("jobId", job.id);
            saving.put("percent", 98);
            saving.put("status", "Saving to your gallery.");
            notifyListeners("onProgress", saving);

            long finalBytes = toSave.length();
            MediaStoreSaver.SaveOutcome saveResult = MediaStoreSaver.saveToGallery(getContext(), toSave, title, ext);
            String destination = saveResult.destinationLabel;

            int subtitleCount = 0;
            String subtitleUri = "";
            JSONArray subPaths = file.optJSONArray("subtitle_paths");
            if (subPaths != null) {
                for (int i = 0; i < subPaths.length(); i++) {
                    String subPath = subPaths.optString(i, "");
                    if (subPath.isEmpty()) continue;
                    File subFile = new File(subPath);
                    if (!subFile.exists() || subFile.length() == 0) continue;
                    String subName = subFile.getName();
                    int dot = subName.lastIndexOf('.');
                    String subExt = (dot > 0 && dot < subName.length() - 1) ? subName.substring(dot + 1) : "srt";
                    try {
                        MediaStoreSaver.SaveOutcome subSaved = MediaStoreSaver.saveSubtitleFile(getContext(), subFile, title, subExt);
                        subtitleCount++;
                        if (subtitleUri.isEmpty() && subSaved.mediaUri != null) {
                            subtitleUri = subSaved.mediaUri;
                        }
                    } catch (Exception ignored) {}
                }
            }

            if (job.cancelled.get()) return;

            String sizeFormatted = DowniNativeUtils.formatBytes(finalBytes);
            JSObject progress = new JSObject();
            progress.put("jobId", job.id);
            progress.put("url", job.url);
            progress.put("percent", 100);
            progress.put("status", "Saved to your gallery");
            progress.put("complete", true);
            progress.put("title", title);
            progress.put("destination", destination);
            progress.put("mediaUri", saveResult.mediaUri);
            progress.put("mimeType", saveResult.mimeType);
            progress.put("subtitleCount", subtitleCount);
            progress.put("subtitleUri", subtitleUri);
            if (file.has("tagged")) {
                progress.put("tagged", file.optBoolean("tagged", false));
            }
            if (uRes != null) {
                progress.put("shieldMode", uRes.mode);
                progress.put("shieldReason", uRes.reason);
                progress.put("shieldInputSha", uRes.inputSha256);
                progress.put("shieldSha", uRes.outputSha256);
            } else {
                progress.put("shieldMode", "");
                progress.put("shieldReason", "");
                progress.put("shieldInputSha", "");
                progress.put("shieldSha", "");
            }
            progress.put("totalBytes", finalBytes);
            progress.put("downloadedBytes", finalBytes);
            progress.put("sizeFormatted", sizeFormatted);
            progress.put("sizeText", sizeFormatted);
            notifyListeners("onProgress", progress);

            DowniDownloadService.finishJob(getContext(), job.id, true, isAppInForeground);
            try { job.call.resolve(progress); } catch (Exception ignored) {}
            Log.i("DOWNI", "job saved " + job.id + " -> " + destination);
        } catch (Exception error) {
            String detail = error.getMessage() == null ? "Unknown download error" : error.getMessage();
            String cause = EngineError.cause(detail);
            String raw = EngineError.raw(detail);
            if ("cancelled".equals(cause)) {
                if (job.cancelled.compareAndSet(false, true)) {
                    JSObject progress = new JSObject();
                    progress.put("jobId", job.id);
                    progress.put("failed", true);
                    progress.put("cause", "cancelled");
                    progress.put("error", raw.isEmpty() ? "Download cancelled." : raw);
                    notifyListeners("onProgress", progress);
                    DowniDownloadService.finishJob(getContext(), job.id, false, isAppInForeground);
                    try { job.call.reject(raw.isEmpty() ? "Download cancelled." : raw, "cancelled", error); } catch (Exception ignored) {}
                }
            } else if (!job.cancelled.get()) {
                JSObject progress = new JSObject();
                progress.put("jobId", job.id);
                progress.put("failed", true);
                progress.put("error", raw);
                progress.put("cause", cause);
                notifyListeners("onProgress", progress);
                DowniDownloadService.finishJob(getContext(), job.id, false, isAppInForeground);
                try { job.call.reject(raw, cause, error); } catch (Exception ignored) {}
                Log.i("DOWNI", "job failed " + job.id + " [" + cause + "]: " + raw);
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
            call.reject("Paste a valid public video link.", "link", (Exception) null);
            return;
        }
        call.setKeepAlive(true);
        miscExecutor.execute(() -> {
            try {
                DowniNativeUtils.ensurePythonStarted(getContext());
                PyObject response = Python.getInstance().getModule("downloader")
                        .callAttr("inspect", url, Build.VERSION.SDK_INT);
                JSONObject info = new JSONObject(response.toString());
                if (info.optInt("duration", 0) <= 0 && info.optString("thumbnail", "").isEmpty()) {
                    call.reject("That link isn't a video.", "link", (Exception) null);
                    return;
                }
                JSObject result = new JSObject();
                result.put("title", info.optString("title", "Video"));
                result.put("uploader", info.optString("uploader", ""));
                result.put("duration", info.optInt("duration", 0));
                result.put("thumbnail", info.optString("thumbnail", ""));
                result.put("url", info.optString("webpage_url", url));
                result.put("platform", info.optString("platform", "other"));
                result.put("note", info.optString("note", ""));
                result.put("hasSubtitles", info.optBoolean("has_subtitles", false));
                JSArray subLangs = new JSArray();
                JSONArray rawSubLangs = info.optJSONArray("subtitle_langs");
                if (rawSubLangs != null) {
                    for (int i = 0; i < rawSubLangs.length(); i++) {
                        String code = rawSubLangs.optString(i, "");
                        if (!code.isEmpty()) subLangs.put(code);
                    }
                }
                result.put("subtitleLangs", subLangs);

                JSONArray fmts = info.optJSONArray("formats");
                if (fmts != null) {
                    JSArray jsFmts = new JSArray();
                    for (int i = 0; i < fmts.length(); i++) {
                        JSONObject obj = fmts.getJSONObject(i);
                        JSObject item = new JSObject();
                        item.put("id", obj.optString("id", "best"));
                        item.put("label", obj.optString("label", "Standard"));
                        item.put("ext", obj.optString("ext", "mp4"));
                        item.put("badge", obj.optString("badge", "HD"));
                        item.put("filesize", obj.optLong("filesize", 0L));
                        jsFmts.put(item);
                    }
                    result.put("formats", jsFmts);
                }
                call.resolve(result);
            } catch (Exception error) {
                String detail = error.getMessage() == null ? "" : error.getMessage();
                String cause = EngineError.cause(detail);
                String raw = EngineError.raw(detail);
                call.reject(raw.isEmpty() ? "DOWNI couldn't read that link." : raw, cause, error);
            }
        });
    }

    @PluginMethod
    public void setAutoUnique(PluginCall call) {
        boolean enabled = call.getBoolean("enabled", false);
        getContext().getSharedPreferences(DowniNativeUtils.PREFS_SETTINGS, Context.MODE_PRIVATE)
                .edit().putBoolean("autoUnique", enabled).apply();
        JSObject res = new JSObject();
        res.put("enabled", enabled);
        call.resolve(res);
    }

    @PluginMethod
    public void getAutoUnique(PluginCall call) {
        boolean enabled = getContext().getSharedPreferences(DowniNativeUtils.PREFS_SETTINGS, Context.MODE_PRIVATE)
                .getBoolean("autoUnique", true);
        JSObject res = new JSObject();
        res.put("enabled", enabled);
        call.resolve(res);
    }

    @PluginMethod
    public void makeVideoUnique(PluginCall call) {
        String uriStr = call.getString("uri");
        String title = call.getString("title", "Video");
        if (uriStr == null || uriStr.trim().isEmpty()) {
            call.reject("No video uri provided");
            return;
        }
        call.setKeepAlive(true);
        miscExecutor.execute(() -> {
            File tempInput = null;
            File tempOutput = null;
            try {
                Uri uri = Uri.parse(uriStr);
                tempInput = new File(getContext().getCacheDir(), "src_" + System.currentTimeMillis() + ".tmp");

                try (InputStream in = getContext().getContentResolver().openInputStream(uri);
                     FileOutputStream out = new FileOutputStream(tempInput)) {
                    if (in == null) throw new IllegalArgumentException("Cannot open video stream");
                    byte[] buf = new byte[64 * 1024];
                    int r;
                    while ((r = in.read(buf)) != -1) {
                        out.write(buf, 0, r);
                    }
                }

                IsoBmff.ContainerType container = IsoBmff.sniff(tempInput);
                String candExt = container.extension.isEmpty() ? "mp4" : container.extension;
                tempOutput = new File(getContext().getCacheDir(), "uniq_" + System.currentTimeMillis() + "." + candExt);

                String tier = DowniNativeUtils.normalizeShieldTier(
                        getContext().getSharedPreferences(DowniNativeUtils.PREFS_SETTINGS, Context.MODE_PRIVATE)
                                .getString("shieldTier", "standard"));
                VideoUniquifier.Result uRes = VideoUniquifier.process(tempInput, tempOutput, tier);
                if (uRes.success && VideoUniquifier.Result.isAppliedMode(uRes.mode)) {
                    String cleanTitle = (title.endsWith("(Unique)") ? title : (title + " (Unique)")).trim();
                    MediaStoreSaver.SaveOutcome saveResult = MediaStoreSaver.saveToGallery(getContext(), uRes.file, cleanTitle, uRes.extension);
                    JSObject res = new JSObject();
                    res.put("success", true);
                    res.put("destination", saveResult.destinationLabel);
                    res.put("mediaUri", saveResult.mediaUri);
                    res.put("mimeType", saveResult.mimeType);
                    res.put("title", cleanTitle);
                    res.put("shieldMode", uRes.mode);
                    res.put("shieldReason", uRes.reason);
                    res.put("shieldInputSha", uRes.inputSha256);
                    res.put("shieldSha", uRes.outputSha256);
                    call.resolve(res);
                } else {
                    String msg = (uRes != null && uRes.reason != null && !uRes.reason.isEmpty())
                            ? uRes.reason
                            : "Format not upload-safe for modification; original left untouched.";
                    call.reject("Anti-Repost Shield skipped: " + msg);
                }
            } catch (Exception e) {
                call.reject("Uniquifier failed: " + e.getMessage());
            } finally {
                try { if (tempInput != null && tempInput.exists()) tempInput.delete(); } catch (Exception ignored) {}
                try { if (tempOutput != null && tempOutput.exists()) tempOutput.delete(); } catch (Exception ignored) {}
            }
        });
    }

    @PluginMethod
    public void diagnose(PluginCall call) {
        call.setKeepAlive(true);
        miscExecutor.execute(() -> {
            try {
                DowniNativeUtils.ensurePythonStarted(getContext());
                PyObject response = Python.getInstance().getModule("downloader").callAttr("diagnose");
                String raw = response.toString();
                Log.i("DOWNI", "diagnose -> " + raw.substring(0, Math.min(raw.length(), 300)));
                JSONObject info = new JSONObject(raw);
                JSObject result = new JSObject();
                JSONArray rows = info.optJSONArray("results");
                JSArray results = new JSArray();
                if (rows != null) {
                    try {
                        results = new JSArray(rows.toString());
                    } catch (JSONException parseError) {
                        results = new JSArray();
                    }
                }
                result.put("results", results);
                result.put("ytDlp", info.optString("yt_dlp", ""));
                result.put("python", info.optString("python", ""));
                call.resolve(result);
            } catch (Exception error) {
                call.reject("Diagnostics failed: " + error.getMessage(), error);
            }
        });
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
                CharSequence value = intent.getCharSequenceExtra(android.content.Intent.EXTRA_PROCESS_TEXT);
                if (value == null) value = intent.getCharSequenceExtra(android.content.Intent.EXTRA_PROCESS_TEXT_READONLY);
                if (value != null) shared = value.toString();
            }
            if (!shared.isEmpty()) {
                intent.setAction("__downi_consumed__");
            }
        }
        result.put("url", shared);
        call.resolve(result);
    }

    @PluginMethod
    public void syncDropSettings(PluginCall call) {
        String qualities = call.getString("qualities", "");
        synchronized (DowniNativeUtils.PREFS_LOCK) {
            android.content.SharedPreferences.Editor ed = getContext()
                    .getSharedPreferences(DowniNativeUtils.PREFS_SETTINGS, Context.MODE_PRIVATE)
                    .edit()
                    .putString("dropQualities", qualities == null ? "" : qualities)
                    .remove("dropMode");
            if (call.hasOption("shieldTier")) {
                ed.putString("shieldTier", DowniNativeUtils.normalizeShieldTier(call.getString("shieldTier", "standard")));
            }
            ed.apply();
        }
        call.resolve();
    }

    @PluginMethod
    public void syncShieldSettings(PluginCall call) {
        String tier = DowniNativeUtils.normalizeShieldTier(call.getString("tier", "standard"));
        synchronized (DowniNativeUtils.PREFS_LOCK) {
            getContext().getSharedPreferences(DowniNativeUtils.PREFS_SETTINGS, Context.MODE_PRIVATE)
                    .edit()
                    .putString("shieldTier", tier)
                    .apply();
        }
        JSObject res = new JSObject();
        res.put("tier", tier);
        call.resolve(res);
    }

    @PluginMethod
    public void getShieldSettings(PluginCall call) {
        String tier = DowniNativeUtils.normalizeShieldTier(
                getContext().getSharedPreferences(DowniNativeUtils.PREFS_SETTINGS, Context.MODE_PRIVATE)
                        .getString("shieldTier", "standard"));
        JSObject res = new JSObject();
        res.put("tier", tier);
        call.resolve(res);
    }

    @PluginMethod
    public void getDropJobs(PluginCall call) {
        JSObject result = new JSObject();
        try {
            android.content.SharedPreferences prefs = getContext()
                    .getSharedPreferences(DowniNativeUtils.PREFS_SETTINGS, Context.MODE_PRIVATE);
            Set<String> liveJobIds = DowniDownloadService.liveJobIdsSnapshot();
            JSONArray kept = JobSnapshot.readAndPrune(getContext(), liveJobIds, System.currentTimeMillis());
            result.put("jobs", kept);
            result.put("lastError", prefs.getString("dropLastError", ""));
        } catch (Exception e) {
            result.put("jobs", new JSONArray());
        }
        call.resolve(result);
    }

    @PluginMethod
    public void getGrabLedger(PluginCall call) {
        JSObject result = new JSObject();
        try {
            result.put("bytes", DowniDownloadService.grabBytes(getContext()));
            result.put("history", new JSONArray(DowniDownloadService.grabHistoryJson(getContext())));
        } catch (Exception e) {
            result.put("bytes", 0);
            result.put("history", new JSONArray());
        }
        call.resolve(result);
    }

    @PluginMethod
    public void resetGrabLedger(PluginCall call) {
        try { DowniDownloadService.resetGrabLedger(getContext()); } catch (Exception ignored) {}
        call.resolve();
    }

    @PluginMethod
    public void cancelDropJob(PluginCall call) {
        String jobId = call.getString("jobId");
        if (jobId != null && !jobId.trim().isEmpty()) {
            boolean cancelled = DowniDownloadService.cancelSharedJob(getContext(), jobId);
            if (!cancelled && DowniDownloadService.isAlive()) {
                Intent intent = new Intent(getContext(), DowniDownloadService.class);
                intent.setAction("shared_cancel");
                intent.putExtra("jobId", jobId);
                try { getContext().startService(intent); } catch (Exception ignored) {}
            }
        }
        call.resolve();
    }

    @PluginMethod
    public void consumeOpenQueueFlag(PluginCall call) {
        JSObject result = new JSObject();
        boolean open = false;
        try {
            android.content.SharedPreferences prefs = getContext()
                    .getSharedPreferences(DowniNativeUtils.PREFS_SETTINGS, Context.MODE_PRIVATE);
            open = prefs.getBoolean("openQueuePending", false);
            if (open) prefs.edit().putBoolean("openQueuePending", false).apply();
            prefs.edit().remove("openVaultPending").apply();
        } catch (Exception ignored) {}
        result.put("open", open);
        call.resolve(result);
    }

    @PluginMethod
    public void notificationStatus(PluginCall call) {
        JSObject result = new JSObject();
        boolean enabled = true;
        try {
            enabled = androidx.core.app.NotificationManagerCompat.from(getContext()).areNotificationsEnabled();
        } catch (Exception ignored) {}
        result.put("enabled", enabled);
        result.put("canPrompt", Build.VERSION.SDK_INT >= 33);
        call.resolve(result);
    }

    @PluginMethod
    public void hapticTick(PluginCall call) {
        final String style = call.getString("style", "light");
        final int constant;
        if ("error".equalsIgnoreCase(style)) {
            constant = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                    ? HapticFeedbackConstants.REJECT
                    : HapticFeedbackConstants.LONG_PRESS;
        } else if ("success".equalsIgnoreCase(style)) {
            constant = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                    ? HapticFeedbackConstants.CONFIRM
                    : HapticFeedbackConstants.VIRTUAL_KEY;
        } else if ("medium".equalsIgnoreCase(style)) {
            constant = HapticFeedbackConstants.CONTEXT_CLICK;
        } else {
            constant = HapticFeedbackConstants.CLOCK_TICK;
        }
        android.app.Activity activity = getActivity();
        if (activity == null || getBridge() == null) {
            call.resolve();
            return;
        }
        activity.runOnUiThread(() -> {
            try {
                WebView webView = getBridge().getWebView();
                if (webView != null) {
                    webView.performHapticFeedback(constant);
                }
            } catch (Exception ignored) {}
            call.resolve();
        });
    }

    @PluginMethod
    public void openSavedMedia(PluginCall call) {
        String rawUri = call.getString("mediaUri", "");
        String requestedMime = call.getString("mimeType", "");
        try {
            Uri contentUri = resolveAccessibleMediaUri(rawUri);
            String mime = resolveOpenMimeType(contentUri, requestedMime);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(contentUri, mime);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                getContext().startActivity(intent);
            } catch (ActivityNotFoundException firstMiss) {
                if (!isCaptionOrTextFallbackEligible(mime, rawUri)) {
                    throw firstMiss;
                }
                Intent textIntent = new Intent(Intent.ACTION_VIEW);
                textIntent.setDataAndType(contentUri, "text/plain");
                textIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    getContext().startActivity(textIntent);
                } catch (ActivityNotFoundException secondMiss) {
                    // Last-resort share sheet when the OEM ROM ships no text/caption viewer.
                    Intent share = new Intent(Intent.ACTION_SEND);
                    share.setType("text/plain");
                    share.putExtra(Intent.EXTRA_STREAM, contentUri);
                    share.setClipData(ClipData.newUri(getContext().getContentResolver(), "Captions", contentUri));
                    share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    Intent chooser = Intent.createChooser(share, "Open captions with");
                    chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    getContext().startActivity(chooser);
                }
            }
            call.resolve();
        } catch (ActivityNotFoundException e) {
            call.reject("No app found on your device to open this media.", e);
        } catch (Exception e) {
            call.reject(e.getMessage() != null ? e.getMessage() : "Could not open saved media.", e);
        }
    }

    private static boolean isCaptionOrTextFallbackEligible(String mime, String rawUri) {
        String m = mime != null ? mime.toLowerCase(java.util.Locale.US) : "";
        String u = rawUri != null ? rawUri.toLowerCase(java.util.Locale.US) : "";
        return "application/x-subrip".equals(m)
                || "text/vtt".equals(m)
                || "text/srt".equals(m)
                || u.endsWith(".srt")
                || u.endsWith(".vtt");
    }

    @PluginMethod
    public void shareSavedMedia(PluginCall call) {
        String rawUri = call.getString("mediaUri", "");
        String requestedMime = call.getString("mimeType", "");
        String title = call.getString("title", "");
        try {
            Uri contentUri = resolveAccessibleMediaUri(rawUri);
            String mime = resolveOpenMimeType(contentUri, requestedMime);
            String label = (title != null && !title.trim().isEmpty()) ? title.trim() : "Share media";
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType(mime);
            share.putExtra(Intent.EXTRA_STREAM, contentUri);
            if (!label.isEmpty()) {
                share.putExtra(Intent.EXTRA_SUBJECT, label);
                share.putExtra(Intent.EXTRA_TITLE, label);
            }
            share.setClipData(ClipData.newUri(getContext().getContentResolver(), label, contentUri));
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Intent chooser = Intent.createChooser(share, label);
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            getContext().startActivity(chooser);
            call.resolve();
        } catch (ActivityNotFoundException e) {
            call.reject("No app available to share this media.", e);
        } catch (Exception e) {
            call.reject(e.getMessage() != null ? e.getMessage() : "Could not share saved media.", e);
        }
    }

    @PluginMethod
    public void openAppSettings(PluginCall call) {
        String target = call.getString("target", "app_info");
        String pkg = getContext().getPackageName();
        Intent intent;
        if ("notifications".equalsIgnoreCase(target) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, pkg);
        } else if ("unknown_apps".equalsIgnoreCase(target) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + pkg));
        } else {
            intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg));
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            getContext().startActivity(intent);
            call.resolve();
        } catch (Exception primaryErr) {
            try {
                Intent fallback = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg));
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(fallback);
                call.resolve();
            } catch (Exception fallbackErr) {
                call.reject("Could not open Android settings.", fallbackErr);
            }
        }
    }

    private Uri resolveAccessibleMediaUri(String rawUri) throws Exception {
        if (!DowniNativeUtils.isSupportedMediaUri(rawUri)) {
            throw new IllegalArgumentException("No shareable media URI is stored for this item.");
        }
        Uri parsed = Uri.parse(rawUri.trim());
        Uri resolved = parsed;
        if ("file".equalsIgnoreCase(parsed.getScheme())) {
            String path = parsed.getPath();
            if (path == null || path.isEmpty()) {
                throw new FileNotFoundException("Saved file path is empty.");
            }
            File f = new File(path);
            if (!f.exists()) {
                throw new FileNotFoundException("Saved file was moved or deleted.");
            }
            resolved = FileProvider.getUriForFile(getContext(), getContext().getPackageName() + ".fileprovider", f);
        }
        try (AssetFileDescriptor afd = getContext().getContentResolver().openAssetFileDescriptor(resolved, "r")) {
            if (afd == null) {
                throw new FileNotFoundException("Saved file is no longer accessible.");
            }
        } catch (FileNotFoundException e) {
            throw new FileNotFoundException("Saved file was moved or deleted.");
        } catch (SecurityException e) {
            throw new SecurityException("Permission to access this file was revoked.");
        }
        return resolved;
    }

    private String resolveOpenMimeType(Uri contentUri, String requestedMime) {
        if (requestedMime != null && !requestedMime.trim().isEmpty()) {
            return requestedMime.trim();
        }
        try {
            String detected = getContext().getContentResolver().getType(contentUri);
            if (detected != null && !detected.trim().isEmpty()) {
                return detected.trim();
            }
        } catch (Exception ignored) {}
        return "video/mp4";
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

    private String getAppVersionName() {
        try {
            PackageInfo pInfo = getContext().getPackageManager().getPackageInfo(getContext().getPackageName(), 0);
            return pInfo.versionName;
        } catch (Exception e) {
            return BuildConfig.VERSION_NAME;
        }
    }
}
