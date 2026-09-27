package com.omnidownloader.app.downicore;

/**
 * V3.2 Downi Core — the motion language, as numbers.
 *
 * Straight from design sheet 6 ("Animation & Motion Language", timing table) and sheet 5
 * ("Physical Interaction"): quick response 0.1 s, wake 0.4 s, release/settle 0.4 s, snap to
 * edge 0.3 s, progress pulse 0.4 s, error pulse 0.3 s — plus the sheet's S-curve easing
 * ("fast, but smooth") and its four principles: natural, subtle, fluid, never mechanical.
 *
 * These are the only durations the Core is allowed to use. If a sheet changes, it changes
 * here — never at a call site.
 */
public final class CoreMotion {
    public static final long QUICK_MS = 100;      // tap response / drag follow
    public static final long PRESS_MS = 100;      // compression in
    public static final long RELEASE_MS = 200;    // rebound (0.1 s in + 0.1 s out, sheet 6)
    public static final long WAKE_MS = 600;       // the settled end of the C2 storyboard
    public static final long WAKE_AWARE_MS = 400; // the AWARE wake: the same shape, quieter ceilings

    /**
     * Sheet C2's wake storyboard: awareness -> energy builds -> the membrane activates -> the core
     * brightens -> settled. The first four beats are one {@code WAKE_STEP_MS} apart and the final
     * settle takes two steps (the energy arrives, then rests). The old constant was a dead 200 ms
     * literal; these beats are what make it real, and the tests pin them.
     */
    public static final long WAKE_STEP_MS = 100;
    public static final long[] WAKE_BEATS_MS = {
            WAKE_STEP_MS, 2L * WAKE_STEP_MS, 3L * WAKE_STEP_MS, 4L * WAKE_STEP_MS, WAKE_MS
    };

    /** A non-snapping release settles back with this small restrained swell (sheet C3 §2). */
    public static final long RELEASE_SETTLE_MS = 180;

    // ---- C3 — THE TOUCH PHYSICS (sheet C3), the numbers every press/drag/snap path must use --------
    // These lived at their call sites (a View's clamp, a touch listener's literal, an animator's
    // envelope), where nothing could pin them and a free-model edit could quietly change the feel.
    // They are the sheet's numbers: ~10 % compression on a press, a ~4 px interior slosh, edge
    // magnetism only within 12 dp, and a snap that flattens on the contact axis and bulges
    // perpendicular to it by half as much.
    /** The interior slosh's ceiling: the mark trails the container by at most this (sheet C3: ~4 px). */
    public static final float MARK_LAG_DP = 4f;
    /** ... and it trails by this fraction of the finger's own travel. */
    public static final float MARK_LAG_FRACTION = 0.06f;
    /** A release within this much of an edge magnetises to it; anywhere else stays draggable. */
    public static final float EDGE_MAGNET_DP = 12f;
    /** C3 §3's edge contact: the gel flattens along the contact axis by this much at the peak. */
    public static final float SNAP_SQUASH = 0.10f;
    /** ... and bulges perpendicular to that axis by this fraction of the flattening. */
    public static final float SNAP_BULGE_FRACTION = 0.5f;
    /** C3's press: ~10 % compression inward while the finger is down (settles at 0). */
    public static final float PRESS_SQUASH = 0.10f;
    /** C3 §2's release swell cap: the gel never passes this on the rebound. */
    public static final float RELEASE_SWELL = 0.05f;
    /** The subtle COMPLETE/FAILED -> IDLE return (sheet C5 RETURN): the held look fades to rest. */
    public static final long RETURN_MS = 400;
    public static final long DRAG_MS = 100;       // follow the finger, no lag beyond this
    public static final long SNAP_MS = 300;       // edge magnetism — subtle, never forced
    public static final long PROGRESS_MS = 400;   // a progress step eases in over this
    public static final long PAUSE_MS = 400;      // the energy freezes gently
    public static final long COMPLETE_MS = 400;   // success pulse, then a calm return
    public static final long ERROR_MS = 300;      // restrained error pulse
    // The Reach (sheet C4): tether growth, node travel, capture flash, retraction.
    public static final long REACH_GROW_MS = 200;
    public static final long TETHER_SEND_MS = 350;
    public static final long TETHER_RETURN_MS = 250;
    public static final long CAPTURE_PULSE_MS = 150;
    public static final long REACH_FADE_MS = 200;

    /** Smooth S-curve (sheet 6, "Easing curve"): slow start, fast middle, soft landing. */
    public static float easeInOut(float t) {
        if (t <= 0f) return 0f;
        if (t >= 1f) return 1f;
        return t * t * (3f - 2f * t);            // smoothstep
    }

    /** Overshoot for release / edge settle: reaches 1 with a small rebound, never past 1.1. */
    public static float easeOutBack(float t) {
        if (t <= 0f) return 0f;
        if (t >= 1f) return 1f;
        float c = 1.70158f;
        float u = t - 1f;
        return 1f + (c + 1f) * u * u * u + c * u * u;
    }

    /** One-shot pulse envelope (0 -> 1 -> 0) for confirmation and error flashes. */
    public static float pulse(float t) {
        if (t <= 0f || t >= 1f) return 0f;
        return (float) Math.sin(Math.PI * t);
    }

    /**
     * Sheet C2's staged energy: a piecewise ramp that ARRIVES at each storyboard beat instead of
     * sweeping once. {@code start} is the resting value at t=0; {@code beatValues[i]} is the value
     * reached at {@link #WAKE_BEATS_MS}[i]. Between beats the curve eases (S-curve), so the energy
     * still moves like a material — it just lands in the sheet's five beats. Pure + tested; the
     * array is the shape, and a missing beat is a programming error, not a silent default.
     */
    public static float wakeStage(float start, float[] beatValues, float t) {
        if (beatValues.length != WAKE_BEATS_MS.length) {
            throw new IllegalArgumentException("wakeStage needs one value per beat");
        }
        if (t <= 0f) return start;
        float prevT = 0f;
        float prevV = start;
        for (int i = 0; i < WAKE_BEATS_MS.length; i++) {
            float bt = WAKE_BEATS_MS[i] / (float) WAKE_MS;
            float bv = beatValues[i];
            if (t >= bt) { prevT = bt; prevV = bv; continue; }
            return prevV + (bv - prevV) * easeInOut((t - prevT) / (bt - prevT));
        }
        return beatValues[beatValues.length - 1];
    }

    /**
     * The C3 §2 release settle: a restrained swell — 1.00 -> peak -> 1.00, never past 1.05. The
     * finger let go of a Core that did not snap; the gel relaxes, it does not bounce like a toy.
     */
    public static float releaseSettle(float t) {
        return 1f + RELEASE_SWELL * pulse(t);
    }

    /**
     * The C3 §3 edge contact as an envelope: how much the gel flattens along the contact axis at
     * time {@code t} of the snap (0 -> {@link #SNAP_SQUASH} at the midpoint -> 0), so the deformation
     * ARRIVES while the Core travels and is gone when it lands — "no trace after the snap".
     *
     * Pure on purpose: this used to be an animator's inline `sin(PI * t) * 0.10f`, where the sheet's
     * number could drift unnoticed. The bulge perpendicular to the contact axis is
     * {@link #SNAP_BULGE_FRACTION} of whatever this returns.
     */
    public static float snapSquash(float t) {
        return (float) Math.sin(Math.PI * t) * SNAP_SQUASH;
    }

    private CoreMotion() {}
}
