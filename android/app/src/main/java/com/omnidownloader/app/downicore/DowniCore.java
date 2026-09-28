package com.omnidownloader.app.downicore;

import android.accessibilityservice.AccessibilityService;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;

/**
 * V3.2 Downi Core — the window host (approved visual shell + Phase B interaction).
 *
 * Ruling D1: the window is `TYPE_ACCESSIBILITY_OVERLAY` — an accessibility service may add it
 * without the "display over other apps" grant, and it dies with the service, so there is never
 * an orphaned Core on screen. The attach-once / toggle-visibility discipline is the one proven
 * on the vivo V2058 by `DowniBubble` (a re-added window loses touch on that ROM).
 *
 * Phase B interaction rules, enforced here:
 *  - the Core owns a real touch target while visible, but is non-touchable while DOWNI drives the
 *    platform UI and while hidden;
 *  - size is 48/56/64 dp (sheet 5's touch-area range), remembered in `downi_fetcher` prefs;
 *  - press, drag, drag-vs-tap and position memory are real; a release only magnetises when the
 *    Core is already within 12 dp of an edge, so it remains draggable anywhere.
 */
public final class DowniCore {

    public interface Listener {
        void onCoreLog(String msg);
        void onCoreTap();
        void onCoreMoved(int x, int y);
    }

    private static final String PREFS = "downi_fetcher";
    private static final String KEY_X = "core_x";
    private static final String KEY_Y = "core_y";
    private static final String KEY_SIZE = "core_size_dp";

    /** Sheet 5, "touch area ~48-64dp". */
    public static final int[] SIZES_DP = {48, 56, 64};
    public static final int DEFAULT_SIZE_DP = 64;

    private final AccessibilityService svc;
    private final Listener listener;
    private final float dp;

    private WindowManager wm;
    private WindowManager.LayoutParams lp;
    private CoreHost view;
    private boolean attached;
    private boolean visible;
    private boolean destroyed;
    private int sizeDp;
    private int swpx, shpx;                 // cached screen bounds
    private boolean interactive = true;
    private boolean shielded;               // run mode: absorb touches, act on none (Wave 1 fix)
    private final int touchSlop;
    private float downRawX, downRawY;
    private int downX, downY;
    private boolean dragging;
    private ValueAnimator edgeAnim;
    private ValueAnimator settleAnim;       // C3 §2's release settle (ruling R5)
    private String baseState = CoreStates.IDLE;   // the arbiter's state; touch overrides it briefly

    public DowniCore(AccessibilityService svc, Listener listener) {
        this.svc = svc;
        this.listener = listener;
        this.dp = svc.getResources().getDisplayMetrics().density;
        this.sizeDp = prefs().getInt(KEY_SIZE, DEFAULT_SIZE_DP);
        this.touchSlop = ViewConfiguration.get(svc).getScaledTouchSlop();
        this.view = createView();
    }

    public int sizeDp() { return sizeDp; }
    public String state() { return view.state(); }
    public float progress() { return view.progress(); }
    public float markScale() { return view.markScale(); }

    /**
     * What the authored stage is drawing right now (M4 bench). {@link #setState} logs the note only
     * at the instant of a state change, which cannot answer the motion questions: a caller that
     * wants to know whether a composition is still animating, and on which frame, asks again later.
     */
    public String stageNote() { return view.stageNote(); }

    public boolean isShown() {
        if (!attached || !visible) return false;
        try { return view.isAttachedToWindow() && view.getVisibility() == View.VISIBLE; }
        catch (Throwable t) { return false; }
    }

    public void setState(String s) {
        view.setState(s);
        listener.onCoreLog("CORE_STATE " + view.state() + " " + view.stageNote());
    }

    /**
     * The arbiter's chosen state (job > detection — see DowniFetcherService). Touch interaction
     * (pressed/dragging/snapped) still overrides it physically, but when the finger leaves,
     * the Core returns HERE instead of a hardcoded idle — so a Core that is mid-download does
     * not forget its job just because the user dragged it.
     */
    public void setBaseState(String s) {
        if (s == null || !CoreStates.isKnown(s)) return;
        boolean interacting = dragging;
        baseState = s;
        if (!interacting && view.state() != null && !view.state().equals(s)
                && !CoreStates.isTransient(view.state())) {
            view.setState(s);
            listener.onCoreLog("CORE_STATE " + view.state() + " " + view.stageNote());
        }
    }

    /** The state the Core should fall back to when an interaction ends. */
    public String baseState() {
        return baseState;
    }

    public void setProgress(float v) {
        float clamped = v < 0f ? 0f : (v > 1f ? 1f : v);
        if (Math.abs(clamped - view.progress()) < 0.0005f) return;   // a poll tick is not a change
        view.setProgress(v);
        listener.onCoreLog("CORE_PROGRESS " + Math.round(view.progress() * 100f));
    }

    public void setMarkScale(float s) {
        view.setMarkScale(s);
        listener.onCoreLog("CORE_MARK_SCALE " + view.markScale());
    }

    /** The wake grade (sheet C2): READY = full wake, AWARE = the quieter honest rise. */
    public void setWakeGrade(boolean ready) {
        view.setWakeGrade(ready);
    }

    /** FAILED's neutral variant (sheet C6): "not this kind of thing", never an alarm. */
    public void setUnsupported(boolean u) {
        view.setUnsupported(u);
    }

    /** Step mode (sheet C4): hold the resolving orbit at a chain step, or null for continuous. */
    public void setOrbitStep(Float degrees) {
        view.setOrbitStep(degrees);
    }

    /** False only while DOWNI is driving platform UI; hidden windows are always non-touchable. */
    public void setInteractive(boolean on) {
        interactive = on;
        if (!attached || !visible || lp == null) return;
        applyInteractiveFlag();
        try { wm.updateViewLayout(view, lp); } catch (Throwable ignored) {}
        listener.onCoreLog("CORE_INTERACTIVE " + on);
    }

    /**
     * Run mode (Wave 1, owner-reported defect): while the resolver works the Core used to go
     * NOT_TOUCHABLE, so any touch the user aimed at it — a second tap, a stray brush — landed
     * on the FEED underneath, where a little vertical movement is the next-reel drag ("the
     * link copies and the reel scrolled"). Now the run puts the Core in SHIELDED mode: it
     * stays touchable and silently absorbs everything aimed at it, while the resolver's own
     * injected gestures still reach the platform because {@link #setInteractive} drops the
     * touchable flag only for the brief moment each injected gesture is in flight.
     */
    public void setShielded(boolean on) {
        shielded = on;
        listener.onCoreLog("CORE_SHIELDED " + on);
    }

    /** Momentarily focusable for Android 10+ clipboard access; always restored after the read. */
    public void setFocusable(boolean on) {
        if (!attached || lp == null) return;
        int next = on ? (lp.flags & ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
                : (lp.flags | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        if (next == lp.flags) return;
        lp.flags = next;
        try { wm.updateViewLayout(view, lp); } catch (Throwable ignored) {}
    }

    public void requestFocus() {
        try { if (view != null) view.requestFocus(); } catch (Throwable ignored) {}
    }

    public boolean hasWindowFocus() {
        try { return view != null && view.hasWindowFocus(); } catch (Throwable t) { return false; }
    }

    /**
     * True while this Core's window is deliberately focusable — the clipboard-read window of a
     * resolver run (Wave 1). coreTick consults it: during that window our OWN package becomes
     * the active window (the focus pre-warm), which must not be mistaken for "left the target
     * app" or the Core hides out from under the clipboard read (device log 18:42:48, D-q).
     */
    public boolean isFocusableNow() {
        try { return lp != null && (lp.flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) == 0; }
        catch (Throwable t) { return false; }
    }

    /** The Core's screen-space center and radius — the Reach layer's tether anchor (Wave 2). */
    public float[] windowCenterAndRadius() {
        int px = Math.round(sizeDp * dp);
        float cx = (lp != null ? lp.x : 0) + px / 2f;
        float cy = (lp != null ? lp.y : 0) + px / 2f;
        return new float[]{cx, cy, px / 2f};
    }

    /** 48 / 56 / 64 dp; the window is rebuilt because a window's size is fixed at creation. */
    public void setSizeDp(int want) {
        int next = DEFAULT_SIZE_DP;
        int best = Integer.MAX_VALUE;
        for (int v : SIZES_DP) {
            int d = Math.abs(v - want);
            if (d < best) { best = d; next = v; }
        }
        if (next == sizeDp) { listener.onCoreLog("CORE_SIZE " + sizeDp + "dp (unchanged)"); return; }
        String keepState = view.state();
        float keepProgress = view.progress();
        float keepScale = view.markScale();
        boolean wasVisible = visible;
        detach();
        sizeDp = next;
        prefs().edit().putInt(KEY_SIZE, sizeDp).apply();
        view = createView();
        view.setMarkScale(keepScale);
        view.setProgress(keepProgress);
        view.setState(keepState);
        listener.onCoreLog("CORE_SIZE " + sizeDp + "dp");
        if (wasVisible) show();
    }

    /** Debug/gate helper; touch uses the same clamped geometry, then persists on release. */
    public void move(int x, int y) {
        moveTo(x, y);
        persistPosition();
    }

    /** Settings-card hook: forget the remembered spot and return to the default position. */
    public void resetPosition() {
        prefs().edit().remove(KEY_X).remove(KEY_Y).apply();
        if (lp == null) return;
        int px = Math.round(sizeDp * dp);
        lp.x = clamp(Math.max(0, screenW() - px - Math.round(8 * dp)), 0, Math.max(0, screenW() - px));
        lp.y = clamp(Math.round(screenH() * 0.62f), 0, Math.max(0, screenH() - px));
        try { wm.updateViewLayout(view, lp); } catch (Throwable ignored) {}
        listener.onCoreLog("CORE_POSITION_RESET x=" + lp.x + " y=" + lp.y);
    }

    public void show() {
        if (destroyed) return;
        if (attached && !windowAlive()) {                 // the ROM dropped the window
            listener.onCoreLog("CORE_WINDOW_LOST rebuild=1");
            detach();
            view = createView();
        }
        if (!attached) {
            if (!buildLp()) return;
            try {
                wm.addView(view, lp);
                attached = true;
                listener.onCoreLog("CORE_ATTACH type=" + lp.type + " x=" + lp.x + " y=" + lp.y
                        + " size=" + Math.round(sizeDp * dp) + "px size_dp=" + sizeDp
                        + " touchable=" + (interactive ? 1 : 0));
            } catch (Throwable t) {
                listener.onCoreLog("CORE_ATTACH_FAIL err=" + t);
                return;
            }
        }
        if (visible || lp == null) return;
        swpx = screenW();
        shpx = screenH();
        int px = Math.round(sizeDp * dp);
        lp.x = clamp(lp.x, 0, Math.max(0, swpx - px));
        lp.y = clamp(lp.y, 0, Math.max(0, shpx - px));
        // Wave 2: the object ARRIVES — a 150 ms fade instead of a pop (spec §4.14).
        view.setAlpha(0f);
        view.setVisibility(View.VISIBLE);
        visible = true;
        applyInteractiveFlag();
        try { wm.updateViewLayout(view, lp); } catch (Throwable ignored) {}
        view.animate().alpha(1f).setDuration(150).start();
        listener.onCoreLog("CORE_SHOW state=" + view.state());
    }

    /**
     * Re-clamps a shown Core into the current screen bounds. Rotation (or a foldable unfold)
     * leaves the remembered coordinates beyond the new bounds — the window manager then draws the
     * Core half or wholly off-screen, where no touch can reach it (cell B5: "clamped, never half
     * off-screen"). The clamped position is deliberately NOT persisted, so rotating back restores
     * where the user actually left it. No-op while the Core already fits.
     */
    public void ensureOnScreen() {
        if (!attached || !visible || lp == null) return;
        int px = Math.round(sizeDp * dp);
        int maxX = Math.max(0, screenW() - px);
        int maxY = Math.max(0, screenH() - px);
        if (lp.x < 0 || lp.y < 0 || lp.x > maxX || lp.y > maxY) moveTo(lp.x, lp.y);
    }

    public void hide() {
        if (!attached || !visible) return;
        visible = false;
        // Wave 2: the object LEAVES — fade out 150 ms, then GONE (window stays attached;
        // this ROM loses touch on re-add, so the window itself is never removed).
        view.animate().alpha(0f).setDuration(150).withEndAction(new Runnable() {
            @Override public void run() {
                if (!visible) view.setVisibility(View.GONE);
            }
        }).start();
        if (lp != null) {
            lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            try { wm.updateViewLayout(view, lp); } catch (Throwable ignored) {}
        }
        listener.onCoreLog("CORE_HIDE");
    }

    private boolean windowAlive() {
        try { return attached && view.isAttachedToWindow(); }
        catch (Throwable t) { return false; }
    }

    private void detach() {
        visible = false;
        if (edgeAnim != null) { edgeAnim.cancel(); edgeAnim = null; }
        if (settleAnim != null) { settleAnim.cancel(); settleAnim = null; }
        if (attached) {
            try { wm.removeViewImmediate(view); } catch (Throwable ignored) {}
            attached = false;
        }
    }

    public void destroy() {
        destroyed = true;
        detach();
    }

    // ---------- Phase B interaction ----------

    private CoreHost createView() {
        final CoreHost next = new CoreHost(svc);
        next.setFocusableInTouchMode(true);
        next.setClickable(true);
        next.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (!visible || !interactive || lp == null) return false;
                if (shielded) return true;    // run mode: the Core absorbs the touch, acts on none
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        if (edgeAnim != null) { edgeAnim.cancel(); edgeAnim = null; }
                        if (settleAnim != null) { settleAnim.cancel(); settleAnim = null; }
                        downRawX = e.getRawX();
                        downRawY = e.getRawY();
                        downX = lp.x;
                        downY = lp.y;
                        dragging = false;
                        next.setState(CoreStates.PRESSED);
                        listener.onCoreLog("CORE_TOUCH down");
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        float dx = e.getRawX() - downRawX;
                        float dy = e.getRawY() - downRawY;
                        if (!dragging && Math.hypot(dx, dy) > touchSlop) {
                            dragging = true;
                            next.setState(CoreStates.DRAGGING);
                        }
                        if (dragging) {
                            moveTo(Math.round(downX + dx), Math.round(downY + dy));
                            // interior slosh (V-3): the mark trails the container, gel-style, by the
                            // sheet C3 fraction of the finger's travel (clamped in CoreHost)
                            next.setMarkLag(-dx * CoreMotion.MARK_LAG_FRACTION,
                                    -dy * CoreMotion.MARK_LAG_FRACTION);
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                        listener.onCoreLog("CORE_TOUCH up dragging=" + dragging);
                        if (dragging) {
                            next.setMarkLag(0f, 0f);            // the interior springs home
                            finishDrag();
                        } else {
                            next.setState(baseState);
                            listener.onCoreTap();
                        }
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        listener.onCoreLog("CORE_TOUCH cancel dragging=" + dragging);
                        if (dragging) persistPosition();
                        next.setMarkLag(0f, 0f);
                        next.setState(baseState);
                        return true;
                    default:
                        return false;
                }
            }
        });
        return next;
    }

    private int sqLog = 0;      // V3.3.1: how many snap frames the debug log has reported

    private void finishDrag() {
        if (edgeAnim != null) { edgeAnim.cancel(); edgeAnim = null; }
        swpx = screenW();
        shpx = screenH();
        int px = Math.round(sizeDp * dp);
        int maxX = Math.max(0, swpx - px);
        int maxY = Math.max(0, shpx - px);
        int near = Math.round(CoreMotion.EDGE_MAGNET_DP * dp);   // sheet C3: magnetise only from here
        int nearX = lp.x;
        int nearY = lp.y;
        boolean magnetic = false;
        // The contact axis is the axis whose EDGE the Core met — not whether the magnet had to move
        // it: `moveTo` clamps, so a finger that already dragged the Core flush to an edge leaves
        // `nearX == lp.x`, and the old `horizontalHit = nearX != lp.x` then called a left-edge hit a
        // VERTICAL one. Measured on the M5 pass's own frames (2026-09-28): a left-edge snap widened
        // the Core on the row by ~8 % at the envelope's peak — the bulge — where sheet C3 asks for
        // the flattening. A corner (both axes in range) keeps the horizontal contact; every gesture
        // in `tools\core_touch.ps1` is horizontal.
        boolean magnetX = false;
        if (lp.x <= near) { nearX = 0; magnetic = true; magnetX = true; }
        else if (lp.x >= maxX - near) { nearX = maxX; magnetic = true; magnetX = true; }
        if (lp.y <= near) { nearY = 0; magnetic = true; }
        else if (lp.y >= maxY - near) { nearY = maxY; magnetic = true; }

        if (!magnetic) {
            persistPosition();
            view.setState(baseState);        // the arbiter's truth, not a hardcoded idle (Wave 0)
            startReleaseSettle();            // C3 §2 (ruling R5): a restrained settle, never a lock
            listener.onCoreMoved(lp.x, lp.y);
            return;
        }

        final int targetX = nearX;
        final int targetY = nearY;
        final int fromX = lp.x;
        final int fromY = lp.y;
        // Gel physics (V-3): the body flattens against the edge it meets — squash on the contact
        // axis, slight bulge on the other — then settles back. No trace after the snap.
        final boolean horizontalHit = magnetX;
        view.setState(CoreStates.SNAPPED);
        edgeAnim = ValueAnimator.ofFloat(0f, 1f);
        edgeAnim.setDuration(CoreMotion.SNAP_MS);
        edgeAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) {
                float t = CoreMotion.easeInOut((Float) a.getAnimatedValue());
                moveTo(Math.round(fromX + (targetX - fromX) * t),
                        Math.round(fromY + (targetY - fromY) * t));
                float env = CoreMotion.snapSquash(t);     // C3 §3: pure envelope, pinned in CoreMotionTest
                if (horizontalHit) {
                    view.setGelSquash(1f - env, 1f + env * CoreMotion.SNAP_BULGE_FRACTION);
                } else {
                    view.setGelSquash(1f + env * CoreMotion.SNAP_BULGE_FRACTION, 1f - env);
                }
                // V3.3.1: the snap's own account - the envelope, what the view holds, and how many
                // draws have carried it. The pixels froze through a whole snap once; this settles
                // that with the device's own log instead of an argument. DEBUG only (bench channel).
                if (com.omnidownloader.app.BuildConfig.DEBUG && (sqLog++ % 4 == 0)) {
                    listener.onCoreLog("CORE_SQUASH t=" + Math.round(t * 100f) / 100f
                            + " env=" + env + " sx=" + view.gelSquashX()
                            + " draws=" + view.squashDraws);
                }
            }
        });
        edgeAnim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) {
                view.setGelSquash(1f, 1f);
                persistPosition();
                view.setState(baseState);
                listener.onCoreMoved(lp.x, lp.y);
                edgeAnim = null;
            }
        });
        edgeAnim.start();
    }

    /**
     * C3 §2's RELEASE SETTLE (ruling R5): when the finger lets go of a Core that did NOT snap to
     * an edge, the gel relaxes through a small restrained swell — CoreMotion.releaseSettle runs
     * 1.00 -> at most 1.05 -> 1.00 over RELEASE_SETTLE_MS. Physical, never a spring toy, and no
     * trace is left behind (the squash returns to exactly 1,1).
     */
    private void startReleaseSettle() {
        if (settleAnim != null) { settleAnim.cancel(); settleAnim = null; }
        settleAnim = ValueAnimator.ofFloat(0f, 1f);
        settleAnim.setDuration(CoreMotion.RELEASE_SETTLE_MS);
        settleAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) {
                float s = CoreMotion.releaseSettle((Float) a.getAnimatedValue());
                view.setGelSquash(s, s);
            }
        });
        settleAnim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) {
                view.setGelSquash(1f, 1f);
                settleAnim = null;
            }
        });
        settleAnim.start();
    }

    private void moveTo(int x, int y) {
        if (lp == null) return;
        if (swpx <= 0) swpx = screenW();
        if (shpx <= 0) shpx = screenH();
        int px = Math.round(sizeDp * dp);
        lp.x = clamp(x, 0, Math.max(0, swpx - px));
        lp.y = clamp(y, 0, Math.max(0, shpx - px));
        try { wm.updateViewLayout(view, lp); } catch (Throwable ignored) {}
    }

    private void persistPosition() {
        if (lp == null) return;
        prefs().edit().putInt(KEY_X, lp.x).putInt(KEY_Y, lp.y).apply();
    }

    private void applyInteractiveFlag() {
        if (lp == null) return;
        if (visible && interactive) lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        else lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
    }

    // ---------- window + geometry ----------

    private boolean buildLp() {
        try {
            if (wm == null) wm = (WindowManager) svc.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) { listener.onCoreLog("CORE_NO_WINDOW_SERVICE"); return false; }
            int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
            int px = Math.round(sizeDp * dp);
            lp = new WindowManager.LayoutParams(px, px,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, flags,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            int sw = screenW(), sh = screenH();
            SharedPreferences prefs = prefs();
            int x = prefs.getInt(KEY_X, sw - px - Math.round(8 * dp));
            int y = prefs.getInt(KEY_Y, Math.round(sh * 0.62f));
            lp.x = clamp(x, 0, Math.max(0, sw - px));
            lp.y = clamp(y, 0, Math.max(0, sh - px));
            return true;
        } catch (Throwable t) {
            listener.onCoreLog("CORE_LP_FAIL err=" + t);
            return false;
        }
    }

    private SharedPreferences prefs() {
        return svc.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @SuppressWarnings("deprecation")
    private int screenW() {
        try {
            if (Build.VERSION.SDK_INT >= 30) return wm.getCurrentWindowMetrics().getBounds().width();
        } catch (Throwable ignored) {}
        try {
            DisplayMetrics dm = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(dm);
            return dm.widthPixels;
        } catch (Throwable t) {
            return svc.getResources().getDisplayMetrics().widthPixels;
        }
    }

    @SuppressWarnings("deprecation")
    private int screenH() {
        try {
            if (Build.VERSION.SDK_INT >= 30) return wm.getCurrentWindowMetrics().getBounds().height();
        } catch (Throwable ignored) {}
        try {
            DisplayMetrics dm = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(dm);
            return dm.heightPixels;
        } catch (Throwable t) {
            return svc.getResources().getDisplayMetrics().heightPixels;
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
