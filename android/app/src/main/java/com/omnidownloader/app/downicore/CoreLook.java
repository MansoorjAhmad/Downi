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
        public float markSink;   // 0..1 how deep the mark sits pressed into the gel
        public boolean bars;     // PAUSED bars (two small bars — never text)
        public float barAlpha;
    }

    // ---------- sheet C2's wake, per channel, at the five beats ----------
    // awareness · energy builds · the membrane activates · the core brightens · settled
    // The beat FRACTIONS come from CoreMotion.WAKE_BEATS_MS; every channel's settled value is
    // exactly the DETECTED value it lands on, so the wake can never drift from the settled look.

    /** READY (a tap will land): the full wake. */
    static final float[] WAKE_READY_DETECTED = {0.35f, 0.80f, 0.90f, 0.95f, 1.00f};
    static final float[] WAKE_READY_HALO = {0.22f, 0.35f, 0.55f, 0.75f, 0.85f};
    static final float[] WAKE_READY_RIM = {0.62f, 0.74f, 0.86f, 0.95f, 1.00f};
    static final float[] WAKE_READY_TRACK = {0.10f, 0.125f, 0.145f, 0.16f, 0.16f};

    /** AWARE (watching, routes degraded): the same five beats, quieter ceilings (~55%). */
    static final float[] WAKE_AWARE_DETECTED = {0.20f, 0.35f, 0.45f, 0.50f, 0.55f};
    static final float[] WAKE_AWARE_HALO = {0.21f, 0.23f, 0.26f, 0.28f, 0.30f};
    static final float[] WAKE_AWARE_RIM = {0.58f, 0.62f, 0.68f, 0.73f, 0.78f};
    static final float[] WAKE_AWARE_MARK = {0.84f, 0.83f, 0.82f, 0.81f, 0.80f};
    static final float[] WAKE_AWARE_TRACK = {0.08f, 0.095f, 0.11f, 0.12f, 0.12f};

    /**
     * @param state    one of {@link CoreStates}
     * @param t        0..1 transition progress for the state's own animation (0 when settled)
     * @param progress 0..1 job progress, only meaningful in the progress-carrying states
     */
    public static Look of(String state, float t, float progress) {
        return of(state, t, progress, true);
    }

    /**
     * @param readyGrade true = the full wake (a tap will land); false = the AWARE grade
     *                   (watching, but routes degraded) — sheet C2's two honest wakes
     */
    public static Look of(String state, float t, float progress, boolean readyGrade) {
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
            // Sheet C2's wake storyboard, STAGED (ruling R1, 2026-09-26): awareness at 100 ms,
            // energy builds at 200, the membrane activates at 300, the core brightens at 400,
            // settled at 600. Energy still gathers IN THE RIM — the old transient full ring stays
            // gone; the surge reads as the membrane brightening, never as a circle appearing.
            if (readyGrade) {
                L.detected = CoreMotion.wakeStage(0f, WAKE_READY_DETECTED, t);
                L.halo = CoreMotion.wakeStage(0.20f, WAKE_READY_HALO, t);
                L.rim = CoreMotion.wakeStage(0.55f, WAKE_READY_RIM, t);
                L.track = CoreMotion.wakeStage(0.07f, WAKE_READY_TRACK, t);
            } else {
                // the AWARE wake: the same five beats, quieter ceilings (~55% energy, sheet C2)
                L.detected = CoreMotion.wakeStage(0f, WAKE_AWARE_DETECTED, t);
                L.halo = CoreMotion.wakeStage(0.20f, WAKE_AWARE_HALO, t);
                L.rim = CoreMotion.wakeStage(0.55f, WAKE_AWARE_RIM, t);
                L.mark = CoreMotion.wakeStage(0.85f, WAKE_AWARE_MARK, t);
                L.track = CoreMotion.wakeStage(0.07f, WAKE_AWARE_TRACK, t);
            }
            L.perimeter = 0f;
        } else if (CoreStates.DETECTED.equals(state)) {
            L.detected = 1f;
            L.perimeter = 0f;
            if (readyGrade) {
                // READY: "tap me and it's yours" — the approved full wake.
                L.halo = 0.85f;
                L.rim = 1.00f;
                L.mark = 0.85f;
                L.track = 0.16f;
            } else {
                // AWARE: "I see what you're watching" — ~55% energy on every channel,
                // unmistakably the same object (sheet C2). Halo 0.30 == 55% alpha after
                // the host's /0.55 normalization.
                L.halo = 0.30f;
                L.rim = 0.78f;
                L.mark = 0.80f;
                L.track = 0.12f;
                L.detected = 0.55f;
            }
        } else if (CoreStates.RESOLVING.equals(state)) {
            // §M-1 + sheet C4 ENGAGE: the chevron sinks slightly, internal energy activates,
            // and the orbit light appears at the top (the host draws it at the step position).
            L.detected = 1f;
            L.halo = 0.55f;
            L.rim = 1.00f;
            L.mark = 1.00f;
            L.markSink = 0.5f;
            L.track = 0.16f;
        } else if (CoreStates.PRESSED.equals(state)) {
            // sheet 6 #2: compress inward in 0.1 s, brighter response; the mark sinks into the gel.
            L.detected = 1f;
            L.scale = 1f - 0.10f * e;
            L.halo = 0.45f + 0.25f * e;
            L.rim = 1.00f;
            L.mark = 1.00f;
            L.markSink = e;
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
            // §M-1 THE ENERGY LAW: the mark LENDS its light to the perimeter — the identity dims
            // while the download lives, and the ring speaks for it. Returned at completion.
            L.detected = 1f;
            L.perimeter = p;
            L.track = 0.18f;
            L.rim = 1.00f;
            L.mark = 0.60f;
            L.halo = 0.50f + 0.12f * p + 0.10f * pulse;
        } else if (CoreStates.PAUSED.equals(state)) {
            // sheet 4: "download paused — the energy freezes gently." R-C ruling (owner,
            // sheets v2 1 & 5): the bars REPLACE the mark while paused — no chevron, fully
            // frozen, no breath (sheet C5: "frozen state — no motion, no change").
            L.detected = 1f;
            L.perimeter = p;
            L.track = 0.18f;
            L.rim = 0.55f;
            L.halo = 0.34f;
            L.mark = 0f;
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
            // sheet 6 #8 / §M-1: the light RETURNS to the mark — the ring collapses inward as the
            // identity blooms back to full. A subtle confirmation, then a calm hold.
            L.detected = 1f;
            L.perimeter = 1f - 0.65f * e;
            L.track = 0.06f;
            L.rim = 1.00f;
            L.mark = 0.60f + 0.40f * e;
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

    /**
     * The RETURN fade (sheet C5 RETURN, ruling R6): COMPLETE/FAILED -> IDLE is a subtle fade, not a
     * cut. {@code from} is the look that was being held; t=0 returns it unchanged, t=1 has fully
     * arrived at the resting look. Pure, so the envelope is unit-tested rather than eyeballed.
     */
    public static Look returnFade(Look from, float t) {
        Look idle = of(CoreStates.IDLE, 0f, 0f);
        float k = CoreMotion.easeInOut(t);
        Look L = new Look();
        L.halo = blend(from.halo, idle.halo, k);
        L.rim = blend(from.rim, idle.rim, k);
        L.mark = blend(from.mark, idle.mark, k);
        L.scale = blend(from.scale, idle.scale, k);
        L.perimeter = blend(from.perimeter, idle.perimeter, k);
        L.track = blend(from.track, idle.track, k);
        L.error = blend(from.error, idle.error, k);
        L.detected = blend(from.detected, idle.detected, k);
        L.markSink = blend(from.markSink, idle.markSink, k);
        L.barAlpha = blend(from.barAlpha, idle.barAlpha, k);
        L.bars = from.bars && t < 0.5f;      // the paused bars leave early in the return
        return L;
    }

    private static float blend(float a, float b, float k) {
        return a + (b - a) * k;
    }

    /**
     * The unsupported (neutral) mood belongs to FAILED alone (sheet C6). The flag carries over
     * into the NEXT state only while that state IS failed; any other state clears it, so a later
     * failure renders rose unless it is explicitly marked unsupported again. Pure + tested.
     */
    public static boolean unsupportedCarriesOver(String nextState, boolean current) {
        return current && CoreStates.FAILED.equals(nextState);
    }

    // ---------- the Core's art (Fetcher 2.0, sheet C1) ----------
    // The Core's material is authored art, so its geometry has to be *measured* rather than
    // derived like the old procedural disc (the owner's complaint was exactly this class of silent
    // failure — a correct-looking number drawing the wrong thing). `tools/core_sheet_extract.py`
    // prints these two numbers from the shipping PNGs and CoreArtSpecTest guards them on the JVM.

    /** The Core art's disc diameter / tile side (`core_orb.png`, the sheet's hero render). */
    public static final float ART_TILE_RATIO = 0.8606f;

    /** The same for the paused art (`core_orb_paused.png`). Smaller because C1's state row keeps
     *  more transparent glow padding around the object than the hero crop does. */
    public static final float ART_TILE_RATIO_PAUSED = 0.7799f;

    /**
     * The tile side in dp that puts the art's disc exactly where the Core's disc is.
     *
     * The disc may NOT move: `r` is the same one {@link CoreHost} and the sheets before it used
     * (§6 small visual, §19 full touch target), so the art is scaled up by 1/ratio around it —
     * the tile's transparent margin is the glow's room, not part of the Core.
     */
    public static float artTileSideDp(int sizeDp, float tileRatio) {
        float r = (sizeDp / 2f - DISC_INSET_DP) * VISUAL_IN_WINDOW;
        return 2f * r / tileRatio;
    }

    /** The art for a paused Core. Per sheet C1 the pause recedes the energy; the TILE RATIO of the
     *  two assets differs, so which art decides which ratio. */
    public static float artTileRatio(boolean paused) {
        return paused ? ART_TILE_RATIO_PAUSED : ART_TILE_RATIO;
    }


    // ---------- the mark's geometry (sheet 2's asset, sheets 3/4's size) ----------
    // NOT DRAWN BY FETCHER 2.0's VIEW ANY MORE: the chevron ships inside the Core art. Everything
    // below stays because the debug gate still drives it (`mark <scale>` on the core channel) and
    // because the Lottie stage splits the chevron back out as its own layer, where it will need
    // the same measured size. `downi_core_mark*.png` is therefore kept as an asset, unused.
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
