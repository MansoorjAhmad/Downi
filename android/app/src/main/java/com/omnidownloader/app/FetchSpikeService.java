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

import com.omnidownloader.app.downicore.CoreHaptics;
import com.omnidownloader.app.downicore.CoreJobBinding;
import com.omnidownloader.app.downicore.CoreStates;
import com.omnidownloader.app.downicore.DowniCore;
import com.omnidownloader.app.fetcher.AttentionLedger;
import com.omnidownloader.app.fetcher.DeliveryGuard;
import com.omnidownloader.app.fetcher.MediaUrl;
import com.omnidownloader.app.fetcher.StrategyLedger;

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

    /** Live instance for same-process control hooks (settings card size/position calls). */
    private static volatile FetchSpikeService live;

    /** Settings-card hook: apply a new Core size to the live Core, if it is running. */
    public static void applyCoreSizeLive(int dp) {
        FetchSpikeService s = live;
        if (s != null && s.core != null) s.core.setSizeDp(dp);
    }

    /** Settings-card hook: put the Core back at its default spot. */
    public static void resetCorePositionLive() {
        FetchSpikeService s = live;
        if (s != null && s.core != null) s.core.resetPosition();
    }
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
    /** True once this run has confirmed the platform share sheet is open (panel must be closed). */
    private boolean chainSheetNeedsClose;

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

    // V3.2 Downi Core is the live Fetcher face. The resolver remains plumbing behind one tap.
    private boolean chainRunning;
    private int sheetSwipes;                         // platform-sheet scrolls used by this run
    private static final int MAX_SHEET_SWIPES = 2;
    private int sheetWaits;                          // re-scans while the sheet is still animating
    private int sheetScans;                          // sheet-tree fast-lane retries (IG only)
    private static final int MAX_SHEET_WAITS = 1;
    // D-e (2026-09-25): the clipboard read is a *race* — our window must actually hold focus, and
    // setFocusable(true) alone does not grant it. So the read is retried a few times behind the tap.
    private int clipTries;
    private static final int MAX_CLIP_TRIES = 6;

    /**
     * Run generation, bumped by every {@link #beginChainRun()}. A run's delayed steps capture it
     * and stand down when it has moved on: `chainStep3` posts the clipboard reads and the focus
     * release and then immediately ends the run (`chainReset`), so without this token a second tap
     * inside the retry window would leave run N's reader armed under run N+1 — able to deliver run
     * N's (stale) clipboard URL for run N+1, claim the budget, or fight over `clipTries`.
     */
    private int runGen;

    /** Same-video rapid-retap guard (sheet 8 §5 URL matching) — see {@link DeliveryGuard}. */
    private static final long DUP_WINDOW_MS = 8_000;
    private final DeliveryGuard delivery = new DeliveryGuard(DUP_WINDOW_MS);

    // ---------- The attention model + strategy self-awareness (next-level pass) ----------

    /**
     * What the user is looking at right now, with confidence. Fed from every tree dump; a READY
     * candidate lets a Core tap resolve INSTANTLY on Instagram (no sheet, no flash) — the tap
     * still authorizes everything. TikTok's tree is opaque, so its taps keep the copy-link chain.
     */
    private final AttentionLedger ledger = new AttentionLedger();

    /** Per-platform/per-route success ledger — the resolver's self-awareness. */
    private final StrategyLedger strategies = new StrategyLedger();

    /** URL sightings from one tree walk: [url, visibleToUser]. Drained into the ledger per dump. */
    private final java.util.ArrayList<String[]> urlSightings = new java.util.ArrayList<>();

    /** Feed the ledger one walk's sightings; only true media pages on the passive-capable host. */
    private void feedLedger() {
        long now = SystemClock.elapsedRealtime();
        ledger.beginDump(now);
        for (String[] s : urlSightings) {
            String url = s[0];
            boolean visible = "1".equals(s[1]);
            String why = MediaUrl.reason(url);
            if (why == null && url.contains("instagram.")) ledger.offer(url, visible, now);
        }
        urlSightings.clear();
    }

    private static String platformOf(String url) {
        String u = url == null ? "" : url.toLowerCase(Locale.US);
        if (u.contains("instagram")) return "instagram";
        if (u.contains("tiktok")) return "tiktok";
        return "other";
    }

    /** The last URL this Core delivered — pause/resume debug commands target its job. */
    private String lastDeliveredUrl;

    /** Newest dropLive row id matching url (when given) and state. Debug-channel helper. */
    private String findDropJobId(String url, String stateWanted) {
        try {
            org.json.JSONArray list = new org.json.JSONArray(getSharedPreferences("downi_settings", MODE_PRIVATE)
                    .getString("dropLive", "[]"));
            String found = null;
            for (int i = 0; i < list.length(); i++) {
                org.json.JSONObject o = list.optJSONObject(i);
                if (o == null) continue;
                if (url != null && !url.equals(o.optString("url"))) continue;
                if (stateWanted != null && !stateWanted.equals(o.optString("state"))) continue;
                found = o.optString("id");
            }
            return found;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Platform short name from the foreground session package (for records without a URL). */
    private String sessionShort() {
        String p = sessionPkg == null ? "" : sessionPkg;
        if (p.contains("instagram")) return "instagram";
        if (p.contains("musically") || p.contains("trill")) return "tiktok";
        return "other";
    }

    private void recordStrategy(String route, boolean ok, String url) {
        String platform = platformOf(url);
        strategies.record(platform, route, ok, SystemClock.elapsedRealtime());
        log("STRATEGY " + platform + " " + strategies.summary(platform, route));
    }

    // Screen-off discipline (§E2): nothing polls, dumps, or shows while the screen is dark.
    private volatile boolean screenOn = true;
    private final android.content.BroadcastReceiver screenReceiver = new android.content.BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                screenOn = false;
                if (core != null) core.hide();
            } else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                screenOn = true;    // coreTick re-shows on the next tick over a target app
            }
        }
    };

    // A rebind without onDestroy (this ROM wipes the a11y binding on its own) must not double-post
    // the polling loops — they run on the same looper for the life of the instance.
    private boolean pollersArmed;

    // Foreground-service defence against the vendor power manager (see armForeground()).
    private boolean fgsArmed;
    private int fgsTries;

    // V3.2 Downi Core — approved shell + Phase B interaction, now the production-facing control.
    private DowniCore core;
    private Boolean coreManualVisibility;         // debug show/hide; null = follow target app
    private final DowniCore.Listener coreListener = new DowniCore.Listener() {
        @Override public void onCoreLog(String msg) { log(msg); }
        @Override public void onCoreTap() {
            try { FetchSpikeService.this.onCoreTap(); }
            catch (Throwable t) { log("CORE_TAP_ERR " + t); chainReset(); }
        }
        @Override public void onCoreMoved(int x, int y) { log("CORE_MOVED x=" + x + " y=" + y); }
    };

    // ---------- The living Core: real detection + real job (master package §9/§30/§31) ----------
    //
    // ONE OBJECT, MANY STATES, with honest precedence: a real download job beats detection,
    // detection beats idle. Interaction (press/drag/snap) still physically overrides everything,
    // but returns to the arbiter's state instead of a hardcoded idle — a Core that is
    // mid-download must not forget its job just because the user dragged it.
    private CoreJobBinding jobBinding;            // read-only observer of DowniDownloadService
    private String jobState = CoreStates.IDLE;    // what the tracked job says (IDLE = none)
    private float jobProgress;                    // 0..1, the job's real percent
    private boolean videoDetected;                // last known watching-signal state (Phase 0 P0-2)
    private long lastDetectFlipAt;
    private Runnable failedHold;
    private String lastLoggedJobState = "";
    private int lastLoggedJobPct = -1;
    private static final long DETECT_DEBOUNCE_MS = 1200;
    private static final long FAILED_HOLD_MS = 3000;

    /**
     * Recomputes what the Core should show and applies it as its base state.
     */
    private void applyCoreState() {
        if (core == null) return;
        String s;
        if (!CoreStates.IDLE.equals(jobState)) {
            s = jobState;                          // a real job is the loudest truth
        } else if (chainRunning) {
            s = CoreStates.RESOLVING;              // the tap fired; the resolver is working
        } else {
            s = videoDetected ? CoreStates.DETECTED : CoreStates.IDLE;
        }
        if (CoreStates.showsProgress(jobState)) core.setProgress(jobProgress);
        core.setBaseState(s);
    }

    /**
     * Detection wake (master package §9): watching signals found in the platform's tree mean a
     * video is on screen — the Core rises once (WAKE settles to DETECTED in the host view). No
     * signals (profiles, settings, DMs) → honest idle. Detection never downloads; it only wakes
     * the Core so the user knows a tap will find something.
     */
    private void updateDetection(boolean hasSignal) {
        if (chainRunning || hasSignal == videoDetected) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastDetectFlipAt < DETECT_DEBOUNCE_MS) return;   // next dump re-checks
        lastDetectFlipAt = now;
        videoDetected = hasSignal;
        applyCoreState();
        if (hasSignal && core != null) {
            core.setState(CoreStates.WAKE);
            if (screenOn && core.isShown()) CoreHaptics.detected(this);
        }
        log("CORE_DETECT detected=" + hasSignal);
    }

    /**
     * The resolver could not keep its promise (no share row, empty clipboard, rejected URL,
     * refused delivery). The Core says so once — restrained error tint, retry-ready — then
     * returns to whatever is actually true (master package §15: "Something went wrong.",
     * never "SYSTEM FAILURE!!!").
     */
    private void resolverFailed(String why) {
        log("CORE_RESOLVE_FAIL why=" + why);
        if (failedHold != null) main.removeCallbacks(failedHold);
        jobState = CoreStates.FAILED;
        jobProgress = 0f;
        applyCoreState();
        if (screenOn && core != null && core.isShown()) CoreHaptics.failed(this);
        failedHold = new Runnable() {
            @Override public void run() {
                if (CoreStates.FAILED.equals(jobState)) {   // never clobber a newer job/run
                    jobState = CoreStates.IDLE;
                    applyCoreState();
                }
                failedHold = null;
            }
        };
        main.postDelayed(failedHold, FAILED_HOLD_MS);
    }

    // Reads fetch-spike/core.cmd — the Core's Phase A command channel:
    //   show | hide | list | size <48|56|64> | state <name> | progress <0-100> | mark <scale> | at <x> <y>
    private final Runnable corePoll = new Runnable() {
        @Override public void run() {
            if (screenOn) { try { pollCoreCmd(); } catch (Throwable t) { log("CORE_POLL_ERR " + t); } }
            main.postDelayed(this, 800);
        }
    };

    // Tracks the foreground package so the Core follows IG/TikTok even when no accessibility
    // event fires (service switched on while the platform is already open).
    private final Runnable coreTickPoll = new Runnable() {
        @Override public void run() {
            if (screenOn) { try { coreTick(); } catch (Throwable t) { log("CORE_TICK_ERR " + t); } }
            main.postDelayed(this, 900);
        }
    };

    // Polls for fetch-spike/chain.cmd — the spike's command channel (dry|click).
    private final Runnable chainPoll = new Runnable() {
        @Override public void run() {
            if (screenOn) { try { pollChainCmd(); } catch (Throwable t) { log("CHAIN_POLL_ERR " + t); } }
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
            if (screenOn) {
                try {
                    Runtime rt = Runtime.getRuntime();
                    long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
                    long maxMb = rt.maxMemory() / (1024 * 1024);
                    log("HEARTBEAT uptime_s=" + ((SystemClock.elapsedRealtime() - connectedAt) / 1000)
                            + " heap_mb=" + usedMb + "/" + maxMb
                            + " session=" + (sessionPkg == null ? "-" : sessionPkg)
                            + " dumps=" + dumpCount + " chain=" + chainRunning
                            + " core=" + (core != null && core.isShown())
                            + " fgs=" + fgsArmed + "/" + fgsTries);
                } catch (Throwable t) {
                    log("HEARTBEAT_ERR " + t);
                }
            }
            main.postDelayed(this, HEARTBEAT_MS);
        }
    };

    private static final class Counter { int nodes; }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        live = this;

        // The user chose this service once; record that so MainActivity may RE-arm it after a
        // vendor wipe (vivo's ABE clears enabled_accessibility_services when it force-stops us).
        // Without this flag the app would never resurrect a service the user never enabled.
        try {
            getSharedPreferences("downi_fetcher", MODE_PRIVATE)
                .edit().putBoolean("wasArmed", true).apply();
        } catch (Throwable ignored) {}

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

        // The approved Core is now the live control over IG/TikTok. The legacy spike bubble is
        // no longer instantiated; its proven tap resolver remains unchanged underneath.
        core = new DowniCore(this, coreListener);
        log("CORE_READY live=true window=TYPE_ACCESSIBILITY_OVERLAY states=" + CoreStates.list());

        // The Core becomes the face of the download service: a read-only observer of the same
        // job snapshot the in-app Queue renders (master package: ONE JOB, ONE SOURCE OF TRUTH).
        jobBinding = new CoreJobBinding(this, new CoreJobBinding.Listener() {
            @Override public void onJobView(CoreJobBinding.JobView v) {
                if (v == null || v.coreState == null) return;
                // Progress re-applies (the number moved); an unchanged terminal view must not
                // re-fire the completion animation every poll.
                boolean wasComplete = CoreStates.COMPLETE.equals(jobState);
                if (v.coreState.equals(jobState) && !CoreStates.PROGRESS.equals(v.coreState)) return;
                jobState = v.coreState;
                jobProgress = v.progress;
                applyCoreState();
                if (screenOn && core != null && core.isShown()) {
                    if (CoreStates.COMPLETE.equals(jobState) && !wasComplete) CoreHaptics.complete(FetchSpikeService.this);
                    if (CoreStates.FAILED.equals(jobState)) CoreHaptics.failed(FetchSpikeService.this);
                }
                int pct = Math.round(v.progress * 100f);
                if (!v.coreState.equals(lastLoggedJobState) || pct != lastLoggedJobPct) {
                    lastLoggedJobState = v.coreState;
                    lastLoggedJobPct = pct;
                    log("CORE_JOB state=" + v.coreState + " pct=" + pct);
                }
            }
        });

        // Death-hunt forensics: a heartbeat whose absence we can measure.
        connectedAt = SystemClock.elapsedRealtime();
        log("HEARTBEAT armed every " + (HEARTBEAT_MS / 1000) + "s");

        // One set of loops per service instance. This ROM has been observed to rebind an
        // accessibility service without calling onDestroy (it wipes the binding on its own
        // mid-run); the posts above run on this instance's own looper, so a rebind reuses the
        // live loops instead of doubling the polling.
        if (!pollersArmed) {
            pollersArmed = true;
            main.postDelayed(chainPoll, 2000);
            main.postDelayed(coreTickPoll, 1200);
            main.postDelayed(corePoll, 1500);
            main.postDelayed(heartbeat, HEARTBEAT_MS);
        }

        // Screen-off discipline: no polls, no dumps, no Core while the screen is dark.
        try {
            android.content.IntentFilter f = new android.content.IntentFilter();
            f.addAction(Intent.ACTION_SCREEN_OFF);
            f.addAction(Intent.ACTION_SCREEN_ON);
            androidx.core.content.ContextCompat.registerReceiver(
                    this, screenReceiver, f, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
        } catch (Throwable t) {
            log("SCREEN_RECEIVER_ERR " + t);
        }

        // Anti-kill defence: without this the vendor power manager ends the whole feature.
        armForeground();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        try {
            if (event == null) return;
            if (!screenOn) return;                       // screen-off discipline: zero work in the dark
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
                ledger.onScrollTransition();     // the feed paged: pre-scroll candidates are gone
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
        if (core != null) core.hide();       // no Core left behind when the service stops
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
        if (jobBinding != null) jobBinding.stop();
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
        ledger.clear();                      // a new app context: attention resets
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
        ledger.clear();                      // leaving the platform: attention resets
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
        urlSightings.clear();
        walk(root, 0, body, corpus, c);

        String bodyStr = body.toString();
        if (bodyStr.equals(lastDumpBody)) {
            log("DUMP reason=" + reason + " nodes=" + c.nodes + " identical_to_previous");
            urlSightings.clear();
            return;                              // candidates unchanged as well
        }
        lastDumpBody = bodyStr;
        dumpCount++;
        sessionDumps++;
        log("DUMP_" + dumpCount + " reason=" + reason + " pkg=" + sessionPkg + " nodes=" + c.nodes);
        log(bodyStr);                            // full tree, one chunk
        extractAndVerdict(corpus.toString());
        feedLedger();                            // attention: what is on screen right now
        updateDetection(!signals.isEmpty());     // Phase 0 P0-2: watching signals = a video is on screen
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

        // URL sightings with visibility — the Attention Ledger's raw evidence.
        String nodeText = (t == null ? "" : t.toString()) + " " + (d == null ? "" : d.toString());
        if (nodeText.contains("http")) {
            Matcher um = URL_P.matcher(nodeText);
            while (um.find()) {
                urlSightings.add(new String[]{um.group().replaceAll("[\\.,;:!?)\\]\"']+$", ""),
                        node.isVisibleToUser() ? "1" : "0"});
                if (urlSightings.size() >= 12) break;
            }
        }

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
                //
                // D-h (found 2026-09-25 18:0x by reading this code, not by log): claim ONLY when the
                // gate can actually deliver. pipeline() returns early when handoff is disabled, so
                // claiming first marked the run "delivered" without a grab and made the clipboard/
                // chooser routes stand down -> a run that found its URL and still delivered nothing.
                if (handoffEnabled && claimDelivery("dump_of_sheet")) pipeline(url);
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

    // ---------- live Downi Core + tap resolver ----------
    // The Fetcher's face: a floating DOWNI Core over IG/TikTok. A tap runs the
    // resolver *behind* the scenes — the owner never drives a share sheet.

    private String foregroundPkg() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null && root.getPackageName() != null) return root.getPackageName().toString();
        } catch (Throwable ignored) {}
        return null;
    }

    private void coreTick() {
        if (core == null) return;
        String pkg = foregroundPkg();
        if (pkg == null) pkg = sessionPkg;
        boolean onTarget = pkg != null && TARGETS.contains(pkg);
        if (onTarget && sessionPkg == null) {
            startSession(pkg);
        } else if (!onTarget && sessionPkg != null && !chainRunning
                && pkg != null && !pkg.equals(getPackageName())) {
            endSession("poll:" + pkg);
        }
        boolean followTarget = (onTarget && sessionPkg != null) || chainRunning;
        boolean keep = coreManualVisibility != null ? coreManualVisibility : followTarget;
        if (keep && !core.isShown()) {
            core.show();
            applyCoreState();                    // re-apply the honest state on return
            log("CORE_LIVE_SHOW pkg=" + pkg + " on_target=" + onTarget);
            if (!fgsArmed) armForeground();
        } else if (!keep && core.isShown()) {
            core.hide();
            videoDetected = false;               // off-target: detection resets, the job binding
            jobState = CoreStates.IDLE;          // keeps watching for the next entry
            core.setState(CoreStates.IDLE);
            log("CORE_LIVE_HIDE pkg=" + pkg);
        }
        if (keep && core.isShown()) core.ensureOnScreen();   // rotation can move the bounds under a shown Core
    }

    /**
     * The owner's tap — the only thing that ever starts a download (ruling 2026-09-25 ~18:20).
     * If the attention ledger holds a READY candidate (the video on screen was seen, visible,
     * dwelled on, and the feed hasn't paged since), the tap resolves INSTANTLY — no share sheet,
     * no flash. Otherwise the proven copy-link chain runs. TikTok never enters the ledger
     * (its tree is opaque), so its taps always take the chain.
     */
    private void onCoreTap() {
        if (sessionPkg == null) { log("CORE_TAP_NO_SESSION open IG/TikTok first"); return; }
        if (chainRunning) { log("CORE_TAP_BUSY ignored"); return; }
        AttentionLedger.Candidate cand = ledger.best(SystemClock.elapsedRealtime());
        if (cand != null) {
            log("CORE_TAP session=" + sessionPkg + " passive=1 confidence=READY url=" + clip(cand.url, 120));
            try {
                beginChainRun();
                deliverByTap(cand.url, "ledger");
            } catch (Throwable t) {
                log("CHAIN_ERR " + t);
                resolverFailed("passive_" + t.getClass().getSimpleName());
            }
            chainReset();
            return;
        }
        log("CORE_TAP session=" + sessionPkg);
        try { beginChainRun(); runChain(true); }
        catch (Throwable t) { log("CHAIN_ERR " + t); chainReset(); }
    }

    /**
     * One resolver run, however it was started (a Core tap or the bench chain.cmd). Owns the
     * run-scoped state in one place: the one-delivery budget (D-a), the sheet-scroll budget, the
     * stale-step token, and the window flags — the clipboard reads of the *previous* run may still
     * be pending (the retry window outlives the run), so a new run must invalidate them and start
     * from a non-focusable, non-interactive Core.
     */
    private void beginChainRun() {
        chainRunning = true;
        chainDelivered = false;
        chainClickedCopy = false;
        chainSheetNeedsClose = false;
        sheetSwipes = 0;
        sheetWaits = 0;
        sheetScans = 0;
        runGen++;
        if (core != null) {
            core.setFocusable(false);          // clean slate; this run re-focuses at its own step 3
            core.setInteractive(false);        // platform automation owns touch now
        }
    }

    /** Ends a resolver run; the Core is neutral and touchable again. */
    private void chainReset() {
        chainRunning = false;
        chainDelivered = false;             // each run gets its own one-delivery budget (D-a)
        if (core != null) {
            core.setState(CoreStates.IDLE);
            core.setInteractive(true);
        }
    }

    /**
     * Every chain step is posted to the main looper — and an uncaught throwable in one of them
     * takes the whole process down with it, which is exactly how the first tap died on device
     * 2026-09-25 (StackOverflowError inside a View callback). Steps go through here now, so a
     * failed step ends its run instead of killing the Core and the service with it.
     */
    private void postStep(final Runnable step, long delayMs) {
        main.postDelayed(new Runnable() {
            @Override public void run() {
                try { step.run(); }
                catch (Throwable t) { log("CHAIN_STEP_ERR " + t); chainReset(); }
            }
        }, delayMs);
    }

    /** True for nodes from our own overlay (the Core) — never a click candidate. */
    private boolean isOurs(AccessibilityNodeInfo n) {
        try {
            CharSequence p = n.getPackageName();
            return p != null && getPackageName().contentEquals(p);
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------- Q4: pipeline contract ----------

    /**
     * The owner's tap (ruling 2026-09-25 ~18:20). A **user-initiated** download always goes through:
     * the gate in `spike_config.properties` exists only so bench runs can prove the *automatic* path
     * still grabs — in the product nothing may download without a tap, and nothing a tap asks for may
     * be refused. So this path ignores the gate deliberately; the dump path keeps it.
     */
    private void deliverByTap(String url, String route) {
        // Single-funnel validation: every route (clipboard, ledger tree, dump) passes the same
        // media-page rule right before the pipeline — a non-media URL can never slip through.
        String why = MediaUrl.reason(url);
        if (why != null) {
            log("CHAIN_URL_REJECTED route=" + route + " reason=" + why);
            resolverFailed("rejected_" + why);
            return;
        }
        long now = SystemClock.elapsedRealtime();
        String platform = platformOf(url);
        if (!delivery.allow(url, now)) {
            // A second tap on the same video seconds apart (clipboard still holding the link) used
            // to start a second identical engine job and save "Video (1).mp4" next to "Video.mp4".
            // Sheet 8 §5: URL matching prevents a resubmitted job. A deliberate re-fetch later than
            // the window still passes.
            log("CHAIN_DELIVER_DUP route=" + route + " suppressed=same_url_within_"
                    + (DUP_WINDOW_MS / 1000) + "s");
            return;
        }
        // Duplicate prevention BEFORE creating a job (master package §32/§33): an equivalent job
        // that is running is ADOPTED — the Core binds to it instead of firing a second identical
        // download; one that just completed means the file already exists.
        String active = CoreJobBinding.activeStateFor(this, url);
        if ("running".equals(active)) {
            log("CHAIN_DELIVER_ADOPT state=running url=" + clip(url, 200));
            jobState = CoreStates.PROGRESS;
            jobProgress = 0f;                    // the binding's first poll brings the real percent
            applyCoreState();
            if (jobBinding != null) jobBinding.track(url);
            return;
        }
        if ("done".equals(active)) {
            log("CHAIN_DELIVER_DUP route=" + route + " suppressed=already_saved");
            return;
        }
        if (!claimDelivery(route)) return;
        try {
            DowniDownloadService.startShared(this, url);
            delivery.record(url, now);
            lastDeliveredUrl = url;
            recordStrategy(route, true, url);
            log("CHAIN_DELIVER_OK route=" + route + " tap=1 url=" + clip(url, 200));
            // The Core becomes the live visual representation of this job (master package §11).
            jobState = CoreStates.PROGRESS;
            jobProgress = 0f;
            applyCoreState();
            if (jobBinding != null) jobBinding.track(url);
        } catch (Throwable t) {
            log("CHAIN_DELIVER_FAIL route=" + route + " err=" + t);
            recordStrategy(route, false, url);
            resolverFailed("deliver_" + t.getClass().getSimpleName());
        }
    }

    private void pipeline(String url) {
        if (!seenUrls.add(url)) return;          // one PIPELINE line per URL
        if (!handoffEnabled) {
            log("PIPELINE_READY contract=DowniDownloadService.startShared(url) handoff=disabled url="
                    + clip(url, 300));
            return;
        }
        try {
            DowniDownloadService.startShared(this, url);
            delivery.record(url, SystemClock.elapsedRealtime());   // bench grabs count as deliveries too
            lastDeliveredUrl = url;
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
            if (lower.equals("show")) { coreManualVisibility = Boolean.TRUE; core.show(); return; }
            if (lower.equals("hide")) { coreManualVisibility = Boolean.FALSE; core.hide(); return; }
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
            if (parts.length >= 1 && parts[0].equals("pause")) {
                // D3 device proof: pause the Fetcher's most recent running grab — found by its
                // delivered URL in the service's own job snapshot.
                String job = findDropJobId(lastDeliveredUrl, "running");
                if (job == null) { log("CORE_CMD_PAUSE miss why=no_running_job"); return; }
                android.content.Intent i = new android.content.Intent(this, DowniDownloadService.class);
                i.setAction("shared_pause");
                i.putExtra("jobId", job);
                startService(i);
                log("CORE_CMD_PAUSE job=" + job);
                return;
            }
            if (parts.length >= 1 && parts[0].equals("status")) {
                // D3 forensics: what the paused layer actually holds right now.
                try {
                    android.content.SharedPreferences prefs = getSharedPreferences("downi_settings", MODE_PRIVATE);
                    log("STATUS pausedGrabs=" + prefs.getString("pausedGrabs", "{}"));
                    org.json.JSONArray rows = new org.json.JSONArray(prefs.getString("dropLive", "[]"));
                    for (int i = 0; i < rows.length(); i++) {
                        org.json.JSONObject o = rows.optJSONObject(i);
                        if (o != null) log("STATUS row id=" + o.optString("id") + " state=" + o.optString("state")
                                + " pct=" + o.optInt("pct") + " url=" + clip(o.optString("url"), 60));
                    }
                } catch (Throwable t) { log("STATUS_ERR " + t); }
                return;
            }
            if (parts.length >= 1 && parts[0].equals("resume")) {
                // Starts the service if dead (its onCreate sweeps interrupted transfers into
                // resumable paused grabs), then resumes the newest one.
                android.content.Intent i = new android.content.Intent(this, DowniDownloadService.class);
                i.setAction("shared_resume_latest");
                startService(i);
                log("CORE_CMD_RESUME latest");
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
        boolean click = cmd.contains("click");
        if (click && chainRunning) { log("CHAIN_BUSY ignored"); return; }
        try {
            // A bench click run obeys the same one-delivery discipline as a tap — without this the
            // dump path treated the run as "not running" and could deliver without claiming while
            // the clipboard route claimed separately (the original D-a duplicate shape).
            if (click) beginChainRun();
            runChain(click);
        }
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
        if (root == null) { log("CHAIN_ROOT_NULL"); if (doClick) { resolverFailed("root_null"); chainReset(); } return; }
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
        if (t == null) { log("CHAIN_NO_SHARE_CLICK"); resolverFailed("no_share_row"); chainReset(); return; }
        log("CHAIN_SHARE_CLICK text=" + clip(nodeText(t), 120)
                + " route=" + clickNode(t));
        // Event-driven wait (§H2): poll for the share surface's own window instead of sleeping a
        // fixed 1300 ms — the sheet opens in ~700 ms on this ROM, so step 2 starts sooner; the
        // deadline fallback keeps OEM same-window sheets working.
        postStep(new Runnable() { @Override public void run() { waitShareSurface(0); } }, 300);
    }

    private void waitShareSurface(final int attempt) {
        if (!chainRunning) return;               // the run ended under us
        if (shareSurfaceOpen()) {
            chainSheetNeedsClose = true;         // a sheet is up — somebody must close it
            // The window opens BEFORE its content loads (device 2026-09-26 08:57: TikTok's
            // sheet tree was still showing feed rows at +80 ms, and an early scan read the
            // feed instead of the sheet -> no_copy_link). Wait for the sheet's own content:
            // a copy-link candidate anywhere in the surface/platform trees — with a deadline.
            ArrayList<AccessibilityNodeInfo> copyProbe = new ArrayList<>();
            collectCandidates(shareSurfaceRoot(), new ArrayList<AccessibilityNodeInfo>(),
                    copyProbe, new ArrayList<AccessibilityNodeInfo>(), new Counter(), 0);
            collectCandidates(pickShareRoot("CHAIN_CONTENT_PROBE", false), new ArrayList<AccessibilityNodeInfo>(),
                    copyProbe, new ArrayList<AccessibilityNodeInfo>(), new Counter(), 0);
            if (!copyProbe.isEmpty()) {
                log("CHAIN_SURFACE_OPEN after_ms=" + (300 + attempt * 250) + " content=ready");
                chainStep2();
                return;
            }
            if (attempt >= 15) {                 // ~3.7 s total: scan anyway, scrolls take over
                log("CHAIN_SURFACE_TIMEOUT fallback=scan");
                chainStep2();
                return;
            }
            postStep(new Runnable() { @Override public void run() { waitShareSurface(attempt + 1); } }, 250);
            return;
        }
        if (attempt >= 7) {
            log("CHAIN_SURFACE_TIMEOUT fallback=scan");
            chainStep2();                        // OEM same-window sheets: scan anyway
            return;
        }
        postStep(new Runnable() { @Override public void run() { waitShareSurface(attempt + 1); } }, 250);
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
        // Owner ruling 2026-09-25 ~18:20: the Fetcher must NOT act as Downi Drop. The routes that
        // clicked DOWNI in the system chooser (or the sheet row that opens it) are DELETED — that is
        // Drop's territory, and presenting it as the Fetcher is what the owner rejected. The one
        // target here is the platform's own **Copy link**: DOWNI reads the link it just copied and
        // starts the download. Nothing is shared with anyone, and nothing downloads without a tap.
        //
        // THE SHEET-TREE FAST LANE (measured 2026-09-26 08:41): on Instagram the OPEN SHEET's own
        // window tree holds the reel's page URL (quiet-watch dumps are 21/21 clean, but sheet-time
        // dumps carried it). When it is here, deliver straight from the sheet — skipping the
        // copy-link click AND the focus/clipboard dance saves ~1.5-2 s and one fragile step.
        // The sheet gets its BACK press before delivery; nothing else changes.
        java.util.ArrayList<String> sheetUrls = corpusUrls(root);
        for (String cand : sheetUrls) {
            if (MediaUrl.reason(cand) != null) continue;
            log("CHAIN_SHEET_TREE_DELIVER url=" + clip(cand, 200));
            chainSheetNeedsClose = false;
            log("CHAIN_CLOSE_PANEL back=" + performGlobalAction(GLOBAL_ACTION_BACK) + " why=sheet_tree_route");
            deliverByTap(cand, "sheet_tree");
            chainReset();
            return;
        }
        // Instagram only: give the sheet's web content one extra beat (350 ms) to expose its URL
        // before falling to the copy-link click — the tree lane saves the click AND the focus
        // dance. TikTok skips this entirely (its sheet tree never holds a URL).
        if ("instagram".equals(sessionShort()) && shareSurfaceOpen() && sheetScans < 1) {
            sheetScans++;
            log("CHAIN_SHEET_TREE_RETRY n=" + sheetScans + " why=awaiting_sheet_webview");
            postStep(new Runnable() { @Override public void run() { chainStep2(); } }, 350);
            return;
        }
        AccessibilityNodeInfo c = firstClickable(copy);
        if (c == null) {
            // IG's sheet lists DM targets first; its own action rows ("share", "copy link")
            // sit below the fold unless the sheet is scrolled (device 2026-09-25).
            //
            // DEFECT (owner report 2026-09-25 night): the old code swiped BLINDLY — an upward
            // center-screen stroke, which is exactly the feed's next-video gesture. When the
            // share surface had not opened yet (or had already closed), that stroke scrolled
            // the VIDEO away instead of the sheet. A swipe may now only ever fire when the
            // share surface is verifiably on screen as its own window; otherwise the run waits
            // once for the sheet animation and then fails honestly, never touching the feed.
            boolean surface = shareSurfaceOpen();
            if (surface && sheetSwipes < MAX_SHEET_SWIPES) {
                sheetSwipes++;
                log("CHAIN_SHEET_SCROLL n=" + sheetSwipes + "/" + MAX_SHEET_SWIPES
                        + " swipe=" + swipeSheetList());
                postStep(new Runnable() { @Override public void run() { chainStep2(); } }, 900);
                return;
            }
            if (!surface && sheetWaits < MAX_SHEET_WAITS) {
                sheetWaits++;
                log("CHAIN_SHEET_WAIT n=" + sheetWaits + "/" + MAX_SHEET_WAITS
                        + " why=surface_not_open_no_swipe");
                postStep(new Runnable() { @Override public void run() { chainStep2(); } }, 900);
                return;
            }
            log("CHAIN_NO_TARGET neither DOWNI nor Copy link found"
                    + (surface ? "" : " note=share_surface_never_opened"));
            resolverFailed(surface ? "no_copy_link" : "sheet_never_opened");
            chainReset();
            return;
        }
        chainClickedCopy = true;
        chainSheetNeedsClose = true;
        log("CHAIN_TARGET_CLICK which=copylink text=" + clip(nodeText(c), 120)
                + " route=" + clickNode(c));
        postStep(new Runnable() { @Override public void run() { chainStep3(); } }, 1200);
    }

    // ---------- deleted 2026-09-25 ~18:20 by owner ruling ----------
    // `findSheetShareRow()`, `chainChooser()` and `swipeChooserList()` used to click DOWNI inside the
    // system share chooser (TikTok lists it directly; Instagram's sheet has a "share" row that opens
    // the chooser). That transport belongs to **Downi Drop**, not the Fetcher: a share sheet whose
    // target is DOWNI is exactly what the owner already has. The Fetcher's own transport is the
    // platform's "Copy link" -> clipboard -> `deliverByTap()`. The three methods and the
    // `chooserSwipes` counter are gone rather than kept dormant, so no future session can drift back
    // into calling them.


    /** Scroll the platform's own share sheet (gentle: it is anchored to the bottom). */
    private boolean swipeSheetList() {
        return swipeWithinSheet(0.80f, 0.64f);
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

    /**
     * True when a share surface (the platform's sheet or the system chooser) is verifiably on
     * screen as its OWN window. This ROM's sheet is a separate `com.vivo.upslide` window; other
     * devices use the system resolver — anything that is not the target app, our overlay, or the
     * system UI counts. This is the gate that keeps the sheet-scroll swipe OFF the feed: no
     * share surface, no swipe (the feed's next-video gesture must never fire from a fetch).
     */
    private boolean shareSurfaceOpen() {
        try {
            for (AccessibilityWindowInfo w : getWindows()) {
                AccessibilityNodeInfo r;
                try { r = w.getRoot(); } catch (Throwable t) { continue; }
                if (r == null || r.getPackageName() == null) continue;
                String wp = r.getPackageName().toString();
                if (wp.equals(getPackageName())) continue;                  // our overlay
                if (wp.equals("com.android.systemui")) continue;            // shade / keys
                if (wp.equals(sessionPkg) || TARGETS.contains(wp)) continue; // the platform app itself
                return true;                                                // a share surface exists
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /** The share surface's own window root (this ROM: com.vivo.upslide), or null when closed. */
    private AccessibilityNodeInfo shareSurfaceRoot() {
        try {
            for (AccessibilityWindowInfo w : getWindows()) {
                AccessibilityNodeInfo r;
                try { r = w.getRoot(); } catch (Throwable t) { continue; }
                if (r == null || r.getPackageName() == null) continue;
                String wp = r.getPackageName().toString();
                if (wp.equals(getPackageName()) || wp.equals("com.android.systemui")) continue;
                if (wp.equals(sessionPkg) || TARGETS.contains(wp)) continue;
                return r;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * Media-page URLs visible in the open share surface first, then the platform app's tree.
     * The sheet-tree fast lane's evidence source (§next-level: IG leaks the reel URL through the
     * open sheet's window — delivering from it skips the copy-link click and the clipboard dance).
     */
    private java.util.ArrayList<String> corpusUrls(AccessibilityNodeInfo platformRoot) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        collectUrls(shareSurfaceRoot(), out, new Counter(), 0);
        collectUrls(platformRoot, out, new Counter(), 0);
        return out;
    }

    private void collectUrls(AccessibilityNodeInfo node, java.util.ArrayList<String> out,
                             Counter c, int depth) {
        if (node == null || c.nodes >= MAX_NODES || depth > MAX_DEPTH || out.size() >= 8) return;
        if (isOurs(node)) return;
        c.nodes++;
        String txt = strOrEmpty(node.getText()) + " " + strOrEmpty(node.getContentDescription());
        if (txt.contains("http")) {
            Matcher m = URL_P.matcher(txt);
            while (m.find() && out.size() < 8) {
                String u = m.group().replaceAll("[\\.,;:!?)\\]\"']+$", "");
                if (!out.contains(u)) out.add(u);
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = null;
            try { child = node.getChild(i); } catch (Throwable ignored) {}
            collectUrls(child, out, c, depth + 1);
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
        if (chainSheetNeedsClose) {
            log("CHAIN_CLOSE_PANEL back=" + performGlobalAction(GLOBAL_ACTION_BACK));
            chainSheetNeedsClose = false;
        }
        log("CHAIN_DONE focus_pkg=" + pkg);
        logWindows("CHAIN_DONE");

        // The platform's own "Copy link" has just put the real deep link on the clipboard.
        // Android 10+ refuses clipboard reads to apps that are not in focus, so the Core
        // is briefly made focusable, then restored.
        if (chainClickedCopy && !chainDelivered) {
            clipTries = 0;
            final int gen = runGen;             // a newer run invalidates these steps
            if (core != null) core.setFocusable(true);
            postStep(new Runnable() { @Override public void run() { clipAttempt(gen); } }, 500);
            // Hold focus for the whole retry budget, then always give it back — a permanently
            // focusable bubble would eat the platform's back key and its own touches.
            postStep(new Runnable() {
                @Override public void run() {
                    if (gen != runGen) return;  // the run that asked for focus was superseded
                    if (core != null) core.setFocusable(false);
                }
            }, 500 + MAX_CLIP_TRIES * 250 + 400);
        } else if (chainClickedCopy) {
            log("CHAIN_CLIP_SUPPRESSED already_delivered");   // D-a: one delivery per tap
        }
        chainReset();                       // bubble idle + touchable again
    }

    /**
     * One clipboard read-and-deliver attempt of run {@code gen}. Runs after the run itself has
     * ended (`chainReset` in {@link #chainStep3}), so every attempt must re-check its generation:
     * a second tap starts a new run whose steps must never race these — a stale reader here would
     * otherwise deliver the previous video's URL, claim the new run's delivery budget, or share
     * {@code clipTries} with the live reader (audit 2026-09-25).
     */
    private void clipAttempt(final int gen) {
        if (gen != runGen) { log("CHAIN_CLIP_STALE gen=" + gen + " now=" + runGen); return; }
        // D-e (2026-09-25): this route was a coin flip — 3 of 5 attempts came back got=null. The
        // cause is focus: make-focusable is not focused, and Android 10+ hands the clipboard only
        // to an app that holds focus. So each attempt asks for focus and *reports* whether it got
        // it, and the read is retried while the copy settles.
        if (core != null) { core.setFocusable(true); core.requestFocus(); }
        boolean focus = core != null && core.hasWindowFocus();
        String url = readClipboardText();
        log("CHAIN_CLIP_TRY n=" + (clipTries + 1) + "/" + MAX_CLIP_TRIES
                + " focus=" + focus + " got=" + (url == null ? "null" : "yes"));
        if (url == null) {
            clipTries++;
            if (clipTries < MAX_CLIP_TRIES) {
                postStep(new Runnable() { @Override public void run() { clipAttempt(gen); } }, 250);
                return;
            }
            log("CHAIN_CLIPBOARD got=null text= tries=" + clipTries);
            strategies.record(sessionShort(), "clipboard", false, SystemClock.elapsedRealtime());
            log("STRATEGY " + sessionShort() + " " + strategies.summary(sessionShort(), "clipboard"));
            resolverFailed("clipboard_empty");
            return;
        }
        log("CHAIN_CLIPBOARD got=yes text=" + clip(url, 200));
        String why = MediaUrl.reason(url);      // D-b: a media page, not a bio/redirect link
        if (why == null) {
            // The owner's tap is the trigger, so this delivers regardless of the bench gate
            // (ruling 2026-09-25: nothing auto-downloads, nothing a tap asks for is refused).
            deliverByTap(url, "clipboard");
        } else {
            log("CHAIN_URL_REJECTED reason=" + why);
            resolverFailed("rejected_" + why);
        }
    }

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
        if (isOurs(node)) return;             // the Core is not a platform button
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
                    .setContentText("Tap the Core on an Instagram or TikTok video")
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
        live = null;
        log("SERVICE_DESTROY");
        main.removeCallbacks(chainPoll);
        main.removeCallbacks(coreTickPoll);
        main.removeCallbacks(heartbeat);
        main.removeCallbacks(corePoll);
        if (failedHold != null) { main.removeCallbacks(failedHold); failedHold = null; }
        if (jobBinding != null) { jobBinding.stop(); jobBinding = null; }
        try { unregisterReceiver(screenReceiver); } catch (Throwable ignored) {}
        pollersArmed = false;               // a fresh bind after destroy must re-post the loops
        disarmForeground();
        if (core != null) core.destroy();
        closing = true;
        outbox.offer(POISON);
        try { if (writer != null) writer.join(300); } catch (InterruptedException ignored) {}
        try { if (fileOut != null) fileOut.close(); } catch (Exception ignored) {}
        super.onDestroy();
    }
}



