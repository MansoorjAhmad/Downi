package com.omnidownloader.app.downicore;

/**
 * V3.2 Downi Core — the look of a state, as pure numbers.
 *
 * A pure function of (state, transition t, progress): the view stays dumb and the design
 * stays in one table. Every value traces to a design sheet —
 *   sheet 1/5  the Core itself: glass disc, glow, mark, "subtle and calm" at rest
 *   sheet 4    perimeter = progress, PAUSED/RESUMING/COMPLETED/FAILED moods, no percent text
 *   sheet 6    timings, easing, the error and confirmation pulses
 * Deliberately free of Android types, so it is unit-testable by plain JVM code.
 */
public final class CoreLook {

    /** Immutable-ish description of what the Core should look like right now. */
    public static final class Look {
        public float halo;       // outer glow alpha 0..1
        public float rim;        // rim / perimeter brightness 0..1
        public float mark;       // mark alpha 0..1
        public float scale;      // press / settle scale (1 = rest)
        public float perimeter;  // 0..1 of the circle drawn as progress (sheet 4)
        public float track;      // alpha of the dim "not yet" track
        public float error;      // 0..1 rose error tint
        public float detected;   // 0..1 how awake the Core is
        public boolean bars;     // PAUSED bars (two small bars — never text)
        public float barAlpha;
    }

    /**
     * @param state    one of {@link CoreStates}
     * @param t        0..1 transition progress for the state's own animation (0 when settled)
     * @param progress 0..1 job progress, only meaningful in the progress-carrying states
     */
    public static Look of(String state, float t, float progress) {
        Look L = new Look();
        float p = progress < 0f ? 0f : (progress > 1f ? 1f : progress);
        float e = CoreMotion.easeInOut(t);
        float pulse = CoreMotion.pulse(t);

        // Resting values — sheet 5/§8: idle is almost dormant. Downi is HERE, quietly.
        L.halo = 0.20f;
        L.rim = 0.55f;
        L.mark = 0.85f;
        L.scale = 1f;
        L.track = 0.07f;
        L.perimeter = 0f;

        if (CoreStates.WAKE.equals(state)) {
            // sheet 6 #1 / §21: energy begins to rise -> perimeter expands -> settles to detected.
            L.detected = e;
            L.halo = 0.20f + 0.65f * e;
            L.rim = 0.55f + 0.45f * e;
            L.track = 0.07f + 0.09f * e;
            L.perimeter = e;
        } else if (CoreStates.DETECTED.equals(state)) {
            // §9: the wake must READ as "Downi found something" without text — visibly awake,
            // unmistakably brighter than idle, still silent.
            L.detected = 1f;
            L.halo = 0.85f;
            L.rim = 1.00f;
            L.track = 0.16f;
            L.perimeter = 0f;
        } else if (CoreStates.PRESSED.equals(state)) {
            // sheet 6 #2: compress inward in 0.1 s, brighter response.
            L.detected = 1f;
            L.scale = 1f - 0.10f * e;
            L.halo = 0.45f + 0.25f * e;
            L.rim = 1.00f;
            L.mark = 1.00f;
            L.track = 0.16f;
        } else if (CoreStates.DRAGGING.equals(state)) {
            L.detected = 1f;
            L.halo = 0.60f;
            L.rim = 1.00f;
            L.mark = 1.00f;
            L.track = 0.16f;
        } else if (CoreStates.SNAPPED.equals(state)) {
            // sheet 5 #4: snaps gently, still movable — a settle, not a lock.
            L.detected = 1f;
            L.halo = 0.55f + 0.15f * pulse;
            L.rim = 1.00f;
            L.scale = 1f + 0.05f * pulse;
            L.track = 0.16f;
        } else if (CoreStates.PROGRESS.equals(state)) {
            L.detected = 1f;
            L.perimeter = p;
            L.track = 0.18f;
            L.rim = 1.00f;
            L.mark = 1.00f;
            L.halo = 0.50f + 0.12f * p + 0.10f * pulse;
        } else if (CoreStates.PAUSED.equals(state)) {
            // sheet 4: "download paused — the energy freezes gently."
            L.detected = 1f;
            L.perimeter = p;
            L.track = 0.18f;
            L.rim = 0.55f;
            L.halo = 0.34f;
            L.mark = 0.60f;
            L.bars = true;
            L.barAlpha = 0.90f;
        } else if (CoreStates.RESUMING.equals(state)) {
            // sheet 4: "the flow returns smoothly."
            L.detected = 1f;
            L.perimeter = p;
            L.track = 0.18f;
            L.rim = 0.55f + 0.45f * e;
            L.halo = 0.34f + 0.21f * e;
            L.mark = 0.60f + 0.40f * e;
            L.bars = true;
            L.barAlpha = 0.90f * (1f - e);
        } else if (CoreStates.COMPLETING.equals(state)) {
            // sheet 6 #8: a subtle confirmation pulse, then a calm return.
            L.detected = 1f;
            L.perimeter = 1f;
            L.track = 0.06f;
            L.rim = 1.00f;
            L.mark = 1.00f;
            L.halo = 0.50f + 0.35f * pulse;
            L.scale = 1f + 0.05f * pulse;
        } else if (CoreStates.COMPLETE.equals(state)) {
            L.detected = 1f;
            L.perimeter = 1f;
            L.track = 0.06f;
            L.rim = 1.00f;
            L.mark = 1.00f;
            L.halo = 0.55f;
        } else if (CoreStates.FAILED.equals(state)) {
            // sheet 6 #9: restrained error state with a soft retry-ready feel.
            L.detected = 1f;
            L.perimeter = p;
            L.track = 0.12f;
            L.error = 1f - 0.35f * e;
            L.rim = 0.65f;
            L.mark = 0.70f;
            L.halo = 0.30f + 0.25f * pulse;
        }
        return L;
    }

    // ---------- the mark's geometry (sheet 2's asset, sheets 3/4's size) ----------
    // The mark itself is lifted pixel-exact from sheet 2 by `tools/core_mark_from_sheet.py`; how
    // large it sits inside the disc is *measured* off sheets 3/4. That measurement lives here, in
    // the pure table, rather than in a comment inside the view: the owner's complaint was a wrong
    // mark, and a wrong size is the same kind of silent failure. `CoreMarkSpecTest` asserts every
    // number below against the shipping asset.

    /** Where the mark stands on the sheets: glyph height / disc diameter. Sheet 3 measures 0.528
     *  (idle) and 0.531 (detected) with `tools/core_mark_measure.py`. */
    public static final float SHEET_MARK_DISC_RATIO = 0.53f;

    /** The shipping asset is a square tile in which the visible glyph fills this much of the height
     *  and width — measured on the 512 px master (`alpha > 8`: 458 x 357 px), not estimated. */
    public static final float MARK_TILE_HEIGHT = 458f / 512f;   // 0.8945
    public static final float MARK_TILE_WIDTH = 357f / 512f;    // 0.6973

    /** The Core's disc geometry in dp, mirroring {@link CoreHost}: the window keeps a glow inset,
     *  the visible pebble is a fraction of the window (§6 small visual, §19 full touch target),
     *  and the energy perimeter sits inside the disc by the rim inset. */
    public static final float DISC_INSET_DP = 6f;
    public static final float VISUAL_IN_WINDOW = 0.80f;
    public static final float RIM_INSET_DP = 1.6f;

    /** The sizes that ship (sheet 5's touch-area range) — the sizes one tile scale has to serve. */
    public static final int[] SIZES_DP = {48, 56, 64};

    /** What a given tile scale actually draws at {@code sizeDp}: mark height / disc diameter. */
    public static float markDiscRatio(float tileScale, int sizeDp) {
        float r = (sizeDp / 2f - DISC_INSET_DP) * VISUAL_IN_WINDOW;
        float rIn = r - RIM_INSET_DP;
        return tileScale * MARK_TILE_HEIGHT * (rIn / r);
    }

    /**
     * The tile scale that keeps the drawn mark closest to {@code discRatio} at *every* shipping
     * size — the scale with the smallest worst-case error, on the same two-decimal grid the gate
     * tunes by hand and inside the view's clamp (0.30..1.20).
     *
     * Two decimals is deliberate: one scale ships for 48, 56 and 64 dp, and the dp insets are a
     * larger share of a small Core, so an exact solve at one size would be visibly off at another.
     * Solving for the worst case instead keeps every size within ~0.8% of the sheets' ratio.
     */
    public static float markScaleFor(float discRatio) {
        float best = 0.30f;
        float bestWorst = Float.MAX_VALUE;
        for (int i = 30; i <= 120; i++) {
            float s = i / 100f;
            float worst = 0f;
            for (int dp : SIZES_DP) {
                float e = Math.abs(markDiscRatio(s, dp) - discRatio);
                if (e > worst) worst = e;
            }
            if (worst < bestWorst) {
                bestWorst = worst;
                best = s;
            }
        }
        return best;
    }

    /** The shipping tile scale — the sheets' own ratio, best-fitted once. 0.63, and never a guess. */
    public static final float MARK_SCALE = markScaleFor(SHEET_MARK_DISC_RATIO);

    /** Worst-case gap between the drawn mark and the sheets' ratio, over the shipping sizes. */
    public static float markWorstCaseError(float tileScale) {
        float worst = 0f;
        for (int dp : SIZES_DP) {
            float e = Math.abs(markDiscRatio(tileScale, dp) - SHEET_MARK_DISC_RATIO);
            if (e > worst) worst = e;
        }
        return worst;
    }

    private CoreLook() {}
}
