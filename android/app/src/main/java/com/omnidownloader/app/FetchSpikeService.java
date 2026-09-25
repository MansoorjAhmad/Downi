package com.omnidownloader.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import com.omnidownloader.app.downicore.CoreStates;
import com.omnidownloader.app.downicore.DowniCore;
import com.omnidownloader.app.fetcher.MediaUrl;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
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
    private static final long HEARTBEAT_MS = 30_000;   // liveness + memory snapshot cadence

    private final Handler main = new Handler(Looper.getMainLooper());
    private final LinkedBlockingQueue<String> outbox = new LinkedBlockingQueue<>();
    private final Executor mainExec = new Executor() {
        @Override public void execute(Runnable r) { main.post(r); }
    };

    private Thread writer;
    private BufferedWriter fileOut;
    private long logBytes;
    private volatile boolean closing;
    private long connectedAt;             // service-connect stamp, for HEARTBEAT uptime

    private String sessionPkg;            // non-null while a target app is foreground
    private long sessionStart;
    private int dumpCount, sessionDumps, shotCount, step3Hits;
    private long lastDumpAt, lastScrollLogAt;
    private String lastDumpBody = "";
    private Runnable settleDump;
    private final Set<String> seenUrls = new HashSet<>();  // one PIPELINE line per URL
    private final LinkedHashSet<String> signals = new LinkedHashSet<>();
    private boolean handoffEnabled;

    // Phase 0.5 chain test: true while the last chain click was "Copy link"
    // (so chainStep3 can close the platform share panel afterwards).
    private boolean chainClickedCopy;

    /**
     * Defect D-a (found on device 2026-09-25): one tap could deliver the SAME url **twice**. Two
     * routes can carry it — the sheet route (clicking DOWNI in the chooser -> DropActivity ->
     * `startShared`) and the copy-link route (clipboard -> {@link #pipeline}) — and `seenUrls`
     * only ever saw the second one, because DropActivity's intake never passes through this class.
     * Evidence: `Video by fliqr.clips.mp4` and `fliqr.clips (1).mp4`, both exactly 2,135,039 B,
     * from a single 16:34 tap (`PIPELINE_HANDOFF_OK` 16:34:34.339 then `CHAIN_CHOOSER_DOWNI`
     * 16:34:35.852). So exactly one route delivers per run: the first to get there claims it.
     */
    private boolean chainDelivered;

    /** One delivery per tap. The first route wins; any later route is logged and stands down. */
    private boolean claimDelivery(String route) {
        if (chainDelivered) {
            log("CHAIN_DELIVER_DUP route=" + route + " suppressed=already_delivered");
            return false;
        }
        chainDelivered = true;
        log("CHAIN_DELIVER route=" + route);
        return true;
    }

    // Phase 1 (owner ruling 2026-09-25): the Fetcher's face is the floating bubble.
    // The chain is only what happens *behind* a bubble tap.
    private DowniBubble bubble;
    private boolean chainRunning;
    private int chooserSwipes;                       // chooser list scrolls used by this run
    private int sheetSwipes;                         // platform-sheet scrolls used by this run
    private static final int MAX_CHOOSER_SWIPES = 4;
    private static final int MAX_SHEET_SWIPES = 2;

    // Foreground-service defence against the vendor power manager (see armForeground()).
    private boolean fgsArmed;
    private int fgsTries;

    // V3.2 Downi Core (owner-approved plan 2026-09-25) — Phase A is the shell and the visual
    // states: no interaction, no detection, no download path. It is driven from the debug
    // channel (fetch-spike/core.cmd) and its window is NOT_TOUCHABLE, so it cannot take a tap.
    private DowniCore core;
    private final DowniCore.Listener coreListener = new DowniCore.Listener() {
        @Override public void onCoreLog(String msg) { log(msg); }
    };

    // Reads fetch-spike/core.cmd — the Core's Phase A command channel:
    //   show | hide | list | size <48|56|64> | state <name> | progress <0-100> | mark <scale> | at <x> <y>
    private final Runnable corePoll = new Runnable() {
        @Override public void run() {
            try { pollCoreCmd(); } catch (Throwable t) { log("CORE_POLL_ERR " + t); }
            main.postDelayed(this, 800);
        }
    };

    private final DowniBubble.Listener bubbleListener = new DowniBubble.Listener() {
        // NB: must be qualified. An unqualified onBubbleTap() here resolves to this
        // anonymous class's own method — infinite recursion, StackOverflowError on the
        // first tap (found on device 2026-09-25, crash buffer).
        @Override public void onBubbleTap() {
            // A throwable escaping a View callback kills the whole process — that is exactly how
            // the first tap died on device 2026-09-25 (StackOverflowError in this listener). The
            // whole tap path is fenced now: a bug can cost one run, never the service.
            try { FetchSpikeService.this.onBubbleTap(); }
            catch (Throwable t) { log("BUBBLE_TAP_ERR " + t); chainReset(); }
        }
        @Override public void onBubbleMoved(int x, int y) { log("BUBBLE_MOVED x=" + x + " y=" + y); }
        @Override public void onBubbleLog(String msg) { log(msg); }
    };

    // Tracks the foreground package so the bubble follows IG/TikTok even when no
    // accessibility event fires (service switched on while the platform is already open).
    private final Runnable bubblePoll = new Runnable() {
        @Override public void run() {
            try { bubbleTick(); } catch (Throwable t) { log("BUBBLE_TICK_ERR " + t); }
            main.postDelayed(this, 900);
        }
    };

    // Polls for fetch-spike/chain.cmd — the spike's command channel (dry|click).
    private final Runnable chainPoll = new Runnable() {
        @Override public void run() {
            try { pollChainCmd(); } catch (Throwable t) { log("CHAIN_POLL_ERR " + t); }
            main.postDelayed(this, 1500);
        }
    };

    /**
     * Death-hunt forensics (2026-09-25). If the process is killed again, the last line of the
     * spike log is now a health snapshot instead of a bare DUMP: heap climbing over the run
     * points at memory pressure (H3), while a gap with no HEARTBEAT, no `TRIM_MEMORY` and no
     * `SERVICE_UNBIND` means an abrupt external kill (H1/H2). Its own failures are contained.
     */
    private final Runnable heartbeat = new Runnable() {
        @Override public void run() {
            try {
                Runtime rt = Runtime.getRuntime();
                long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
                long maxMb = rt.maxMemory() / (1024 * 1024);
                log("HEARTBEAT uptime_s=" + ((SystemClock.elapsedRealtime() - connectedAt) / 1000)
                        + " heap_mb=" + usedMb + "/" + maxMb
                        + " session=" + (sessionPkg == null ? "-" : sessionPkg)
                        + " dumps=" + dumpCount + " chain=" + chainRunning
                        + " bubble=" + (bubble != null && bubble.isShown())
                        + " fgs=" + fgsArmed + "/" + fgsTries);
            } catch (Throwable t) {
                log("HEARTBEAT_ERR " + t);
            }
            main.postDelayed(this, HEARTBEAT_MS);
        }
    };

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
        log("CHAIN test armed: write fetch-spike/chain.cmd (dry|click) via adb");
        main.postDelayed(chainPoll, 2000);

        // Phase 1: the bubble the owner asked for — it lives on top of IG/TikTok.
        bubble = new DowniBubble(this, bubbleListener);
        main.postDelayed(bubblePoll, 1200);
        log("BUBBLE_READY window=TYPE_ACCESSIBILITY_OVERLAY tap=runs the chain behind the scenes");

        // V3.2 Core (Phase A): the shell exists but stays invisible until the debug channel says
        // `show`. Nothing about it touches detection, interaction or downloads yet.
        core = new DowniCore(this, coreListener);
        main.postDelayed(corePoll, 1500);
        log("CORE_READY states=" + CoreStates.list() + " cmd=fetch-spike/core.cmd");

        // Death-hunt forensics: a heartbeat whose absence we can measure.
        connectedAt = SystemClock.elapsedRealtime();
        main.postDelayed(heartbeat, HEARTBEAT_MS);
        log("HEARTBEAT armed every " + (HEARTBEAT_MS / 1000) + "s");

        // Anti-kill defence: without this the vendor power manager ends the whole feature.
        armForeground();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        try {
            if (event == null) return;
            int type = event.getEventType();
            String pkg = event.getPackageName() != null ? event.getPackageName().toString() : null;

            if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                // Our own bubble overlay is a window too: its appearance must never be
                // mistaken for "another app came to the front" (it killed the IG session
                // on device 2026-09-25, leaving every tap with no session to act on).
                if (pkg != null && pkg.equals(getPackageName())) return;
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
        if (bubble != null) bubble.hide();   // no bubble left behind when the service stops
        if (core != null) core.hide();       // neither the Core
    }

    /**
     * Forensic line for the 2026-09-25 hunt: a clean "Android unbound / a11y disabled us" lands
     * here (and explains an `enabled_accessibility_services` reset under our feet), while a hard
     * process kill writes NOTHING at all. Without this line the two cases looked identical.
     */
    @Override
    public boolean onUnbind(Intent intent) {
        log("SERVICE_UNBIND");
        disarmForeground();
        if (bubble != null) bubble.destroy();
        if (core != null) core.destroy();
        return super.onUnbind(intent);
    }

    /**
     * Android warns before it reclaims memory; on this ROM a silent zero-signal death is what we
     * were staring at, so every warning is written down (a `TRIM_MEMORY_*` line right before the
     * end of the log is the proof for the memory hypothesis).
     */
    @Override
    public void onTrimMemory(int level) {
        log("TRIM_MEMORY level=" + level);
        super.onTrimMemory(level);
    }

    @Override
    public void onLowMemory() {
        log("LOW_MEMORY");
        super.onLowMemory();
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
        // Defect D-b: only a real *media page* may be called HIGH confidence — a bio/redirect link
        // found in the same tree is not a video. The rule is pure and unit-tested
        // (`fetcher/MediaUrl`, `MediaUrlTest`); rejected candidates say why instead of vanishing.
        String url = null;
        for (String cand : urls) {
            String why = MediaUrl.reason(cand);
            if (why == null) { url = cand; break; }
            log("STEP3_REJECT reason=" + why + " url=" + clip(cand, 200));
        }
        if (url != null) {
            step3Hits++;
            log("STEP3_DUMP_" + dumpCount + " confidence=HIGH source=tree url_count=" + urls.size()
                    + " url=" + clip(url, 300));
            if (chainRunning) {
                // Measured on device 2026-09-25: with the sheet open the URL *is* in the tree, and
                // this route delivered 8/8 tapped runs, while the chain's own routes managed 2/5
                // (D-e: `CHAIN_CLIPBOARD got=null`; D-f: chooser missed, no copy-link fallback). So
                // during a run this route may deliver — but only as that run's *single* delivery, so
                // D-a's guarantee still holds and the later routes stand down.
                if (claimDelivery("dump_of_sheet")) pipeline(url);
            } else {
                pipeline(url);
            }
        } else if (!urls.isEmpty()) {
            log("STEP3_DUMP_" + dumpCount + " confidence=NONE note=urls_found_but_none_is_a_media_page"
                    + " url_count=" + urls.size());
        } else if (!ids.isEmpty()) {
            step3Hits++;
            log("STEP3_DUMP_" + dumpCount + " confidence=MEDIUM source=tree ids="
                    + clip(ids.toString(), 300) + " note=identifier_only_resolver_required");
        } else {
            log("STEP3_DUMP_" + dumpCount + " confidence=NONE note=no_url_or_id_in_tree signals="
                    + (signals.isEmpty() ? "none" : clip(signals.toString(), 200)));
        }
    }

    // ---------- Phase 1: the bubble (owner ruling 2026-09-25) ----------
    // The Fetcher's face: a floating DOWNI control over IG/TikTok. A tap runs the
    // Phase 0.5 chain *behind* the scenes — the owner never drives a share sheet.

    private String foregroundPkg() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null && root.getPackageName() != null) return root.getPackageName().toString();
        } catch (Throwable ignored) {}
        return null;
    }

    private void bubbleTick() {
        if (bubble == null) return;
        String pkg = foregroundPkg();
        if (pkg == null) pkg = sessionPkg;
        boolean onTarget = pkg != null && TARGETS.contains(pkg);
        // Session tracking must not depend on a window event alone: a service switched on
        // while IG/TikTok is already open never sees one, so the bubble would show while
        // unable to act ("no session" on every tap — device 2026-09-25).
        if (onTarget && sessionPkg == null) {
            startSession(pkg);
        } else if (!onTarget && sessionPkg != null && !chainRunning
                && pkg != null && !pkg.equals(getPackageName())) {
            endSession("poll:" + pkg);
        }
        boolean keep = (onTarget && sessionPkg != null) || chainRunning;  // never a fake armed state
        if (keep && !bubble.isShown()) {
            bubble.show();
            log("BUBBLE_SHOW pkg=" + pkg + " on_target=" + onTarget);
            // A visible overlay window is the strongest "user can see us" state we can hold, so
            // this is also the most reliable moment to (re)take the foreground (see armForeground).
            if (!fgsArmed) armForeground();
        } else if (!keep && bubble.isShown()) {
            bubble.hide();
            log("BUBBLE_HIDE pkg=" + pkg);
        }
    }

    /** Bubble tap = the owner's zero-touch grab: DOWNI drives the platform's own flow. */
    private void onBubbleTap() {
        if (sessionPkg == null) { log("BUBBLE_TAP_NO_SESSION open IG/TikTok on a video first"); return; }
        if (chainRunning) { log("BUBBLE_TAP_BUSY ignored"); return; }
        log("BUBBLE_TAP session=" + sessionPkg);
        chainRunning = true;
        chooserSwipes = 0;
        sheetSwipes = 0;
        if (bubble != null) {
            bubble.setState(DowniBubble.STATE_BUSY);
            bubble.setInteractive(false);   // DOWNI's own taps must reach the platform app
        }
        try { runChain(true); }
        catch (Throwable t) { log("CHAIN_ERR " + t); chainReset(); }
    }

    /** Ends a run: bubble idle and touchable again. Called on every exit path. */
    private void chainReset() {
        chainRunning = false;
        chainDelivered = false;             // each run gets its own one-delivery budget (D-a)
        if (bubble != null) {
            bubble.setState(DowniBubble.STATE_IDLE);
            bubble.setInteractive(true);
        }
    }

    /**
     * Every chain step is posted to the main looper — and an uncaught throwable in one of them
     * takes the whole process down with it, which is exactly how the first tap died on device
     * 2026-09-25 (StackOverflowError inside a View callback). Steps go through here now, so a
     * failed step ends its run instead of killing the bubble and the service with it.
     */
    private void postStep(final Runnable step, long delayMs) {
        main.postDelayed(new Runnable() {
            @Override public void run() {
                try { step.run(); }
                catch (Throwable t) { log("CHAIN_STEP_ERR " + t); chainReset(); }
            }
        }, delayMs);
    }

    /** True for nodes from this app's own overlay (the bubble) — never a click candidate. */
    private boolean isOurs(AccessibilityNodeInfo n) {
        try {
            CharSequence p = n.getPackageName();
            return p != null && getPackageName().contentEquals(p);
        } catch (Throwable t) {
            return false;
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

    // ---------- Phase 0.5: chain test — can DOWNI drive the platform's own share flow? ----------
    // Command channel: fetch-spike/chain.cmd written via adb, containing "dry" or "click".
    //   dry   → scan the live video screen, log Share candidates only (no clicks).
    //   click → click Share, wait, map the opened panel/sheet (buttons, "Copy link", "DOWNI"),
    //           click the best target, then close the panel if needed.
    // Only nodes whose own text/description names the action are ever clicked.
    // Nothing downloads unless the platform's own flow hands the URL to the pipeline.

    // ---------- V3.2 Core: Phase A command channel (visual states only) ----------
    // Same pattern as chain.cmd — write fetch-spike/core.cmd over adb, the poller consumes it.
    // One command per line, `#` comments allowed. Nothing here detects or downloads.

    private void pollCoreCmd() {
        File f = new File(spikeDir(), "core.cmd");
        if (!f.exists()) return;
        String body = readSmallFile(f);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
        if (body == null) return;
        if (core == null) core = new DowniCore(this, coreListener);
        for (String raw : body.split("[\\r\\n]+")) {
            String cmd = raw.trim();
            if (cmd.isEmpty() || cmd.startsWith("#")) continue;
            log("CORE_CMD cmd=" + cmd);
            runCoreCmd(cmd);
        }
    }

    private void runCoreCmd(String cmd) {
        String lower = cmd.toLowerCase(Locale.US);
        String[] parts = lower.split("\\s+");
        try {
            if (lower.equals("show")) { core.show(); return; }
            if (lower.equals("hide")) { core.hide(); return; }
            if (lower.equals("list")) { log("CORE_STATES " + CoreStates.list()); return; }
            if (parts.length >= 2 && parts[0].equals("size")) {
                core.setSizeDp(Integer.parseInt(parts[1]));
                return;
            }
            if (parts.length >= 2 && parts[0].equals("state")) {
                if (!CoreStates.isKnown(parts[1])) { log("CORE_CMD_BAD_STATE " + parts[1]); return; }
                core.setState(parts[1]);
                return;
            }
            if (parts.length >= 2 && parts[0].equals("progress")) {
                core.setProgress(Integer.parseInt(parts[1]) / 100f);
                return;
            }
            if (parts.length >= 2 && parts[0].equals("mark")) {
                core.setMarkScale(Float.parseFloat(parts[1]));
                return;
            }
            if (parts.length >= 3 && parts[0].equals("at")) {
                core.move(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
                return;
            }
            log("CORE_CMD_UNKNOWN " + cmd);
        } catch (Throwable t) {
            log("CORE_CMD_ERR cmd=" + cmd + " err=" + t);
        }
    }

    private void pollChainCmd() {
        File f = new File(spikeDir(), "chain.cmd");
        if (!f.exists()) return;
        String cmd = readSmallFile(f);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
        cmd = cmd == null ? "dry" : cmd.trim().toLowerCase(Locale.US);
        log("CHAIN_CMD cmd=" + cmd + " session=" + sessionPkg);
        if (sessionPkg == null) { log("CHAIN_NO_SESSION open IG/TikTok on a video first"); return; }
        try { runChain(cmd.contains("click")); }
        catch (Throwable t) { log("CHAIN_ERR " + t); chainReset(); }
    }

    /**
     * Defect D-d (device 2026-09-25): `getRootInActiveWindow()` sometimes returns the **notification
     * shade**, so a chooser walk read quick-settings tiles (`on wi-fi,cmcc-fiber`, `off torch`,
     * `silent`, `expand`, `history`) instead of share targets (16:33:51). Choose the window
     * deliberately instead of trusting the active one:
     *   - the platform app's window (IG/TikTok) holds the in-app sheet, i.e. "Copy link";
     *   - the system chooser (`android` / `intentresolver`) holds DOWNI;
     *   - the shade (`com.android.systemui`) can never hold a share target, so it is never scanned.
     * `preferChooser` picks the chooser first (for the step that looks for DOWNI), otherwise the
     * platform app wins (for the steps that look for a Share row or "Copy link").
     */
    private AccessibilityNodeInfo pickShareRoot(String tag, boolean preferChooser) {
        AccessibilityNodeInfo app = null;
        AccessibilityNodeInfo chooser = null;
        for (AccessibilityWindowInfo w : getWindows()) {
            AccessibilityNodeInfo r;
            try { r = w.getRoot(); } catch (Throwable t) { continue; }
            if (r == null) continue;
            String pkg = r.getPackageName() == null ? "" : r.getPackageName().toString();
            if (pkg.equals("com.android.systemui")) {
                log(tag + "_WINDOW_SKIPPED pkg=" + pkg + " why=shade_cannot_hold_share_targets");
                continue;
            }
            if (pkg.equals("android") || pkg.contains("intentresolver")) {
                if (chooser == null) chooser = r;
            } else if (app == null && (pkg.equals(sessionPkg) || TARGETS.contains(pkg))) {
                app = r;
            }
        }
        AccessibilityNodeInfo pick = preferChooser ? (chooser != null ? chooser : app)
                                                   : (app != null ? app : chooser);
        String which = pick == null ? "active_fallback"
                : (pick == chooser ? "system_chooser" : "platform_app");
        if (pick == null) pick = getRootInActiveWindow();
        log(tag + "_ROOT which=" + which);
        return pick;
    }

    private void runChain(final boolean doClick) {
        AccessibilityNodeInfo root = pickShareRoot("CHAIN_SCAN", false);
        if (root == null) { log("CHAIN_ROOT_NULL"); if (doClick) chainReset(); return; }
        ArrayList<AccessibilityNodeInfo> share = new ArrayList<>();
        ArrayList<AccessibilityNodeInfo> copy = new ArrayList<>();
        ArrayList<AccessibilityNodeInfo> downi = new ArrayList<>();
        collectCandidates(root, share, copy, downi, new Counter(), 0);
        log("CHAIN_SCAN pkg=" + sessionPkg + " share=" + share.size()
                + " copylink=" + copy.size() + " downi=" + downi.size());
        for (AccessibilityNodeInfo n : share) {
            log("CHAIN_SHARE_CANDIDATE id=" + clip(strOrEmpty(n.getViewIdResourceName()), 90)
                    + " text=" + clip(nodeText(n), 120) + " clickable=" + n.isClickable()
                    + " visible=" + n.isVisibleToUser());
        }
        if (!doClick) { log("CHAIN_DRY_DONE"); return; }
        AccessibilityNodeInfo t = firstClickable(share);
        if (t == null) { log("CHAIN_NO_SHARE_CLICK"); chainReset(); return; }
        log("CHAIN_SHARE_CLICK text=" + clip(nodeText(t), 120)
                + " route=" + clickNode(t));
        postStep(new Runnable() { @Override public void run() { chainStep2(); } }, 1300);
    }

    private void chainStep2() {
        logWindows("CHAIN_STEP2");
        AccessibilityNodeInfo root = pickShareRoot("CHAIN_STEP2", false);
        if (root == null) { log("CHAIN_STEP2_ROOT_NULL"); chainReset(); return; }
        ArrayList<AccessibilityNodeInfo> share = new ArrayList<>();
        ArrayList<AccessibilityNodeInfo> copy = new ArrayList<>();
        ArrayList<AccessibilityNodeInfo> downi = new ArrayList<>();
        collectCandidates(root, share, copy, downi, new Counter(), 0);
        ArrayList<AccessibilityNodeInfo> buttons = new ArrayList<>();
        collectButtons(root, buttons, new Counter(), 0);
        for (AccessibilityNodeInfo b : buttons) log("CHAIN_BUTTON " + clip(nodeText(b), 100));
        for (AccessibilityNodeInfo n : copy) {
            log("CHAIN_COPYLINK_CANDIDATE id=" + clip(strOrEmpty(n.getViewIdResourceName()), 90)
                    + " text=" + clip(nodeText(n), 120) + " clickable=" + n.isClickable());
        }
        for (AccessibilityNodeInfo n : downi) {
            log("CHAIN_DOWNI_CANDIDATE id=" + clip(strOrEmpty(n.getViewIdResourceName()), 90)
                    + " text=" + clip(nodeText(n), 120) + " clickable=" + n.isClickable());
        }
        AccessibilityNodeInfo d = firstClickable(downi);
        if (d != null) {                                 // TikTok: DOWNI sits right in the sheet
            chainClickedCopy = false;
            log("CHAIN_TARGET_CLICK which=downi text=" + clip(nodeText(d), 120)
                    + " route=" + clickNode(d));
            postStep(new Runnable() { @Override public void run() { chainStep3(); } }, 1600);
            return;
        }
        AccessibilityNodeInfo row = findSheetShareRow(); // IG: its "share" row opens the chooser
        if (row != null) {
            chainClickedCopy = false;
            log("CHAIN_TARGET_CLICK which=chooser_row text=" + clip(nodeText(row), 120)
                    + " route=" + clickNode(row));
            postStep(new Runnable() { @Override public void run() { chainChooser(); } }, 1600);
            return;
        }
        AccessibilityNodeInfo c = firstClickable(copy);  // last resort: copy link + clipboard
        if (c == null) {
            // IG's sheet lists DM targets first; its own action rows ("share", "copy link")
            // sit below the fold unless the sheet is scrolled (device 2026-09-25).
            if (sheetSwipes < MAX_SHEET_SWIPES) {
                sheetSwipes++;
                log("CHAIN_SHEET_SCROLL n=" + sheetSwipes + "/" + MAX_SHEET_SWIPES
                        + " swipe=" + swipeSheetList());
                postStep(new Runnable() { @Override public void run() { chainStep2(); } }, 900);
                return;
            }
            log("CHAIN_NO_TARGET neither DOWNI nor Copy link found");
            chainReset();
            return;
        }
        chainClickedCopy = true;
        log("CHAIN_TARGET_CLICK which=copylink text=" + clip(nodeText(c), 120)
                + " route=" + clickNode(c));
        postStep(new Runnable() { @Override public void run() { chainStep3(); } }, 1600);
    }

    // ---------- Phase 0.5b: sheet -> DOWNI (the transport with no clipboard) ----------
    // Instagram's in-app sheet carries no DOWNI entry, but its own "share" row opens the
    // system chooser, where DowniDrop is a text/plain target; TikTok lists DOWNI directly.
    // The URL then arrives through the platform's normal share intent — nothing for the
    // owner to tap and no clipboard involved.

    /** The in-app sheet row that opens the system chooser ("share" / "share to" / "more"). */
    private AccessibilityNodeInfo findSheetShareRow() {
        AccessibilityNodeInfo root = pickShareRoot("CHAIN_SHEET_ROW", false);
        if (root == null) return null;
        ArrayList<AccessibilityNodeInfo> share = new ArrayList<>();
        ArrayList<AccessibilityNodeInfo> copy = new ArrayList<>();
        ArrayList<AccessibilityNodeInfo> downi = new ArrayList<>();
        collectCandidates(root, share, copy, downi, new Counter(), 0);
        int screenH = getResources().getDisplayMetrics().heightPixels;
        for (AccessibilityNodeInfo n : share) {
            String t = nodeText(n);
            if (!(t.equals("share") || t.contains("share to") || t.contains("more")
                    || t.contains("other"))) continue;
            Rect r = new Rect();
            try { n.getBoundsInScreen(r); } catch (Throwable ignored) { continue; }
            if (r.exactCenterY() < screenH * 0.45f) continue;   // rows of the sheet live low
            AccessibilityNodeInfo clickable = clickableSelfOrAncestor(n);
            if (clickable != null) return clickable;
        }
        return null;
    }

    /** The chooser is open: find DOWNI and let the platform hand it the real link. */
    private void chainChooser() {
        logWindows("CHAIN_CHOOSER");
        AccessibilityNodeInfo root = pickShareRoot("CHAIN_CHOOSER", true);
        if (root == null) { log("CHAIN_CHOOSER_ROOT_NULL"); chainReset(); return; }
        ArrayList<AccessibilityNodeInfo> share = new ArrayList<>();
        ArrayList<AccessibilityNodeInfo> copy = new ArrayList<>();
        ArrayList<AccessibilityNodeInfo> downi = new ArrayList<>();
        collectCandidates(root, share, copy, downi, new Counter(), 0);
        ArrayList<AccessibilityNodeInfo> buttons = new ArrayList<>();
        collectButtons(root, buttons, new Counter(), 0);
        for (AccessibilityNodeInfo b : buttons) log("CHAIN_CHOOSER_BUTTON " + clip(nodeText(b), 80));
        AccessibilityNodeInfo d = firstClickable(downi);
        if (d != null) {
            if (chainDelivered) {
                // D-a: this tap already delivered (the sheet's URL was read from the tree / the
                // clipboard). Clicking DOWNI now would launch DropActivity for a *second* grab of the
                // same video — the exact double-grab seen at 16:34. Stand down instead.
                log("CHAIN_CHOOSER_SKIPPED already_delivered");
                chainReset();
                return;
            }
            String route = clickNode(d);
            if (!"fail".equals(route)) claimDelivery("chooser_downi");   // D-a: this tap is delivered
            log("CHAIN_CHOOSER_DOWNI text=" + clip(nodeText(d), 80) + " route=" + route);
            chainReset();
            return;
        }
        // The chooser only ranks a handful of targets; DOWNI is further down the list and
        // has to be scrolled into view before it can be tapped.
        if (chooserSwipes < MAX_CHOOSER_SWIPES) {
            chooserSwipes++;
            log("CHAIN_CHOOSER_SCROLL n=" + chooserSwipes + "/" + MAX_CHOOSER_SWIPES
                    + " swipe=" + swipeChooserList());
            postStep(new Runnable() { @Override public void run() { chainChooser(); } }, 900);
            return;
        }
        log("CHAIN_CHOOSER_NO_DOWNI back=" + performGlobalAction(GLOBAL_ACTION_BACK));
        chainReset();
    }

    /** Scroll the chooser list by one page (kept inside the sheet's lower half). */
    private boolean swipeChooserList() {
        return swipeWithinSheet(0.78f, 0.45f);
    }

    /** Scroll the platform's own share sheet (gentler: it is anchored to the bottom). */
    private boolean swipeSheetList() {
        return swipeWithinSheet(0.72f, 0.55f);
    }

    private boolean swipeWithinSheet(float fromFrac, float toFrac) {
        try {
            int w = getResources().getDisplayMetrics().widthPixels;
            int h = getResources().getDisplayMetrics().heightPixels;
            Path p = new Path();
            p.moveTo(w / 2f, h * fromFrac);
            p.lineTo(w / 2f, h * toFrac);
            return dispatchGesture(new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(p, 0, 280)).build(), null, null);
        } catch (Throwable t) {
            log("CHAIN_SWIPE_FAIL " + t);
            return false;
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

    private void chainStep3() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        String pkg = root != null && root.getPackageName() != null ? root.getPackageName().toString() : "?";
        if (chainClickedCopy) {
            log("CHAIN_CLOSE_PANEL back=" + performGlobalAction(GLOBAL_ACTION_BACK));
        }
        log("CHAIN_DONE focus_pkg=" + pkg);
        logWindows("CHAIN_DONE");

        // The platform's own "Copy link" has just put the real deep link on the clipboard.
        // Android 10+ refuses clipboard reads to apps that are not in focus, so the bubble
        // (our own window) is briefly made focusable, then restored. First working
        // SourceResolver: chain -> clipboard -> existing pipeline.
        if (chainClickedCopy && !chainDelivered) {
            if (bubble != null) bubble.setFocusable(true);
            postStep(readClipAndPipe, 500);
            postStep(releaseFocus, 1300);
        } else if (chainClickedCopy) {
            log("CHAIN_CLIP_SUPPRESSED already_delivered");   // D-a: the sheet route already delivered
        }
        chainReset();                       // bubble idle + touchable again
    }

    private final Runnable readClipAndPipe = new Runnable() {
        @Override public void run() {
            String url = readClipboardText();
            log("CHAIN_CLIPBOARD got=" + (url == null ? "null" : "yes") + " text="
                    + clip(url == null ? "" : url, 200));
            String why = MediaUrl.reason(url);      // D-b: a media page, not a bio/redirect link
            if (why == null) {
                if (claimDelivery("clipboard")) {
                    pipeline(url);          // handoff gate decides whether it really grabs
                }
            } else {
                log("CHAIN_URL_REJECTED reason=" + why);
            }
        }
    };

    private final Runnable releaseFocus = new Runnable() {
        @Override public void run() { if (bubble != null) bubble.setFocusable(false); }
    };

    /** The whole point of the chain: turn DOWNI's taps into the video's real URL. */
    private String readClipboardText() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) return null;
            ClipData d = cm.getPrimaryClip();
            if (d == null || d.getItemCount() == 0) return null;
            CharSequence t = d.getItemAt(0).coerceToText(this);
            return t == null ? null : t.toString().trim();
        } catch (Throwable t) {
            log("CHAIN_CLIPBOARD_READ_FAIL " + t);
            return null;
        }
    }

    private void collectCandidates(AccessibilityNodeInfo node, ArrayList<AccessibilityNodeInfo> share,
                                   ArrayList<AccessibilityNodeInfo> copy, ArrayList<AccessibilityNodeInfo> downi,
                                   Counter c, int depth) {
        if (node == null || c.nodes >= MAX_NODES || depth > MAX_DEPTH) return;
        if (isOurs(node)) return;             // never scan the Fetcher's own bubble overlay
        c.nodes++;
        String txt = nodeText(node);
        if (!txt.isEmpty()) {
            if (txt.contains("share") && !txt.contains("reshare")) share.add(node);
            if (txt.contains("copy link")) copy.add(node);
            if (txt.contains("downi")) downi.add(node);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = null;
            try { child = node.getChild(i); } catch (Throwable ignored) {}
            collectCandidates(child, share, copy, downi, c, depth + 1);
        }
    }

    private void collectButtons(AccessibilityNodeInfo node, ArrayList<AccessibilityNodeInfo> out,
                                Counter c, int depth) {
        if (node == null || c.nodes >= MAX_NODES || depth > MAX_DEPTH || out.size() >= 50) return;
        if (isOurs(node)) return;             // the bubble is not a platform button
        c.nodes++;
        if (node.isClickable() && !nodeText(node).isEmpty()) out.add(node);
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = null;
            try { child = node.getChild(i); } catch (Throwable ignored) {}
            collectButtons(child, out, c, depth + 1);
        }
    }

    // ACTION_CLICK first (cleanest); if the app's view refuses it (custom touch
    // listeners return false), fall back to a real gesture tap at the node center.
    // IG's sheet buttons need the gesture route; TikTok's share button honors action.
    private String clickNode(AccessibilityNodeInfo n) {
        try {
            if (n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return "action";
        } catch (Throwable ignored) {}
        return tapCenter(n) ? "gesture" : "fail";
    }

    private boolean tapCenter(AccessibilityNodeInfo n) {
        try {
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            if (r.width() <= 0 || r.height() <= 0) return false;
            Path p = new Path();
            p.moveTo(r.exactCenterX(), r.exactCenterY());
            GestureDescription.StrokeDescription s = new GestureDescription.StrokeDescription(p, 0, 60);
            return dispatchGesture(new GestureDescription.Builder().addStroke(s).build(), null, null);
        } catch (Throwable t) {
            return false;
        }
    }

    private static AccessibilityNodeInfo firstClickable(ArrayList<AccessibilityNodeInfo> list) {
        AccessibilityNodeInfo fallback = null;
        for (AccessibilityNodeInfo n : list) {
            AccessibilityNodeInfo clickable = clickableSelfOrAncestor(n);
            if (clickable == null) continue;
            if (n.isVisibleToUser()) return clickable;   // the video actually on screen wins
            if (fallback == null) fallback = clickable;
        }
        return fallback;
    }

    private static AccessibilityNodeInfo clickableSelfOrAncestor(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo cur = n;
        for (int up = 0; cur != null && up < 4; up++) {
            if (cur.isClickable()) return cur;
            cur = cur.getParent();
        }
        return null;
    }

    private void logWindows(String tag) {
        try {
            for (AccessibilityWindowInfo w : getWindows()) {
                AccessibilityNodeInfo r = w.getRoot();
                String wp = r != null && r.getPackageName() != null ? r.getPackageName().toString() : "?";
                log(tag + " window pkg=" + wp + " type=" + w.getType());
            }
        } catch (Throwable t) {
            log(tag + " window_list_err=" + t);
        }
    }

    private static String nodeText(AccessibilityNodeInfo n) {
        return (strOrEmpty(n.getText()) + " " + strOrEmpty(n.getContentDescription())).trim().toLowerCase(Locale.US);
    }

    private static String strOrEmpty(CharSequence cs) {
        return cs == null ? "" : cs.toString();
    }

    private static String readSmallFile(File f) {
        try {
            InputStream in = new FileInputStream(f);
            try {
                byte[] buf = new byte[256];
                int n = in.read(buf);
                return n > 0 ? new String(buf, 0, n, "UTF-8") : "";
            } finally { in.close(); }
        } catch (Throwable t) { return null; }
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

    // ---------- foreground service: the one defence a vendor power manager respects ----------

    private static final String CH_ID = "downi_fetcher";
    private static final int FGS_ID = 0x0D0E;      // stable id, so re-arming reuses one row
    private static final int FGS_MAX_TRIES = 5;    // then stop retrying and say so

    /**
     * Runs the accessibility service as a foreground service with an ongoing notification.
     *
     * Why (device evidence, vivo V2058 / Funtouch 13, 2026-09-25): `com.vivo.abe` (Application
     * Behavior Engine) force-stopped this process 1.5-4 min after it went to the background —
     * `I am_kill : [0,11208,com.omnidownloader.app,100,stop com.omnidownloader.app due to stop by
     * com.vivo.abe]`. A vendor force-stop ALSO clears `enabled_accessibility_services`, so the
     * Fetcher could not come back until the owner re-armed it by hand: two of the three spike
     * sessions in the log died that way, with a clean crash buffer, a 2-8 MB heap of 256 MB and
     * `RUN_IN_BACKGROUND`/device-idle already allowed. An ongoing foreground notification is the
     * signal every vendor power manager reads as "the user can see this" (and it pins oom_adj at
     * the foreground-service level too). IMPORTANCE_MIN keeps it silent, so the trade is one
     * quiet row in the shade instead of a feature that dies every few minutes.
     */
    private void armForeground() {
        if (fgsArmed || fgsTries >= FGS_MAX_TRIES) return;
        fgsTries++;
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel ch = new NotificationChannel(CH_ID, "Fetcher armed",
                        NotificationManager.IMPORTANCE_MIN);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
            Intent open = new Intent(this, MainActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) piFlags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getActivity(this, 0, open, piFlags);

            Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? new Notification.Builder(this, CH_ID)
                    : new Notification.Builder(this);
            Notification n = b.setSmallIcon(R.drawable.ic_tile_grab)
                    .setContentTitle("DOWNI Fetcher is ready")
                    .setContentText("Tap the bubble on an Instagram or TikTok video")
                    .setContentIntent(pi)
                    .setOngoing(true)
                    .setPriority(Notification.PRIORITY_MIN)
                    .build();
            startForeground(FGS_ID, n);
            fgsArmed = true;
            log("FGS_START id=" + FGS_ID + " channel=" + CH_ID);
        } catch (Throwable t) {
            // API 31+ only allows this while we are user-visible
            // (ForegroundServiceStartNotAllowedException); BUBBLE_SHOW retries us once the
            // overlay window is actually on screen.
            log("FGS_ERR try=" + fgsTries + " " + t);
        }
    }

    private void disarmForeground() {
        if (!fgsArmed) return;
        try {
            stopForeground(true);
        } catch (Throwable ignored) {}
        fgsArmed = false;
        log("FGS_STOP");
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
        main.removeCallbacks(chainPoll);
        main.removeCallbacks(bubblePoll);
        main.removeCallbacks(heartbeat);
        main.removeCallbacks(corePoll);
        disarmForeground();
        if (bubble != null) bubble.destroy();
        if (core != null) core.destroy();
        closing = true;
        outbox.offer(POISON);
        try { if (writer != null) writer.join(300); } catch (InterruptedException ignored) {}
        try { if (fileOut != null) fileOut.close(); } catch (Exception ignored) {}
        super.onDestroy();
    }
}



