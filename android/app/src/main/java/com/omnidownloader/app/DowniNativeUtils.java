package com.omnidownloader.app;

import android.content.Context;
import android.content.SharedPreferences;

import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared native utilities for DOWNI:
 * - Thread-safe Chaquopy runtime startup
 * - Synchronized SharedPreferences JSON access (downi_settings)
 * - Collision-free job IDs
 * - URL extraction + tracking-param stripping for DowniDrop
 * - Shared byte/speed/ETA formatting and updater host allowlist
 */
public final class DowniNativeUtils {

    public static final String PREFS_SETTINGS = "downi_settings";
    public static final Object PREFS_LOCK = new Object();
    private static final Object PYTHON_LOCK = new Object();
    private static final AtomicLong JOB_SEQ = new AtomicLong(0);

    private static final Pattern URL_PATTERN = Pattern.compile("https?://\\S+");
    private static final Set<String> TRACKING_PARAMS = new HashSet<>(Arrays.asList(
            "igsh", "igshid", "si", "fbclid", "gclid", "mc_cid", "mc_eid",
            "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content", "utm_id"
    ));

    private static final Set<String> ALLOWED_UPDATE_HOSTS = new HashSet<>(Arrays.asList(
            "github.com",
            "objects.githubusercontent.com",
            "release-assets.githubusercontent.com",
            "github-releases.githubusercontent.com"
    ));

    private DowniNativeUtils() {}

    /** Thread-safe Chaquopy initialization across MainActivity, Plugin, and Service. */
    public static void ensurePythonStarted(Context context) {
        if (Python.isStarted()) return;
        synchronized (PYTHON_LOCK) {
            if (!Python.isStarted()) {
                Python.start(new AndroidPlatform(context.getApplicationContext()));
            }
        }
    }

    /** Collision-free job identifier even when multiple shares arrive in the same millisecond. */
    public static String nextJobId(String prefix) {
        return prefix + System.currentTimeMillis() + "_" + JOB_SEQ.incrementAndGet();
    }

    /** Extract the first HTTP(S) URL from shared text, trimming punctuation and tracking params. */
    public static String extractAndCleanUrl(String text) {
        if (text == null) return null;
        Matcher m = URL_PATTERN.matcher(text);
        if (!m.find()) return null;
        String raw = m.group().replaceAll("[.,;:!?\"'<>\\)\\]\\}]+$", "");
        return stripTrackingParams(raw);
    }

    /** Pure-Java tracking-parameter stripper (safe for JVM unit tests). */
    public static String stripTrackingParams(String url) {
        if (url == null || url.isEmpty()) return url;
        try {
            int qIdx = url.indexOf('?');
            if (qIdx < 0) return url;
            int hashIdx = url.indexOf('#', qIdx);
            String base = url.substring(0, qIdx);
            String query = hashIdx >= 0 ? url.substring(qIdx + 1, hashIdx) : url.substring(qIdx + 1);
            String fragment = hashIdx >= 0 ? url.substring(hashIdx) : "";
            if (query.isEmpty()) return base + fragment;

            StringBuilder kept = new StringBuilder();
            String[] pairs = query.split("&");
            for (String pair : pairs) {
                if (pair.isEmpty()) continue;
                int eq = pair.indexOf('=');
                String key = (eq >= 0 ? pair.substring(0, eq) : pair).toLowerCase(Locale.US);
                if (TRACKING_PARAMS.contains(key)) continue;
                if (kept.length() > 0) kept.append('&');
                kept.append(pair);
            }
            if (kept.length() == 0) return base + fragment;
            return base + "?" + kept + fragment;
        } catch (Exception e) {
            return url;
        }
    }

    /** Allow only HTTPS URLs hosted on GitHub Releases or GitHub release-asset CDNs. */
    public static boolean isAllowedUpdateUrl(String urlString) {
        if (urlString == null || urlString.trim().isEmpty()) return false;
        try {
            URI uri = URI.create(urlString.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme())) return false;
            String host = uri.getHost();
            if (host == null || host.isEmpty()) return false;
            return ALLOWED_UPDATE_HOSTS.contains(host.toLowerCase(Locale.US));
        } catch (Exception e) {
            return false;
        }
    }

    /** Normalizes Anti-Repost Shield tier strictly to "deep" or "standard". */
    public static String normalizeShieldTier(String tier) {
        return "deep".equalsIgnoreCase(tier != null ? tier.trim() : "") ? "deep" : "standard";
    }

    /** Pure-Java check that a saved media URI uses a local openable scheme (content:// or file://). */
    public static boolean isSupportedMediaUri(String uriString) {
        if (uriString == null || uriString.trim().isEmpty()) return false;
        try {
            URI uri = URI.create(uriString.trim());
            String scheme = uri.getScheme();
            return "content".equalsIgnoreCase(scheme) || "file".equalsIgnoreCase(scheme);
        } catch (Exception e) {
            return false;
        }
    }

    /** 1024-based MB/GB formatting matching DOWNI's established UI convention. */
    public static String formatBytes(long bytes) {
        if (bytes <= 0) return "0 MB";
        double mb = bytes / (1024.0 * 1024.0);
        if (mb >= 1024.0) return String.format(Locale.US, "%.2f GB", mb / 1024.0);
        if (mb >= 100.0) return String.format(Locale.US, "%.0f MB", mb);
        return String.format(Locale.US, "%.1f MB", mb);
    }

    public static String formatSpeed(double bytesPerSec) {
        if (bytesPerSec <= 0) return "";
        double mbPerSec = bytesPerSec / (1024.0 * 1024.0);
        if (mbPerSec >= 1.0) return String.format(Locale.US, "%.1f MB/s", mbPerSec);
        return String.format(Locale.US, "%.0f KB/s", bytesPerSec / 1024.0);
    }

    public static String formatEta(long etaSec) {
        return etaSec > 0 ? (etaSec + "s left") : "";
    }

    public interface JsonObjectMutator {
        void mutate(JSONObject obj) throws Exception;
    }

    public interface JsonArrayMutator {
        JSONArray mutate(JSONArray arr) throws Exception;
    }

    public static void mutateJsonObjectPref(Context context, String key, JsonObjectMutator mutator) {
        synchronized (PREFS_LOCK) {
            try {
                SharedPreferences prefs = context.getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE);
                String raw = prefs.getString(key, "{}");
                JSONObject obj = (raw == null || raw.isEmpty()) ? new JSONObject() : new JSONObject(raw);
                mutator.mutate(obj);
                prefs.edit().putString(key, obj.toString()).apply();
            } catch (Exception ignored) {}
        }
    }

    public static void mutateJsonArrayPref(Context context, String key, JsonArrayMutator mutator) {
        synchronized (PREFS_LOCK) {
            try {
                SharedPreferences prefs = context.getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE);
                String raw = prefs.getString(key, "[]");
                JSONArray arr = (raw == null || raw.isEmpty()) ? new JSONArray() : new JSONArray(raw);
                JSONArray next = mutator.mutate(arr);
                if (next != null) {
                    prefs.edit().putString(key, next.toString()).apply();
                }
            } catch (Exception ignored) {}
        }
    }

    public static void addLongPref(Context context, String key, long delta) {
        if (delta <= 0) return;
        synchronized (PREFS_LOCK) {
            try {
                SharedPreferences prefs = context.getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE);
                prefs.edit().putLong(key, prefs.getLong(key, 0L) + delta).apply();
            } catch (Exception ignored) {}
        }
    }
}
