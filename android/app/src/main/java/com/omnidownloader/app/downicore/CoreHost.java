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
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;

import java.lang.reflect.Field;
import java.util.List;

import com.airbnb.lottie.ImageAssetDelegate;
import com.airbnb.lottie.LottieComposition;
import com.airbnb.lottie.LottieCompositionFactory;
import com.airbnb.lottie.LottieDrawable;
import com.airbnb.lottie.LottieImageAsset;
import com.airbnb.lottie.LottieProperty;
import com.airbnb.lottie.LottieResult;
import com.airbnb.lottie.model.KeyPath;
import com.airbnb.lottie.model.layer.Layer;
import com.airbnb.lottie.value.LottieFrameInfo;
import com.airbnb.lottie.value.SimpleLottieValueCallback;

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
 * Motion is now authored, not arithmetic (§3/§20/§25): a state that has a bodymovin file (see
 * {@link CoreLottie}) draws that file into this same canvas through a {@link LottieDrawable}, so
 * the JSON owns the art's own motion — the wake swell, the press compression, the ring climbing
 * with real progress, the C5 merge flash. The states with no file keep the static art the M1 device
 * gate measured, and if a composition is ever missing or malformed this view falls back to that
 * same static art rather than drawing nothing: **the animation is never the only path to a Core**.
 *
 * The Java overlays stay Java, because they are live touch/state data and not animation: the
 * ambient bloom (sheet C6's rose / muted blue-grey), the rim's own progress arc, the resolver's
 * orbit and the gel squash. The authored ring sits just outside the disc while the rim arc sits on
 * it, so the two read as membrane + halo rather than drawing the same thing twice.
 *
 * Since the chevron is baked into the art, the interior slosh ({@link #setMarkLag}) still reads on
 * the Core as a whole: the gel deforms, the authored art inside it goes with it.
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

    // ---------- the authored motion stage (V3.3 §3) ----------

    /**
     * The state animation the current state draws, or null while the static art carries it. One
     * drawable, reused: a state change swaps the composition, never stacks views.
     */
    private LottieDrawable stage;
    /** Which file {@link #stage} holds (null when the static path is drawing). Logged per state. */
    private String stageAsset;
    /**
     * Why the last stage load was refused (null after a good load). logcat is filtered for this
     * app by this ROM, so the refusal is reported through the one channel the harness reads —
     * DowniCore's CORE_STATE line, via {@link #stageNote()}.
     */
    private String stageError;
    /**
     * How many layers the loaded composition actually parsed to (-1 = none loaded). A composition
     * that loads but parses to zero layers draws nothing at all, which looks exactly like a
     * rendering failure — this number is the discriminator.
     */
    private int stageLayers = -1;
    /** Draw-time diagnostics: a stage that reports success and then paints nothing is invisible to
     *  every gate otherwise, and the CORE_STATE line is the only channel this ROM leaves open. */
    private int stageDraws;
    private String stageDrawFirst, stageDrawErr;
    /** The baked C6 looks, decoded only if a cross-fade state asks (lazy: a megabyte each). */
    private Bitmap orbRose, orbNeutral;
    /** Supplies the JSON's image assets from drawable-nodpi, so the art ships in the APK once. */
    private ImageAssetDelegate artDelegate;

    private static final String TAG = "DOWNI";

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

    // The ambient (looping) stage's budget — see CoreMotion.AMBIENT_*. `stageAmbient` is true only
    // while the current file LOOPS (core_idle_ready, the DETECTED look): every other state's file is a
    // short one-shot that keeps the display's full rate, and the states with no file draw the static
    // art. Armed by {@link #armAmbient} on every load of a looping file, and only ever cleared by a
    // non-looping file, by the end of the window ({@link #holdAmbient}), or by a detach.
    private boolean stageAmbient;
    private long stageLoopUntil;                     // uptime when the breath must stop
    private long stageFrameAt;                       // last ambient repaint (rate cap)
    private boolean stageHeld;                       // paused by the window being invisible

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
        // The JSON's image layers each name a sheet tile, and the tiles live ONCE, in
        // res/drawable-nodpi. Supplying them here (rather than shipping a second copy inside
        // assets/core/images/) is what keeps one file the single source of truth for the material --
        // the same reason CoreTint owns the rose/blue-grey numbers. An unknown name is a generator
        // bug: it returns null so the whole stage is refused, and the static art draws instead of a
        // Core with a missing body.
        artDelegate = new ImageAssetDelegate() {
            @Override public Bitmap fetchBitmap(LottieImageAsset asset) {
                String name = asset == null ? null : asset.getFileName();
                if ("core_orb.png".equals(name)) return orb;
                if ("core_orb_paused.png".equals(name)) return orbPaused;
                if ("core_orb_rose.png".equals(name)) {
                    if (orbRose == null) orbRose = decodeArt(getContext(), R.drawable.core_orb_rose);
                    return orbRose;
                }
                if ("core_orb_neutral.png".equals(name)) {
                    if (orbNeutral == null) orbNeutral = decodeArt(getContext(), R.drawable.core_orb_neutral);
                    return orbNeutral;
                }
                return null;
            }
        };
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
        updateStage(true);          // the state's authored file plays from its first frame
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
        updateStage(false);         // FAILED swaps file with its mood: failure <-> unsupported art
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
        // V3.3 §3: the authored ring is SCRUBBED with the real fraction, never autoplayed, so the
        // animation and the download are the same number (sheet 4: no percent text, no timer).
        if (stage != null && CoreLottie.isScrubbed(stageAsset)) stage.setProgress(progress);
        invalidate();
    }

    private long durationOf(String s) {
        if (CoreStates.WAKE.equals(s)) return readyGrade ? CoreMotion.WAKE_MS : CoreMotion.WAKE_AWARE_MS;
        if (CoreStates.PRESSED.equals(s)) return CoreMotion.PRESS_MS;
        if (CoreStates.SNAPPED.equals(s)) return CoreMotion.SNAP_MS;
        if (CoreStates.RESUMING.equals(s)) return CoreMotion.PAUSE_MS;
        if (CoreStates.COMPLETING.equals(s)) return CoreMotion.COMPLETE_MS;
        if (CoreStates.RETRY.equals(s)) return CoreMotion.RETRY_MS;   // its own 600 ms composition
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
            // M8: the sheen is the one animation that outlives a state change (an 8 s loop that keeps
            // restarting for as long as the download runs), so it carries the ambient cadence too —
            // the frame rate is a budget, not a default (see CoreMotion.AMBIENT_EXTRA_DELAY_MS).
            flow.setFrameDelay(CoreMotion.AMBIENT_EXTRA_DELAY_MS);
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
        // C6: the retry acknowledgement hands over to the resolver's own orbit ("as it re-resolves").
        // If the retry's run has ALREADY reported a job state by then, the arbiter's next push
        // corrects this within a beat — and because RETRY is a transient, that push could not cut the
        // acknowledgement short in the first place (DowniCore.setBaseState's transient guard).
        else if (CoreStates.RETRY.equals(state)) state = CoreStates.RESOLVING;
        updateFlow();
        // An auto-settle IS a state change, so it picks the promoted state's file up exactly like
        // setState does — otherwise the state changes under a stage that still belongs to the old
        // one. This is what made the C5 "brief bright merge" (plan §3: the energy pulls inward,
        // there is a merge, then it settles) invisible on the real finish path: COMPLETING has no
        // file of its own, so entering it clears the stage, and the promotion to COMPLETE used to
        // leave it cleared — the ring's close and the bloom in core_complete never played at all
        // (M4 device pass 2026-09-27: `state complete` typed by hand loaded `core_complete`, the
        // COMPLETING -> COMPLETE promotion logged `stage=null`). RESUMING -> PROGRESS has the same
        // hole: the ring the download owns only came back when a command happened to re-set the
        // state.
        updateStage(true);
        invalidate();                                  // the last frame of the transition
    }

    @Override protected void onDetachedFromWindow() {
        if (anim != null) { anim.cancel(); anim = null; }
        if (fadeAnim != null) { fadeAnim.cancel(); fadeAnim = null; }
        if (flow != null) { flow.cancel(); flow = null; }
        if (orbit != null) { orbit.cancel(); orbit = null; }
        if (stage != null) { stage.cancelAnimation(); stage = null; stageAsset = null; }
        clearAmbient();                          // a detached Core holds no budget and no window
        super.onDetachedFromWindow();
    }

    /** Interior slosh (V-3): the mark trails the container during a drag, then springs home. */
    public void setMarkLag(float lx, float ly) {
        float max = CoreMotion.MARK_LAG_DP * dp;      // sheet C3: ~4 px, clamped
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

        // the stage's canvas follows the disc, so a resize re-places the composition (no restart:
        // a size change is not a state change)
        updateStage(false);
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
        // The authored stage owns the state's own size motion, so when one is drawing the canvas is
        // NOT state-scaled: otherwise the JSON's press compression and this view's would multiply
        // (0.90 x 0.90 on a press). Gel squash still rides on top — that is touch physics, not
        // animation, and it is the one deformation the Core always applies itself.
        float artScale = (stage != null && fadeFrom == null) ? 1f : L.scale;
        c.scale(artScale * squashX, artScale * squashY, cx, cy);

        // 1) ambient bloom (restrained; the halo breathes with the download flow)
        p.setStyle(Paint.Style.FILL);
        p.setShader(mood == 3 ? haloNeutral : (mood == 2 ? haloErr : (mood == 1 ? haloHot : haloIdle)));
        float breath = 0.85f + 0.15f * (float) Math.sin(Math.toRadians(flowDeg));
        p.setAlpha(Math.round(255f * clamp01(L.halo / 0.55f) * (CoreStates.PROGRESS.equals(state) ? breath : 1f)));
        c.drawCircle(cx, cy, r + 8 * dp, p);

        // 2) THE CORE (sheet C1 / V3.3 §3): the owner's art, drawn as the disc itself. When the state
        //    has an authored file the composition draws the material AND its own motion; otherwise the
        //    static tile does, exactly as the M1 device gate measured it. Either path lands the disc
        //    on 2r by the same object/tile ratio, so the two can be compared shot for shot -- and if a
        //    file is ever missing or refused, the second path is what draws.
        if (!drawStage(c)) {
            drawArt(c, orb, CoreLook.ART_TILE_RATIO, 1f, mood, L.error);
            if (L.bars && L.barAlpha > 0.01f) {
                // PAUSED (sheet C1): the same orb with its energy receded, bars instead of the
                // chevron. RESUMING fades it back out through barAlpha — "the flow returns smoothly".
                // A staged PAUSED carries its own bars, so this is the no-file path.
                drawArt(c, orbPaused, CoreLook.ART_TILE_RATIO_PAUSED, clamp01(L.barAlpha / 0.9f),
                        mood, L.error);
            }
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

    // ---------- the authored stage (V3.3 §3) ----------

    /** Which file the stage is drawing — null while the static art carries the state (log/debug). */
    public String stageAsset() { return stageAsset; }

    /**
     * `stage=<file> n=<layers> f=<frame> run=<isAnimating> draws=<n> <state of the first draw>`
     * while the stage owns the state, else `stage=null err=<why>`. The draw half exists because
     * "loaded but paints nothing" — a wrong bounds, an invisible drawable, an image asset that
     * never resolves — looks identical to "never loaded" from outside.
     *
     * `f`/`run` are the live animation, read at the moment the note is taken: a file that is
     * supposed to play (any state but the scrubbed PROGRESS one) has to show a frame past 0 with
     * `run=true` while its 300-750 ms are on screen, and a settled frame with `run=false` after.
     * Without them, "the composition never animated" and "it animated but nothing repainted" are
     * the same reading from the outside — which is exactly what cost the M4 pass two runs.
     *
     * M8 adds the budget to this line: `amb=1` while the looping READY look is still breathing (see
     * {@link #armAmbient}) and `held=1` while the window is not visible. Both change `run`, so they
     * have to be readable from the outside: `run=false` on a looping file means the ambient window is
     * over or the Core is hidden — the budget working — and NOT that the composition failed to start.
     */
    public String stageNote() {
        return "stage=" + stageAsset
                + (stageAsset != null ? " n=" + stageLayers : "")
                + (stage != null ? " f=" + stage.getFrame() + " run=" + stage.isAnimating() : "")
                + (CoreLottie.loops(stageAsset) ? " amb=" + (stageAmbient ? 1 : 0) : "")
                + (stageHeld ? " held=1" : "")
                + (stageDrawFirst != null ? " draws=" + stageDraws + " " + stageDrawFirst : "")
                + (stageDrawErr != null ? " err=" + stageDrawErr : "")
                + (stageAsset == null && stageError != null ? " err=" + stageError : "");
    }

    // ---------- M8: the ambient budget (CoreMotion.AMBIENT_*) ----------
    // WHAT THIS IS FOR. `core_idle_ready` is the one loop in the shipped set, and it is also the file
    // the DETECTED state plays — so on any video screen the Core re-rendered a 60 fps composition
    // forever, in a window floating over someone else's app. Measured on the owner's phone: our own
    // threads burned 55 % of a core and dragged surfaceflinger and the GPU composer with them, on a
    // foreground app that sat at 10 % — which is what the vendor power manager (`com.vivo.abe`)
    // answered with a FORCE STOP, and a force stop also clears `enabled_accessibility_services`, so
    // the Core vanished until DOWNI was opened again (dumpsys activity exit-info, six times).
    // The look is unchanged: the breath still starts whenever the Core arrives on a video or the user
    // touches it, at 24 fps instead of 60, for CoreMotion.AMBIENT_LOOP_WINDOW_MS, and then the frame
    // it stopped on is held. Everything else in the state machine is a short one-shot and keeps the
    // display's own rate — the budget applies to the loop and to nothing else.

    /**
     * Arms the looping look's budget. Called on every (re)load of a looping file, i.e. when the Core
     * actually arrives somewhere or is touched — a breath nobody asked for is the drain.
     */
    private void armAmbient() {
        stageAmbient = true;
        stageFrameAt = 0L;
        stageLoopUntil = SystemClock.uptimeMillis() + CoreMotion.AMBIENT_LOOP_WINDOW_MS;
    }

    /** The current file does not loop: full rate, no window (see {@link #stageFrame}). */
    private void clearAmbient() {
        stageAmbient = false;
        stageFrameAt = 0L;
        stageLoopUntil = 0L;
    }

    /**
     * A frame of the composition asks to be seen. This is the ONLY door from the stage to the screen
     * (the drawable is hand-drawn into this view's canvas, so it reaches us through
     * {@link #invalidateDrawable} and through its own animator listener — both land here), which makes
     * it the one place the budget can be enforced and the one place it cannot be bypassed.
     */
    private void stageFrame() {
        if (stageHeld) return;                     // nobody can see it: no repaint at all
        if (!stageAmbient) { invalidate(); return; }   // a one-shot keeps the display's own rate
        long now = SystemClock.uptimeMillis();
        if (now >= stageLoopUntil) { holdAmbient(); return; }
        if (stageFrameAt != 0L && now - stageFrameAt < CoreMotion.AMBIENT_FRAME_MS) return;
        stageFrameAt = now;
        invalidate();
    }

    /**
     * The ambient window is over: the composition stops where it is and the Core holds that frame in
     * silence. Pausing the drawable matters as much as skipping the repaint — an unpaused Lottie
     * animator keeps computing every layer's value at 60 fps on the main thread of the accessibility
     * service for a frame that is never drawn.
     */
    private void holdAmbient() {
        stageAmbient = false;
        if (stage != null && CoreLottie.loops(stageAsset)) stage.pauseAnimation();
    }

    /**
     * The composition's own repaints obey the budget above. View.invalidateDrawable only honours
     * {@link #verifyDrawable}, and verifyDrawable has accepted the stage since M4 precisely so its
     * frames reach the screen — so this override is the gate on that door, and it must still call
     * through for anything that is not the stage.
     */
    @Override public void invalidateDrawable(Drawable who) {
        if (who == stage) { stageFrame(); return; }
        super.invalidateDrawable(who);
    }

    /**
     * A hidden Core keeps its frame, not its clock.
     *
     * This ROM never removes the overlay window — {@code DowniCore.hide()} only sets this view GONE,
     * because re-adding it loses touch — so the DETECTED look used to keep computing 60 fps frames
     * behind an invisible view, and the framework silently dropped every one of those repaints (a
     * GONE view's invalidate() is a no-op), which is why that cost never showed up as frames rendered.
     * Pausing on GONE is what makes the Core cost nothing while it is not on screen; the same holds
     * for a window the system hides under us.
     */
    private void setStageHeld(boolean held) {
        if (stageHeld == held) return;
        stageHeld = held;
        if (stage == null) return;
        if (held) {
            stage.pauseAnimation();
            return;
        }
        if (CoreLottie.isScrubbed(stageAsset)) return;         // the ring is driven by progress
        boolean loops = CoreLottie.loops(stageAsset);
        boolean withinWindow = SystemClock.uptimeMillis() < stageLoopUntil;
        stageAmbient = loops && withinWindow;
        if (!loops || withinWindow) {                          // a one-shot finishes its own story
            stageFrameAt = 0L;
            stage.playAnimation();
        }
        invalidate();
    }

    @Override protected void onVisibilityChanged(View changed, int visibility) {
        if (changed == this) setStageHeld(visibility != VISIBLE);
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        setStageHeld(visibility != VISIBLE);
    }

    /**
     * Points the stage at the current state's file, or clears it when the state has none.
     *
     * {@code restart} is what a *state change* means: the state's animation plays from its first
     * frame (re-setting a state restarts it, exactly as the Java transition always has), while a
     * re-place (a resize, a mood switch) leaves the animation where it is.
     *
     * Drawn into this view's own canvas rather than added as a child view on purpose: the Core's
     * touch handling — press, finger-follow, slop, edge snap — is the part of Fetcher 2.0 that must
     * NOT change, and {@code ViewGroup} would put a second touch target inside it.
     */
    private void updateStage(boolean restart) {
        if (rIn <= 0f) return;                     // no geometry yet; onSizeChanged calls back
        String want = CoreLottie.assetFor(state, unsupported);
        if (want == null) {
            if (stage != null) {
                stage.cancelAnimation();
                stage = null;
            }
            stageAsset = null;
            stageError = null;
            stageLayers = -1;
            clearAmbient();                        // the static art has no clock to budget
            return;
        }
        boolean same = want.equals(stageAsset) && stage != null;
        if (!same) {
            LottieDrawable loaded = loadStage(want);
            if (loaded == null) {
                if (stage != null) {               // never draw a Core with a missing body
                    stage.cancelAnimation();
                    stage = null;
                }
                stageAsset = null;
                stageLayers = -1;
                clearAmbient();
                return;
            }
            stage = loaded;
            stageAsset = want;
            stageError = null;
            stageDraws = 0;
            stageDrawFirst = null;
            stageDrawErr = null;
        }
        placeStage();
        stage.setAlpha(255);
        if (CoreLottie.isScrubbed(want)) {
            clearAmbient();
            stage.setProgress(progress);           // sheet 4: the ring IS the fraction
        } else if (restart || !same) {
            // M8: only the looping READY look needs a budget; everything else is a short one-shot
            // that keeps the display's own rate and then holds its last frame anyway (repeat 0).
            boolean loops = CoreLottie.loops(want);
            if (loops) armAmbient(); else clearAmbient();
            stage.setRepeatCount(loops ? LottieDrawable.INFINITE : 0);
            stage.setProgress(0f);
            stage.playAnimation();
        }
        // M8: a state change while the Core is not on screen must not restart the clock either —
        // the file is loaded and parked on its first frame, and {@link #setStageHeld} plays it when
        // the window is visible again.
        if (stageHeld) stage.pauseAnimation();
    }

    /**
     * Parses one of the shipped compositions — synchronously, and once per state per process
     * (Lottie caches by key). Synchronous on purpose: the first frame after a state change must
     * already be the new state, and an async load would draw the previous look for a frame or two.
     * The files are 4-19 KB, and refusal is cheap: every path out of here that is not a fully
     * resolved composition returns null, which puts the verified static art back on screen.
     */
    private LottieDrawable loadStage(String asset) {
        try {
            LottieResult<LottieComposition> result = LottieCompositionFactory
                    .fromAssetSync(getContext(), CoreLottie.pathFor(asset));
            LottieComposition comp = result.getValue();
            if (comp == null) {
                return refuse(asset, "no_composition " + result.getException());
            }
            LottieDrawable d = new LottieDrawable();
            d.setImageAssetDelegate(artDelegate);
            d.setComposition(comp);
            // Lottie holds playAnimation() (and resumeAnimation()) back while the drawable is not
            // visible, and a Drawable only becomes visible when something calls setVisible: drawn by
            // hand into this view's canvas there is no View system to flip that bit, so without this
            // the animator never ticks and the stage stays frozen on its first frame (the M2 pass:
            // a loaded core_complete n=3 showed no ring, because its trim was still at frame 0).
            d.setVisible(true, false);
            d.setCallback(this);
            // ...and repaint this view on every frame of the animation. A hand-drawn Drawable
            // cannot do that itself: Lottie asks for a repaint through {@link Drawable.Callback},
            // and View.invalidateDrawable only honours verifyDrawable (background / foreground
            // drawables, see the override below), so the composition's frames would be computed
            // and then dropped — the stage would sit on whichever frame happened to be drawn
            // during some other repaint. That is the second half of the same M4 fault: core_pause
            // measured its first frame's ring opacity for 5.7 s, and core_complete's merge never
            // appeared, no matter how long it was left on screen.
            // M8: that repaint now goes through {@link #stageFrame}, which is where the ambient
            // looping look's budget is enforced (24 fps, then a hold) — and {@link #invalidateDrawable}
            // below is the other door into the same gate, because Lottie also invalidates itself.
            d.addAnimatorUpdateListener(a -> stageFrame());
            // Pin the tile to the layer itself as well: ImageLayer checks a value callback before it
            // asks the composition for an asset, so the authored orb is what draws even if an asset
            // lookup fails on the way (image layers are the one thing Lottie resolves at draw time).
            d.addValueCallback(new KeyPath("core"), LottieProperty.IMAGE,
                    new SimpleLottieValueCallback<Bitmap>() {
                        @Override public Bitmap getValue(LottieFrameInfo<Bitmap> frameInfo) {
                            return orb;
                        }
                    });
            // Resolve the file's image layers now: a missing tile must be a fallback, not a hole in
            // the art, and the first frame must never decode a megabyte mid-draw. Pinning the tile
            // onto the asset as well means ImageLayer finds it on the composition itself, without a
            // trip through the drawable's image-asset manager.
            for (LottieImageAsset image : comp.getImages().values()) {
                Bitmap tile = artDelegate.fetchBitmap(image);
                if (tile == null) {
                    return refuse(asset, "unresolved_image " + image.getFileName());
                }
                image.setBitmap(tile);
            }
            stageError = null;
            stageLayers = comp.getLayers().size();
            return d;
        } catch (Throwable t) {                    // a malformed file must never take the Core down
            return refuse(asset, t.toString());
        }
    }

    /**
     * Records why a file was refused and returns null. This ROM filters the app's logcat, so the
     * reason travels on the CORE_STATE log line (`err=...`) — a refusal must be diagnosable from
     * the spike file alone, which is what the device gate reads.
     */
    private LottieDrawable refuse(String asset, String why) {
        stageError = asset + ": " + why;
        stageLayers = -1;
        Log.w(TAG, "CORE_STAGE_REFUSED " + asset + " (" + why + ")");
        return null;
    }

    /**
     * Lands the composition's 512 px canvas so its disc sits exactly on the Core's disc — the same
     * object/tile ratio {@link #drawArt} uses, which is why a comp built on the two-bar tile goes
     * through {@link CoreLottie#usesPausedTile}: the two tiles pad the canvas differently.
     */
    private void placeStage() {
        float ratio = CoreLottie.usesPausedTile(stageAsset)
                ? CoreLook.ART_TILE_RATIO_PAUSED : CoreLook.ART_TILE_RATIO;
        float side = 2f * r / ratio;
        stage.setBounds(Math.round(cx - side / 2f), Math.round(cy - side / 2f),
                Math.round(cx + side / 2f), Math.round(cy + side / 2f));
    }

    /** Draws the authored state animation into this canvas. False = the static art should draw. */
    /**
     * TEMP DIAGNOSTIC (remove when the image layer is known to draw): walks the layer tree Lottie
     * actually built with reflection, because {@code CompositionLayer.layers} and
     * {@code BaseLayer.visible} are package-private. It answers the one question the public API
     * cannot: is the image layer in the tree, and is its own visibility flag on?
     */
    private String stageTreeDump() {
        try {
            Field clF = LottieDrawable.class.getDeclaredField("compositionLayer");
            clF.setAccessible(true);
            Object root = clF.get(stage);
            if (root == null) return "tree=no_comp_layer";
            Field lsF = root.getClass().getDeclaredField("layers");
            lsF.setAccessible(true);
            List<?> kids = (List<?>) lsF.get(root);
            if (kids == null) return "tree=no_layers_field";
            StringBuilder b = new StringBuilder("tree=[");
            for (Object kid : kids) {
                b.append(kid.getClass().getSimpleName()).append(':');
                for (Class<?> c = kid.getClass(); c != null; c = c.getSuperclass()) {
                    try {
                        Field vf = c.getDeclaredField("visible");
                        vf.setAccessible(true);
                        b.append("visible=").append(vf.getBoolean(kid));
                        break;
                    } catch (NoSuchFieldException ignored) {
                        // keep walking up the class chain
                    }
                }
                for (Class<?> c = kid.getClass(); c != null; c = c.getSuperclass()) {
                    try {
                        Field mf = c.getDeclaredField("layerModel");
                        mf.setAccessible(true);
                        Layer m = (Layer) mf.get(kid);
                        b.append('/').append(m.getName()).append('/').append(m.getRefId());
                        break;
                    } catch (NoSuchFieldException ignored) {
                        // keep walking up the class chain
                    }
                }
                b.append(' ');
            }
            return b.append(']').toString();
        } catch (Throwable t) {
            return "tree_err=" + t;
        }
    }

    /**
     * The stage is drawn into this view's canvas by hand, never set as the background, and the
     * default implementation only accepts the background / foreground drawables — so without this
     * every invalidation the drawable sends is dropped and its frames never reach the screen (see
     * the callback note in {@link #loadStage}). {@link #drawStage} has always assumed this works;
     * this is what makes the assumption true.
     */
    @Override protected boolean verifyDrawable(Drawable who) {
        return who == stage || super.verifyDrawable(who);
    }

    private boolean drawStage(Canvas c) {
        if (stage == null || stageAsset == null) return false;
        try {
            stage.draw(c);                         // the drawable invalidates this view via its callback
        } catch (Throwable t) {                    // a broken file must never take the Core down
            if (stageDrawErr == null) stageDrawErr = t.toString();
            return false;                          // ...and the static art draws instead of a hole
        }
        stageDraws++;
        if (stageDrawFirst == null) {
            Rect b = stage.getBounds();
            LottieComposition comp = stage.getComposition();
            String bmp = "no_image_layers";
            if (comp != null && !comp.getImages().isEmpty()) {
                String id = comp.getImages().keySet().iterator().next();
                Bitmap one = stage.getBitmapForId(id);
                bmp = id + (one == null ? "=null" : "=" + one.getWidth() + "x" + one.getHeight());
            }
            // The layer list is the one thing that separates "the file has no image layer" from "the
            // image layer's refId does not match the assets map" — both look like an orb-less Core.
            StringBuilder ls = new StringBuilder();
            if (comp != null) {
                for (Layer l : comp.getLayers()) {
                    ls.append(l.getName()).append('/').append(l.getLayerType()).append('/')
                            .append(l.getRefId() == null ? "-" : l.getRefId())
                            .append(comp.getImages().containsKey(l.getRefId()) ? "(in_map)" : "(NO_IMAGE_ASSET)")
                            .append(l.isHidden() ? "(hidden)" : "").append(' ');
                }
            }
            stageDrawFirst = "p=" + stage.getProgress() + " vis=" + stage.isVisible() + " al=" + stage.getAlpha()
                    + " b=" + b.left + "," + b.top + "," + b.right + "," + b.bottom
                    + " comp=" + (comp == null ? "null" : comp.getBounds().width() + "x" + comp.getBounds().height())
                    + " " + bmp + " layers=[" + ls.toString().trim() + "] " + stageTreeDump();
        }
        return true;
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
