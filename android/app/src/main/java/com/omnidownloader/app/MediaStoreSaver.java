package com.omnidownloader.app;

import android.Manifest;
import android.content.ContentValues;
import android.content.Context;
import android.content.UriPermission;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.webkit.MimeTypeMap;

import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Unified MediaStore / SAF gallery saver + Shield invocation helper shared by
 * {@link DowniEnginePlugin} and {@link DowniDownloadService}.
 *
 * Guarantees:
 * - Single synchronized naming lock across foreground & headless downloads.
 * - Accurate MIME mapping (.opus, .ogg, .webm, .mkv, .m4a, .mp3, .mp4).
 * - Honest destination labels ("Custom folder", "Gallery / Movies", "Gallery / Music", "App folder (DOWNI)").
 * - Never deletes {@code source} unless persistence succeeded.
 */
public final class MediaStoreSaver {

    private static final Object NAME_LOCK = new Object();
    private static final Map<String, String> EXPLICIT_MIMES = new HashMap<>();

    static {
        EXPLICIT_MIMES.put("mp4", "video/mp4");
        EXPLICIT_MIMES.put("m4v", "video/mp4");
        EXPLICIT_MIMES.put("webm", "video/webm");
        EXPLICIT_MIMES.put("mkv", "video/x-matroska");
        EXPLICIT_MIMES.put("3gp", "video/3gpp");
        EXPLICIT_MIMES.put("mov", "video/quicktime");
        EXPLICIT_MIMES.put("mp3", "audio/mpeg");
        EXPLICIT_MIMES.put("m4a", "audio/mp4");
        EXPLICIT_MIMES.put("aac", "audio/aac");
        EXPLICIT_MIMES.put("opus", "audio/opus");
        EXPLICIT_MIMES.put("ogg", "audio/ogg");
        EXPLICIT_MIMES.put("oga", "audio/ogg");
        EXPLICIT_MIMES.put("flac", "audio/flac");
        EXPLICIT_MIMES.put("wav", "audio/wav");
        EXPLICIT_MIMES.put("vtt", "text/vtt");
        EXPLICIT_MIMES.put("srt", "application/x-subrip");
        EXPLICIT_MIMES.put("ass", "text/x-ssa");
        EXPLICIT_MIMES.put("ssa", "text/x-ssa");
        EXPLICIT_MIMES.put("ttml", "application/ttml+xml");
        EXPLICIT_MIMES.put("jpg", "image/jpeg");
        EXPLICIT_MIMES.put("jpeg", "image/jpeg");
        EXPLICIT_MIMES.put("png", "image/png");
        EXPLICIT_MIMES.put("webp", "image/webp");
    }

    public static final class SaveOutcome {
        public final String destinationLabel;
        public final String mediaUri;
        public final String mimeType;
        public final long mediaId;
        public final boolean video;

        public SaveOutcome(String destinationLabel, String mediaUri, String mimeType, long mediaId, boolean video) {
            this.destinationLabel = (destinationLabel == null || destinationLabel.isEmpty()) ? "Gallery" : destinationLabel;
            this.mediaUri = mediaUri != null ? mediaUri : "";
            this.mimeType = (mimeType == null || mimeType.isEmpty()) ? "video/mp4" : mimeType;
            this.mediaId = mediaId;
            this.video = video;
        }
    }

    public static final class ShieldOutcome {
        public final File fileToSave;
        public final String ext;
        public final VideoUniquifier.Result result;
        public final boolean deleteAfterSave;

        public ShieldOutcome(File fileToSave, String ext, VideoUniquifier.Result result, boolean deleteAfterSave) {
            this.fileToSave = fileToSave;
            this.ext = ext;
            this.result = result;
            this.deleteAfterSave = deleteAfterSave;
        }
    }

    public interface CancelFlag {
        boolean isCancelled();
    }

    private MediaStoreSaver() {}

    /** Pure-Java MIME lookup table with Android MimeTypeMap fallback. */
    public static String mimeForExtension(String extension) {
        String ext = normalizeExt(extension);
        String explicit = EXPLICIT_MIMES.get(ext);
        if (explicit != null) return explicit;
        try {
            String mapped = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            if (mapped != null && !mapped.isEmpty()) return mapped;
        } catch (Throwable ignored) {}
        return "video/mp4";
    }

    public static String normalizeExt(String extension) {
        if (extension == null || extension.trim().isEmpty()) return "mp4";
        String clean = extension.trim().toLowerCase(Locale.US).replaceFirst("^\\.+", "");
        return clean.isEmpty() ? "mp4" : clean;
    }

    public static boolean isAudioExtension(String extension) {
        String ext = normalizeExt(extension);
        return "mp3".equals(ext) || "m4a".equals(ext) || "opus".equals(ext)
                || "ogg".equals(ext) || "oga".equals(ext) || "aac".equals(ext)
                || "flac".equals(ext) || "wav".equals(ext);
    }

    /**
     * Runs Anti-Repost Shield S2 when enabled and applicable (skips audio formats and
     * preserves original on failure/skip). Never modifies {@code source} in place.
     */
    public static ShieldOutcome applyShieldIfEnabled(
            File source,
            String title,
            String ext,
            boolean wantUnique,
            String formatId,
            CancelFlag cancelFlag) {
        return applyShieldIfEnabled(source, title, ext, wantUnique, formatId, "standard", cancelFlag);
    }

    /**
     * Runs Anti-Repost Shield ({@code "standard"} S2+ or {@code "deep"} hardware creator tier)
     * when enabled and applicable (skips audio formats and preserves original on failure/skip).
     */
    public static ShieldOutcome applyShieldIfEnabled(
            File source,
            String title,
            String ext,
            boolean wantUnique,
            String formatId,
            String shieldTier,
            CancelFlag cancelFlag) {
        String cleanExt = normalizeExt(ext);
        boolean isAudioReq = "audio".equalsIgnoreCase(formatId)
                || "audio_opus".equalsIgnoreCase(formatId)
                || isAudioExtension(cleanExt);
        if (!wantUnique || isAudioReq) {
            return new ShieldOutcome(source, cleanExt, null, false);
        }
        if (cancelFlag != null && cancelFlag.isCancelled()) {
            return new ShieldOutcome(source, cleanExt, null, false);
        }
        try {
            String safeBase = TitleSanitizer.sanitize(title);
            File uniqueCandidate = new File(source.getParentFile(), safeBase + "-unique-temp." + cleanExt);
            VideoUniquifier.Result uRes = VideoUniquifier.process(source, uniqueCandidate, shieldTier);
            if (uRes.success && VideoUniquifier.Result.isAppliedMode(uRes.mode)) {
                if (uRes.file != null && !uRes.file.equals(source)) {
                    try { source.delete(); } catch (Exception ignored) {}
                    return new ShieldOutcome(uRes.file, normalizeExt(uRes.extension), uRes, true);
                }
                return new ShieldOutcome(uRes.file, normalizeExt(uRes.extension), uRes, false);
            }
            return new ShieldOutcome(source, cleanExt, uRes, false);
        } catch (Throwable t) {
            return new ShieldOutcome(source, cleanExt, null, false);
        }
    }

    /** Checks whether a persisted SAF tree URI still has write permission and exists. */
    public static boolean isSafTreeAccessible(Context context, String treeUriStr) {
        if (treeUriStr == null || treeUriStr.trim().isEmpty()) return false;
        try {
            Uri tree = Uri.parse(treeUriStr);
            boolean hasPermission = false;
            List<UriPermission> perms = context.getContentResolver().getPersistedUriPermissions();
            if (perms != null) {
                for (UriPermission p : perms) {
                    if (tree.equals(p.getUri()) && p.isWritePermission()) {
                        hasPermission = true;
                        break;
                    }
                }
            }
            if (!hasPermission) return false;

            Uri docUri = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
            String[] proj = {
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
            };
            try (Cursor c = context.getContentResolver().query(docUri, proj, null, null, null)) {
                if (c == null || !c.moveToFirst()) return false;
                String mime = c.getString(1);
                return DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);
            }
        } catch (Exception e) {
            return false;
        }
    }

    /** Pure-Java fallback that derives a readable folder name from an Android SAF documentId. */
    static String folderNameFromDocumentId(String docId) {
        if (docId == null || docId.trim().isEmpty()) return "";
        String trimmed = docId.trim();
        int colon = trimmed.indexOf(':');
        String rel = colon >= 0 ? trimmed.substring(colon + 1) : trimmed;
        while (rel.endsWith("/")) rel = rel.substring(0, rel.length() - 1);
        if (rel.isEmpty()) return colon >= 0 ? "Internal storage" : "";
        int slash = rel.lastIndexOf('/');
        String leaf = slash >= 0 ? rel.substring(slash + 1) : rel;
        return leaf.trim();
    }

    /** Returns the user-visible display name of a persisted SAF tree URI, or "" if unavailable. */
    public static String getSafFolderDisplayName(Context context, String treeUriStr) {
        if (context == null || treeUriStr == null || treeUriStr.trim().isEmpty()) return "";
        try {
            Uri tree = Uri.parse(treeUriStr);
            String docId = DocumentsContract.getTreeDocumentId(tree);
            Uri docUri = DocumentsContract.buildDocumentUriUsingTree(tree, docId);
            String[] proj = { DocumentsContract.Document.COLUMN_DISPLAY_NAME };
            try (Cursor c = context.getContentResolver().query(docUri, proj, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    String name = c.getString(0);
                    if (name != null && !name.trim().isEmpty()) {
                        return name.trim();
                    }
                }
            }
            return folderNameFromDocumentId(docId);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Saves {@code source} to the user's configured SAF folder, or MediaStore (Movies/ or Music/),
     * or public directory on API 24-28, or app-private external folder as last resort.
     * Deletes {@code source} ONLY when saving succeeds; throws {@link IOException} otherwise.
     */
    public static SaveOutcome saveToGallery(Context context, File source, String title, String extension) throws IOException {
        if (source == null || !source.exists()) {
            throw new IOException("Downloaded file missing");
        }
        String ext = normalizeExt(extension);
        String mime = mimeForExtension(ext);
        boolean isAudio = mime.startsWith("audio/");
        boolean isImage = mime.startsWith("image/");
        boolean videoMime = !isAudio && !isImage;

        String treeUriStr = context.getSharedPreferences(DowniNativeUtils.PREFS_SETTINGS, Context.MODE_PRIVATE)
                .getString("treeUri", "");
        Uri safParentDoc = null;
        String safFolderName = "";
        if (isSafTreeAccessible(context, treeUriStr)) {
            Uri tree = Uri.parse(treeUriStr);
            safParentDoc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
            safFolderName = getSafFolderDisplayName(context, treeUriStr);
        }

        String displayName = nextGalleryName(context, title, ext, safParentDoc);

        // 1. Custom SAF folder
        if (safParentDoc != null) {
            try {
                Uri destination = DocumentsContract.createDocument(context.getContentResolver(), safParentDoc, mime, displayName);
                if (destination != null) {
                    boolean copied = copyFileToUri(context, source, destination);
                    if (copied) {
                        scanSafDocument(context, destination, mime);
                        try { source.delete(); } catch (Exception ignored) {}
                        String safLabel = safFolderName.isEmpty()
                                ? "Custom folder"
                                : ("Custom folder (" + safFolderName + ")");
                        return new SaveOutcome(safLabel, destination.toString(), mime, 0L, videoMime);
                    }
                }
            } catch (Exception ignored) {}
        }

        // 2. MediaStore (API 29+ scoped storage; also works on some API 24-28 devices)
        boolean canWriteLegacy = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                || ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
                values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                String relDir = isAudio ? Environment.DIRECTORY_MUSIC : (isImage ? Environment.DIRECTORY_PICTURES : Environment.DIRECTORY_MOVIES);
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, relDir + "/");
                values.put(MediaStore.MediaColumns.IS_PENDING, 1);

                Uri collection = isAudio
                        ? MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                        : (isImage ? MediaStore.Images.Media.EXTERNAL_CONTENT_URI : MediaStore.Video.Media.EXTERNAL_CONTENT_URI);
                Uri destination = context.getContentResolver().insert(collection, values);
                if (destination != null) {
                    boolean copied = copyFileToUri(context, source, destination);
                    if (copied) {
                        ContentValues ready = new ContentValues();
                        ready.put(MediaStore.MediaColumns.IS_PENDING, 0);
                        context.getContentResolver().update(destination, ready, null, null);
                        long savedId = 0L;
                        try { savedId = Long.parseLong(destination.getLastPathSegment()); } catch (Exception ignored) {}
                        try { source.delete(); } catch (Exception ignored) {}
                        String destLabel = isAudio ? "Gallery / Music" : (isImage ? "Gallery / Pictures" : "Gallery / Movies");
                        return new SaveOutcome(destLabel,
                                destination.toString(), mime, savedId, videoMime);
                    } else {
                        try { context.getContentResolver().delete(destination, null, null); } catch (Exception ignored) {}
                    }
                }
            } catch (Exception ignored) {}
        }

        // 3. Public Media Directory (primarily API 24-28 with WRITE_EXTERNAL_STORAGE)
        if (canWriteLegacy) {
            try {
                File publicDir = Environment.getExternalStoragePublicDirectory(
                        isAudio ? Environment.DIRECTORY_MUSIC : (isImage ? Environment.DIRECTORY_PICTURES : Environment.DIRECTORY_MOVIES));
                if (!publicDir.exists()) publicDir.mkdirs();
                File destFile = new File(publicDir, displayName);
                copyFileToFile(source, destFile);
                MediaScannerConnection.scanFile(context, new String[]{destFile.getAbsolutePath()}, new String[]{mime}, null);
                try { source.delete(); } catch (Exception ignored) {}
                String contentUri = "";
                try {
                    contentUri = FileProvider.getUriForFile(
                            context, context.getPackageName() + ".fileprovider", destFile).toString();
                } catch (Exception ignored) {
                    contentUri = Uri.fromFile(destFile).toString();
                }
                return new SaveOutcome(isAudio ? "Gallery / Music" : "Gallery / Movies",
                        contentUri, mime, 0L, videoMime);
            } catch (Exception ignored) {}
        }

        // 4. App External Files fallback (honest label — never claim Gallery/Movies; empty mediaUri)
        try {
            File appDir = context.getExternalFilesDir(isAudio ? Environment.DIRECTORY_MUSIC : Environment.DIRECTORY_MOVIES);
            if (appDir != null) {
                if (!appDir.exists()) appDir.mkdirs();
                File destFile = new File(appDir, displayName);
                copyFileToFile(source, destFile);
                MediaScannerConnection.scanFile(context, new String[]{destFile.getAbsolutePath()}, new String[]{mime}, null);
                try { source.delete(); } catch (Exception ignored) {}
                return new SaveOutcome("App folder (DOWNI)", "", mime, 0L, videoMime);
            }
        } catch (Exception ignored) {}

        // All save methods failed: preserve source and report failure honestly.
        throw new IOException("Could not save media file to device storage.");
    }

    /**
     * Saves a downloaded subtitle file (.srt/.vtt) to the active SAF folder, or MediaStore.Downloads
     * on API 29+, or public Downloads on API 24-28, or app-private Downloads fallback.
     */
    public static SaveOutcome saveSubtitleFile(Context context, File source, String title, String extension) throws IOException {
        if (source == null || !source.exists()) {
            throw new IOException("Subtitle file missing");
        }
        String ext = normalizeExt(extension);
        String mime = mimeForExtension(ext);

        String treeUriStr = context.getSharedPreferences(DowniNativeUtils.PREFS_SETTINGS, Context.MODE_PRIVATE)
                .getString("treeUri", "");
        Uri safParentDoc = null;
        String safFolderName = "";
        if (isSafTreeAccessible(context, treeUriStr)) {
            Uri tree = Uri.parse(treeUriStr);
            safParentDoc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
            safFolderName = getSafFolderDisplayName(context, treeUriStr);
        }

        String displayName = nextGalleryName(context, title, ext, safParentDoc);

        // 1. Custom SAF folder
        if (safParentDoc != null) {
            try {
                Uri destination = DocumentsContract.createDocument(context.getContentResolver(), safParentDoc, mime, displayName);
                if (destination != null && copyFileToUri(context, source, destination)) {
                    try { source.delete(); } catch (Exception ignored) {}
                    String safLabel = safFolderName.isEmpty() ? "Custom folder" : ("Custom folder (" + safFolderName + ")");
                    return new SaveOutcome(safLabel, destination.toString(), mime, 0L, false);
                }
            } catch (Exception ignored) {}
        }

        // 2. MediaStore.Downloads (API 29+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
                values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/DOWNI/");
                values.put(MediaStore.MediaColumns.IS_PENDING, 1);

                Uri destination = context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (destination != null) {
                    if (copyFileToUri(context, source, destination)) {
                        ContentValues ready = new ContentValues();
                        ready.put(MediaStore.MediaColumns.IS_PENDING, 0);
                        context.getContentResolver().update(destination, ready, null, null);
                        long savedId = 0L;
                        try { savedId = Long.parseLong(destination.getLastPathSegment()); } catch (Exception ignored) {}
                        try { source.delete(); } catch (Exception ignored) {}
                        return new SaveOutcome("Downloads / DOWNI", destination.toString(), mime, savedId, false);
                    } else {
                        try { context.getContentResolver().delete(destination, null, null); } catch (Exception ignored) {}
                    }
                }
            } catch (Exception ignored) {}
        }

        // 3. Public Downloads directory (API 24-28 with WRITE_EXTERNAL_STORAGE)
        boolean canWriteLegacy = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                || ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        if (canWriteLegacy) {
            try {
                File dlDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (!dlDir.exists()) dlDir.mkdirs();
                File destFile = new File(dlDir, displayName);
                copyFileToFile(source, destFile);
                try { source.delete(); } catch (Exception ignored) {}
                String contentUri = "";
                try {
                    contentUri = FileProvider.getUriForFile(
                            context, context.getPackageName() + ".fileprovider", destFile).toString();
                } catch (Exception ignored) {}
                return new SaveOutcome("Downloads", contentUri, mime, 0L, false);
            } catch (Exception ignored) {}
        }

        // 4. App External Downloads fallback
        try {
            File appDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (appDir != null) {
                if (!appDir.exists()) appDir.mkdirs();
                File destFile = new File(appDir, displayName);
                copyFileToFile(source, destFile);
                try { source.delete(); } catch (Exception ignored) {}
                return new SaveOutcome("App folder (DOWNI)", "", mime, 0L, false);
            }
        } catch (Exception ignored) {}

        throw new IOException("Could not save subtitle file to device storage.");
    }

    private static boolean copyFileToUri(Context context, File source, Uri destination) throws IOException {
        try (InputStream input = new FileInputStream(source);
             OutputStream output = context.getContentResolver().openOutputStream(destination)) {
            if (output == null) return false;
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            output.flush();
            return true;
        }
    }

    private static void copyFileToFile(File source, File destFile) throws IOException {
        try (InputStream input = new FileInputStream(source);
             OutputStream output = new FileOutputStream(destFile)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            output.flush();
        }
    }

    private static String nextGalleryName(Context context, String title, String ext, Uri safParentDoc) {
        synchronized (NAME_LOCK) {
            String base = TitleSanitizer.sanitize(title);
            if (base.toLowerCase(Locale.US).endsWith("." + ext)) {
                base = base.substring(0, base.length() - ext.length() - 1).trim();
                if (base.isEmpty()) base = "Video";
            }
            String key = (base + "." + ext).toLowerCase(Locale.US);
            android.content.SharedPreferences names = context.getSharedPreferences("downi_saved_names", Context.MODE_PRIVATE);
            android.content.SharedPreferences legacyNames = context.getSharedPreferences("omni_saved_names", Context.MODE_PRIVATE);
            int number = names.getInt(key, -1);
            if (number < 0) {
                number = legacyNames.getInt(key, 0);
                if (number > 0) names.edit().putInt(key, number).apply();
            }
            Set<String> safExisting = safParentDoc != null ? listSafNames(context, safParentDoc) : null;
            String candidate = number == 0 ? base + "." + ext : base + " (" + number + ")." + ext;
            while (galleryNameExists(context, candidate)
                    || (safExisting != null && safExisting.contains(candidate.toLowerCase(Locale.US)))) {
                number++;
                candidate = base + " (" + number + ")." + ext;
            }
            names.edit().putInt(key, number + 1).apply();
            return candidate;
        }
    }

    private static Set<String> listSafNames(Context context, Uri safParentDoc) {
        Set<String> existing = new HashSet<>();
        try {
            String parentId = DocumentsContract.getDocumentId(safParentDoc);
            Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(safParentDoc, parentId);
            String[] proj = {DocumentsContract.Document.COLUMN_DISPLAY_NAME};
            try (Cursor c = context.getContentResolver().query(childrenUri, proj, null, null, null)) {
                if (c != null) {
                    while (c.moveToNext()) {
                        String n = c.getString(0);
                        if (n != null) existing.add(n.toLowerCase(Locale.US));
                    }
                }
            }
        } catch (Exception ignored) {}
        return existing;
    }

    private static boolean galleryNameExists(Context context, String displayName) {
        String[] projection = {MediaStore.MediaColumns._ID};
        String selection = MediaStore.MediaColumns.DISPLAY_NAME + "=?";
        try (Cursor cursor = context.getContentResolver().query(
                MediaStore.Files.getContentUri("external"), projection, selection, new String[]{displayName}, null)) {
            return cursor != null && cursor.moveToFirst();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void scanSafDocument(Context context, Uri documentUri, String mime) {
        try {
            String docId = DocumentsContract.getDocumentId(documentUri);
            int colon = docId.indexOf(':');
            if (colon <= 0) return;
            String device = docId.substring(0, colon);
            String rel = docId.substring(colon + 1);
            if (!"primary".equals(device) || rel.isEmpty()) return;
            File f = new File(Environment.getExternalStorageDirectory(), rel);
            MediaScannerConnection.scanFile(context, new String[]{f.getAbsolutePath()}, new String[]{mime}, null);
        } catch (Exception ignored) {}
    }
}
