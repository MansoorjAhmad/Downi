package com.omnidownloader.app.downicore;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.view.View;

import com.omnidownloader.app.R;

/**
 * V3.2 Downi Core — the face (master package §4/§5/§6: identity + ambient perimeter + material).
 *
 * Two layers, per the master package:
 *   LAYER A  the sheet-2 identity mark, permanent, never the app icon;
 *   LAYER B  an organic, slightly asymmetric energy perimeter over a glossy obsidian body —
 *            NOT a "mathematically perfect generic circle".
 *
 * Material (sheet 1/2/5): deep smoked-black body with real depth shading, a soft specular
 * highlight top-left, a teal gel rim, and restrained bloom. Premium comes from shape +
 * proportion + material + lighting + motion — never from gamer effects.
 *
 * Size (§6/§19): the visual pebble is drawn at {@link #VISUAL_IN_WINDOW} of its window, so the
 * visible Core stays small (~50 px class at the 64 dp window) while the touch target stays the
 * full window — small visual footprint, comfortable interaction area. The padding is part of the
 * near-Core touch zone; it does not cover any extra app surface beyond what the Core already did.
 *
 * Motion (§20/§25): a state draws itself once; only transitions animate (idle cost stays zero —
 * cell K-A5). The one continuous motion is the DOWNLOADING energy flow: a slow rotating sheen on
 * the perimeter, running only while a real job is in PROGRESS and never while idle.
 */
public final class CoreHost extends View {

    /**
     * Tile side as a fraction of the Core's inner disc diameter. Not hand-picked: it is the sheets'
     * own proportion, solved in {@link CoreLook#MARK_SCALE} from the measured 0.53 disc ratio (the
     * glyph fills 89% of its tile) and asserted by `CoreMarkSpecTest`. Still live-tunable by the
     * owner at the gate (`mark <scale>` on the debug channel) — nothing else depends on this number.
     */
    public static final float DEFAULT_MARK_SCALE = CoreLook.MARK_SCALE;

    /**
     * The visible Core as a fraction of its window. The rest of the window is transparent touch
     * margin: the pebble reads small on screen while the finger target stays the full 48/56/64 dp.
     */
    public static final float VISUAL_IN_WINDOW = 0.80f;

    /** How large the visual disc is relative to the window at draw time (for tests/tools). */
    public static final float visualFractionOfWindow() { return VISUAL_IN_WINDOW; }

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF inner = new RectF();         // perimeter track
    private final RectF glass = new RectF();         // glass highlight arc
    private final RectF markDst = new RectF();       // mark destination
    private final RectF barL = new RectF(), barR = new RectF();
    private final Path blob = new Path();            // the organic silhouette (LAYER B body)
    private final Path clip = new Path();            // circular clip for the mark
    private final Matrix flowMatrix = new Matrix();  // rotates the downloading sheen
    private final float dp;

    private final Bitmap mark;
    private float markScale = DEFAULT_MARK_SCALE;

    private String state = CoreStates.IDLE;
    private float progress = 0f;
    private float animT = 0f;                        // 0..1 transition progress
    private ValueAnimator anim;
    private ValueAnimator flow;                      // DOWNLOADING energy flow (§25)
    private ValueAnimator orbit;                     // RESOLVING rim orbit (§M-1)
    private float flowDeg;                           // the sheen's current rotation
    private float orbitDeg;                          // the resolving light's position
    private float markLagX, markLagY;                // interior slosh (gel physics, V-3)
    private float squashX = 1f, squashY = 1f;        // edge-snap gel deformation (V-3)
    private boolean animCancelled;                   // a cancelled transition must never settle

    private Shader haloIdle, haloHot, haloErr, body, gloss, bounce, rim, rimErr, sweep;
    private float cx, cy, r, blobMin, rIn;

    public CoreHost(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        setContentDescription(c.getString(R.string.downi_core_desc));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        mark = decodeMark(c);
    }

    /** The sheet-2 mark, 512 px master, undecimated — density scaling is ours, not the framework's. */
    private static Bitmap decodeMark(Context c) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inScaled = false;
            return BitmapFactory.decodeResource(c.getResources(), R.drawable.downi_core_mark, o);
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------- state API (what the debug channel drives in Phase A) ----------

    public String state() { return state; }

    public float progress() { return progress; }

    public float markScale() { return markScale; }

    public void setMarkScale(float s) {
        float next = s < 0.30f ? 0.30f : (s > 1.20f ? 1.20f : s);   // tile side vs disc diameter
        if (Math.abs(next - markScale) < 0.001f) return;
        markScale = next;
        invalidate();                                 // one frame; no loop
    }

    public void setState(String s) {
        if (s == null || !CoreStates.isKnown(s)) return;
        state = s;
        startTransition(durationOf(s));
        updateFlow();
        invalidate();
    }

    public void setProgress(float v) {
        float next = v < 0f ? 0f : (v > 1f ? 1f : v);
        if (Math.abs(next - progress) < 0.0005f) return;
        progress = next;
        if (CoreStates.PROGRESS.equals(state)) startTransition(CoreMotion.PROGRESS_MS);
        invalidate();
    }

    private static long durationOf(String s) {
        if (CoreStates.WAKE.equals(s)) return CoreMotion.WAKE_MS;
        if (CoreStates.PRESSED.equals(s)) return CoreMotion.PRESS_MS;
        if (CoreStates.SNAPPED.equals(s)) return CoreMotion.SNAP_MS;
        if (CoreStates.RESUMING.equals(s)) return CoreMotion.PAUSE_MS;
        if (CoreStates.COMPLETING.equals(s)) return CoreMotion.COMPLETE_MS;
        if (CoreStates.FAILED.equals(s)) return CoreMotion.ERROR_MS;
        return 0L;
    }

    private void startTransition(long ms) {
        if (anim != null) { anim.cancel(); anim = null; }
        animT = 0f;
        animCancelled = false;
        if (ms <= 0L) return;
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(ms);
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) {
                animT = (Float) a.getAnimatedValue();
                invalidate();
            }
        });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationCancel(Animator a) { animCancelled = true; }
            @Override public void onAnimationEnd(Animator a) { if (!animCancelled) settle(); }
        });
        anim.start();
    }

    /**
     * The DOWNLOADING energy flow (§25): the perimeter sheen slowly rotates and the halo breathes,
     * so the Core reads as an active process — "energy flows" — without a percent of distraction.
     * The RESOLVING rim orbit is its sibling: one light circles the rim at ~1.2 s per lap while
     * the resolver works. Both run ONLY in their own state; idle never animates (K-A5).
     */
    private void updateFlow() {
        boolean shouldFlow = CoreStates.PROGRESS.equals(state);
        boolean shouldOrbit = CoreStates.RESOLVING.equals(state);
        if (shouldFlow && flow == null) {
            flow = ValueAnimator.ofFloat(0f, 1f);
            flow.setDuration(8000L);
            flow.setRepeatCount(ValueAnimator.INFINITE);
            flow.setRepeatMode(ValueAnimator.RESTART);
            flow.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override public void onAnimationUpdate(ValueAnimator a) {
                    flowDeg = 360f * (Float) a.getAnimatedValue();
                    invalidate();
                }
            });
            flow.start();
        } else if (!shouldFlow && flow != null) {
            flow.cancel();
            flow = null;
            flowDeg = 0f;
        }
        if (shouldOrbit && orbit == null) {
            orbit = ValueAnimator.ofFloat(0f, 1f);
            orbit.setDuration(1200L);
            orbit.setRepeatCount(ValueAnimator.INFINITE);
            orbit.setRepeatMode(ValueAnimator.RESTART);
            orbit.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override public void onAnimationUpdate(ValueAnimator a) {
                    orbitDeg = 360f * (Float) a.getAnimatedValue();
                    invalidate();
                }
            });
            orbit.start();
        } else if (!shouldOrbit && orbit != null) {
            orbit.cancel();
            orbit = null;
            orbitDeg = 0f;
        }
    }

    /** What a transient state becomes when its short animation ends — then nothing animates. */
    private void settle() {
        animT = 0f;
        if (CoreStates.WAKE.equals(state)) state = CoreStates.DETECTED;
        else if (CoreStates.RESUMING.equals(state)) state = CoreStates.PROGRESS;
        else if (CoreStates.COMPLETING.equals(state)) state = CoreStates.COMPLETE;
        updateFlow();
        invalidate();                                  // the last frame of the transition
    }

    @Override protected void onDetachedFromWindow() {
        if (anim != null) { anim.cancel(); anim = null; }
        if (flow != null) { flow.cancel(); flow = null; }
        if (orbit != null) { orbit.cancel(); orbit = null; }
        super.onDetachedFromWindow();
    }

    /** Interior slosh (V-3): the mark trails the container during a drag, then springs home. */
    public void setMarkLag(float lx, float ly) {
        float max = 4f * dp;
        float nx = Math.max(-max, Math.min(max, lx));
        float ny = Math.max(-max, Math.min(max, ly));
        if (Math.abs(nx - markLagX) < 0.15f && Math.abs(ny - markLagY) < 0.15f) return;
        markLagX = nx;
        markLagY = ny;
        invalidate();
    }

    /** Gel squash (V-3): the body flattens against the edge it snaps to, then settles. */
    public void setGelSquash(float sx, float sy) {
        if (Math.abs(sx - squashX) < 0.003f && Math.abs(sy - squashY) < 0.003f) return;
        squashX = sx;
        squashY = sy;
        invalidate();
    }

    // ---------- geometry + shaders (built once per size) ----------

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        cx = w / 2f;
        cy = h / 2f;
        float inset = 6 * dp;                          // room for the glow
        r = (Math.min(w, h) / 2f - inset) * VISUAL_IN_WINDOW;   // the visible pebble (§6: small)
        rIn = r - 1.6f * dp;
        inner.set(cx - rIn, cy - rIn, cx + rIn, cy + rIn);
        float gi = 2.6f * dp;
        glass.set(cx - r + gi, cy - r + gi, cx + r - gi, cy + r - gi);

        // The organic silhouette: a softly lobed body — never a mathematically perfect circle
        // (§6). The lobes are subtle and FIXED (no idle morphing): shape is identity, not noise.
        float a4 = 0.032f, a2 = 0.018f, p4 = 0.55f, p2 = 1.9f;
        blob.reset();
        final int N = 64;
        float[] xs = new float[N];
        float[] ys = new float[N];
        for (int i = 0; i < N; i++) {
            float th = (float) (Math.PI * 2 * i / N);
            float rb = r * (1f + a4 * (float) Math.cos(4 * th + p4) + a2 * (float) Math.cos(2 * th + p2));
            xs[i] = cx + rb * (float) Math.cos(th);
            ys[i] = cy + rb * (float) Math.sin(th);
        }
        blob.moveTo((xs[N - 1] + xs[0]) / 2f, (ys[N - 1] + ys[0]) / 2f);
        for (int i = 0; i < N; i++) {
            float nx = xs[(i + 1) % N], ny = ys[(i + 1) % N];
            blob.quadTo(xs[i], ys[i], (xs[i] + nx) / 2f, (ys[i] + ny) / 2f);
        }
        blob.close();
        blobMin = r * (1f - a4 - a2);

        haloIdle = new RadialGradient(cx, cy, r + inset + 2 * dp,
                new int[]{0x2B22D3EE, 0x0F22D3EE, 0x00000000}, new float[]{0f, 0.62f, 1f}, Shader.TileMode.CLAMP);
        haloHot = new RadialGradient(cx, cy, r + inset + 2 * dp,
                new int[]{0x7322D3EE, 0x2422D3EE, 0x00000000}, new float[]{0f, 0.62f, 1f}, Shader.TileMode.CLAMP);
        haloErr = new RadialGradient(cx, cy, r + inset + 2 * dp,
                new int[]{0x55FB7185, 0x1AFB7185, 0x00000000}, new float[]{0f, 0.62f, 1f}, Shader.TileMode.CLAMP);

        // Deep obsidian body with real depth: darker toward the lower-right, a faint teal bounce
        // light from below (volumetric read), and one soft specular gloss top-left (sheet 1/5).
        body = new LinearGradient(cx - r, cy - r, cx + r, cy + r,
                new int[]{0xFF1B2C48, 0xFF0B1322, 0xF9060B14}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
        bounce = new RadialGradient(cx, cy + r * 0.62f, r * 1.05f,
                new int[]{0x1A22D3EE, 0x00000000}, new float[]{0f, 1f}, Shader.TileMode.CLAMP);
        gloss = new RadialGradient(cx - r * 0.34f, cy - r * 0.44f, r * 0.95f,
                new int[]{0x3DFFFFFF, 0x14000000, 0x00000000}, new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP);

        rim = new SweepGradient(cx, cy,
                new int[]{0xFF9BE8FF, 0xFF22D3EE, 0xFF3B82F6, 0xFF2DD4BF, 0xFF9BE8FF}, null);
        rimErr = new LinearGradient(cx - r, cy - r, cx + r, cy + r,
                new int[]{0xFFFFC4D0, 0xFFFB7185, 0xFFF43F5E, 0xFFFFC4D0}, null, Shader.TileMode.CLAMP);
        sweep = new SweepGradient(cx, cy,
                new int[]{0xFF7DF9FF, 0xFF22D3EE, 0xFF3B82F6, 0xFF7DF9FF}, null);

        clip.reset();
        clip.addCircle(cx, cy, rIn - 0.6f * dp, Path.Direction.CW);
    }

    private static float clamp01(float v) { return v < 0f ? 0f : (v > 1f ? 1f : v); }

    // ---------- drawing ----------

    @Override protected void onDraw(Canvas c) {
        if (rIn <= 0f) return;
        CoreLook.Look L = CoreLook.of(state, animT, progress);
        int mood = L.error > 0.25f ? 2 : (L.detected > 0.4f ? 1 : 0);
        int save = c.save();
        c.scale(L.scale * squashX, L.scale * squashY, cx, cy);   // gel squash rides on state scale

        // 1) ambient bloom (restrained; the halo breathes with the download flow)
        p.setStyle(Paint.Style.FILL);
        p.setShader(mood == 2 ? haloErr : (mood == 1 ? haloHot : haloIdle));
        float breath = 0.85f + 0.15f * (float) Math.sin(Math.toRadians(flowDeg));
        p.setAlpha(Math.round(255f * clamp01(L.halo / 0.55f) * (CoreStates.PROGRESS.equals(state) ? breath : 1f)));
        c.drawCircle(cx, cy, r + 8 * dp, p);

        // 2) the organic body — deep obsidian with depth, bounce light and gloss (sheet 1/5)
        p.setAlpha(255);
        p.setShader(body);
        c.drawPath(blob, p);
        p.setShader(bounce);
        c.drawPath(blob, p);
        p.setShader(gloss);
        c.drawPath(blob, p);
        p.setShader(null);

        // 3) glass accent arc (sheet 4's glass layer)
        p.setStyle(Paint.Style.STROKE);
        p.setColor(0x40EAF9FF);
        p.setStrokeWidth(1.1f * dp);
        c.drawArc(glass, 196f, 148f, false, p);

        // 4) the gel rim — the energy perimeter's home
        p.setShader(mood == 2 ? rimErr : rim);
        p.setStrokeWidth(2.2f * dp);
        p.setAlpha(Math.round(255f * clamp01(L.rim)));
        c.drawPath(blob, p);
        p.setShader(null);

        // 5) the energy perimeter IS the progress (sheet 4) — no percent text anywhere
        if (L.track > 0.001f || L.perimeter > 0.001f) {
            p.setColor(0xFF22D3EE);
            p.setAlpha(Math.round(255f * L.track));
            p.setStrokeWidth(2.2f * dp);
            c.drawCircle(cx, cy, rIn, p);
            if (L.perimeter > 0.001f) {
                if (flowDeg != 0f) {                     // the sheen flows while downloading
                    flowMatrix.reset();
                    flowMatrix.postRotate(flowDeg, cx, cy);
                    sweep.setLocalMatrix(flowMatrix);
                } else {
                    sweep.setLocalMatrix(null);
                }
                p.setShader(sweep);
                p.setAlpha(Math.round(255f * clamp01(0.65f + 0.35f * L.perimeter)));
                p.setStrokeWidth(2.6f * dp);
                p.setStrokeCap(Paint.Cap.ROUND);
                c.drawArc(inner, -90f, 360f * L.perimeter, false, p);
                if (L.perimeter > 0.02f && L.perimeter < 0.999f) {
                    // the comet head: the arc's leading edge is brighter than its tail —
                    // direction and motion read from the same ring (§M-1).
                    float head = -90f + 360f * L.perimeter;
                    p.setShader(null);
                    p.setColor(0xFFBDFBFF);
                    p.setAlpha(255);
                    p.setStrokeWidth(3.2f * dp);
                    c.drawArc(inner, head - 6f, 12f, false, p);
                }
                p.setStrokeCap(Paint.Cap.BUTT);
                p.setShader(null);
            }
        }

        // 5b) the RESOLVING orbit (§M-1): one light circling the rim while the resolver works
        if (CoreStates.RESOLVING.equals(state)) {
            p.setShader(null);
            p.setColor(0xFFBDFBFF);
            p.setAlpha(235);
            p.setStrokeWidth(2.8f * dp);
            p.setStrokeCap(Paint.Cap.ROUND);
            c.drawArc(inner, orbitDeg - 5f, 10f, false, p);
            p.setStrokeCap(Paint.Cap.BUTT);
        }

        p.setAlpha(255);
        if (mark != null && L.mark > 0.01f) {            // LAYER A: the sheet-2 mark, permanent
            int s2 = c.save();
            c.clipPath(clip);
            float side = 2f * rIn * markScale;
            float sink = 0.8f * dp * L.markSink;         // pressed into the gel (§M-1)
            markDst.set(cx - side / 2f + markLagX,
                    cy - side / 2f + markLagY + sink,
                    cx + side / 2f + markLagX,
                    cy + side / 2f + markLagY + sink);
            p.setAlpha(Math.round(255f * clamp01(L.mark)));
            c.drawBitmap(mark, null, markDst, p);
            p.setAlpha(255);
            c.restoreToCount(s2);
        }

        if (L.bars && L.barAlpha > 0.01f) {              // PAUSED: bars, never text
            p.setColor(0xFFEAF9FF);
            p.setAlpha(Math.round(255f * clamp01(L.barAlpha)));
            float bw = 2.0f * dp, bh = 9.5f * dp, gap = 3.4f * dp, top = cy - bh / 2f;
            barL.set(cx - gap / 2f - bw, top, cx - gap / 2f, top + bh);
            barR.set(cx + gap / 2f, top, cx + gap / 2f + bw, top + bh);
            c.drawRoundRect(barL, 1f * dp, 1f * dp, p);
            c.drawRoundRect(barR, 1f * dp, 1f * dp, p);
            p.setAlpha(255);
        }
        c.restoreToCount(save);
    }
}
