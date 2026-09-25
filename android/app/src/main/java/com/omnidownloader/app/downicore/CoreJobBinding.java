package com.omnidownloader.app.downicore;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * V3.2 Downi Core — the job binding (master package §30/§31: ONE JOB = ONE SOURCE OF TRUTH).
 *
 * The Core is the *face* of {@link com.omnidownloader.app.DowniDownloadService}, never a second
 * download manager. This class is a read-only observer: after a tap delivers a URL, it watches the
 * service's own job snapshot (`downi_settings/dropLive` — the same rows the in-app Queue renders,
 * written by the service on the same 800 ms floor as its notifications) and turns that job's real
 * state into Core states:
 *
 *   running  → PROGRESS   (the perimeter IS the job's percent — sheet 4)
 *   done     → COMPLETING → settles to COMPLETE in the host view
 *   failed   → FAILED
 *   canceled → IDLE
 *
 * It never writes jobs, files or the Vault (Core invariant 4). Terminal rows in the snapshot are
 * only pruned by the service's NEXT write, so this binding re-applies the same 20 s terminal TTL
 * the app uses — a finished job must fade out, not linger on the Core.
 *
 * The state mapping is pure and unit-tested (`CoreJobBindingTest`); the Android-specific part is
 * only the prefs read and the poll loop.
 */
public final class CoreJobBinding {

    /** How the Core should look for one job snapshot row. */
    public static final class JobView {
        public final String coreState;   // a CoreStates name, or null when nothing is live
        public final float progress;     // 0..1, only meaningful for PROGRESS/FAILED
        public final boolean terminal;   // true when the tracking should stop after this view

        JobView(String coreState, float progress, boolean terminal) {
            this.coreState = coreState;
            this.progress = progress;
            this.terminal = terminal;
        }
    }

    /** Terminal snapshot rows expire after this — mirrors DowniDownloadService's card TTL. */
    public static final long TERMINAL_TTL_MS = 20_000L;
    /** Poll cadence — the service writes on an 800 ms floor, so this reads every write. */
    private static final long POLL_MS = 800L;

    public interface Listener {
        /** One snapshot read → what the Core should show. Always called on the main thread. */
        void onJobView(JobView view);
    }

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            try {
                JobView v = readSnapshot();
                if (v != null) listener.onJobView(v);
                if (v == null || !v.terminal) main.postDelayed(this, POLL_MS);
                else polling = false;
            } catch (Throwable t) {
                main.postDelayed(this, POLL_MS);
            }
        }
    };
    private boolean polling;
    private String trackedUrl;

    public CoreJobBinding(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    /** Track the job for {@code url} — the URL a Core tap just delivered. */
    public synchronized void track(String url) {
        if (url == null || url.isEmpty()) return;
        trackedUrl = url;
        if (!polling) {
            polling = true;
            main.post(poll);
        }
    }

    /** Stop watching (service shutting down, Core hidden). */
    public synchronized void stop() {
        trackedUrl = null;
        polling = false;
        main.removeCallbacks(poll);
    }

    public synchronized boolean isTracking() {
        return polling;
    }

    /**
     * The newest snapshot row for the tracked URL, mapped to a {@link JobView}.
     * Returns null when nothing is live for it (unknown, expired, or service snapshot gone).
     */
    private JobView readSnapshot() {
        String url;
        synchronized (this) {
            url = trackedUrl;
        }
        if (url == null) return null;
        JSONObject row = newestRowFor(url);
        if (row == null) {
            return new JobView(CoreStates.IDLE, 0f, true);   // job vanished from the snapshot
        }
        return viewFor(row, System.currentTimeMillis());
    }

    private JSONObject newestRowFor(String url) {
        try {
            SharedPreferences prefs = context.getSharedPreferences("downi_settings", Context.MODE_PRIVATE);
            String raw = prefs.getString("dropLive", "");
            if (raw == null || raw.isEmpty()) return null;
            JSONArray list = new JSONArray(raw);
            JSONObject found = null;
            for (int i = 0; i < list.length(); i++) {
                JSONObject o = list.optJSONObject(i);
                if (o == null) continue;
                if (url.equals(o.optString("url"))) found = o;   // rows are appended newest-last
            }
            return found;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Pure mapping: one snapshot row → what the Core shows. Unit-tested. */
    public static JobView viewFor(JSONObject row, long nowMs) {
        return viewFor(row.optString("state", "running"),
                row.optDouble("pct", 0), row.optLong("ts", nowMs), nowMs);
    }

    /** The mapping's pure core — primitives only, so the JVM suite needs no Android classes. */
    public static JobView viewFor(String dropState, double pctRaw, long ts, long nowMs) {
        String state = dropState == null ? "running" : dropState;
        float pct = Math.max(0f, Math.min(1f, (float) pctRaw / 100f));

        if ("running".equals(state)) {
            return new JobView(CoreStates.PROGRESS, pct, false);
        }
        // Terminal rows are only pruned by the service's next write, so age them out here too.
        if (nowMs - ts > TERMINAL_TTL_MS) {
            return new JobView(CoreStates.IDLE, 0f, true);
        }
        if ("done".equals(state)) {
            return new JobView(CoreStates.COMPLETING, 1f, false);   // host settles it to COMPLETE
        }
        if ("failed".equals(state)) {
            return new JobView(CoreStates.FAILED, pct, false);
        }
        if ("canceled".equals(state)) {
            return new JobView(CoreStates.IDLE, 0f, true);
        }
        return new JobView(CoreStates.PROGRESS, pct, false);
    }

    /** Which job snapshot state an URL currently has, or null — the pre-tap duplicate check. */
    public static String activeStateFor(Context context, String url) {
        if (url == null || url.isEmpty()) return null;
        try {
            SharedPreferences prefs = context.getSharedPreferences("downi_settings", Context.MODE_PRIVATE);
            String raw = prefs.getString("dropLive", "");
            if (raw == null || raw.isEmpty()) return null;
            JSONArray list = new JSONArray(raw);
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
}
