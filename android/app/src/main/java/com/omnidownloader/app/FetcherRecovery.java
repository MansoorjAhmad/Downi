package com.omnidownloader.app;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;

/**
 * The Fetcher's recovery, in one place (Wave 3 — "never stranded").
 *
 * The vivo ABE force-stop clears `enabled_accessibility_services`; the Fetcher used to come
 * back only when the user opened DOWNI (MainActivity). Recovery is now an app-wide property:
 * EVERY DOWNI entry point re-arms (MainActivity, DropActivity, GrabTileService, and the
 * download service), and — the self-healing experiment (plan decision D-V2-4) — a 30-minute
 * framework JobScheduler job re-applies the binding without waiting for the user.
 *
 * The guards can never change:
 *   1. only a service the user themselves enabled (`wasArmed`, set by the service on connect),
 *   2. only while the user hasn't turned it off (`userEnabled`),
 *   3. only with the one-time adb WRITE_SECURE_SETTINGS grant present,
 *   4. the battery-low deferral keeps the cost honest.
 * Without the grant the app never touches secure settings — exactly as before.
 */
public final class FetcherRecovery {

    private static final String A11Y_COMPONENT =
            "com.omnidownloader.app/com.omnidownloader.app.DowniFetcherService";
    private static final int KEEPALIVE_JOB_ID = 3343;

    private FetcherRecovery() {}

    /** True when the Fetcher is user-wanted and the app may manage its own binding. */
    public static boolean wantedAndAllowed(Context context) {
        try {
            SharedPreferences prefs = context.getSharedPreferences("downi_fetcher", Context.MODE_PRIVATE);
            return prefs.getBoolean("wasArmed", false)
                    && prefs.getBoolean("userEnabled", true)
                    && context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
                        == android.content.pm.PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Re-apply the binding if it is missing. Returns the outcome as a machine-readable
     * string for the log: ok-present (already armed), ok-rearmed, skipped-guard, err-…
     */
    public static String ensureArmed(Context context) {
        try {
            if (!wantedAndAllowed(context)) return "skipped-guard";
            android.content.ContentResolver cr = context.getContentResolver();
            String current = android.provider.Settings.Secure.getString(
                    cr, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (current != null && current.contains(A11Y_COMPONENT)) {
                if (android.provider.Settings.Secure.getInt(
                        cr, android.provider.Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) {
                    android.provider.Settings.Secure.putInt(
                            cr, android.provider.Settings.Secure.ACCESSIBILITY_ENABLED, 1);
                }
                return "ok-present";
            }
            // Drop any stale DOWNI tokens (old component names survive a rename in the binding).
            StringBuilder kept = new StringBuilder();
            if (current != null) {
                for (String t : current.split(":")) {
                    if (t.trim().isEmpty() || t.contains("com.omnidownloader.app/")) continue;
                    if (kept.length() > 0) kept.append(':');
                    kept.append(t.trim());
                }
            }
            String next = kept.length() > 0 ? kept + ":" + A11Y_COMPONENT : A11Y_COMPONENT;
            android.provider.Settings.Secure.putString(
                    cr, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next);
            android.provider.Settings.Secure.putInt(
                    cr, android.provider.Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            android.util.Log.i("DOWNI", "fetcher re-armed after vendor wipe (wasArmed=true)");
            return "ok-rearmed";
        } catch (Throwable t) {
            return "err-" + t.getClass().getSimpleName();
        }
    }

    /**
     * D-V2-4 self-healing experiment: a periodic framework JobScheduler job that re-applies
     * the binding without the user opening anything. 30 minutes, battery-not-low, no new
     * permission (JobScheduler is framework; the WRITE_SECURE_SETTINGS grant was already a
     * one-time adb act). Unschedules itself when the guards say the Fetcher is not wanted.
     */
    public static void scheduleKeepAlive(Context context) {
        try {
            JobScheduler js = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            if (!wantedAndAllowed(context)) {
                if (js.getPendingJob(KEEPALIVE_JOB_ID) != null) {
                    js.cancel(KEEPALIVE_JOB_ID);
                    android.util.Log.i("DOWNI", "fetcher keep-alive unscheduled (guards)");
                }
                return;
            }
            if (js.getPendingJob(KEEPALIVE_JOB_ID) != null) return;   // already scheduled
            JobInfo job = new JobInfo.Builder(KEEPALIVE_JOB_ID,
                    new ComponentName(context, FetcherKeepAliveJob.class))
                    .setPeriodic(30 * 60 * 1000L)
                    .setRequiresBatteryNotLow(true)
                    .setPersisted(false)
                    .build();
            int r = js.schedule(job);
            android.util.Log.i("DOWNI", "fetcher keep-alive scheduled r=" + r);
        } catch (Throwable t) {
            android.util.Log.i("DOWNI", "fetcher keep-alive schedule failed: " + t);
        }
    }
}
