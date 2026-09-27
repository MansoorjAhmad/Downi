package com.omnidownloader.app.downicore;

import android.accessibilityservice.AccessibilityService;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Build;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AnimationUtils;

/**
 * The Reach — the resolution choreography layer (Wave 2, sheet C4).
 *
 * A full-screen TYPE_ACCESSIBILITY_OVERLAY window (same window type as the Core: no new
 * permission, dies with the service) that is NOT_TOUCHABLE and NOT_FOCUSABLE — pure light,
 * it can never intercept a touch. It exists ONLY while a resolver run is alive, so idle
 * cost stays zero (K-A5).
 *
 * What it draws, in the sheet's language:
 *   - a thin translucent tether from the Core's rim toward the platform control the resolver
 *     is about to touch — base teal fading to nothing, with a bright node traveling along it;
 *   - a soft landing highlight where the tether meets the control;
 *   - on capture: the node returns, a brief flash at the rim, then the tether retracts and
 *     the layer fades away.
 *
 * The layer never draws across the user's content arbitrarily: the tether lands on the REAL
 * screen bounds of the control the resolver already decided to touch.
 */
public final class ReachLayer {

    public interface Listener { void onLog(String msg); }

    private static final int TEAL = 0xFF22D3EE;
    private static final int HOT = 0xFFBDFBFF;

    private final AccessibilityService svc;
    private final Listener listener;
    private final float dp;
    private WindowManager wm;
    private View view;
    private boolean attached;

    private float coreX, coreY, coreR;
    private final RectF target = new RectF();       // the control the tether lands on
    private boolean hasTarget;
    private float grow;                             // 0..1 tether length
    private float nodeT = -1f;                      // 0..1 along the path, <0 = no node
    private float captureT = -1f;                   // 0..1 capture flash envelope
    private float layerAlpha = 1f;
    private ValueAnimator anim;
    private ValueAnimator nodeAnim;                 // the traveling node's own animation (cancelled with the tether)
    private boolean drawFaultLogged;                // one honest line, never a log storm per frame

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path tether = new Path();
    private final PathMeasure measure = new PathMeasure(tether, false);
    private final float[] pos = new float[2];
    private final float[] tan = new float[2];

    public ReachLayer(AccessibilityService svc, Listener listener) {
        this.svc = svc;
        this.listener = listener;
        this.dp = svc.getResources().getDisplayMetrics().density;
    }

    /** The layer exists only while a run is alive — attach lazily on the first run. */
    public void begin(float coreX, float coreY, float coreR) {
        try {
            if (!attached) {
                if (wm == null) wm = (WindowManager) svc.getSystemService(Context.WINDOW_SERVICE);
                if (view == null) view = createView();
                int w = screenW(), h = screenH();
                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                        w, h,
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.TRANSLUCENT);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) lp.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                wm.addView(view, lp);
                attached = true;
            }
            this.coreX = coreX;
            this.coreY = coreY;
            this.coreR = coreR;
            hasTarget = false;
            grow = 0f;
            nodeT = -1f;
            captureT = -1f;
            layerAlpha = 1f;
            view.setAlpha(1f);
            invalidate();
            listener.onLog("REACH_BEGIN core=" + Math.round(coreX) + "," + Math.round(coreY));
        } catch (Throwable t) {
            listener.onLog("REACH_BEGIN_FAIL " + t);
        }
    }

    /** The resolver decided which control to touch — extend the tether to its real bounds. */
    public void reachTo(Rect targetBounds) {
        try {
            if (!attached || targetBounds == null) return;
            target.set(targetBounds);
            hasTarget = true;
            cancelAnim();
            tether.reset();
            tether.moveTo(coreX, coreY);
            // a slight curve toward the target keeps the filament organic (sheet C4)
            float mx = (coreX + target.centerX()) / 2f;
            float my = (coreY + target.centerY()) / 2f;
            float dx = target.centerX() - coreX, dy = target.centerY() - coreY;
            float len = (float) Math.max(1.0, Math.hypot(dx, dy));
            tether.quadTo(mx - dy / len * 40f * dp, my + dx / len * 40f * dp,
                    target.centerX(), target.centerY());
            measure.setPath(tether, false);
            anim = ValueAnimator.ofFloat(grow, 1f);
            anim.setDuration(CoreMotion.REACH_GROW_MS);
            anim.addUpdateListener(a -> {
                grow = (Float) a.getAnimatedValue();
                invalidate();
            });
            anim.start();
            sendNode();
            invalidate();
            listener.onLog("REACH_TO " + target.centerX() + "," + target.centerY());
        } catch (Throwable t) {
            listener.onLog("REACH_TO_FAIL " + t);
        }
    }

    /** One bright node travels Core -> target (the resolver "sending" a step). */
    private void sendNode() {
        if (nodeAnim != null) { nodeAnim.cancel(); nodeAnim = null; }
        nodeAnim = ValueAnimator.ofFloat(0f, 1f);
        nodeAnim.setDuration(CoreMotion.TETHER_SEND_MS);
        nodeAnim.addUpdateListener(a -> {
            nodeT = (Float) a.getAnimatedValue();
            invalidate();
        });
        nodeAnim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) {
                nodeAnim = null; nodeT = -1f; invalidate();
            }
        });
        nodeAnim.start();
    }

    /** One place for the window's repaint, and one guard: it may already be gone. */
    private void invalidate() {
        if (view != null) view.invalidate();
    }

    /** Capture: the node returns to the Core and the rim flashes once (sheet C4 CAPTURE). */
    public void capture() {
        try {
            if (!attached) return;
            cancelAnim();
            if (measure.getLength() <= 0) return;
            anim = ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(CoreMotion.TETHER_RETURN_MS + CoreMotion.CAPTURE_PULSE_MS);
            anim.addUpdateListener(a -> {
                float t = (Float) a.getAnimatedValue();
                if (t < 0.625f) {
                    nodeT = 1f - (t / 0.625f);        // the node returns (250 ms)
                } else {
                    nodeT = -1f;
                    captureT = (t - 0.625f) / 0.375f; // the rim flash (150 ms)
                }
                invalidate();
            });
            anim.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator a) {
                    nodeT = -1f; captureT = -1f; retract(); invalidate();
                }
            });
            anim.start();
        } catch (Throwable t) {
            listener.onLog("REACH_CAPTURE_FAIL " + t);
        }
    }

    /** The run is over — retract and fade, then let the window go. */
    public void end() {
        try {
            if (!attached) return;
            cancelAnim();
            anim = ValueAnimator.ofFloat(grow, 0f);
            anim.setDuration(CoreMotion.REACH_FADE_MS);
            anim.addUpdateListener(a -> {
                grow = (Float) a.getAnimatedValue();
                layerAlpha = grow;
                view.setAlpha(layerAlpha);
                invalidate();
            });
            anim.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator a) {
                    detach();
                }
            });
            anim.start();
        } catch (Throwable t) {
            listener.onLog("REACH_END_FAIL " + t);
        }
    }

    public void destroy() {
        cancelAnim();
        detach();
        view = null;
    }

    private void retract() { grow = 0f; }

    private void cancelAnim() {
        if (anim != null) { anim.cancel(); anim = null; }
        if (nodeAnim != null) { nodeAnim.cancel(); nodeAnim = null; }
    }

    private void detach() {
        if (attached && view != null) {
            try { wm.removeViewImmediate(view); } catch (Throwable ignored) {}
            attached = false;
        }
    }

    private View createView() {
        View v = new View(svc) {
            // DEFECT (device 2026-09-27, found in this session's crash log): a bare `draw(c)`
            // here resolved to the INHERITED `View.draw(Canvas)`, not this class's painter —
            // Java picks the innermost enclosing member, and the anonymous subclass inherits
            // one. `View.draw` calls `onDraw`, which called `View.draw`… an infinite recursion
            // on the main thread that ends in StackOverflowError. Every Core tap then killed
            // the whole app right after the share row was clicked (the share sheet opened, the
            // Reach attached, and the process died mid-frame — "nothing happens after that").
            // The qualified call is what makes this the layer's painter, and nothing else.
            // And a paint fault must never again kill the process mid-frame (this window is
            // drawn on the main thread, where no resolver try/catch can reach it): it is
            // reported once, then the layer goes quiet.
            @Override protected void onDraw(Canvas c) {
                try { ReachLayer.this.draw(c); }
                catch (Throwable t) {
                    if (!drawFaultLogged) {
                        drawFaultLogged = true;
                        listener.onLog("REACH_DRAW_FAULT " + t);
                    }
                }
            }
        };
        v.setWillNotDraw(false);
        return v;
    }

    private void draw(Canvas c) {
        if (!hasTarget || grow <= 0f) return;
        float targetX = target.centerX(), targetY = target.centerY();

        // the tether: base teal fading to nothing at the tip, subtle width (subordinate to the Core)
        p.setShader(new LinearGradient(coreX, coreY, targetX, targetY,
                0x5922D3EE, 0x0022D3EE, Shader.TileMode.CLAMP));
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.6f * dp);
        c.drawPath(tether, p);
        p.setShader(null);

        // the landing highlight where the resolver is about to touch
        if (captureT < 0f) {
            float r = 26f * dp;
            p.setColor(0x9922D3EE);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2f * dp);
            c.drawCircle(targetX, targetY, r, p);
            p.setColor(0x3322D3EE);
            c.drawCircle(targetX, targetY, r + 3f * dp, p);
        }

        // the traveling node
        if (nodeT >= 0f && measure.getLength() > 0f) {
            measure.getPosTan(measure.getLength() * nodeT * grow, pos, tan);
            p.setColor(HOT);
            p.setStyle(Paint.Style.FILL);
            c.drawCircle(pos[0], pos[1], 3f * dp, p);
        }

        // the capture flash at the Core's rim
        if (captureT >= 0f) {
            float env = (float) Math.sin(Math.PI * captureT);
            p.setColor(Color.argb(Math.round(200f * env), 0xBD, 0xFB, 0xFF));
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(3f * dp);
            c.drawCircle(coreX, coreY, coreR + 4f * dp + 10f * dp * captureT, p);
        }
        p.setStyle(Paint.Style.FILL);
    }

    private int screenW() {
        try {
            if (Build.VERSION.SDK_INT >= 30) return wm.getCurrentWindowMetrics().getBounds().width();
        } catch (Throwable ignored) {}
        return svc.getResources().getDisplayMetrics().widthPixels;
    }

    private int screenH() {
        try {
            if (Build.VERSION.SDK_INT >= 30) return wm.getCurrentWindowMetrics().getBounds().height();
        } catch (Throwable ignored) {}
        return svc.getResources().getDisplayMetrics().heightPixels;
    }
}
