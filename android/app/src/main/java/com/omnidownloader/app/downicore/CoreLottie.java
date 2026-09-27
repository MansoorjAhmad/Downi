package com.omnidownloader.app.downicore;

/**
 * Fetcher 2.0 — which authored state animation plays (V3.3_PLAN.md §3), and how it is driven.
 *
 * The ten bodymovin files in `assets/core/` were generated from the owner's own sheet art by
 * `tools/core_lottie_build.py`; this table is the only place that maps a Core *state* to one of
 * them, so the view never contains a file name and a state can never be wired to two files. It is
 * plain Java (no android imports) so the JVM tests can pin it against the shipped JSON:
 *
 *   · {@link #assetFor} — the two states that cross-fade rose/blue-grey art pick their file from
 *     the same `unsupported` flag {@link CoreTint} uses, so the baked look and the runtime look
 *     cannot disagree about which mood FAILED is in;
 *   · {@link #isScrubbed} — `core_progress` is *never* played: the view scrubs it with the real
 *     download fraction (sheet 4: the perimeter IS the progress, no timer, no percent text);
 *   · {@link #loops} — only the READY look loops. Everything else plays once and holds its last
 *     frame, and the states with no authored file keep the static art the M1 gate verified, so
 *     idle still never animates (cell K-A5).
 *
 * Two of the ten shipped files are deliberately NOT wired, and that is an owner decision rather
 * than an oversight (see {@link #DORMANT} / {@link #RETRY}); `CoreLottieWiringTest` fails if a
 * file is ever added to the assets folder and then silently ignored.
 */
public final class CoreLottie {

    /** Folder inside the APK's assets (see app/src/main/assets/core). */
    public static final String DIR = "core/";

    /** The file whose ring is the real download fraction (V3.3 §3: "driven by setProgress"). */
    public static final String PROGRESS_FILE = "core_progress";

    /** The only looping look. READY means "a video is here and waiting for a tap". */
    public static final String READY_FILE = "core_idle_ready";

    /**
     * Authored but unwired: sheet C2's lowest energy. It would replace the idle look the M1 device
     * gate verified (two bars instead of the chevron) *and* it breathes on a 1.5 s loop, which cell
     * K-A5 forbids ("nothing animates while idle"). Owner's call whether idle becomes this.
     */
    public static final String DORMANT = "core_dormant";

    /**
     * C6's recovery file: rose -> teal with the C3 press/rebound. Wired to the RETRY state, which the
     * tap that retries a failed grab enters — see {@link CoreStates#RETRY}.
     */
    public static final String RETRY = "core_retry";

    /** Every file `tools/core_lottie_build.py` ships, so the table above can be checked for gaps. */
    public static final String[] SHIPPED = {
            DORMANT, "core_wake", READY_FILE, "core_press", PROGRESS_FILE,
            "core_pause", "core_complete", "core_failure", RETRY, "core_unsupported"};

    /**
     * The authored file for this state, or null when the state has none and the static art (the
     * look the M1 gate measured) should draw instead.
     *
     * RESOLVING/DRAGGING/SNAPPED/RESUMING/COMPLETING are movement, not looks: the Core's own
     * geometry carries them (rim orbit, finger-follow, gel squash, the pause->progress return), and
     * they cross *between* authored files rather than needing one of their own.
     */
    public static String assetFor(String state, boolean unsupported) {
        if (CoreStates.FAILED.equals(state)) return unsupported ? "core_unsupported" : "core_failure";
        if (CoreStates.WAKE.equals(state)) return "core_wake";
        if (CoreStates.DETECTED.equals(state)) return READY_FILE;
        if (CoreStates.PRESSED.equals(state)) return "core_press";
        if (CoreStates.PROGRESS.equals(state)) return PROGRESS_FILE;
        if (CoreStates.PAUSED.equals(state)) return "core_pause";
        if (CoreStates.COMPLETE.equals(state)) return "core_complete";
        if (CoreStates.RETRY.equals(state)) return RETRY;         // C6: rose -> teal, re-resolving
        return null;
    }

    /** The assets key LottieCompositionFactory wants. */
    public static String pathFor(String asset) {
        return DIR + asset + ".json";
    }

    /** True when the view drives this file with real progress instead of playing it. */
    public static boolean isScrubbed(String asset) {
        return PROGRESS_FILE.equals(asset);
    }

    /** True when this file animates on its own, forever (READY only). */
    public static boolean loops(String asset) {
        return READY_FILE.equals(asset);
    }

    /**
     * True when this file's disc sits at the *paused* tile's ratio. The two sheet tiles pad the
     * canvas differently, and that padding is why {@link CoreLook#ART_TILE_RATIO_PAUSED} exists —
     * a comp built on the two-bar tile must be scaled by its own ratio or its disc lands small.
     */
    public static boolean usesPausedTile(String asset) {
        return "core_pause".equals(asset) || DORMANT.equals(asset);
    }

    /** True when some state is wired to this file (the wiring test's completeness check). */
    public static boolean isWired(String asset) {
        for (String s : CoreStates.ALL) {
            if (asset.equals(assetFor(s, false)) || asset.equals(assetFor(s, true))) return true;
        }
        return false;
    }

    private CoreLottie() {}
}
