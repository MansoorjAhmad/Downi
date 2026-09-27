package com.omnidownloader.app.downicore;

/**
 * Fetcher 2.0 — the Core's energy colour, as a pure matrix (no Android types here, so it is
 * unit-tested rather than eyeballed).
 *
 * The Core art (sheet C1) is authored in the sheet's own cyan. Two states must not read as cyan,
 * and the sheets are explicit about how far they move (sheet C6, §1 of the v3.3 plan):
 *
 *   FAILED       energy shifts to a MUTED ROSE — not red, no alarm, "quiet, not broken".
 *   UNSUPPORTED  a muted blue/grey — never red anywhere.
 *
 * This file holds only the maths: {@link #stateOf} names the transition and {@link #matrixFor}
 * returns the 4x5 colour matrix Android's ColorMatrixColorFilter wants. Both are pure, so
 * CoreTintTest pins the endpoints (as-designed at 0, the sheet's target at 1) without a device.
 *
 * The shift is authored as HUE ROTATION + SATURATION REMOVAL rather than a flat tint: the orb's
 * dark glass body must stay obsidian, only its light changes — which is exactly what rotating the
 * hue of a cyan object and desaturating it does, and what flipping every channel toward red does
 * not.
 */
public final class CoreTint {

    /** As designed: the sheet's own cyan, untouched. */
    public static final int AS_DESIGNED = 0;
    /** FAILED (sheet C6): muted rose. */
    public static final int ROSE = 1;
    /** UNSUPPORTED (sheet C6): muted blue/grey. */
    public static final int NEUTRAL = 2;

    /** How far the rose shift goes: cyan (187°) -> muted rose (350°) is +163° of hue. */
    static final float ROSE_HUE_DEG = 163f;
    /** Rose keeps most of its colour but loses the glow's punch — "muted", per sheet C6. */
    static final float ROSE_SATURATION = 0.82f;
    /** The unsupported grey-blue: colour mostly removed, a little kept so it never reads dead. */
    static final float NEUTRAL_HUE_DEG = 18f;
    static final float NEUTRAL_SATURATION = 0.22f;

    /** Which tint a state's mood asks for. `mood` is CoreHost's own 0..3 (sheet C6). */
    public static int stateOf(int mood, float error) {
        if (error <= 0.25f) return AS_DESIGNED;
        return mood == 3 ? NEUTRAL : ROSE;
    }

    /** The 4x5 matrix for a tint, blended from "as designed" by {@code amount} (0..1). */
    public static float[] matrixFor(int tint, float amount) {
        float a = amount < 0f ? 0f : (amount > 1f ? 1f : amount);
        float[] identity = new float[]{
                1f, 0f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f, 0f,
                0f, 0f, 1f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f};
        if (tint == AS_DESIGNED || a <= 0f) return identity;
        float hue = tint == ROSE ? ROSE_HUE_DEG : NEUTRAL_HUE_DEG;
        float sat = tint == ROSE ? ROSE_SATURATION : NEUTRAL_SATURATION;
        float[] target = hueRotateSaturate(hue, sat);
        float[] out = new float[20];
        for (int i = 0; i < 20; i++) {
            out[i] = identity[i] + (target[i] - identity[i]) * a;
        }
        return out;
    }

    /**
     * Hue rotation composed with saturation scaling — the standard luminance-preserving form.
     * Pure arithmetic; the luminance weights are Rec. 709, the same ones Android's ColorMatrix
     * uses for its own saturation helper.
     */
    static float[] hueRotateSaturate(float degrees, float saturation) {
        double rad = Math.toRadians(degrees);
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);
        double lr = 0.213, lg = 0.715, lb = 0.072;

        double r00 = lr + cos * (1 - lr) - sin * lr;
        double r01 = lg - cos * lg - sin * lg;
        double r02 = lb - cos * lb + sin * (1 - lb);
        double r10 = lr - cos * lr + sin * 0.143;
        double r11 = lg + cos * (1 - lg) + sin * 0.140;
        double r12 = lb - cos * lb - sin * 0.283;
        double r20 = lr - cos * lr - sin * (1 - lr);
        double r21 = lg - cos * lg + sin * lg;
        double r22 = lb + cos * (1 - lb) + sin * lb;

        // saturation on top of the rotation (lerp each row toward its Rec.709 luminance row)
        double s = saturation;
        double sr = (1 - s) * lr, sg = (1 - s) * lg, sb = (1 - s) * lb;
        return new float[]{
                (float) (sr + s * r00), (float) (sg + s * r01), (float) (sb + s * r02), 0f, 0f,
                (float) (sr + s * r10), (float) (sg + s * r11), (float) (sb + s * r12), 0f, 0f,
                (float) (sr + s * r20), (float) (sg + s * r21), (float) (sb + s * r22), 0f, 0f,
                0f, 0f, 0f, 1f, 0f};
    }

    private CoreTint() {}
}
