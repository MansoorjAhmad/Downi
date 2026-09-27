package com.omnidownloader.app.downicore;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.view.View;

import com.omnidownloader.app.R;

/**
 * Downi Fetcher 2.0 — the Core. This view draws ONE thing: the owner's Core art from sheet C1.
 *
 * The v3.3 plan is explicit about why (plan §2): the obsidian gel, the cyan energy membrane, the
 * gloss and the folded-ribbon chevron are authored material, not arithmetic. Fetcher 1.0's
 * procedural body + rim + gloss + mark could not reach it, and every "make it glossier" tweak in
 * code drifted the look. So the material now ships as art
 * (`res/drawable-nodpi/core_orb.png`, lifted from the sheet by `tools/core_sheet_extract.py`)
 * and this view only *composes* it:
 *
 *   · the art, scaled so its disc is exactly the Core's disc (measured object/tile ratio);
 *   · the state's ambient bloom behind it (sheet C6's rose / muted blue-grey live here);
 *   · the energy ring — the membrane also carries real download progress (sheet C5);
 *   · the resolver's orbit light on the rim (sheet C4).
 *
 * Motion (§20/§25) is unchanged: a state draws itself once, only transitions animate, and the one
 * continuous motion is the DOWNLOADING sheen — never idle (cell K-A5).
 *
 * Size (§6/§19): the visual disc is {@link #VISUAL_IN_WINDOW} of its window, so the visible Core
 * stays small while the touch target stays the full 48/56/64 dp window. The padding is part of the
 * near-Core touch zone; it does not cover any extra app surface beyond what the Core already did.
 *
 * Since the chevron is baked into the art, the interior slosh ({@link #setMarkLag}) now reads on
 * the Core as a whole — the chevron becomes its own layer again when the Lottie stage splits it
 * (plan §3, milestone "Lottie plumbing").
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
    /** The art's own paint: bilinear filtering (the tile is 512 px, the disc is ~40 dp) + its tint. */
    private final Paint artPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final RectF inner = new RectF();         // perimeter track
    private final RectF artDst = new RectF();        // where the art lands on the disc
    private final Matrix flowMatrix = new Matrix();  // rotates the downloading sheen
    private final float dp;

    /** Fetcher 2.0: the Core IS its art (sheet C1) — no procedural body, rim, gloss or mark. */
    private final Bitmap orb;                        // READY / IDLE look
    private final Bitmap orbPaused;                  // PAUSED look: dimmed energy + bars
    private int tintedAs = -1;                       // which tint artPaint currently carries

    private float markScale = DEFAULT_MARK_SCALE;    // kept for the debug gate until the art splits

    private String state = CoreStates.IDLE;
    private boolean readyGrade = true;      // the wake grade (sheet C2): READY vs AWARE
    private boolean unsupported;            // FAILED's neutral variant (sheet C6)
    private float progress = 0f;
    private float animT = 0f;                        // 0..1 transition progress
    private ValueAnimator anim;
    private ValueAnimator flow;                      // DOWNLOADING energy flow (§25)
    private ValueAnimator orbit;                     // RESOLVING rim orbit (§M-1)
    private float flowDeg;                           // the sheen's current rotation
    private float orbitDeg;                          // the resolving light's position
    private Float orbitFixed;                        // step mode: the orbit holds this angle (C4)
    private float markLagX, markLagY;                // interior slosh (gel physics, V-3)
    private float squashX = 1f, squashY = 1f;        // edge-snap gel deformation (V-3)
    private boolean animCancelled;                   // a cancelled transition must never settle

    private Shader haloIdle, haloHot, haloErr, haloNeutral, sweep;
    private float cx, cy, r, rIn;

    public CoreHost(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        setContentDescription(c.getString(R.string.downi_core_desc));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        orb = decodeArt(c, R.drawable.core_orb);
        orbPaused = decodeArt(c, R.drawable.core_orb_paused);
        artPaint.setFilterBitmap(true);
    }

    /**
     * The Core's art, 512 px master, undecimated — density scaling is ours, not the framework's
     * (the same rule the mark asset followed), and null on any failure so the Core still draws
     * its ring + bloom instead of vanishing.
     */
    private static Bitmap decodeArt(Context c, int res) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inScaled = false;
            return BitmapFactory.decodeResource(c.getResources(), res, o);
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
        // R2/C6: the unsupported (neutral) tint belongs to FAILED alone — leaving FAILED clears
        // it. The rule itself is pure and tested (CoreLook.unsupportedCarriesOver).
        unsupported = CoreLook.unsupportedCarriesOver(s, unsupported);
        // R6 (sheet C5 RETURN): entering IDLE from a look that was holding energy is a subtle
        // fade, not a cut. The 20 s COMPLETE hold is untouched — it lives in the TTL, not here.
        if (CoreStates.IDLE.equals(s) && state != null && !CoreStates.IDLE.equals(state)) {
            startReturnFade(CoreLook.of(state, 0f, progress, readyGrade));
        } else {
            cancelReturnFade();
        }
        state = s;
        startTransition(durationOf(s));
        updateFlow();
        invalidate();
    }

    /**
     * The wake grade (sheet C2): READY is the approved full wake; AWARE rises to ~55%
     * energy and settles quieter. The service computes it from measured route health.
     */
    public void setWakeGrade(boolean ready) {
        if (readyGrade == ready) return;
        readyGrade = ready;
        // a wake already in flight re-times itself for its grade (600 vs 400 ms)
        if (CoreStates.WAKE.equals(state)) startTransition(durationOf(state));
        invalidate();
    }

    /** The restrained neutral response for "not this kind of thing" (sheet C6). */
    public void setUnsupported(boolean u) {
        if (unsupported == u) return;
        unsupported = u;
        invalidate();
    }

    /**
     * Step mode (sheet C4 STEPS): hold the orbit light at a named chain step (0/90/180/270),
     * or pass null to resume the continuous rotation. Ignored outside RESOLVING.
     */
    public void setOrbitStep(Float degrees) {
        orbitFixed = degrees;
        invalidate();
    }

    public void setProgress(float v) {
        float next = v < 0f ? 0f : (v > 1f ? 1f : v);
        if (Math.abs(next - progress) < 0.0005f) return;
        progress = next;
        if (CoreStates.PROGRESS.equals(state)) startTransition(CoreMotion.PROGRESS_MS);
        invalidate();
    }

    private long durationOf(String s) {
        if (CoreStates.WAKE.equals(s)) return readyGrade ? CoreMotion.WAKE_MS : CoreMotion.WAKE_AWARE_MS;
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
        if (fadeAnim != null) { fadeAnim.cancel(); fadeAnim = null; }
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

    // ---------- R6: the RETURN fade (sheet C5) ----------

    private CoreLook.Look fadeFrom;        // the settled look the fade starts from
    private float fadeT;                   // 0..1 through CoreMotion.RETURN_MS
    private ValueAnimator fadeAnim;

    /**
     * Sheet C5 RETURN (ruling R6): the held COMPLETE/FAILED look fades into rest instead of
     * cutting. The 20 s COMPLETE hold happens upstream (the snapshot TTL) — this is only the
     * leaving. One-shot; idle still never animates (cell K-A5).
     */
    private void startReturnFade(CoreLook.Look from) {
        cancelReturnFade();
        fadeFrom = from;
        fadeT = 0f;
        fadeAnim = ValueAnimator.ofFloat(0f, 1f);
        fadeAnim.setDuration(CoreMotion.RETURN_MS);
        fadeAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) {
                fadeT = (Float) a.getAnimatedValue();
                invalidate();
            }
        });
        fadeAnim.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator a) {
                fadeFrom = null;               // the state's own look takes over
                fadeAnim = null;
                invalidate();
            }
        });
        fadeAnim.start();
    }

    private void cancelReturnFade() {
        if (fadeAnim != null) { fadeAnim.cancel(); fadeAnim = null; }
        fadeFrom = null;
        fadeT = 0f;
    }

    // ---------- geometry + shaders (built once per size) ----------

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        cx = w / 2f;
        cy = h / 2f;
        float inset = 6 * dp;                          // room for the glow
        r = (Math.min(w, h) / 2f - inset) * VISUAL_IN_WINDOW;   // the visible pebble (§6: small)
        rIn = r - 1.6f * dp;
        inner.set(cx - rIn, cy - rIn, cx + rIn, cy + rIn);
        // Fetcher 1.0 built the silhouette here (a 64-point lobed path, body/bounce/gloss shaders,
        // a stroked gel rim, a separate mark bitmap). Fetcher 2.0 does not: the sheet's own art
        // carries the silhouette, the lobing, the material and the chevron, so all of that geometry
        // and every one of those shaders is gone. What is left in this view is composition.

        haloIdle = new RadialGradient(cx, cy, r + inset + 2 * dp,
                new int[]{0x2B22D3EE, 0x0F22D3EE, 0x00000000}, new float[]{0f, 0.62f, 1f}, Shader.TileMode.CLAMP);
        haloHot = new RadialGradient(cx, cy, r + inset + 2 * dp,
                new int[]{0x7322D3EE, 0x2422D3EE, 0x00000000}, new float[]{0f, 0.62f, 1f}, Shader.TileMode.CLAMP);
        haloErr = new RadialGradient(cx, cy, r + inset + 2 * dp,
                new int[]{0x55FB7185, 0x1AFB7185, 0x00000000}, new float[]{0f, 0.62f, 1f}, Shader.TileMode.CLAMP);
        // The UNSUPPORTED neutral mood (sheet C6): muted blue/gray energy — "not available",
        // never red, never an alarm.
        haloNeutral = new RadialGradient(cx, cy, r + inset + 2 * dp,
                new int[]{0x4C8FA8B8, 0x198FA8B8, 0x00000000}, new float[]{0f, 0.62f, 1f}, Shader.TileMode.CLAMP);

        // Only the ring's sweep is built here now: the body, gloss, bounce and rim shaders went
        // with the procedural material (see the note above).
        sweep = new SweepGradient(cx, cy,
                new int[]{0xFF7DF9FF, 0xFF22D3EE, 0xFF3B82F6, 0xFF7DF9FF}, null);
    }

    private static float clamp01(float v) { return v < 0f ? 0f : (v > 1f ? 1f : v); }

    // ---------- drawing ----------

    @Override protected void onDraw(Canvas c) {
        if (rIn <= 0f) return;
        CoreLook.Look L = fadeFrom != null
                ? CoreLook.returnFade(fadeFrom, fadeT)                  // R6: the subtle RETURN
                : CoreLook.of(state, animT, progress, readyGrade);
        // mood 0 idle · 1 awake · 2 rose failure · 3 NEUTRAL unsupported (sheet C6)
        int mood = (L.error > 0.25f && unsupported) ? 3 : (L.error > 0.25f ? 2 : (L.detected > 0.4f ? 1 : 0));
        int save = c.save();
        c.scale(L.scale * squashX, L.scale * squashY, cx, cy);   // gel squash rides on state scale

        // 1) ambient bloom (restrained; the halo breathes with the download flow)
        p.setStyle(Paint.Style.FILL);
        p.setShader(mood == 3 ? haloNeutral : (mood == 2 ? haloErr : (mood == 1 ? haloHot : haloIdle)));
        float breath = 0.85f + 0.15f * (float) Math.sin(Math.toRadians(flowDeg));
        p.setAlpha(Math.round(255f * clamp01(L.halo / 0.55f) * (CoreStates.PROGRESS.equals(state) ? breath : 1f)));
        c.drawCircle(cx, cy, r + 8 * dp, p);

        // 2) THE CORE (sheet C1): the owner's art, drawn as the disc itself. Its material — obsidian
        //    gel, cyan membrane, gloss and folded chevron — ships as the art (see the class note),
        //    so the whole pebble is one drawBitmap. The tile's transparent padding is the glow's
        //    room; the art's measured object/tile ratio keeps the disc exactly where it was.
        drawArt(c, orb, CoreLook.ART_TILE_RATIO, 1f, mood, L.error);
        if (L.bars && L.barAlpha > 0.01f) {
            // PAUSED (sheet C1): the same orb with its energy receded, bars instead of the chevron.
            // RESUMING fades it back out through barAlpha — the design's "the flow returns smoothly".
            drawArt(c, orbPaused, CoreLook.ART_TILE_RATIO_PAUSED, clamp01(L.barAlpha / 0.9f),
                    mood, L.error);
        }

        // 3) the energy perimeter IS the progress (sheet 4) — no percent text anywhere. The stroke
        //    style is set unconditionally here because it used to be set by the (now removed) glass
        //    arc, and the RESOLVING orbit below draws with the same style.
        p.setStyle(Paint.Style.STROKE);
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

        // 4) the RESOLVING orbit (§M-1, sheet C4 STEPS): one light on the rim while the
        // resolver works. In step mode it HOLDS a named position (0/90/180/270 — the chain's
        // clock); without a fixed step it rotates continuously (the legacy read).
        if (CoreStates.RESOLVING.equals(state)) {
            float deg = orbitFixed != null ? orbitFixed : orbitDeg;
            p.setShader(null);
            p.setColor(0xFFBDFBFF);
            p.setAlpha(235);
            p.setStrokeWidth(2.8f * dp);
            p.setStrokeCap(Paint.Cap.ROUND);
            c.drawArc(inner, deg - 5f, 10f, false, p);
            p.setStrokeCap(Paint.Cap.BUTT);
        }

        // 5) THE CHEVRON AND THE BARS ARE IN THE ART. The chevron is the sheet's own folded ribbon
        //    inside the gel (drawn with the material in step 2), and the paused bars arrive with
        //    the paused art. Nothing is painted over the Core here any more: the old clipPath +
        //    drawBitmap + canvas round-trip per frame went with the separate mark, and the interior
        //    slosh now rides the whole pebble (see the class note).
        c.restoreToCount(save);
    }

    /**
     * Draws one of the Core's art tiles so its disc lands exactly on the Core's disc.
     *
     * {@code tileRatio} is the art's measured object-diameter / tile-side (printed by
     * `tools/core_sheet_extract.py`, asserted by CoreArtSpecTest). Keeping the ratio in
     * {@link CoreLook} — not in this view — is what stops the disc geometry drifting away from the
     * sheet while the material changes underneath it.
     *
     * The state's tint (sheet C6) is applied here rather than baked into a second asset, so the
     * rose / muted blue-grey energy shift is one pure matrix (see {@link CoreTint}).
     */
    private void drawArt(Canvas c, Bitmap art, float tileRatio, float alpha, int mood, float error) {
        if (art == null || tileRatio <= 0f || alpha <= 0.001f) return;
        float side = 2f * r / tileRatio;
        artDst.set(cx - side / 2f, cy - side / 2f, cx + side / 2f, cy + side / 2f);
        int tint = CoreTint.stateOf(mood, error);
        int step = Math.round(clamp01(error) * 24f);        // quantized: no per-frame allocation
        int key = tint * 100 + step;
        if (key != tintedAs) {
            artPaint.setColorFilter(new ColorMatrixColorFilter(CoreTint.matrixFor(tint, step / 24f)));
            tintedAs = key;
        }
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setAlpha(Math.round(255f * clamp01(alpha)));
        c.drawBitmap(art, null, artDst, artPaint);
        p.setAlpha(255);
    }
}
