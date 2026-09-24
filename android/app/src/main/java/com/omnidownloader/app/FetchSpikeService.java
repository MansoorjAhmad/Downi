package com.omnidownloader.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.graphics.Bitmap;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * V3.2 Phase 0 — detectability spike (owner approval 2026-09-24, HARD GATE).
 *
 * Answers the four spike questions by LOGGING ONLY — this service never draws a
 * bubble and never downloads anything:
 *   Q1 foreground package (SESSION_START/END)
 *   Q2 video-watching signals in the accessibility tree (STEP2)
 *   Q3 any URL/identifier for the current video (STEP3 + confidence)
 *   Q4 whether the result can reach DowniDownloadService.startShared (PIPELINE_READY;
 *      a real handoff only when fetch-spike/spike_config.properties says handoff=true)
 *
 * Screenshots (API 34+) are a diagnostic fallback only, exactly per plan §Phase 0.
 * Everything is written to files/fetch-spike/ on this device — local only, delete
 * after analysis. Debug tool; never part of a release build.
 */
public class FetchSpikeService extends AccessibilityService {
    private static final String TAG = "DowniSpike";
    private static final String POISON = new String("close"); // writer queue stop marker

    private static final Set<String> TARGETS = new HashSet<>();
    static {
        TARGETS.add("com.instagram.android");
        TARGETS.add("com.zhiliaoapp.musically"); // TikTok (global)
        TARGETS.add("com.ss.android.ugc.trill"); // TikTok (alternate build)
    }

    private static final Pattern URL_P = Pattern.compile("https?://\\S+");
    private static final Pattern IG_SHORT_P =
            Pattern.compile("instagram\\.com/(?:p|reel|reels|tv)/([A-Za-z0-9_-]{5,64})");
    private static final Pattern TIKTOK_ID_P =
            Pattern.compile("tiktok\\.com/(?:@[^/\\s]+/)?video/(\\d{8,25})");
    private static final Pattern SIGNAL_P =
            Pattern.compile("(?i)seek|progress|player|duration|video|media|scrub|exo");

    private static final long MIN_DUMP_GAP_MS = 600;   // never dump faster than this
    private static final long SETTLE_MS = 650;         // wait after a scroll before dumping
    private static final int MAX_NODES = 1500;         // tree walk guard
    private static final int MAX_DEPTH = 40;           // depth guard
    private static final long LOG_CAP_BYTES = 15L * 1024 * 1024;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final LinkedBlockingQueue<String> outbox = new LinkedBlockingQueue<>();
    private final Executor mainExec = new Executor() {
        @Override public void execute(Runnable r) { main.post(r); }
    };

    private Thread writer;
    private BufferedWriter fileOut;
    private long logBytes;
    private volatile boolean closing;

    private String sessionPkg;            // non-null while a target app is foreground
    private long sessionStart;
    private int dumpCount, sessionDumps, shotCount, step3Hits;
    private long lastDumpAt, lastScrollLogAt;
    private String lastDumpBody = "";
    private Runnable settleDump;
    private final Set<String> seenUrls = new HashSet<>();  // one PIPELINE line per URL
    private final LinkedHashSet<String> signals = new LinkedHashSet<>();
    private boolean handoffEnabled;

    private static final class Counter { int nodes; }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();

        // Screenshot capability is declared in XML (android:canTakeScreenshot, API 34).
        File dir = spikeDir();
        dir.mkdirs();
        handoffEnabled = readHandoffGate(dir);
        try {
            fileOut = new BufferedWriter(new FileWriter(new File(dir, "spike_" + tsFile() + ".log")));
        } catch (Exception e) {
            Log.e(TAG, "cannot open spike log", e);
        }
        startWriter();

        log("=== DOWNI FETCHER SPIKE — Phase 0 (LOCAL ONLY; delete after analysis) ===");
        log("SERVICE_CONNECTED sdk=" + Build.VERSION.SDK_INT + " handoff=" + handoffEnabled);
        log("CONTRACT target=DowniDownloadService.startShared(context,url)");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        try {
            if (event == null) return;
            int type = event.getEventType();
            String pkg = event.getPackageName() != null ? event.getPackageName().toString() : null;

            if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                if (pkg != null && TARGETS.contains(pkg)) {
                    if (!pkg.equals(sessionPkg)) startSession(pkg);
                    dump("window_state:" + clip(classNameOf(event), 60));
                } else if (sessionPkg != null) {
                    endSession("other_app:" + pkg);
                }
                return;
            }

            if (sessionPkg == null) return;                          // outside target apps
            if (pkg != null && !sessionPkg.equals(pkg)) return;      // stale event from elsewhere

            if (type == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
                long now = SystemClock.elapsedRealtime();
                if (now - lastScrollLogAt > 1000) {
                    lastScrollLogAt = now;
                    log("STEP2_SCROLL pkg=" + sessionPkg + " from=" + event.getFromIndex()
                            + " to=" + event.getToIndex());
                }
                scheduleSettleDump("scroll_settle");
            } else if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
                maybeDump("content_changed");
            }
        } catch (Throwable t) {
            log("EVENT_ERROR " + t);
        }
    }

    @Override
    public void onInterrupt() {
        log("INTERRUPT");
    }

    // ---------- sessions (Q1) ----------

    private void startSession(String pkg) {
        sessionPkg = pkg;
        sessionStart = System.currentTimeMillis();
        dumpCount = sessionDumps = shotCount = step3Hits = 0;
        lastDumpBody = "";
        seenUrls.clear();
        log("SESSION_START pkg=" + pkg);
        maybeScreenshot("session_start");
    }

    private void endSession(String why) {
        log("SESSION_END pkg=" + sessionPkg + " why=" + why
                + " duration_ms=" + (System.currentTimeMillis() - sessionStart)
                + " dumps=" + dumpCount + " step3_hits=" + step3Hits + " shots=" + shotCount);
        if (settleDump != null) main.removeCallbacks(settleDump);
        settleDump = null;
        sessionPkg = null;
        lastDumpBody = "";
    }

    // ---------- tree dumps (Q2 + Q3) ----------

    private void scheduleSettleDump(final String reason) {
        if (settleDump != null) main.removeCallbacks(settleDump);
        settleDump = new Runnable() {
            @Override public void run() { maybeDump(reason); }
        };
        main.postDelayed(settleDump, SETTLE_MS);
    }

    private void maybeDump(String reason) {
        if (SystemClock.elapsedRealtime() - lastDumpAt < MIN_DUMP_GAP_MS) return;
        dump(reason);
    }

    private void dump(String reason) {
        lastDumpAt = SystemClock.elapsedRealtime();
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            log("DUMP_NULL reason=" + reason);   // finding: root unavailable
            return;
        }
        StringBuilder body = new StringBuilder(16 * 1024);
        StringBuilder corpus = new StringBuilder(16 * 1024);
        Counter c = new Counter();
        signals.clear();
        walk(root, 0, body, corpus, c);

        String bodyStr = body.toString();
        if (bodyStr.equals(lastDumpBody)) {
            log("DUMP reason=" + reason + " nodes=" + c.nodes + " identical_to_previous");
            return;                              // candidates unchanged as well
        }
        lastDumpBody = bodyStr;
        dumpCount++;
        sessionDumps++;
        log("DUMP_" + dumpCount + " reason=" + reason + " pkg=" + sessionPkg + " nodes=" + c.nodes);
        log(bodyStr);                            // full tree, one chunk
        extractAndVerdict(corpus.toString());
        if (sessionDumps == 4) maybeScreenshot("dump4");
    }

    private void walk(AccessibilityNodeInfo node, int depth, StringBuilder body,
                      StringBuilder corpus, Counter c) {
        if (node == null || c.nodes >= MAX_NODES || depth > MAX_DEPTH) return;
        c.nodes++;
        String cls = node.getClassName() != null ? node.getClassName().toString() : "";
        String id = node.getViewIdResourceName();
        CharSequence t = node.getText();
        CharSequence d = node.getContentDescription();

        body.append(depth).append('|')
                .append(clip(cls, 60)).append('|')
                .append(clip(id == null ? "" : id, 90)).append('|')
                .append(clip(t == null ? "" : t.toString(), 120)).append('|')
                .append(clip(d == null ? "" : d.toString(), 120)).append('|')
                .append(node.isClickable() ? 1 : 0).append('\n');

        if (t != null) corpus.append(t).append('\n');
        if (d != null) corpus.append(d).append('\n');
        if (id != null) corpus.append(id).append('\n');

        String sigSrc = id != null ? id : cls;
        if (!sigSrc.isEmpty() && SIGNAL_P.matcher(sigSrc).find()) signals.add(clip(sigSrc, 80));

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = null;
            try { child = node.getChild(i); } catch (Throwable ignored) {}
            walk(child, depth + 1, body, corpus, c);
        }
    }

    private void extractAndVerdict(String corpus) {
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        Matcher m = URL_P.matcher(corpus);
        while (m.find() && urls.size() < 8) {
            urls.add(m.group().replaceAll("[\\.,;:!?)\\]\"']+$", ""));
        }
        m = IG_SHORT_P.matcher(corpus);
        while (m.find() && ids.size() < 8) ids.add("ig:" + m.group(1));
        m = TIKTOK_ID_P.matcher(corpus);
        while (m.find() && ids.size() < 8) ids.add("tiktok:" + m.group(1));

        if (!signals.isEmpty()) {
            log("STEP2_DUMP_" + dumpCount + " watching_signals=" + clip(signals.toString(), 300));
        }
        String url = urls.isEmpty() ? null : urls.iterator().next();
        if (url != null) {
            step3Hits++;
            log("STEP3_DUMP_" + dumpCount + " confidence=HIGH source=tree url_count=" + urls.size()
                    + " url=" + clip(url, 300));
            pipeline(url);
        } else if (!ids.isEmpty()) {
            step3Hits++;
            log("STEP3_DUMP_" + dumpCount + " confidence=MEDIUM source=tree ids="
                    + clip(ids.toString(), 300) + " note=identifier_only_resolver_required");
        } else {
            log("STEP3_DUMP_" + dumpCount + " confidence=NONE note=no_url_or_id_in_tree signals="
                    + (signals.isEmpty() ? "none" : clip(signals.toString(), 200)));
        }
    }

    // ---------- Q4: pipeline contract ----------

    private void pipeline(String url) {
        if (!seenUrls.add(url)) return;          // one PIPELINE line per URL
        if (!handoffEnabled) {
            log("PIPELINE_READY contract=DowniDownloadService.startShared(url) handoff=disabled url="
                    + clip(url, 300));
            return;
        }
        try {
            DowniDownloadService.startShared(this, url);
            log("PIPELINE_HANDOFF_OK url=" + clip(url, 300));
        } catch (Throwable t) {
            log("PIPELINE_HANDOFF_FAIL url=" + clip(url, 200) + " err=" + t);
        }
    }

    // ---------- screenshots (diagnostic fallback, spike only) ----------

    private void maybeScreenshot(String reason) {
        if (Build.VERSION.SDK_INT < 34) {
            log("SCREENSHOT_SKIPPED reason=" + reason + " why=sdk_below_34");
            return;
        }
        if (shotCount >= 6) return;
        shotCount++;
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExec, new TakeScreenshotCallback() {
                @Override public void onSuccess(ScreenshotResult result) {
                    saveShot(result, shotCount);
                }
                @Override public void onFailure(int error) {
                    log("SCREENSHOT_FAIL error=" + error);
                }
            });
            log("SCREENSHOT_REQUEST reason=" + reason + " n=" + shotCount);
        } catch (Throwable t) {
            log("SCREENSHOT_THROW reason=" + reason + " err=" + t);
        }
    }

    private void saveShot(final ScreenshotResult result, final int n) {
        new Thread(new Runnable() {
            @Override public void run() {
                HardwareBuffer hb = null;
                Bitmap bm = null, copy = null;
                try {
                    File dir = new File(spikeDir(), "shots");
                    dir.mkdirs();
                    File f = new File(dir, "shot_" + tsFile() + "_" + n + ".png");
                    hb = result.getHardwareBuffer();
                    bm = Bitmap.wrapHardwareBuffer(hb, result.getColorSpace());
                    if (bm == null) { log("SCREENSHOT_NULL_BITMAP"); return; }
                    copy = bm.copy(Bitmap.Config.ARGB_8888, false);
                    if (copy == null) { log("SCREENSHOT_COPY_FAIL"); return; }
                    FileOutputStream fos = new FileOutputStream(f);
                    copy.compress(Bitmap.CompressFormat.PNG, 100, fos);
                    fos.close();
                    log("SCREENSHOT_SAVED path=" + f.getName() + " ts=" + result.getTimestamp());
                } catch (Throwable t) {
                    log("SCREENSHOT_SAVE_FAIL " + t);
                } finally {
                    if (copy != null) copy.recycle();
                    if (bm != null) bm.recycle();
                    if (hb != null) hb.close();
                }
            }
        }, "spike-shot").start();
    }

    // ---------- log plumbing (writer thread keeps event handling jank-free) ----------

    private void startWriter() {
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
        }, "spike-log-writer");
        writer.start();
    }

    private synchronized void writeFile(String s) {
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

    private void log(String msg) {
        String line = ts() + " " + msg;
        Log.i(TAG, line);
        outbox.offer(line);
    }

    // ---------- helpers ----------

    private File spikeDir() {
        File base = getExternalFilesDir(null);
        if (base == null) base = getFilesDir();
        return new File(base, "fetch-spike");
    }

    private boolean readHandoffGate(File dir) {
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

    private static String classNameOf(AccessibilityEvent e) {
        return e.getClassName() != null ? e.getClassName().toString() : "?";
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static String ts() {
        return new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
    }

    private static String tsFile() {
        return new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
    }

    @Override
    public void onDestroy() {
        log("SERVICE_DESTROY");
        closing = true;
        outbox.offer(POISON);
        try { if (writer != null) writer.join(300); } catch (InterruptedException ignored) {}
        try { if (fileOut != null) fileOut.close(); } catch (Exception ignored) {}
        super.onDestroy();
    }
}



