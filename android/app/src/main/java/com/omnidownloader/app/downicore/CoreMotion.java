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
    public static final long WAKE_MS = 600;       // rise 0.2 + expand 0.2 + settle 0.2
    public static final long WAKE_STEP_MS = 200;
    public static final long DRAG_MS = 100;       // follow the finger, no lag beyond this
    public static final long SNAP_MS = 300;       // edge magnetism — subtle, never forced
    public static final long PROGRESS_MS = 400;   // a progress step eases in over this
    public static final long PAUSE_MS = 400;      // the energy freezes gently
    public static final long COMPLETE_MS = 400;   // success pulse, then a calm return
    public static final long ERROR_MS = 300;      // restrained error pulse

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

    private CoreMotion() {}
}
