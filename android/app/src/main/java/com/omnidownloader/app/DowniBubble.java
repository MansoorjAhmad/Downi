package com.omnidownloader.app;

import android.accessibilityservice.AccessibilityService;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;

import androidx.core.content.ContextCompat;

/**
 * V3.2 Phase 1 — the DOWNI Fetcher bubble: a floating, draggable DOWNI control that
 * sits on top of Instagram/TikTok and fires a grab when tapped.
 *
 * Owner ruling 2026-09-25: the bubble IS the feature — the Phase 0.5 chain (DOWNI
 * driving the platform's own link flow) is only what happens *behind* a tap. The user
 * never opens a share sheet, never copies a link and never leaves the platform app.
 *
 * Window: TYPE_ACCESSIBILITY_OVERLAY — an accessibility service may add overlay windows
 * without the "display over other apps" grant, and the window dies with the service (no
 * orphaned overlay when accessibility is switched off). While a run is in flight the
 * bubble turns non-touchable so DOWNI's own taps always land in the platform app below,
 * never on the bubble above it.
 *
 * Drawn in code, no layout XML: glass disc, cyan→blue rim, the DOWNI mark, and a sweeping
 * arc while busy. Position is remembered in "downi_fetcher" prefs and clamped on show.
 */
class DowniBubble {
    static final int STATE_IDLE = 0;
    static final int STATE_BUSY = 1;

    interface Listener {
        void onBubbleTap();
        void onBubbleMoved(int x, int y);
        void onBubbleLog(String msg);
    }

    private static final String PREFS = "downi_fetcher";
    private static final String KEY_X = "bubble_x";
    private static final String KEY_Y = "bubble_y";

    private final AccessibilityService svc;
    private final Listener listener;
    private BubbleView view;        // rebuilt when the OS drops our window (see show())
    private final float dp;
    private final int sizePx;

    private WindowManager wm;
    private WindowManager.LayoutParams lp;
    private boolean attached;      // window added once and kept — re-adding loses touch on this ROM
    private boolean visible;
    private int swpx, shpx;        // cached screen bounds, kept out of the drag hot path
    private boolean destroyed;
    private int state = STATE_IDLE;

    DowniBubble(AccessibilityService svc, Listener listener) {
        this.svc = svc;
        this.listener = listener;
        this.dp = svc.getResources().getDisplayMetrics().density;
        this.sizePx = Math.round(64 * dp);
        this.view = new BubbleView(svc);
    }

    /**
     * Truth, not memory. This used to return the `visible` flag alone — so when the OS dropped
     * our overlay window behind our back (vivo V2058, 2026-09-25) the flag kept reporting
     * "shown", show() was never called again, and every tap fell straight through to the app
     * below (zero BUBBLE_TOUCH lines on a fresh drag). A bubble is only "shown" now if the
     * window manager really holds the view.
     */
    boolean isShown() {
        if (!attached || !visible) return false;
        try { return view.isAttachedToWindow() && view.getVisibility() == View.VISIBLE; }
        catch (Throwable t) { return false; }
    }

    /** True only while the window manager really holds our view. */
    private boolean windowAlive() {
        try { return attached && view.isAttachedToWindow(); }
        catch (Throwable t) { return false; }
    }

    void setState(int s) {
        if (state == s) return;
        state = s;
        view.invalidate();
    }

    /** False while DOWNI is driving the platform UI: taps must pass through the bubble. */
    void setInteractive(boolean on) {
        if (lp == null || !attached || !visible) return;
        int f = lp.flags;
        int next = on ? (f & ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
                      : (f | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        if (next == f) return;
        lp.flags = next;
        try { wm.updateViewLayout(view, lp); } catch (Throwable ignored) {}
    }

    /**
     * Momentarily focusable = this app counts as "in focus" for the platform's clipboard
     * check (Android 10+ denies clipboard reads to apps that are not focused, which is
     * why the chain's "Copy link" produced got=null on device 2026-09-25). Always
     * restored: a permanently focusable bubble would steal the platform's back/keys.
     */
    void setFocusable(boolean on) {
        if (lp == null || !attached) return;
        int f = lp.flags;
        int next = on ? (f & ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
                      : (f | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        if (next == f) return;
        lp.flags = next;
        try { wm.updateViewLayout(view, lp); } catch (Throwable ignored) {}
    }

    /** Does our own window hold focus **right now**? (Focus, not focusability — see setFocusable.) */
    boolean hasWindowFocus() {
        try { return view != null && view.hasWindowFocus(); } catch (Throwable t) { return false; }
    }

    /**
     * Ask the window manager for focus. `setFocusable(true)` only clears FLAG_NOT_FOCUSABLE, which
     * does not by itself move focus to us — measured on the vivo V2058 (2026-09-25): the clipboard
     * read failed 3 of 5 times with the flag cleared, because focus stayed on TikTok.
     */
    void requestFocus() {
        try { if (view != null) view.requestFocus(); } catch (Throwable ignored) {}
    }

    /**
     * Attach once, then only toggle visibility/flags. Empirically on the vivo V2058
     * (Android 13) a window that is removeView'd and re-added never receives touch
     * again — the bubble appeared but taps fell through to the app below (2026-09-25,
     * BUBBLE_ATTACH then zero BUBBLE_TOUCH lines on a fresh drag).
     */
    void show() {
        if (destroyed) return;
        if (attached && !windowAlive()) {
            // The ROM dropped the window (overlay cleanup / lost window token) while our flag
            // still said "attached". Re-adding the SAME view never gets touch back on this ROM
            // (see the note below), so the view is rebuilt from scratch — it is drawn entirely
            // in code, which makes that cheap, and it gets a fresh input channel with it.
            listener.onBubbleLog("BUBBLE_WINDOW_LOST rebuild=1");
            detach();
            view = new BubbleView(svc);
            swpx = 0;
            shpx = 0;
        }
        if (!attached) {
            if (!buildLp()) return;
            try {
                wm.addView(view, lp);
                attached = true;
                listener.onBubbleLog("BUBBLE_ATTACH type=" + lp.type + " x=" + lp.x + " y=" + lp.y
                        + " size=" + sizePx);
            } catch (Throwable t) {
                listener.onBubbleLog("BUBBLE_ATTACH_FAIL err=" + t);
                return;
            }
        }
        if (visible || lp == null) return;
        swpx = screenW();
        shpx = screenH();                                 // re-clamp (rotation, size change)
        lp.x = clamp(lp.x, 0, Math.max(0, swpx - sizePx));
        lp.y = clamp(lp.y, 0, Math.max(0, shpx - sizePx));
        lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        view.setVisibility(View.VISIBLE);
        try { wm.updateViewLayout(view, lp); } catch (Throwable ignored) {}
        visible = true;
    }

    void hide() {
        if (!attached || !visible) return;
        visible = false;
        view.setVisibility(View.GONE);
        if (lp != null) {
            lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;   // never block taps while hidden
            try { wm.updateViewLayout(view, lp); } catch (Throwable ignored) {}
        }
    }

    /** Drops the window only — safe to call when nothing is attached. */
    private void detach() {
        visible = false;
        if (attached) {
            try { wm.removeViewImmediate(view); } catch (Throwable ignored) {}
            attached = false;
        }
    }

    void destroy() {
        destroyed = true;
        detach();
    }

    // ---------- window + geometry ----------

    private boolean buildLp() {
        try {
            if (wm == null) wm = (WindowManager) svc.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) { listener.onBubbleLog("BUBBLE_NO_WINDOW_SERVICE"); return false; }
            int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
            lp = new WindowManager.LayoutParams(sizePx, sizePx,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, flags,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            int sw = screenW(), sh = screenH();
            SharedPreferences prefs = svc.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            int x = prefs.getInt(KEY_X, sw - sizePx - Math.round(8 * dp));
            int y = prefs.getInt(KEY_Y, Math.round(sh * 0.62f));
            lp.x = clamp(x, 0, Math.max(0, sw - sizePx));
            lp.y = clamp(y, 0, Math.max(0, sh - sizePx));
            return true;
        } catch (Throwable t) {
            listener.onBubbleLog("BUBBLE_LP_FAIL err=" + t);
            return false;
        }
    }

    /** Remembered so returning to IG/TikTok puts the bubble back where the owner left it. */
    private void persist() {
        try {
            svc.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putInt(KEY_X, lp.x).putInt(KEY_Y, lp.y).apply();
        } catch (Throwable ignored) {}
    }

    private void dragTo(int x, int y) {
        if (lp == null) return;
        int maxX = Math.max(0, (swpx > 0 ? swpx : screenW()) - sizePx);
        int maxY = Math.max(0, (shpx > 0 ? shpx : screenH()) - sizePx);
        lp.x = clamp(x, 0, maxX);
        lp.y = clamp(y, 0, maxY);
        try { wm.updateViewLayout(view, lp); } catch (Throwable ignored) {}
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

    // ---------- the bubble itself ----------

    /** Press-drag slides it anywhere; a press that never moves is a tap (= fire a grab). */
    private final class BubbleView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Drawable glyph;
        private final RectF oval = new RectF();
        private final Matrix rot = new Matrix();
        private final int slop;
        private Shader haloIdle, haloBusy, disc, rim, sweep;
        private float cx, cy, r, spin, pressScale = 1f;
        private float downRawX, downRawY;
        private int startX, startY;
        private boolean dragging;
        private ValueAnimator pressAnim;

        BubbleView(Context c) {
            super(c);
            // The same identity mark the Core wears (design sheet 2) — never the app/launcher
            // icon, and never the old v2.5 mono arrow. Owner ruling 2026-09-25.
            glyph = ContextCompat.getDrawable(c, R.drawable.downi_core_mark);
            slop = ViewConfiguration.get(c).getScaledTouchSlop();
            setContentDescription(c.getString(R.string.downi_bubble_desc));
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        }

        @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            cx = w / 2f;
            cy = h / 2f;
            r = Math.min(w, h) / 2f - 6 * dp;
            haloIdle = new RadialGradient(cx, cy, r + 6 * dp,
                    new int[]{0x3322D3EE, 0x1222D3EE, 0x00000000}, new float[]{0f, 0.62f, 1f},
                    Shader.TileMode.CLAMP);
            haloBusy = new RadialGradient(cx, cy, r + 6 * dp,
                    new int[]{0x6622D3EE, 0x1F22D3EE, 0x00000000}, new float[]{0f, 0.62f, 1f},
                    Shader.TileMode.CLAMP);
            disc = new RadialGradient(cx, cy - r * 0.3f, r * 1.3f,
                    new int[]{0xF21B2436, 0xF20C1322, 0xFA060A12}, new float[]{0f, 0.55f, 1f},
                    Shader.TileMode.CLAMP);
            rim = new LinearGradient(cx - r, cy - r, cx + r, cy + r,
                    new int[]{0xFF9BE8FF, 0xFF22D3EE, 0xFF3B82F6, 0xFF9BE8FF}, null,
                    Shader.TileMode.CLAMP);
            sweep = new SweepGradient(cx, cy, new int[]{0x0022D3EE, 0xFF7DF9FF, 0x0022D3EE},
                    new float[]{0f, 0.5f, 1f});
            oval.set(cx - r, cy - r, cx + r, cy + r);
            if (glyph != null) {
                // The sheet's mark stands ~half the 52 dp disc across, and it fills 89% of its
                // own square tile — so the tile is 29 dp for a ~26 dp mark.
                int g = Math.round(29 * dp);
                glyph.setBounds(Math.round(cx - g / 2f), Math.round(cy - g / 2f),
                        Math.round(cx + g / 2f), Math.round(cy + g / 2f));
            }
        }

        @Override protected void onDraw(Canvas c) {
            boolean busy = state == STATE_BUSY;
            int save = c.save();
            c.scale(pressScale, pressScale, cx, cy);

            p.setStyle(Paint.Style.FILL);                        // glow
            p.setShader(busy ? haloBusy : haloIdle);
            c.drawCircle(cx, cy, r + 6 * dp, p);

            p.setShader(disc);                                   // glass
            c.drawCircle(cx, cy, r, p);

            p.setStyle(Paint.Style.STROKE);                      // rim
            p.setShader(rim);
            p.setStrokeWidth(1.6f * dp);
            c.drawCircle(cx, cy, r - 0.9f * dp, p);

            if (busy) {                                          // sweeping arc
                rot.setRotate(spin * 360f, cx, cy);
                sweep.setLocalMatrix(rot);
                p.setShader(sweep);
                p.setStrokeWidth(2.6f * dp);
                p.setStrokeCap(Paint.Cap.ROUND);
                c.drawArc(oval, 0f, 360f, false, p);
            }

            if (glyph != null) {                                 // DOWNI mark
                glyph.setAlpha(busy ? 150 : 255);
                glyph.draw(c);
            }
            c.restoreToCount(save);

            if (busy) {
                spin = (spin + 0.012f) % 1f;
                postInvalidateOnAnimation();
            }
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (lp == null) return false;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    listener.onBubbleLog("BUBBLE_TOUCH down");
                    downRawX = e.getRawX();
                    downRawY = e.getRawY();
                    startX = lp.x;
                    startY = lp.y;
                    dragging = false;
                    press(true);
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getRawX() - downRawX;
                    float dy = e.getRawY() - downRawY;
                    if (!dragging && Math.hypot(dx, dy) > slop) dragging = true;  // drag ≠ tap
                    if (dragging) dragTo(Math.round(startX + dx), Math.round(startY + dy));
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    listener.onBubbleLog("BUBBLE_TOUCH up dragging=" + dragging);
                    press(false);
                    if (dragging) {
                        persist();
                        listener.onBubbleMoved(lp.x, lp.y);
                    } else {
                        listener.onBubbleTap();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    listener.onBubbleLog("BUBBLE_TOUCH cancel");
                    press(false);
                    return true;
                default:
                    return super.onTouchEvent(e);
            }
        }

        private void press(boolean down) {
            if (pressAnim != null) pressAnim.cancel();
            pressAnim = ValueAnimator.ofFloat(pressScale, down ? 0.90f : 1f);
            pressAnim.setDuration(down ? 90 : 170);
            pressAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override public void onAnimationUpdate(ValueAnimator a) {
                    pressScale = (Float) a.getAnimatedValue();
                    invalidate();
                }
            });
            pressAnim.start();
        }
    }
}
