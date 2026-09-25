package com.omnidownloader.app.downicore;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.view.View;

import com.omnidownloader.app.R;

/**
 * V3.2 Downi Core — the face. One view, drawn in code (no layout XML), hosted by
 * {@link DowniCore}. The approved visual states and Phase B interaction share this view; the
 * service binds detection and job state around it.
 *
 * The mark is the **Core's own identity mark from design sheet 2** — the glossy teal
 * "folded ribbon chevron" — lifted pixel-exact out of the owner's sheet by
 * `tools/core_mark_from_sheet.py` (`R.drawable.downi_core_mark`, 512 px, transparent).
 *
 * Owner ruling 2026-09-25 (correction): the Fetcher must NOT wear the app/launcher icon
 * (`downi_app_icon` = the speed-D). That asset stays the app's own identity (launcher,
 * splash, store) and is no longer drawn anywhere in the Core. The mark is never redrawn,
 * restyled or re-traced — the sheet's own pixels are what ships.
 *
 * Cost discipline (cell K-A5): a state draws itself once; only a short transition animates.
 */
public final class CoreHost extends View {

    /** Tile side as a fraction of the Core's inner disc diameter. Not hand-picked: it is the sheets'
     *  own proportion, solved in {@link CoreLook#MARK_SCALE} from the measured 0.53 disc ratio (the
     *  glyph fills 89% of its tile) and asserted by `CoreMarkSpecTest`. Gate-confirmed on the vivo
     *  V2058: 0.476 of the disc read too small at 0.56, 0.531 matches sheet 3 at 0.63 (48/56/64 dp).
     *  Still live-tunable by the owner at the gate (`mark <scale>` on the debug channel) — nothing
     *  else depends on this number. */
    public static final float DEFAULT_MARK_SCALE = CoreLook.MARK_SCALE;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();          // rim + halo
    private final RectF inner = new RectF();         // perimeter track
    private final RectF glass = new RectF();         // glass highlight arc
    private final RectF markDst = new RectF();       // mark destination
    private final RectF barL = new RectF(), barR = new RectF();
    private final Path clip = new Path();
    private final float dp;

    private final Bitmap mark;
    private float markScale = DEFAULT_MARK_SCALE;

    private String state = CoreStates.IDLE;
    private float progress = 0f;
    private float animT = 0f;                        // 0..1 transition progress
    private ValueAnimator anim;
    private boolean animCancelled;                   // a cancelled transition must never settle

    private Shader haloIdle, haloHot, haloErr, disc, rim, rimErr, sweep;
    private float cx, cy, r, rIn;

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

    /** What a transient state becomes when its short animation ends — then nothing animates. */
    private void settle() {
        animT = 0f;
        if (CoreStates.WAKE.equals(state)) state = CoreStates.DETECTED;
        else if (CoreStates.RESUMING.equals(state)) state = CoreStates.PROGRESS;
        else if (CoreStates.COMPLETING.equals(state)) state = CoreStates.COMPLETE;
        invalidate();                                  // the last frame of the transition
    }

    @Override protected void onDetachedFromWindow() {
        if (anim != null) { anim.cancel(); anim = null; }
        super.onDetachedFromWindow();
    }

    // ---------- geometry + shaders (built once per size) ----------

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        cx = w / 2f;
        cy = h / 2f;
        float inset = 6 * dp;                          // room for the glow (DowniBubble recipe)
        r = Math.min(w, h) / 2f - inset;
        rIn = r - 1.2f * dp;
        oval.set(cx - r, cy - r, cx + r, cy + r);
        inner.set(cx - rIn, cy - rIn, cx + rIn, cy + rIn);
        float gi = 2.4f * dp;
        glass.set(cx - r + gi, cy - r + gi, cx + r - gi, cy + r - gi);

        haloIdle = new RadialGradient(cx, cy, r + inset,
                new int[]{0x3322D3EE, 0x1222D3EE, 0x00000000}, new float[]{0f, 0.62f, 1f}, Shader.TileMode.CLAMP);
        haloHot = new RadialGradient(cx, cy, r + inset,
                new int[]{0x6622D3EE, 0x1F22D3EE, 0x00000000}, new float[]{0f, 0.62f, 1f}, Shader.TileMode.CLAMP);
        haloErr = new RadialGradient(cx, cy, r + inset,
                new int[]{0x55FB7185, 0x1AFB7185, 0x00000000}, new float[]{0f, 0.62f, 1f}, Shader.TileMode.CLAMP);
        disc = new RadialGradient(cx, cy - r * 0.3f, r * 1.3f,
                new int[]{0xF21B2436, 0xF20C1322, 0xFA060A12}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
        rim = new LinearGradient(cx - r, cy - r, cx + r, cy + r,
                new int[]{0xFF9BE8FF, 0xFF22D3EE, 0xFF3B82F6, 0xFF9BE8FF}, null, Shader.TileMode.CLAMP);
        rimErr = new LinearGradient(cx - r, cy - r, cx + r, cy + r,
                new int[]{0xFFFFC4D0, 0xFFFB7185, 0xFFF43F5E, 0xFFFFC4D0}, null, Shader.TileMode.CLAMP);
        sweep = new SweepGradient(cx, cy,
                new int[]{0xFF7DF9FF, 0xFF22D3EE, 0xFF3B82F6, 0xFF7DF9FF}, null);

        clip.reset();
        clip.addCircle(cx, cy, rIn, Path.Direction.CW);
    }

    private static float clamp01(float v) { return v < 0f ? 0f : (v > 1f ? 1f : v); }

    // ---------- drawing ----------

    @Override protected void onDraw(Canvas c) {
        if (rIn <= 0f) return;
        CoreLook.Look L = CoreLook.of(state, animT, progress);
        int mood = L.error > 0.25f ? 2 : (L.detected > 0.4f ? 1 : 0);
        int save = c.save();
        c.scale(L.scale, L.scale, cx, cy);

        p.setStyle(Paint.Style.FILL);
        p.setShader(mood == 2 ? haloErr : (mood == 1 ? haloHot : haloIdle));
        p.setAlpha(Math.round(255f * clamp01(L.halo / 0.55f)));
        c.drawCircle(cx, cy, r + 6 * dp, p);

        p.setShader(disc);
        p.setAlpha(255);
        c.drawCircle(cx, cy, r, p);

        p.setStyle(Paint.Style.STROKE);                       // glass layer (sheet 4)
        p.setShader(null);
        p.setColor(0x40EAF9FF);
        p.setStrokeWidth(1.1f * dp);
        c.drawArc(glass, 196f, 148f, false, p);

        p.setShader(mood == 2 ? rimErr : rim);                // rim
        p.setStrokeWidth(1.6f * dp);
        p.setAlpha(Math.round(255f * clamp01(L.rim)));
        c.drawCircle(cx, cy, r - 0.9f * dp, p);

        if (L.track > 0.001f || L.perimeter > 0.001f) {       // energy perimeter = progress
            p.setShader(null);
            p.setColor(0xFF22D3EE);
            p.setAlpha(Math.round(255f * L.track));
            p.setStrokeWidth(2.2f * dp);
            c.drawCircle(cx, cy, rIn, p);
            if (L.perimeter > 0.001f) {
                p.setShader(sweep);
                p.setAlpha(Math.round(255f * clamp01(0.65f + 0.35f * L.perimeter)));
                p.setStrokeWidth(2.6f * dp);
                p.setStrokeCap(Paint.Cap.ROUND);
                c.drawArc(inner, -90f, 360f * L.perimeter, false, p);
                p.setStrokeCap(Paint.Cap.BUTT);
            }
        }

        p.setAlpha(255);
        p.setShader(null);
        if (mark != null && L.mark > 0.01f) {                 // the sheet-2 mark, clipped to the disc
            int s2 = c.save();
            c.clipPath(clip);
            float side = 2f * rIn * markScale;
            markDst.set(cx - side / 2f, cy - side / 2f, cx + side / 2f, cy + side / 2f);
            p.setAlpha(Math.round(255f * clamp01(L.mark)));
            c.drawBitmap(mark, null, markDst, p);
            p.setAlpha(255);
            c.restoreToCount(s2);
        }

        if (L.bars && L.barAlpha > 0.01f) {                   // PAUSED: bars, never text
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
