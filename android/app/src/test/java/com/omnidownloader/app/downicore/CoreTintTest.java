package com.omnidownloader.app.downicore;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The Core's state colour, pinned on the JVM (sheet C6, plan §1).
 *
 * Two failure modes this file exists to catch, both invisible in a code review:
 *   · a tint that stops being "as designed" at rest — the Core must be the SHEET's cyan whenever
 *     nothing is wrong, so `error == 0` has to be the identity matrix, exactly;
 *   · a "muted rose" that is really a red alarm — sheet C6 is explicit that failure is quiet and
 *     that the unsupported state must contain no red at all.
 */
public class CoreTintTest {

    private static final float EPS = 0.0005f;

    private static int[] apply(float[] m, int r, int g, int b) {
        return new int[]{
                clamp((int) Math.round(m[0] * r + m[1] * g + m[2] * b + m[4])),
                clamp((int) Math.round(m[5] * r + m[6] * g + m[7] * b + m[9])),
                clamp((int) Math.round(m[10] * r + m[11] * g + m[12] * b + m[14]))};
    }

    private static int clamp(int v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }

    private static int chroma(int[] rgb) {
        int max = Math.max(rgb[0], Math.max(rgb[1], rgb[2]));
        int min = Math.min(rgb[0], Math.min(rgb[1], rgb[2]));
        return max - min;
    }

    @Test public void asDesignedIsTheIdentity() {
        float[] m = CoreTint.matrixFor(CoreTint.AS_DESIGNED, 1f);
        float[] expect = new float[]{1, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 1, 0};
        for (int i = 0; i < 20; i++) {
            assertEquals("identity element " + i, expect[i], m[i], EPS);
        }
    }

    /** At rest — and at the very start of any transition — the sheet's own cyan is untouched. */
    @Test public void zeroAmountIsAlwaysTheIdentity() {
        for (int tint : new int[]{CoreTint.ROSE, CoreTint.NEUTRAL}) {
            float[] m = CoreTint.matrixFor(tint, 0f);
            assertEquals("r gain at tint " + tint, 1f, m[0], EPS);
            assertEquals("g gain at tint " + tint, 1f, m[6], EPS);
            assertEquals("b gain at tint " + tint, 1f, m[12], EPS);
            assertEquals("cross term at tint " + tint, 0f, m[1], EPS);
        }
    }

    /** Which tint a mood asks for, including the "still cyan below the error threshold" rule. */
    @Test public void moodPicksTheRoseOrTheNeutral() {
        assertEquals(CoreTint.AS_DESIGNED, CoreTint.stateOf(2, 0.10f));
        assertEquals(CoreTint.ROSE, CoreTint.stateOf(2, 1.00f));
        assertEquals(CoreTint.ROSE, CoreTint.stateOf(1, 0.60f));
        assertEquals(CoreTint.NEUTRAL, CoreTint.stateOf(3, 1.00f));
        assertEquals(CoreTint.AS_DESIGNED, CoreTint.stateOf(3, 0.00f));
    }

    /** Every row still sums to 1: the shift recolours the Core without brightening or darkening it. */
    @Test public void tintPreservesWhite() {
        for (int tint : new int[]{CoreTint.ROSE, CoreTint.NEUTRAL}) {
            float[] m = CoreTint.matrixFor(tint, 1f);
            for (int row = 0; row < 3; row++) {
                float sum = m[row * 5] + m[row * 5 + 1] + m[row * 5 + 2];
                assertEquals("white must survive tint " + tint + " row " + row, 1f, sum, 1e-4f);
            }
            assertEquals("alpha stays", 1f, m[18], EPS);
            assertEquals("no alpha bleed", 0f, m[15] + m[16] + m[17] + m[19], EPS);
        }
    }

    /** FAILED moves the sheet's cyan onto the rose side of the wheel — not red, not orange. */
    @Test public void roseIsRose() {
        int[] out = apply(CoreTint.matrixFor(CoreTint.ROSE, 1f), 34, 211, 238);   // the sheet's cyan
        assertTrue("rose must be red-dominant (was " + out[0] + "," + out[1] + "," + out[2] + ")",
                out[0] > out[1] + 40);
        assertTrue("rose keeps blue above green (was " + out[0] + "," + out[1] + "," + out[2] + ")",
                out[2] > out[1]);
        assertTrue("and it stays muted, never a pure red (was " + out[0] + ")", out[1] > 40);
    }

    /** UNSUPPORTED drains the colour (sheet C6: muted blue/grey) without going red. */
    @Test public void unsupportedIsMuted() {
        int[] before = new int[]{34, 211, 238};
        int[] out = apply(CoreTint.matrixFor(CoreTint.NEUTRAL, 1f), before[0], before[1], before[2]);
        assertTrue("the chroma must collapse (was " + chroma(before) + ", now " + chroma(out) + ")",
                chroma(out) < chroma(before) / 2);
        assertTrue("no red anywhere (was " + out[0] + "," + out[1] + "," + out[2] + ")",
                out[0] <= Math.max(out[1], out[2]) + 8);
    }

    /** The transition itself: half-way is half-way, element for element. */
    @Test public void amountBlendsToTheTarget() {
        float[] full = CoreTint.matrixFor(CoreTint.ROSE, 1f);
        float[] half = CoreTint.matrixFor(CoreTint.ROSE, 0.5f);
        assertEquals("r gain", (1f + full[0]) / 2f, half[0], EPS);
        assertEquals("cross term", full[1] / 2f, half[1], EPS);
        assertEquals("amount is clamped above 1", full[0], CoreTint.matrixFor(CoreTint.ROSE, 4f)[0], EPS);
        assertEquals("amount is clamped below 0", 1f, CoreTint.matrixFor(CoreTint.ROSE, -2f)[0], EPS);
    }
}
