package com.omnidownloader.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Set;

/**
 * The job-state bus contract (Wave 0).
 *
 * `downi_settings/dropLive` is the ONE source of truth for headless download jobs:
 * {@link DowniDownloadService} writes it (same 800 ms floor as its notifications), and
 * three readers consume it — the app's Queue (DowniEnginePlugin.getDropJobs), the Downi
 * Core (downicore/CoreJobBinding), and the Fetcher's own duplicate checks. Each reader
 * used to carry its own copy of the TTL and the row-scanning rules; drift between those
 * copies is exactly the class of truth bug v3.1.1 fought (defects N8-N13). This class is
 * the one home for the shared rules.
 *
 * Behavior-preserving by design: every rule below is the rule its single reader already
 * had. The write-side prune (running rows never age out on write) stays in the service —
 * it is the writer's half of the contract and is documented there.
 */
public final class JobSnapshot {

    /** Terminal (done/failed/canceled) rows expire after this — the shared card TTL (N8). */
    public static final long TERMINAL_TTL_MS = 20_000L;

    /** The service keeps at most this many rows in the snapshot (newest last). */
    public static final int MAX_ROWS = 8;

    static final String PREFS = "downi_settings";
    static final String KEY = "dropLive";

    private JobSnapshot() {}

    /** The raw snapshot as stored, never null. */
    public static JSONArray read(Context context) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String raw = prefs.getString(KEY, "");
            if (raw == null || raw.isEmpty()) return new JSONArray();
            return new JSONArray(raw);
        } catch (Throwable t) {
            return new JSONArray();
        }
    }

    /** Rewrite the snapshot (the service and the read-pruning plugin are the only writers). */
    public static void write(Context context, JSONArray list) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY, list.toString()).apply();
        } catch (Exception ignored) {}
    }

    /**
     * The newest row for {@code url}, or null. Rows are appended newest-last, so the LAST
     * match is the newest. This is the read {@link com.omnidownloader.app.downicore.CoreJobBinding}
     * tracks a job by.
     */
    public static JSONObject newestRowFor(Context context, String url) {
        if (url == null || url.isEmpty()) return null;
        JSONObject found = null;
        JSONArray list = read(context);
        for (int i = 0; i < list.length(); i++) {
            JSONObject o = list.optJSONObject(i);
            if (o != null && url.equals(o.optString("url"))) found = o;
        }
        return found;
    }

    /**
     * The newest row for {@code url} whose state equals {@code stateWanted} (null = any),
     * or null. This is the read the Fetcher's debug pause/resume commands use.
     */
    public static JSONObject newestRowFor(Context context, String url, String stateWanted) {
        if (url == null || url.isEmpty()) return null;
        JSONObject found = null;
        JSONArray list = read(context);
        for (int i = 0; i < list.length(); i++) {
            JSONObject o = list.optJSONObject(i);
            if (o == null) continue;
            if (!url.equals(o.optString("url"))) continue;
            if (stateWanted != null && !stateWanted.equals(o.optString("state"))) continue;
            found = o;
        }
        return found;
    }

    /**
     * Which snapshot state {@code url} currently has, or null — the pre-tap duplicate
     * check. A live job wins outright; a terminal row counts only inside its TTL window.
     */
    public static String activeStateFor(Context context, String url) {
        if (url == null || url.isEmpty()) return null;
        try {
            JSONArray list = read(context);
            long now = System.currentTimeMillis();
            String state = null;
            for (int i = 0; i < list.length(); i++) {
                JSONObject o = list.optJSONObject(i);
                if (o == null || !url.equals(o.optString("url"))) continue;
                String s = o.optString("state", "running");
                if ("running".equals(s)) return s;                        // a live job wins outright
                if (now - o.optLong("ts", now) <= TERMINAL_TTL_MS) state = s;
            }
            return state;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Read-prune: what the app may display (N8 + N11). A "running" row is only honest while
     * the service still holds that grab — {@code liveJobIds == null} means no service is
     * alive at all, so every running row is stale. Terminal rows expire on age. Prune only
     * removes, so the caller can detect a rewrite need by comparing lengths.
     */
    public static JSONArray pruneForDisplay(JSONArray list, Set<String> liveJobIds, long now) {
        JSONArray kept = new JSONArray();
        for (int i = 0; i < list.length(); i++) {
            JSONObject o = list.optJSONObject(i);
            if (o == null) continue;
            boolean running = "running".equals(o.optString("state"));
            if (running) {
                if (liveJobIds == null || !liveJobIds.contains(o.optString("id"))) continue;
            } else if (now - o.optLong("ts", now) > TERMINAL_TTL_MS) {
                continue;
            }
            kept.put(o);
        }
        return kept;
    }
}
