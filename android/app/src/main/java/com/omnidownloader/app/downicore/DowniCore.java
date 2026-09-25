package com.omnidownloader.app.downicore;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

/**
 * V3.2 Downi Core — the window host (Phase A: shell + visual states only).
 *
 * Ruling D1: the window is `TYPE_ACCESSIBILITY_OVERLAY` — an accessibility service may add it
 * without the "display over other apps" grant, and it dies with the service, so there is never
 * an orphaned Core on screen. The attach-once / toggle-visibility discipline is the one proven
 * on the vivo V2058 by `DowniBubble` (a re-added window loses touch on that ROM).
 *
 * Phase A rules, enforced here:
 *  - the window is `FLAG_NOT_TOUCHABLE` at all times: this is a visual preview, it must never
 *    eat a tap meant for the app underneath (interaction is Phase B);
 *  - size is 48/56/64 dp (sheet 5's touch-area range), remembered in `downi_fetcher` prefs;
 *  - position is remembered in the same prefs (Phase B adds the drag that writes it by hand).
 */
public final class DowniCore {

    public interface Listener { void onCoreLog(String msg); }

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

    public DowniCore(AccessibilityService svc, Listener listener) {
        this.svc = svc;
        this.listener = listener;
        this.dp = svc.getResources().getDisplayMetrics().density;
        this.sizeDp = prefs().getInt(KEY_SIZE, DEFAULT_SIZE_DP);
        this.view = new CoreHost(svc);
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

    public void setProgress(float v) {
        view.setProgress(v);
        listener.onCoreLog("CORE_PROGRESS " + Math.round(view.progress() * 100f));
    }

    public void setMarkScale(float s) {
        view.setMarkScale(s);
        listener.onCoreLog("CORE_MARK_SCALE " + view.markScale());
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
        view = new CoreHost(svc);
        view.setMarkScale(keepScale);
        view.setProgress(keepProgress);
        view.setState(keepState);
        listener.onCoreLog("CORE_SIZE " + sizeDp + "dp");
        if (wasVisible) show();
    }

    /** Debug/gate helper: place the Core anywhere (Phase B turns this into a real drag). */
    public void move(int x, int y) {
        if (lp == null) return;
        swpx = screenW();
        shpx = screenH();
        int px = Math.round(sizeDp * dp);
        lp.x = clamp(x, 0, Math.max(0, swpx - px));
        lp.y = clamp(y, 0, Math.max(0, shpx - px));
        try { wm.updateViewLayout(view, lp); } catch (Throwable ignored) {}
        prefs().edit().putInt(KEY_X, lp.x).putInt(KEY_Y, lp.y).apply();
    }

    public void show() {
        if (destroyed) return;
        if (attached && !windowAlive()) {                 // the ROM dropped the window
            listener.onCoreLog("CORE_WINDOW_LOST rebuild=1");
            detach();
            view = new CoreHost(svc);
        }
        if (!attached) {
            if (!buildLp()) return;
            try {
                wm.addView(view, lp);
                attached = true;
                listener.onCoreLog("CORE_ATTACH type=" + lp.type + " x=" + lp.x + " y=" + lp.y
                        + " size=" + Math.round(sizeDp * dp) + "px size_dp=" + sizeDp
                        + " touchable=0");
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
        try { wm.updateViewLayout(view, lp); } catch (Throwable ignored) {}
        visible = true;
        listener.onCoreLog("CORE_SHOW state=" + view.state());
    }

    public void hide() {
        if (!attached || !visible) return;
        visible = false;
        view.setVisibility(View.GONE);                    // window stays; this ROM loses touch on re-add
        listener.onCoreLog("CORE_HIDE");
    }

    private boolean windowAlive() {
        try { return attached && view.isAttachedToWindow(); }
        catch (Throwable t) { return false; }
    }

    private void detach() {
        visible = false;
        if (attached) {
            try { wm.removeViewImmediate(view); } catch (Throwable ignored) {}
            attached = false;
        }
    }

    public void destroy() {
        destroyed = true;
        detach();
    }

    // ---------- window + geometry ----------

    private boolean buildLp() {
        try {
            if (wm == null) wm = (WindowManager) svc.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) { listener.onCoreLog("CORE_NO_WINDOW_SERVICE"); return false; }
            int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;   // Phase A: visual only
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
