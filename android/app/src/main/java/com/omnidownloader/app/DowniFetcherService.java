package com.omnidownloader.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import com.omnidownloader.app.downicore.CoreArbiter;
import com.omnidownloader.app.downicore.ReachLayer;
import com.omnidownloader.app.downicore.CoreHaptics;
import com.omnidownloader.app.downicore.CoreJobBinding;
import com.omnidownloader.app.downicore.CoreStates;
import com.omnidownloader.app.downicore.DowniCore;
import com.omnidownloader.app.fetcher.ChainObserver;
import com.omnidownloader.app.fetcher.CoreTapAction;
import com.omnidownloader.app.fetcher.Route;
import com.omnidownloader.app.fetcher.EventRing;
import com.omnidownloader.app.fetcher.ShareRows;
import com.omnidownloader.app.fetcher.ShareSurface;
import com.omnidownloader.app.fetcher.SheetSwipe;
import com.omnidownloader.app.fetcher.AttentionLedger;
import com.omnidownloader.app.fetcher.DeliveryGuard;
import com.omnidownloader.app.fetcher.MediaUrl;
import com.omnidownloader.app.fetcher.PlatformProfile;
import com.omnidownloader.app.fetcher.StrategyLedger;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DOWNI Fetcher — the accessibility service that hosts the Downi Core over Instagram
 * and TikTok (v3.2, Phase G renamed from the detectability spike).
 *
 * Reads the platform app's accessibility tree on this device only:
 *   - foreground package (SESSION_START/END) → the Core appears over the target apps;
 *   - video-watching signals (STEP2) → the Core wakes honestly, never downloads;
 *   - any URL/identifier for the current video (STEP3) → evidence for the resolver
 *     and the attention ledger;
 *   - a user tap on the Core is the ONLY thing that starts a download, through the
 *     unchanged DowniDownloadService.startShared contract.
 *
 * Bench instruments (the forensic file log, the core.cmd/chain.cmd command channels,
 * screenshots, the auto-handoff gate) exist for device gates only and are hard-gated
 * to BuildConfig.DEBUG — a release build carries none of them (Phase G).
 */
public class DowniFetcherService extends AccessibilityService {
    private static final String TAG = "DowniFetcher";

    /** Live instance for same-process control hooks (settings card size/position calls). */
    private static volatile DowniFetcherService live;

    /** Settings-card hook: apply a new Core size to the live Core, if it is running. */
    public static void applyCoreSizeLive(int dp) {
        DowniFetcherService s = live;
        if (s != null && s.core != null) s.core.setSizeDp(dp);
    }

    /** Settings-card hook: put the Core back at its default spot. */
    public static void resetCorePositionLive() {
        DowniFetcherService s = live;
        if (s != null && s.core != null) s.core.resetPosition();
    }

    /**
     * True while THIS app has a live Fetcher service instance bound — the honest "is it actually
     * running right now" that the settings card and the re-arm banner need. `armed` in the setting
     * only says the platform has us enabled; this says the service exists.
     */
    public static boolean isBound() {
        return live != null;
    }

    /**
     * The black box (Wave 5): the last 50 events of every build, not just debug ones. The plugin
     * reads this when the service is alive, and the persisted copy ("downi_fetcher"/"lastEvents")
     * when it is not — so the events before a vendor kill are still readable afterwards.
     */
    private static final EventRing BLACKBOX = new EventRing();

    public static EventRing blackbox() {
        return BLACKBOX;
    }

    /**
     * Persists the ring so it survives a kill and can be read without adb. Called every few events
     * (EventRing.FLUSH_EVERY) and at milestones; one small prefs entry, no file I/O.
     */
    private void persistBlackbox() {
        try {
            getSharedPreferences("downi_fetcher", MODE_PRIVATE).edit()
                    .putString("lastEvents", BLACKBOX.dump())
                    .putLong("lastEventAt", System.currentTimeMillis())
                    .apply();
            BLACKBOX.markFlushed();
        } catch (Throwable ignored) {}
    }

    /**
     * Route health from the live service's strategy ledger (Wave 1): per platform+route
     * recent success rates over a rolling 20-attempt window, as a JSON array for the
     * settings card. Empty array when no Fetcher is running — honest, not a guess. Rates
     * are session-scoped: they describe THIS process's measured reality.
     */
    public static org.json.JSONArray routeHealth() {
        org.json.JSONArray out = new org.json.JSONArray();
        DowniFetcherService s = live;
        if (s == null) return out;
        try {
            for (StrategyLedger.Stat st : s.strategies.stats()) {
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("platform", st.platform);
                o.put("route", st.route);
                o.put("rate", st.rate < 0 ? -1 : Math.round(st.rate * 100) / 100.0);
                o.put("samples", st.samples);
                out.put(o);
            }
        } catch (Throwable ignored) {}
        return out;
    }

    /** The current session's profile, or null outside a target app (Wave 1: one home
     *  for everything that differs between Instagram and TikTok). */
    private PlatformProfile profile() {
        return PlatformProfile.forPackage(sessionPkg);
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
    private static final long HEARTBEAT_MS = 30_000;   // liveness + memory snapshot cadence

    private final Handler main = new Handler(Looper.getMainLooper());

    private long connectedAt;             // service-connect stamp, for HEARTBEAT uptime

    private String sessionPkg;            // non-null while a target app is foreground
    private long sessionStart;
    private int dumpCount, sessionDumps, step3Hits;
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
     * The share-surface window THIS run saw open, as an identity (package + title + bounds).
     * The close gate asks {@link ShareSurface#stillOpen} about this window instead of asking
     * "does any non-systemui window exist?" — which on this ROM is always true (the
     * `com.vivo.upslide` gesture bar, and the IME while a keyboard is up), so a BACK press meant
     * for an already-dismissed sheet landed on the FEED and advanced the reel (owner report
     * 2026-09-28, 5/5 on TikTok). Recorded by `waitShareSurface`, cleared by the close gate.
     */
    private ShareSurface.Win chainSheetWin;
    /** The descriptor the policy picked in the last {@link #sheetSurfaceWindow()} call. */
    private ShareSurface.Win lastSurfaceWin;
    /**
     * True once this run has tried the platform's own overflow ("More actions for this post") because
     * the share row could not be used — the Home feed's second route (Wave 4, 2026-09-28).
     */
    private boolean chainTriedOverflow;

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
    private int sheetWaits;                          // re-scans while the sheet is still animating
    private int sheetScans;                          // sheet-tree fast-lane retries (budget: profile)
    private static final int MAX_SHEET_WAITS = 1;
    // D-e (2026-09-25): the clipboard read is a *race* — our window must actually hold focus, and
    // setFocusable(true) alone does not grant it. So the read is retried a few times behind the tap.
    private int clipTries;
    private static final int MAX_CLIP_TRIES = 6;
    // M8: the three numbers of that window, as numbers. They were literals spread across chainStep3
    // and clipAttempt, including a hand-computed focus hold (`500 + 6 * 250 + 400`) that had to be
    // re-derived by hand whenever one of them moved — the drift this file's other budgets live in
    // named constants to avoid. The first read is 350 ms, not 500: the focus pre-warm (chainStep3
    // asks for focus the moment the share panel closes) already existed when the 500 was authored,
    // and every retry below still covers a copy that lands late, so this only removes dead time.
    private static final long CLIP_READY_MS = 350;
    private static final long CLIP_RETRY_MS = 250;
    private static final long CLIP_HOLD_TAIL_MS = 400;

    /** How long the Core stays focusable for the whole read window — derived, never hand-added. */
    private static long clipFocusHoldMs() {
        return CLIP_READY_MS + MAX_CLIP_TRIES * CLIP_RETRY_MS + CLIP_HOLD_TAIL_MS;
    }

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

    // Wave 1: the run narrates. The observer receives every named beat; this service's
    // listener logs them, times the run, and fires the capture haptic at the clipboard
    // beat (Wave 2's Reach choreography will listen to the same events from CoreHost).
    private long runStartedAt;
    private boolean runEndPending;                    // clipboard window still owns the run's end
    private String runRoute;                          // the route that delivered, or null
    private final ChainObserver observer = new ChainObserver() {
        @Override public void onRunStarted(String platform, String routePlan, boolean interactive) {
            log("RUN_START platform=" + platform + " plan=" + routePlan);
        }
        @Override public void onStep(String step, String detail) {
            log("RUN_STEP step=" + step + (detail == null || detail.isEmpty() ? "" : " " + detail));
        }
        @Override public void onCaptured(String route, String url) {
            log("RUN_CAPTURE route=" + route + " url=" + clip(url, 120));
            if (screenOn && core != null && core.isShown()) CoreHaptics.capture(DowniFetcherService.this);
        }
        @Override public void onRunEnded(boolean delivered, String route, long durationMs) {
            log("RUN_END delivered=" + delivered + " route=" + (route == null ? "-" : route)
                    + " ms=" + durationMs);
        }
    };

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
        PlatformProfile p = profile();       // Wave 1: ledger eligibility is the profile's call
        for (String[] s : urlSightings) {
            String url = s[0];
            boolean visible = "1".equals(s[1]);
            String why = MediaUrl.reason(url);
            if (why == null && p != null && p.passiveCapable) ledger.offer(url, visible, now);
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

    /** Newest dropLive row id matching url (when given) and state. Debug-channel helper.
     *  Wave 0: the row-scanning rule lives in the shared {@link JobSnapshot} contract. */
    private String findDropJobId(String url, String stateWanted) {
        try {
            if (url == null || url.isEmpty()) {          // null url = newest row of any url
                org.json.JSONArray list = JobSnapshot.read(this);
                String found = null;
                for (int i = 0; i < list.length(); i++) {
                    org.json.JSONObject o = list.optJSONObject(i);
                    if (o == null) continue;
                    if (stateWanted != null && !stateWanted.equals(o.optString("state"))) continue;
                    found = o.optString("id");
                }
                return found;
            }
            org.json.JSONObject row = JobSnapshot.newestRowFor(this, url, stateWanted);
            if (row == null) return null;
            String id = row.optString("id");
            return id.isEmpty() ? null : id;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Platform short name from the foreground session package (for records without a URL). */
    private String sessionShort() {
        PlatformProfile p = profile();
        return p != null ? p.key : "other";
    }

    private void recordStrategy(String route, boolean ok, String url) {
        String platform = platformOf(url);
        // Wave 1: the ledger speaks the Route vocabulary. The clipboard MECHANISM delivers
        // the copy_link ROUTE (logs keep `route=clipboard` for history continuity).
        String routeKey = "clipboard".equals(route) ? Route.COPY_LINK : route;
        strategies.record(platform, routeKey, ok, SystemClock.elapsedRealtime());
        log("STRATEGY " + platform + " " + strategies.summary(platform, routeKey));
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
                // M8: in the dark the tick loop now sleeps at OFFSCREEN_LOOP_MS, so the Core would
                // come back up to five seconds late on a screen that just lit under a video. The
                // first lit tick is immediate instead — the same shape the a11y event path uses.
                main.post(new Runnable() { @Override public void run() { coreTick(); } });
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
    private ReachLayer reach;                   // the run's choreography layer (Wave 2)
    private Boolean coreManualVisibility;         // debug show/hide; null = follow target app
    private final DowniCore.Listener coreListener = new DowniCore.Listener() {
        @Override public void onCoreLog(String msg) { log(msg); }
        @Override public void onCoreTap() {
            try { DowniFetcherService.this.onCoreTap(); }
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
     * The precedence rules live in the pure, tested {@link CoreArbiter} (Wave 0);
     * the DETECTED look is graded READY/AWARE by measured route health (Wave 2).
     */
    private void applyCoreState() {
        if (core == null) return;
        String s = CoreArbiter.baseState(jobState, chainRunning, videoDetected);
        if (CoreStates.DETECTED.equals(s)) core.setWakeGrade(wakeReady());
        if (CoreStates.showsProgress(jobState)) core.setProgress(jobProgress);
        core.setBaseState(s);
    }

    /** The wake grade threshold (Wave 2): degrade to AWARE only on MEASURED failure. */
    private static final double READY_RATE_FLOOR = 0.60;
    private static final int READY_MIN_SAMPLES = 3;
    private static final long WAKE_TICK_MIN_GAP_MS = 4_000;
    private long lastWakeTickAt;

    /**
     * True when a tap will plausibly land on this platform right now. The ledger's READY
     * candidate (Instagram) is instant confidence; otherwise the platform's gesture routes
     * must be measurably healthy. No data yet = READY — the Core never claims brokenness it
     * has not measured (the honesty rule cuts both ways).
     */
    private boolean wakeReady() {
        PlatformProfile p = profile();
        if (p == null) return false;
        if (p.passiveCapable && ledger.best(SystemClock.elapsedRealtime()) != null) return true;
        boolean anyData = false;
        for (StrategyLedger.Stat st : strategies.stats()) {
            if (!p.key.equals(st.platform) || st.samples < READY_MIN_SAMPLES) continue;
            anyData = true;
            if (st.rate >= READY_RATE_FLOOR) return true;   // one healthy route is enough
        }
        return !anyData;                                    // unmeasured = not yet failing
    }

    /**
     * Detection wake (master package §9): watching signals found in the platform's tree mean a
     * video is on screen — the Core rises once (WAKE settles to DETECTED in the host view). No
     * signals (profiles, settings, DMs) → honest idle. Detection never downloads; it only wakes
     * the Core so the user knows a tap will find something. Wave 2: the wake is GRADED (sheet
     * C2) and only the READY wake ticks — rate-limited to one per 4 s.
     */
    private void updateDetection(boolean hasSignal) {
        if (chainRunning || hasSignal == videoDetected) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastDetectFlipAt < DETECT_DEBOUNCE_MS) return;   // next dump re-checks
        lastDetectFlipAt = now;
        videoDetected = hasSignal;
        applyCoreState();
        if (hasSignal && core != null) {
            boolean ready = wakeReady();
            core.setWakeGrade(ready);
            core.setState(CoreStates.WAKE);
            if (ready && screenOn && core.isShown() && now - lastWakeTickAt >= WAKE_TICK_MIN_GAP_MS) {
                lastWakeTickAt = now;
                CoreHaptics.detected(this);
            }
        }
        log("CORE_DETECT detected=" + hasSignal + " grade=" + (hasSignal ? (wakeReady() ? "ready" : "aware") : "none"));
    }

    /**
     * The resolver could not keep its promise (no share row, empty clipboard, rejected URL,
     * refused delivery). The Core says so once — restrained error tint, retry-ready — then
     * returns to whatever is actually true (master package §15: "Something went wrong.",
     * never "SYSTEM FAILURE!!!"). Wave 2: an UNSUPPORTED rejection (a photo post) takes the
     * neutral blue-gray mood instead of rose (sheet C6), with its own dull double-tap.
     */
    private void resolverFailed(String why) {
        log("CORE_RESOLVE_FAIL why=" + why);
        persistBlackbox();                            // a failure is a milestone: keep it on disk
        if (failedHold != null) main.removeCallbacks(failedHold);
        jobState = CoreStates.FAILED;
        jobProgress = 0f;
        boolean unsupported = MediaUrl.isUnsupportedReason(why);
        applyCoreState();
        if (core != null) core.setUnsupported(unsupported);
        if (screenOn && core != null && core.isShown()) {
            if (unsupported) CoreHaptics.unsupported(this);
            else CoreHaptics.failed(this);
        }
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

    // M8: the loops below live as long as the service does, and until now each re-posted itself at its
    // lit-screen cadence even in the dark — where it skips its work but is still a main-thread wakeup
    // ~2.4 times a second, which is exactly the profile the vendor power manager answers with a FORCE
    // STOP (see the ambient note in CoreHost). The lit cadences are untouched (every device gate was
    // measured at them); the dark half of the phone's life runs at OFFSCREEN_LOOP_MS.
    private static final long OFFSCREEN_LOOP_MS = 5_000;
    private static final long CORE_CMD_POLL_MS = 800;
    private static final long CORE_TICK_POLL_MS = 900;
    private static final long CHAIN_CMD_POLL_MS = 1_500;

    /** A loop's own cadence while the screen is on; the dark cadence while it is not (§E2). */
    private long loopDelay(long onScreenMs) {
        return screenOn ? onScreenMs : OFFSCREEN_LOOP_MS;
    }

    // Reads fetch-spike/core.cmd — the Core's Phase A command channel:
    //   show | hide | list | size <48|56|64> | state <name> | progress <0-100> | mark <scale> | at <x> <y>
    private final Runnable corePoll = new Runnable() {
        @Override public void run() {
            if (screenOn) { try { pollCoreCmd(); } catch (Throwable t) { log("CORE_POLL_ERR " + t); } }
            main.postDelayed(this, loopDelay(CORE_CMD_POLL_MS));
        }
    };

    // Tracks the foreground package so the Core follows IG/TikTok even when no accessibility
    // event fires (service switched on while the platform is already open).
    private final Runnable coreTickPoll = new Runnable() {
        @Override public void run() {
            if (screenOn) { try { coreTick(); } catch (Throwable t) { log("CORE_TICK_ERR " + t); } }
            main.postDelayed(this, loopDelay(CORE_TICK_POLL_MS));
        }
    };

    // Polls for fetch-spike/chain.cmd — the spike's command channel (dry|click).
    private final Runnable chainPoll = new Runnable() {
        @Override public void run() {
            if (screenOn) { try { pollChainCmd(); } catch (Throwable t) { log("CHAIN_POLL_ERR " + t); } }
            main.postDelayed(this, loopDelay(CHAIN_CMD_POLL_MS));
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

        // Wave 5: the black box carries the PREVIOUS session over, and says out loud when that session
        // never stopped cleanly — which is the only trace a vendor kill leaves, since logcat is empty
        // for release builds. The events before the kill then stay readable after it.
        try {
            String prev = getSharedPreferences("downi_fetcher", MODE_PRIVATE)
                    .getString("lastEvents", "");
            if (prev != null && !prev.isEmpty()) {
                String last = prev.substring(prev.lastIndexOf('\n') + 1).trim();
                BLACKBOX.seed(prev);
                if (!last.contains("SERVICE_DESTROY") && !last.contains("SESSION_STOP")) {
                    BLACKBOX.add(ts() + " SESSION_DIED_UNEXPECTEDLY prev_last=" + EventRing.clean(last));
                }
            }
        } catch (Throwable ignored) {}

        // Bench plumbing (log file, handoff gate, screenshots) lives in FetcherBench —
        // debug builds only; a release build does no bench I/O at all. The screenshot
        // capability itself is declared ONLY in the debug source set's a11y config.
        File dir = FetcherBench.dir(this);
        handoffEnabled = FetcherBench.readHandoffGate(dir);
        FetcherBench.openLog(dir);

        log("=== DOWNI FETCHER (debug build: bench log local only) ===");
        log("SERVICE_CONNECTED sdk=" + Build.VERSION.SDK_INT + " handoff=" + handoffEnabled);
        log("CONTRACT target=DowniDownloadService.startShared(context,url)");
        log("BENCH armed: fetch-spike/chain.cmd (dry|click) + core.cmd, debug builds only");
        persistBlackbox();   // the session's opening lines are on disk before anything can kill us

        // The approved Core is now the live control over IG/TikTok. The legacy spike bubble is
        // no longer instantiated; its proven tap resolver remains unchanged underneath.
        core = new DowniCore(this, coreListener);
        log("CORE_READY live=true window=TYPE_ACCESSIBILITY_OVERLAY states=" + CoreStates.list());

        // The Reach (Wave 2, sheet C4): the run's choreography layer — tether, landing
        // highlight, capture flash. Attached lazily per run, gone when the run's truth lands.
        reach = new ReachLayer(this, msg -> log(msg));

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
                    if (CoreStates.COMPLETE.equals(jobState) && !wasComplete) CoreHaptics.complete(DowniFetcherService.this);
                    if (CoreStates.FAILED.equals(jobState)) CoreHaptics.failed(DowniFetcherService.this);
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
            // Perceived speed (owner report 2026-09-26): the Core must be on screen as soon as
            // the service exists - the first visibility tick runs NOW, the loop then steadies
            // at its 900 ms cadence.
            main.post(new Runnable() { @Override public void run() { coreTick(); } });
            main.postDelayed(coreTickPoll, CORE_TICK_POLL_MS);
            main.postDelayed(corePoll, CORE_CMD_POLL_MS);
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
                if (pkg != null && PlatformProfile.isTarget(pkg)) {
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
        if (reach != null) reach.destroy();
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
        dumpCount = sessionDumps = step3Hits = 0;
        lastDumpBody = "";
        seenUrls.clear();
        ledger.clear();                      // a new app context: attention resets
        log("SESSION_START pkg=" + pkg);
        FetcherBench.screenshot(this, "session_start");
        // Perceived speed: the Core appears WITH the session - a target app coming to the
        // foreground triggers the visibility tick immediately instead of waiting up to 900 ms
        // for the next poll. Idempotent: coreTick only acts when something changed.
        main.post(new Runnable() { @Override public void run() { coreTick(); } });
    }

    private void endSession(String why) {
        log("SESSION_END pkg=" + sessionPkg + " why=" + why
                + " duration_ms=" + (System.currentTimeMillis() - sessionStart)
                + " dumps=" + dumpCount + " step3_hits=" + step3Hits);
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
        if (BuildConfig.DEBUG) log(bodyStr);     // full tree, one chunk (debug builds only)
        extractAndVerdict(corpus.toString());
        feedLedger();                            // attention: what is on screen right now
        updateDetection(!signals.isEmpty());     // Phase 0 P0-2: watching signals = a video is on screen
        if (sessionDumps == 4) FetcherBench.screenshot(this, "dump4");
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
        boolean onTarget = pkg != null && PlatformProfile.isTarget(pkg);
        if (onTarget && sessionPkg == null) {
            startSession(pkg);
        } else if (!onTarget && sessionPkg != null && !chainRunning
                && pkg != null && !pkg.equals(getPackageName())) {
            endSession("poll:" + pkg);
        }
        // Wave 1 (D-q, device log 18:42:48): during the clipboard-read window the focus pre-warm
        // makes OUR OWN window the active one — that must not read as "left the target app" or
        // the Core hides out from under the very read that needs it. While the Core's window is
        // deliberately focusable, hold position; a user simply opening DOWNI (Core not focusable)
        // still hides it exactly as before.
        boolean oursFocusWindow = pkg != null && pkg.equals(getPackageName())
                && core != null && core.isFocusableNow();
        boolean followTarget = (onTarget && sessionPkg != null) || chainRunning || oursFocusWindow;
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
     * Wave 2 tap grammar (D-V2-3): the tap's MEANING follows the Core's state — a PAUSED Core
     * resumes its job, a COMPLETE Core peeks at the file, a FAILED Core retries — and every
     * other state fetches. If the attention ledger holds a READY candidate the fetch resolves
     * INSTANTLY; otherwise the proven copy-link chain runs.
     */
    private void onCoreTap() {
        if (chainRunning) { log("CORE_TAP_BUSY ignored"); return; }
        String base = CoreArbiter.baseState(jobState, chainRunning, videoDetected);
        String tracked = jobBinding != null ? jobBinding.trackedUrl() : null;
        CoreTapAction.Action action = CoreTapAction.of(base, tracked != null);
        log("CORE_TAP session=" + sessionPkg + " action=" + action + " state=" + base);
        switch (action) {
            case RESUME:
                resumeTracked(tracked);
                return;
            case PEEK:
                peekTracked(tracked);
                return;
            case RETRY:
                if (tracked != null) {
                    // C6: the Core acknowledges the tap FIRST — the rose reads back to teal through
                    // the C3 press/rebound (core_retry), and settles into RESOLVING after RETRY_MS.
                    // Nothing below can cut it short: RETRY is a transient, and
                    // DowniCore.setBaseState refuses to clobber one (the same guard the drag uses).
                    if (core != null) core.setState(CoreStates.RETRY);
                    // The engine failed this URL — failures may retry freely (master package §33).
                    try { beginChainRun(); deliverByTap(tracked, "retry"); } catch (Throwable t) { log("CHAIN_ERR " + t); }
                    chainReset();
                    return;
                }
                break;                       // a resolver dead-end: fall through to a fresh fetch
            case FETCH:
                break;
        }
        if (sessionPkg == null) { log("CORE_TAP_NO_SESSION open IG/TikTok first"); return; }
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

    /** Tap on a PAUSED Core: continue the transfer from the bytes on disk (D3). */
    private void resumeTracked(String url) {
        try {
            org.json.JSONObject row = JobSnapshot.newestRowFor(this, url, "paused");
            String jobId = row == null ? null : row.optString("id");
            if (jobId == null || jobId.isEmpty()) { log("CORE_RESUME miss why=no_paused_row"); return; }
            android.content.Intent i = new android.content.Intent(this, DowniDownloadService.class);
            i.setAction("shared_resume");
            i.putExtra("jobId", jobId);
            startService(i);
            log("CORE_RESUME job=" + jobId + " url=" + clip(url, 120));
            // R2 (approved 2026-09-26): a resume was REALLY requested at this door, so the Core
            // may show RESUMING. The transient is protected from the job feed (setBaseState skips
            // transients) and settles to PROGRESS on the service's next snapshot write. If the
            // engine cannot resume, no running row ever lands and the Core returns to PAUSED.
            core.setState(CoreStates.RESUMING);
        } catch (Throwable t) { log("CORE_RESUME_ERR " + t); }
    }

    /**
     * Tap on a COMPLETE Core: open the Vault at the file (the peek, sheet C5). The media
     * reference is parked for the web layer, which opens the Vault and the player on resume.
     * The activity launch is best-effort — a vendor block on background activity starts
     * demotes the peek to a notification instead of ever failing silently.
     */
    private void peekTracked(String url) {
        try {
            org.json.JSONObject row = JobSnapshot.newestRowFor(this, url, "done");
            long mediaId = row == null ? 0 : row.optLong("mediaId", 0);
            boolean isVideo = row == null || row.optBoolean("isVideo", true);
            getSharedPreferences("downi_settings", MODE_PRIVATE).edit()
                    .putString("openVaultPending", new org.json.JSONObject()
                            .put("mediaId", mediaId).put("isVideo", isVideo).toString()).apply();
            log("CORE_PEEK mediaId=" + mediaId + " video=" + isVideo);
            try {
                android.content.Intent open = new android.content.Intent(this, MainActivity.class);
                open.setFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                        | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(open);
            } catch (Throwable blocked) {
                log("CORE_PEEK_BLOCKED " + blocked);   // the web consumes the pending flag on next open
            }
        } catch (Throwable t) { log("CORE_PEEK_ERR " + t); }
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
        chainSheetWin = null;
        lastSurfaceWin = null;
        chainTriedOverflow = false;
        sheetSwipes = 0;
        sheetWaits = 0;
        sheetScans = 0;
        runGen++;
        runStartedAt = SystemClock.elapsedRealtime();
        runRoute = null;
        runEndPending = false;              // a newer run owns its own narration (D-i staleness)
        if (core != null) {
            core.setFocusable(false);          // clean slate; this run re-focuses at its own step 3
            // Wave 1 (owner-reported): the Core used to go NOT_TOUCHABLE for the whole run, so
            // the owner's second tap or a stray brush landed on the FEED, where a little vertical
            // movement is the next-reel drag ("the link copies and the reel scrolled"). The Core
            // now SHIELDS instead: touchable, absorbing, acting on nothing — the resolver drops
            // touchability only for the brief moment each injected gesture is in flight.
            core.setShielded(true);
        }
        // The arbiter now says RESOLVING (unless a real job outranks it) — apply it so the
        // rim-orbit light actually renders while the resolver works. Wave 0 audit: the run
        // never applied its own state, so the Core sat in its previous look for the whole run.
        applyCoreState();
        // Wave 2 (sheet C4): the Reach begins — the choreography layer anchors to the Core.
        if (reach != null && core != null && core.isShown()) {
            float[] c = core.windowCenterAndRadius();
            reach.begin(c[0], c[1], c[2]);
            if (core != null) core.setOrbitStep(0f);   // ENGAGE: the orbit light appears at the top
        }
        // Wave 1: the run narrates. routePlan is the profile's ordered route list.
        PlatformProfile p = profile();
        observer.onRunStarted(sessionShort(),
                p != null ? joinRoutes(p.routes) : "-", core != null);
    }

    private static String joinRoutes(String[] routes) {
        StringBuilder b = new StringBuilder();
        for (String r : routes) { if (b.length() > 0) b.append('>'); b.append(r); }
        return b.toString();
    }

    /**
     * Ends a resolver run; the Core is touchable again and shows the arbiter's truth — NOT a
     * hardcoded idle. Wave 0 audit: the old `setState(IDLE)` here clobbered a running job's
     * PROGRESS (until the next binding poll) and a resolver failure's FAILED hold (the 3 s
     * "something went wrong" moment never rendered on the paths that reset right after
     * {@link #resolverFailed}). Applying the arbiter keeps both honest.
     */
    private void chainReset() {
        persistBlackbox();                  // a run end is a milestone: the box keeps it for the UI
        boolean delivered = chainDelivered;
        String route = runRoute;
        long ms = SystemClock.elapsedRealtime() - runStartedAt;
        chainRunning = false;
        chainDelivered = false;             // each run gets its own one-delivery budget (D-a)
        // Wave 1 honesty fix (found on device, 17:53 run): the clipboard retry window OUTLIVES
        // the run (D-i's design), so ending the narration here said "delivered=false" while the
        // async attempts were still about to deliver — the log contradicted reality. When the
        // copy-link path is still pending, the run's TRUE end is the clipboard attempts'
        // terminal branch (delivered, rejected, or exhausted) — those fire the end instead.
        if (chainClickedCopy && !delivered) {
            runEndPending = true;
        } else {
            observer.onRunEnded(delivered, route, ms);
            if (reach != null) reach.end();     // the choreography retracts with the truth
        }
        if (core != null) {
            core.setShielded(false);           // the run's gestures are done or deferred
            core.setOrbitStep(null);           // the orbit returns to its continuous read
            core.setInteractive(true);
            applyCoreState();
        }
    }

    /** Fires the pending run end once — the async clipboard window's true outcome. */
    private void endPendingRun(boolean delivered) {
        if (!runEndPending) return;
        runEndPending = false;
        observer.onRunEnded(delivered, delivered ? runRoute : null,
                SystemClock.elapsedRealtime() - runStartedAt);
        if (reach != null) reach.end();         // the choreography retracts with the truth
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
        // Wave 1: normalize per-share tracking (and the carousel slide index) away first, so
        // the same post always carries one string through dedup and the engine.
        url = MediaUrl.canonicalize(url);
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
            runRoute = route;                    // the run's narrated outcome (observer)
            recordStrategy(route, true, url);
            log("CHAIN_DELIVER_OK route=" + route + " tap=1 url=" + clip(url, 200));
            // The capture beat, uniform for every route (sheet C4 CAPTURE): the node returns,
            // the rim flashes, the orbit steps to 270. The light tick says "delivered".
            if (reach != null) reach.capture();
            if (core != null) core.setOrbitStep(270f);
            if (screenOn && core != null && core.isShown()) CoreHaptics.capture(this);
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
        if (!BuildConfig.DEBUG) return;          // Phase G: the bench channel never ships
        File f = new File(FetcherBench.dir(this), "core.cmd");
        if (!f.exists()) return;
        String body = FetcherBench.readSmallFile(f);
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
            if (lower.equals("stage")) {
                // M4 bench: the stage's live animation (file, frame, running) on demand. The
                // CORE_STATE line only carries it at the instant of a state change, so the question
                // "did that composition actually play?" needs a probe of its own.
                log("CORE_STAGE " + core.stageNote());
                return;
            }
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
            if (parts.length >= 2 && parts[0].equals("grade")) {
                // Wave 2 bench: drive the wake grade for the C2 gate (ready|aware).
                core.setWakeGrade(!"aware".equals(parts[1]));
                log("CORE_GRADE " + parts[1]);
                return;
            }
            if (parts.length >= 2 && parts[0].equals("unsupported")) {
                // Wave 2 bench: drive FAILED's neutral mood for the C6 gate.
                core.setUnsupported("true".equals(parts[1]));
                log("CORE_UNSUPPORTED " + parts[1]);
                return;
            }
            if (parts.length >= 3 && parts[0].equals("reach")) {
                // Wave 2 bench: hold the Reach tether to a fixed screen point (C4 gate).
                try {
                    if (reach == null) reach = new ReachLayer(this, msg -> log(msg));
                    float[] rc = core.windowCenterAndRadius();
                    reach.begin(rc[0], rc[1], rc[2]);
                    int tx = Integer.parseInt(parts[1]), ty = Integer.parseInt(parts[2]);
                    reach.reachTo(new Rect(tx - 60, ty - 60, tx + 60, ty + 60));
                    core.setOrbitStep(180f);
                    log("CORE_CMD_REACH to=" + tx + "," + ty);
                } catch (Throwable t2) { log("CORE_CMD_REACH_ERR " + t2); }
                return;
            }
            if (lower.equals("reachend")) {
                if (reach != null) reach.end();
                core.setOrbitStep(null);
                log("CORE_CMD_REACH_END");
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
        if (!BuildConfig.DEBUG) return;          // Phase G: the bench channel never ships
        File f = new File(FetcherBench.dir(this), "chain.cmd");
        if (!f.exists()) return;
        String cmd = FetcherBench.readSmallFile(f);
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
            } else if (app == null && (pkg.equals(sessionPkg) || PlatformProfile.isTarget(pkg))) {
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
        ArrayList<AccessibilityNodeInfo> overflow = new ArrayList<>();
        collectCandidates(root, share, copy, downi, overflow, new Counter(), 0);
        log("CHAIN_SCAN pkg=" + sessionPkg + " share=" + share.size()
                + " copylink=" + copy.size() + " downi=" + downi.size()
                + " overflow=" + overflow.size());
        for (AccessibilityNodeInfo n : share) {
            log("CHAIN_SHARE_CANDIDATE id=" + clip(strOrEmpty(n.getViewIdResourceName()), 90)
                    + " text=" + clip(nodeText(n), 120) + " clickable=" + n.isClickable()
                    + " visible=" + n.isVisibleToUser());
        }
        if (!doClick) { log("CHAIN_DRY_DONE"); return; }
        AccessibilityNodeInfo t = firstClickable(share);
        String route = null;
        if (t == null) {
            // Wave 4 (owner report 2026-09-28: "the Fetcher is reliable on the Reels tab but
            // inconsistent on the Home feed"): the feed's row IS the row a finger hits, but nothing
            // in its chain is clickable in the tree. A gesture at the row's OWN bounds — never a
            // screen fraction — is the honest equivalent of the user's tap.
            t = firstTappable(share);
            if (t != null) route = "gesture_bounds";
        }
        if (t == null) {
            // The feed's second route: the post's own overflow, whose menu carries "Copy link".
            if (!chainTriedOverflow) { chainStepOverflow(); return; }
            log("CHAIN_NO_SHARE_CLICK overflow=tried");
            resolverFailed("no_share_row");
            chainReset();
            return;
        }
        observer.onStep("share_found", "");
        // Wave 2 (sheet C4 REACH): the tether lands on the REAL control the resolver is
        // about to touch — its screen bounds are the truth, no invented coordinates.
        try {
            Rect sb = new Rect();
            t.getBoundsInScreen(sb);
            if (reach != null) reach.reachTo(sb);
        } catch (Throwable ignored) {}
        String routeUsed;
        if (route != null) {
            routeUsed = route + "_ok=" + tapCenter(t);   // the tree offers nothing clickable
        } else {
            routeUsed = clickNode(t);
        }
        log("CHAIN_SHARE_CLICK text=" + clip(nodeText(t), 120) + " route=" + routeUsed);
        // Event-driven wait (§H2): poll for the share surface's own window instead of sleeping a
        // fixed 1300 ms — the sheet opens in ~700 ms on this ROM, so step 2 starts sooner; the
        // deadline fallback keeps OEM same-window sheets working.
        postStep(new Runnable() { @Override public void run() { waitShareSurface(0); } }, 300);
    }

    /**
     * The Home feed's second route (Wave 4, 2026-09-28): tap the post's own overflow —
     * *"More actions for this post"* — and let the normal step-2 scan find the platform's **Copy link**
     * inside its menu. Same transport the chain already trusts (the platform's own action, never a share
     * sheet pointed at DOWNI), and the same close gate afterwards, so a menu that dismisses itself can
     * never receive a stray BACK either.
     */
    private void chainStepOverflow() {
        chainTriedOverflow = true;
        AccessibilityNodeInfo root = pickShareRoot("CHAIN_OVERFLOW", false);
        if (root == null) { log("CHAIN_OVERFLOW_NO_ROOT"); resolverFailed("no_share_row"); chainReset(); return; }
        ArrayList<AccessibilityNodeInfo> share = new ArrayList<>();
        ArrayList<AccessibilityNodeInfo> copy = new ArrayList<>();
        ArrayList<AccessibilityNodeInfo> downi = new ArrayList<>();
        ArrayList<AccessibilityNodeInfo> overflow = new ArrayList<>();
        collectCandidates(root, share, copy, downi, overflow, new Counter(), 0);
        AccessibilityNodeInfo o = firstClickable(overflow);
        String route = o != null ? null : "gesture_bounds";
        if (o == null) o = firstTappable(overflow);
        if (o == null) {
            log("CHAIN_OVERFLOW_NONE candidates=0");
            resolverFailed("no_share_row");
            chainReset();
            return;
        }
        observer.onStep("overflow_found", "");
        try {
            Rect sb = new Rect();
            o.getBoundsInScreen(sb);
            if (reach != null) reach.reachTo(sb);
        } catch (Throwable ignored) {}
        String routeUsed = route == null ? clickNode(o) : (route + "_ok=" + tapCenter(o));
        log("CHAIN_OVERFLOW_CLICK text=" + clip(nodeText(o), 120) + " route=" + routeUsed);
        // Its menu is a share surface in every sense the chain cares about: wait for it the same way.
        postStep(new Runnable() { @Override public void run() { waitShareSurface(0); } }, 400);
    }

    private void waitShareSurface(final int attempt) {
        if (!chainRunning) return;               // the run ended under us
        if (sheetSurfaceWindow() != null) {      // picks per WINDOW and stashes lastSurfaceWin
            chainSheetNeedsClose = true;         // a sheet is up — somebody must close it
            chainSheetWin = lastSurfaceWin;      // remember WHICH window: the close gate needs it
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
                observer.onStep("sheet_open", "after_ms=" + (300 + attempt * 250));
                // Wave 2: the orbit light steps to 90 — the sheet is open (sheet C4 STEPS).
                if (core != null) core.setOrbitStep(90f);
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
            observer.onStep("sheet_tree_hit", "");
            log("CHAIN_SHEET_TREE_DELIVER url=" + clip(cand, 200));
            chainSheetNeedsClose = false;
            closeSheetIfOpen("sheet_tree_route");
            deliverByTap(cand, "sheet_tree");
            chainReset();
            return;
        }
        // Wave 1: the sheet-tree retry beat and the swipe budget are the profile's call —
        // Instagram's sheet web content needs one extra beat to expose its URL; TikTok's
        // sheet tree never holds a URL and skips this entirely (measured, see PlatformProfile).
        PlatformProfile p = profile();
        int sheetTreeRetries = p != null ? p.sheetTreeRetries : 0;
        int maxSwipes = p != null ? p.maxSheetSwipes : 2;
        if (sheetTreeRetries > 0 && shareSurfaceOpen() && sheetScans < sheetTreeRetries) {
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
            //
            // WAVE 1 (owner report 2026-09-26: "the reel scrolled"): the fixed screen-fraction
            // stroke itself became suspect. Scrolling now goes through the sheet's OWN
            // scrollable node (ACTION_SCROLL_FORWARD — coordinate-free, cannot touch the
            // feed); the gesture is only the fallback and is clamped INSIDE the sheet
            // window's own bounds by SheetSwipe.endpoints, never a screen fraction.
            boolean surface = shareSurfaceOpen();
            if (surface && sheetSwipes < maxSwipes) {
                sheetSwipes++;
                boolean scrolled = scrollSheet();
                log("CHAIN_SHEET_SCROLL n=" + sheetSwipes + "/" + maxSwipes + " scrolled=" + scrolled);
                if (scrolled) observer.onStep("sheet_scrolled", "n=" + sheetSwipes);
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
            if (!surface && !chainTriedOverflow) {
                log("CHAIN_SHEET_NEVER_OPENED fallback=overflow");
                chainStepOverflow();
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
        observer.onStep("copy_link_clicked", "");
        // Wave 2: the tether moves to the copy-link row (the orbit steps to 180 — sheet C4).
        try {
            Rect cb = new Rect();
            c.getBoundsInScreen(cb);
            if (reach != null) reach.reachTo(cb);
            if (core != null) core.setOrbitStep(180f);
        } catch (Throwable ignored) {}
        log("CHAIN_TARGET_CLICK which=copylink text=" + clip(nodeText(c), 120)
                + " route=" + clickNode(c));
        postStep(new Runnable() { @Override public void run() { chainStep3(); } }, 1200);
    }

    /**
     * Scroll the share sheet one page toward "Copy link" without ever touching the feed.
     * First choice: the sheet's own scrollable node via ACTION_SCROLL_FORWARD — pure node
     * action, no coordinates. Fallback: a gesture whose BOTH endpoints are clamped inside
     * the sheet window's own bounds (SheetSwipe), so even a fall-through can only land on
     * the sheet, never on the video behind it.
     */
    private boolean scrollSheet() {
        try {
            AccessibilityNodeInfo sheetRoot = shareSurfaceRoot();
            if (sheetRoot == null) return false;
            java.util.ArrayList<AccessibilityNodeInfo> scrollables = new java.util.ArrayList<>();
            collectScrollables(sheetRoot, scrollables, new Counter(), 0);
            for (AccessibilityNodeInfo n : scrollables) {
                try {
                    if (n.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true;
                } catch (Throwable ignored) {}
            }
            Rect b = new Rect();
            sheetRoot.getBoundsInScreen(b);
            float density = getResources().getDisplayMetrics().density;
            float[] e = SheetSwipe.endpoints(b.left, b.top, b.right, b.bottom, density);
            if (e == null) return false;
            Path p = new Path();
            p.moveTo(e[0], e[1]);
            p.lineTo(e[2], e[3]);
            return dispatchGestureGuarded(new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(p, 0, 280)).build());
        } catch (Throwable t) {
            log("CHAIN_SHEET_SCROLL_ERR " + t);
            return false;
        }
    }

    /** Depth-capped walk collecting scrollable nodes of the sheet's own window. */
    private void collectScrollables(AccessibilityNodeInfo node,
                                    java.util.ArrayList<AccessibilityNodeInfo> out,
                                    Counter c, int depth) {
        if (node == null || c.nodes >= MAX_NODES || depth > MAX_DEPTH || out.size() >= 4) return;
        if (isOurs(node)) return;
        c.nodes++;
        try { if (node.isScrollable()) out.add(node); } catch (Throwable ignored) {}
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = null;
            try { child = node.getChild(i); } catch (Throwable ignored) {}
            collectScrollables(child, out, c, depth + 1);
        }
    }

    /**
     * Close the platform sheet — but ONLY if the sheet we opened is still there.
     *
     * Wave 1 (owner report "the reel scrolled") guarded this with "is any window that is not ours
     * on screen?" and Wave 3 (owner report 2026-09-28, "the video jumped to the next one", TikTok,
     * 5/5) found why that guard never held: this ROM always has such a window (the
     * `com.vivo.upslide` gesture bar; the IME too, while typing), so a BACK press aimed at a sheet
     * that had ALREADY dismissed itself (TikTok dismisses on Copy link) landed on the FEED and
     * advanced the reel. The gate now asks the two questions that can only be true of a real sheet:
     *   - is the window WE saw open still present ({@link ShareSurface#stillOpen}, by identity), or
     *   - is the sheet's own "Copy link" row still visible (same-window sheets on other OEMs)?
     * If neither, nothing is pressed and the platform is left exactly as it was.
     */
    private void closeSheetIfOpen(String why) {
        boolean sheetHere = ShareSurface.stillOpen(chainSheetWin, currentSurfaceWins());
        boolean contentHere = !sheetHere && copyRowVisible("CHAIN_CLOSE_PROBE");
        boolean open = sheetHere || contentHere;
        String evidence = sheetHere ? "sheet_window_present"
                : (contentHere ? "copy_row_present" : "sheet_gone_no_content");
        if (open) {
            log("CHAIN_CLOSE_PANEL back=" + performGlobalAction(GLOBAL_ACTION_BACK)
                    + (why == null ? "" : " why=" + why) + " evidence=" + evidence);
        } else {
            log("CHAIN_CLOSE_PANEL skipped"
                    + (why == null ? "" : " why=" + why) + " already_closed=1 evidence=" + evidence);
        }
        observer.onStep("panel_closed", open ? "" : "already_closed");
        chainSheetNeedsClose = false;
        chainSheetWin = null;
    }

    // ---------- deleted 2026-09-25 ~18:20 by owner ruling ----------
    // `findSheetShareRow()`, `chainChooser()` and `swipeChooserList()` used to click DOWNI inside the
    // system share chooser (TikTok lists it directly; Instagram's sheet has a "share" row that opens
    // the chooser). That transport belongs to **Downi Drop**, not the Fetcher: a share sheet whose
    // target is DOWNI is exactly what the owner already has. The Fetcher's own transport is the
    // platform's "Copy link" -> clipboard -> `deliverByTap()`. The three methods and the
    // `chooserSwipes` counter are gone rather than kept dormant, so no future session can drift back
    // into calling them.


    // The fixed-fraction sheet swipe (`swipeWithinSheet(0.80f, 0.64f)`) is GONE (Wave 1,
    // owner report "the reel scrolled"): a stroke defined by SCREEN fractions could fall
    // through to the feed when the sheet's window didn't consume it. Scrolling now goes
    // through the sheet's own scrollable node first (`scrollSheet`), and the gesture
    // fallback is clamped inside the sheet window's own bounds by `SheetSwipe.endpoints`.

    /**
     * True when a share surface is verifiably on screen — the gate that keeps the sheet-scroll
     * swipe and the BACK press OFF the feed. Wave 3 (2026-09-28): the decision moved to
     * {@link ShareSurface}, which judges each WINDOW (type + title + geometry). The deleted rule
     * accepted any window that was "not ours, not the system UI, not the platform app", and this
     * ROM always has one — `com.vivo.upslide`'s always-present `SideSlideGestureBar-Bottom`, plus
     * the IME whenever a keyboard is up — which is how the feed's next-video gesture could fire
     * from a fetch.
     */
    private boolean shareSurfaceOpen() {
        return sheetSurfaceWindow() != null;
    }

    /**
     * The window that IS the share surface, or null when no window qualifies. Picks the topmost
     * acceptable window (the same preference the old loop had) and stashes it in
     * {@link #lastSurfaceWin} so the run can remember the sheet's identity for the close gate.
     * Logs its verdict whenever the picked window changes — a bench run then explains itself.
     */
    private AccessibilityWindowInfo sheetSurfaceWindow() {
        ArrayList<ShareSurface.Win> wins = new ArrayList<>();
        ArrayList<AccessibilityWindowInfo> refs = new ArrayList<>();
        mapWindows(wins, refs);
        int idx = ShareSurface.pickIndex(wins, surfaceExcluded());
        lastSurfaceWin = idx < 0 ? null : wins.get(idx);
        if (idx >= 0 && !ShareSurface.sameWindow(chainSheetWin, lastSurfaceWin)) {
            log("CHAIN_SURFACE_SEEN " + ShareSurface.describe(lastSurfaceWin, getPackageName()));
        }
        return idx < 0 ? null : refs.get(idx);
    }

    /** Packages that can never be the share surface: ours, the shade, and the platform apps. */
    private String[] surfaceExcluded() {
        return new String[]{
                getPackageName(),
                "com.android.systemui",
                sessionPkg,
                PlatformProfile.INSTAGRAM.packages[0],
                PlatformProfile.TIKTOK.packages[0],
                PlatformProfile.TIKTOK.packages[1],
        };
    }

    /**
     * Maps the current windows into policy descriptors. {@code refs} receives the live
     * AccessibilityWindowInfo objects in the same order, so an index into one is an index into
     * the other.
     */
    private void mapWindows(ArrayList<ShareSurface.Win> wins,
                            ArrayList<AccessibilityWindowInfo> refs) {
        try {
            int dw = getResources().getDisplayMetrics().widthPixels;
            int dh = getResources().getDisplayMetrics().heightPixels;
            for (AccessibilityWindowInfo w : getWindows()) {
                AccessibilityNodeInfo r;
                try { r = w.getRoot(); } catch (Throwable t) { continue; }
                String pkg = (r == null || r.getPackageName() == null)
                        ? "" : r.getPackageName().toString();
                Rect b = new Rect();
                try { w.getBoundsInScreen(b); } catch (Throwable ignored) {}
                CharSequence ti = null;
                int type = 0;
                try { ti = w.getTitle(); } catch (Throwable ignored) {}
                try { type = w.getType(); } catch (Throwable ignored) {}
                wins.add(ShareSurface.win(pkg, ti == null ? "" : ti.toString(), type,
                        b.left, b.top, b.right, b.bottom, dw, dh));
                refs.add(w);
            }
        } catch (Throwable ignored) {}
    }

    /** The share surface's own window root, or null when no share surface is on screen. */
    private AccessibilityNodeInfo shareSurfaceRoot() {
        AccessibilityWindowInfo w = sheetSurfaceWindow();
        if (w == null) return null;
        try { return w.getRoot(); } catch (Throwable t) { return null; }
    }

    /** Every window right now, as the policy sees them — the close gate's evidence list. */
    private ArrayList<ShareSurface.Win> currentSurfaceWins() {
        ArrayList<ShareSurface.Win> wins = new ArrayList<>();
        mapWindows(wins, new ArrayList<AccessibilityWindowInfo>());
        return wins;
    }

    /**
     * True when the platform tree currently shows the sheet's own "Copy link" row. This is the
     * fallback evidence for OEMs whose sheet lives INSIDE the platform app's window (there is no
     * separate window to identify): a live copy row means a live sheet, and its absence means no
     * sheet — which is what keeps the BACK press off the feed on those devices too.
     */
    private boolean copyRowVisible(String tag) {
        ArrayList<AccessibilityNodeInfo> copyProbe = new ArrayList<>();
        collectCandidates(pickShareRoot(tag, false), new ArrayList<AccessibilityNodeInfo>(),
                copyProbe, new ArrayList<AccessibilityNodeInfo>(), new Counter(), 0);
        return !copyProbe.isEmpty();
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

    // ---------- screenshots (diagnostic fallback) ----------
    // The screenshot plumbing moved to FetcherBench (Wave 0): debug builds only,
    // session-capped, saved under fetch-spike/shots/. The service only asks for one.

    private void chainStep3() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        String pkg = root != null && root.getPackageName() != null ? root.getPackageName().toString() : "?";
        if (chainSheetNeedsClose) {
            // Wave 1 guarded this ("a sheet that auto-dismissed after Copy link must NOT receive
            // a BACK press"); Wave 3 (2026-09-28) fixes it — the gate now asks about the sheet WE
            // saw, never about "any window that is not ours". See closeSheetIfOpen().
            closeSheetIfOpen(null);
            // Wave 1 focus-overlap experiment (D-e): ask for focus NOW, while the sheet's
            // close animation plays, instead of at the first clipboard attempt. Android
            // usually grants window focus within the next ~500 ms, so the first read can
            // succeed one full attempt earlier. Idempotent with the setFocusable below.
            if (chainClickedCopy && !chainDelivered && core != null) {
                core.setFocusable(true);
                core.requestFocus();
            }
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
            postStep(new Runnable() { @Override public void run() { clipAttempt(gen); } }, CLIP_READY_MS);
            // Hold focus for the whole retry budget, then always give it back — a permanently
            // focusable bubble would eat the platform's back key and its own touches. (M8: the hold
            // is derived from the window's own constants — see clipFocusHoldMs — so it can never
            // drift away from the steps it is covering.)
            postStep(new Runnable() {
                @Override public void run() {
                    if (gen != runGen) return;  // the run that asked for focus was superseded
                    if (core != null) core.setFocusable(false);
                }
            }, clipFocusHoldMs());
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
                postStep(new Runnable() { @Override public void run() { clipAttempt(gen); } }, CLIP_RETRY_MS);
                return;
            }
            log("CHAIN_CLIPBOARD got=null text= tries=" + clipTries);
            strategies.record(sessionShort(), "clipboard", false, SystemClock.elapsedRealtime());
            log("STRATEGY " + sessionShort() + " " + strategies.summary(sessionShort(), "clipboard"));
            resolverFailed("clipboard_empty");
            endPendingRun(false);           // the async window's true outcome (Wave 1 honesty)
            return;
        }
        log("CHAIN_CLIPBOARD got=yes text=" + clip(url, 200));
        String why = MediaUrl.reason(url);      // D-b: a media page, not a bio/redirect link
        if (why == null) {
            observer.onCaptured("clipboard", url);   // the capture beat (log narration)
            // The owner's tap is the trigger, so this delivers regardless of the bench gate
            // (ruling 2026-09-25: nothing auto-downloads, nothing a tap asks for is refused).
            deliverByTap(url, "clipboard");
            endPendingRun(chainDelivered);
        } else {
            log("CHAIN_URL_REJECTED reason=" + why);
            resolverFailed("rejected_" + why);
            endPendingRun(false);
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

    /** The four-list form the probes use; the overflow list is only needed by the share step. */
    private void collectCandidates(AccessibilityNodeInfo node, ArrayList<AccessibilityNodeInfo> share,
                                   ArrayList<AccessibilityNodeInfo> copy, ArrayList<AccessibilityNodeInfo> downi,
                                   Counter c, int depth) {
        collectCandidates(node, share, copy, downi, new ArrayList<AccessibilityNodeInfo>(), c, depth);
    }

    /**
     * Wave 4 (owner report 2026-09-28: "Inconsistency on Home Feed"): the classification moved to
     * {@link ShareRows}, because Instagram's feed does not call its share row a share row — its own
     * description is *"Send post. Button. Double tap to choose who to send this post to."* — so the old
     * `contains("share")` test never listed it at all. The same pass gives the post's **overflow**
     * (*"More actions for this post"*) a list of its own: its menu carries the platform's own
     * "Copy link", the transport this chain already trusts.
     */
    private void collectCandidates(AccessibilityNodeInfo node, ArrayList<AccessibilityNodeInfo> share,
                                   ArrayList<AccessibilityNodeInfo> copy, ArrayList<AccessibilityNodeInfo> downi,
                                   ArrayList<AccessibilityNodeInfo> overflow,
                                   Counter c, int depth) {
        if (node == null || c.nodes >= MAX_NODES || depth > MAX_DEPTH) return;
        if (isOurs(node)) return;             // never scan the Fetcher's own bubble overlay
        c.nodes++;
        String txt = nodeText(node);
        if (!txt.isEmpty()) {
            switch (ShareRows.classify(txt, strOrEmpty(node.getViewIdResourceName()))) {
                case ShareRows.SHARE:     share.add(node); break;
                case ShareRows.COPY_LINK: copy.add(node); break;
                case ShareRows.OVERFLOW:  overflow.add(node); break;
                case ShareRows.DOWNI:     downi.add(node); break;
                default: break;
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = null;
            try { child = node.getChild(i); } catch (Throwable ignored) {}
            collectCandidates(child, share, copy, downi, overflow, c, depth + 1);
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

    /**
     * Wave 1 (owner-reported): every injected gesture must reach the PLATFORM even though the
     * Core now stays touchable (shielding) during a run — so touchability is dropped for exactly
     * the flight of each gesture and restored from the gesture's OWN completion callback, never
     * on a timer. Between gestures the Core keeps shielding the user's touches away from the feed.
     */
    private boolean dispatchGestureGuarded(GestureDescription g) {
        try {
            if (core != null) core.setInteractive(false);
            boolean accepted = dispatchGesture(g, new AccessibilityService.GestureResultCallback() {
                @Override public void onCompleted(GestureDescription gestureDescription) {
                    if (core != null) core.setInteractive(true);   // the shield resumes
                }
                @Override public void onCancelled(GestureDescription gestureDescription) {
                    if (core != null) core.setInteractive(true);
                }
            }, null);
            if (!accepted && core != null) core.setInteractive(true);   // callback never fires
            return accepted;
        } catch (Throwable t) {
            if (core != null) core.setInteractive(true);
            return false;
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
            return dispatchGestureGuarded(new GestureDescription.Builder().addStroke(s).build());
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * The node whose own bounds we may tap when the tree offers nothing clickable. Instagram's Home
     * feed is the case this exists for: `row_feed_button_share` is `clickable=false`, yet its bounds
     * are exactly the row a finger hits. The box comes from the node itself — never a screen fraction
     * — so this cannot reach the feed's next-video gesture the way the old blind swipes could.
     */
    private AccessibilityNodeInfo firstTappable(ArrayList<AccessibilityNodeInfo> list) {
        for (AccessibilityNodeInfo n : list) {
            try {
                if (!n.isVisibleToUser()) continue;
                Rect r = new Rect();
                n.getBoundsInScreen(r);
                if (r.width() >= 8 && r.height() >= 8) return n;
            } catch (Throwable ignored) {}
        }
        return null;
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

    // ---------- log plumbing ----------
    // The bench log writer (queue, thread, file, cap) lives in FetcherBench (Wave 0).
    // The service only stamps lines; in a release build the file log does not exist at
    // all — accessibility data never gets written to disk in a release.

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
        FetcherBench.logRaw(line);                  // debug builds only: no release bench I/O
        // Wave 5: the release-safe black box. Measured 2026-09-28: on this ROM logcat returns zero
        // lines for a release build, so without this an install that "did nothing" leaves no trace.
        BLACKBOX.add(line);
        if (BLACKBOX.shouldFlush()) persistBlackbox();
    }

    // ---------- helpers ----------

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

    @Override
    public void onDestroy() {
        live = null;
        log("SERVICE_DESTROY");
        BLACKBOX.add(ts() + " SESSION_STOP clean=1");
        persistBlackbox();                            // a clean stop leaves the box readable
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
        if (reach != null) reach.destroy();
        FetcherBench.closeLog();            // flush, poison, join and close the bench log
        super.onDestroy();
    }
}



