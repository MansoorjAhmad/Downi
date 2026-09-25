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
    private final int touchSlop;
    private float downRawX, downRawY;
    private int downX, downY;
    private boolean dragging;
    private ValueAnimator edgeAnim;
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

    public boolean isShown() {
        if (!attached || !visible) return false;
        try { return view.isAttachedToWindow() && view.getVisibility() == View.VISIBLE; }
        catch (Throwable t) { return false; }
    }

    public void setState(String s) {
        view.setState(s);
        listener.onCoreLog("CORE_STATE " + view.state());
    }

    /**
     * The arbiter's chosen state (job > detection — see FetchSpikeService). Touch interaction
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
            listener.onCoreLog("CORE_STATE " + view.state());
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

    /** False only while DOWNI is driving platform UI; hidden windows are always non-touchable. */
    public void setInteractive(boolean on) {
        interactive = on;
        if (!attached || !visible || lp == null) return;
        applyInteractiveFlag();
        try { wm.updateViewLayout(view, lp); } catch (Throwable ignored) {}
        listener.onCoreLog("CORE_INTERACTIVE " + on);
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
        view.setVisibility(View.VISIBLE);
        visible = true;
        applyInteractiveFlag();
        try { wm.updateViewLayout(view, lp); } catch (Throwable ignored) {}
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
        view.setVisibility(View.GONE);                    // window stays; this ROM loses touch on re-add
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
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        if (edgeAnim != null) { edgeAnim.cancel(); edgeAnim = null; }
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
                        if (dragging) moveTo(Math.round(downX + dx), Math.round(downY + dy));
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                        listener.onCoreLog("CORE_TOUCH up dragging=" + dragging);
                        if (dragging) {
                            finishDrag();
                        } else {
                            next.setState(baseState);
                            listener.onCoreTap();
                        }
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        listener.onCoreLog("CORE_TOUCH cancel dragging=" + dragging);
                        if (dragging) persistPosition();
                        next.setState(baseState);
                        return true;
                    default:
                        return false;
                }
            }
        });
        return next;
    }

    private void finishDrag() {
        if (edgeAnim != null) { edgeAnim.cancel(); edgeAnim = null; }
        swpx = screenW();
        shpx = screenH();
        int px = Math.round(sizeDp * dp);
        int maxX = Math.max(0, swpx - px);
        int maxY = Math.max(0, shpx - px);
        int near = Math.round(12 * dp);
        int nearX = lp.x;
        int nearY = lp.y;
        boolean magnetic = false;
        if (lp.x <= near) { nearX = 0; magnetic = true; }
        else if (lp.x >= maxX - near) { nearX = maxX; magnetic = true; }
        if (lp.y <= near) { nearY = 0; magnetic = true; }
        else if (lp.y >= maxY - near) { nearY = maxY; magnetic = true; }

        if (!magnetic) {
            persistPosition();
            view.setState(CoreStates.IDLE);
            listener.onCoreMoved(lp.x, lp.y);
            return;
        }

        final int targetX = nearX;
        final int targetY = nearY;
        final int fromX = lp.x;
        final int fromY = lp.y;
        view.setState(CoreStates.SNAPPED);
        edgeAnim = ValueAnimator.ofFloat(0f, 1f);
        edgeAnim.setDuration(CoreMotion.SNAP_MS);
        edgeAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) {
                float t = CoreMotion.easeInOut((Float) a.getAnimatedValue());
                moveTo(Math.round(fromX + (targetX - fromX) * t),
                        Math.round(fromY + (targetY - fromY) * t));
            }
        });
        edgeAnim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) {
                persistPosition();
                view.setState(baseState);
                listener.onCoreMoved(lp.x, lp.y);
                edgeAnim = null;
            }
        });
        edgeAnim.start();
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
