package com.omnidownloader.app;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * The Fetcher's bench — the file plumbing of the device gates (DEBUG builds only).
 *
 * Wave 0 extraction, behavior-preserving: the forensic spike log, the handoff gate
 * file, the small command files and the API 34+ screenshot diagnostics used to live
 * as fields and methods inside {@link DowniFetcherService}; the service keeps the
 * orchestration (pollers, command interpreters, chain state) and this class only
 * touches disk. Every public entry point is a no-op or a safe default in a release
 * build, so a release binary carries no bench I/O at all.
 *
 * One rebind hazard is fixed here as a side effect: {@code onServiceConnected} can
 * run again after a clean rebind without {@code onDestroy} (this ROM wipes and
 * restores the a11y binding on its own), and the old instance-owned writer thread
 * was started unconditionally — a rebind could leak a SECOND writer draining the
 * same queue. {@link #openLog} now refuses to start a second writer while one is
 * alive.
 */
public final class FetcherBench {
    private static final String POISON = new String("close");   // writer queue stop marker
    private static final long LOG_CAP_BYTES = 15L * 1024 * 1024;
    private static final int MAX_SHOTS_PER_SESSION = 6;

    private static final LinkedBlockingQueue<String> outbox = new LinkedBlockingQueue<>();
    private static Thread writer;
    private static BufferedWriter fileOut;
    private static long logBytes;
    private static volatile boolean closing;
    private static int shotCount;
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final Executor mainExec = new Executor() {
        @Override public void execute(Runnable r) { main.post(r); }
    };

    private FetcherBench() {}

    /** True in debug builds — the only builds where the bench exists at all. */
    public static boolean enabled() {
        return BuildConfig.DEBUG;
    }

    /** The bench directory under this app's external files dir. */
    public static File dir(android.content.Context c) {
        File base = c.getExternalFilesDir(null);
        if (base == null) base = c.getFilesDir();
        return new File(base, "fetch-spike");
    }

    /**
     * Open a fresh forensic log file and arm the writer thread. Safe on a rebind: a
     * living writer is reused instead of duplicated (the old code leaked one per bind).
     */
    public static void openLog(File dir) {
        if (!BuildConfig.DEBUG) return;
        dir.mkdirs();
        if (writer != null && writer.isAlive()) {
            logRaw(ts() + " BENCH_LOG_REUSED rebind=1");
            return;
        }
        closing = false;
        logBytes = 0;
        try {
            fileOut = new BufferedWriter(new FileWriter(new File(dir, "spike_" + tsFile() + ".log")));
        } catch (Exception e) {
            Log.e("DowniFetcher", "cannot open bench log", e);
            fileOut = null;
            return;
        }
        writer = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    while (!closing) {
                        String line = outbox.take();
                        if (line == POISON) break;
                        writeFile(line);
                    }
                    String extra;
                    while ((extra = outbox.poll()) != null && extra != POISON) writeFile(extra);
                } catch (InterruptedException ignored) {}
            }
        }, "fetcher-bench-log");
        writer.start();
    }

    /** Queue one already-timestamped line for the file log (no-op in release). */
    public static void logRaw(String line) {
        if (!BuildConfig.DEBUG) return;      // no queue growth when the writer is off
        outbox.offer(line);
    }

    /** Flush, poison and join the writer; close the file. Safe to call more than once. */
    public static void closeLog() {
        closing = true;
        outbox.offer(POISON);
        try { if (writer != null) writer.join(300); } catch (InterruptedException ignored) {}
        writer = null;
        try { if (fileOut != null) fileOut.close(); } catch (Exception ignored) {}
        fileOut = null;
    }

    private static synchronized void writeFile(String s) {
        if (fileOut == null) return;
        try {
            String line = s.endsWith("\n") ? s : s + "\n";
            if (logBytes <= LOG_CAP_BYTES) {
                fileOut.write(line);
                logBytes += line.length();
                if (logBytes > LOG_CAP_BYTES) {
                    fileOut.write("=== LOG CAP REACHED — remaining lines dropped ===\n");
                }
            }
            fileOut.flush();
        } catch (Exception ignored) {}
    }

    /**
     * The auto-handoff bench gate (fetch-spike/spike_config.properties, handoff=true).
     * Always false in release: nothing may ever download without a tap in a shipped build.
     */
    public static boolean readHandoffGate(File dir) {
        if (!BuildConfig.DEBUG) return false;
        try {
            File cfg = new File(dir, "spike_config.properties");
            if (!cfg.exists()) return false;
            Properties p = new Properties();
            InputStream in = new FileInputStream(cfg);
            try { p.load(in); } finally { in.close(); }
            return "true".equalsIgnoreCase(p.getProperty("handoff", "false"));
        } catch (Throwable t) {
            return false;
        }
    }

    /** Read one small command file (the bench channels), or null. */
    public static String readSmallFile(File f) {
        try {
            InputStream in = new FileInputStream(f);
            try {
                byte[] buf = new byte[256];
                int n = in.read(buf);
                return n > 0 ? new String(buf, 0, n, "UTF-8") : "";
            } finally { in.close(); }
        } catch (Throwable t) { return null; }
    }

    /**
     * One diagnostic screenshot request (API 34+ platform capability, debug builds only,
     * capped per session). The a11y capability itself is declared ONLY in the debug
     * source set's fetcher_service.xml — release never has it.
     */
    public static void screenshot(final AccessibilityService svc, final String reason) {
        if (!BuildConfig.DEBUG) return;
        if (Build.VERSION.SDK_INT < 34) {
            logRaw(ts() + " SCREENSHOT_SKIPPED reason=" + reason + " why=sdk_below_34");
            return;
        }
        if (shotCount >= MAX_SHOTS_PER_SESSION) return;
        shotCount++;
        final File shotsDir = new File(dir(svc), "shots");
        try {
            svc.takeScreenshot(android.view.Display.DEFAULT_DISPLAY, mainExec,
                    new AccessibilityService.TakeScreenshotCallback() {
                        @Override public void onSuccess(AccessibilityService.ScreenshotResult result) {
                            saveShot(shotsDir, result, shotCount);
                        }
                        @Override public void onFailure(int error) {
                            logRaw(ts() + " SCREENSHOT_FAIL error=" + error);
                        }
                    });
            logRaw(ts() + " SCREENSHOT_REQUEST reason=" + reason + " n=" + shotCount);
        } catch (Throwable t) {
            logRaw(ts() + " SCREENSHOT_THROW reason=" + reason + " err=" + t);
        }
    }

    private static void saveShot(final File shotsDir, final AccessibilityService.ScreenshotResult result,
                                 final int n) {
        new Thread(new Runnable() {
            @Override public void run() {
                android.hardware.HardwareBuffer hb = null;
                Bitmap bm = null, copy = null;
                try {
                    shotsDir.mkdirs();
                    File f = new File(shotsDir, "shot_" + tsFile() + "_" + n + ".png");
                    hb = result.getHardwareBuffer();
                    bm = Bitmap.wrapHardwareBuffer(hb, result.getColorSpace());
                    if (bm == null) { logRaw(ts() + " SCREENSHOT_NULL_BITMAP"); return; }
                    copy = bm.copy(Bitmap.Config.ARGB_8888, false);
                    if (copy == null) { logRaw(ts() + " SCREENSHOT_COPY_FAIL"); return; }
                    FileOutputStream fos = new FileOutputStream(f);
                    copy.compress(Bitmap.CompressFormat.PNG, 100, fos);
                    fos.close();
                    logRaw(ts() + " SCREENSHOT_SAVED path=" + f.getName() + " ts=" + result.getTimestamp());
                } catch (Throwable t) {
                    logRaw(ts() + " SCREENSHOT_SAVE_FAIL " + t);
                } finally {
                    if (copy != null) copy.recycle();
                    if (bm != null) bm.recycle();
                    if (hb != null) hb.close();
                }
            }
        }, "fetcher-bench-shot").start();
    }

    private static String ts() {
        return new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
    }

    private static String tsFile() {
        return new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
    }
}
