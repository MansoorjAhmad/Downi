package com.omnidownloader.app.downicore;

/**
 * V3.2 Downi Core — the state vocabulary (owner-approved plan 2026-09-25, `V3.2_PLAN.md`).
 *
 * One list, one spelling: the debug channel, the host view and (in later phases) the detection
 * and job bindings all speak these names, so a state can never be "armed" in one file and
 * "detected" in another.
 *
 * Hard rules encoded here:
 *  - progress is a 0..1 float shown on the perimeter; there is NO percent-text state
 *    (invariant 2 — sheet 4 is explicit: "No percent. No text.");
 *  - DETECTED is the only "a video is here" state, and it is set from evidence, never assumed
 *    (invariant 6, the honesty rule);
 *  - PAUSED/RESUMING exist visually from Phase A but ship only behind the D3 gate
 *    (`.part` byte-continuation proven on device) — otherwise RETRY is the recovery path.
 */
public final class CoreStates {
    /** Calm, static resting state — nothing animates while idle (cell K-A5). */
    public static final String IDLE = "idle";
    /** Transient: energy rises -> perimeter expands -> settles (sheet 6, ~0.6 s). */
    public static final String WAKE = "wake";
    /** Settled "a video is here" look: brighter perimeter, mark at full presence. */
    public static final String DETECTED = "detected";
    /** Finger down: 0.1 s compression inward (sheet 6). */
    public static final String PRESSED = "pressed";
    /** Finger moving: the Core follows the hand, no visual detune. */
    public static final String DRAGGING = "dragging";
    /** Released near an edge and gently attracted into place (~0.3 s; never a forced snap). */
    public static final String SNAPPED = "snapped";
    /** A job is running: the perimeter itself is the progress indicator (sheet 4). */
    public static final String PROGRESS = "progress";
    /** Frozen mid-progress. Ships only if the D3 byte-continuation test passes. */
    public static final String PAUSED = "paused";
    /** Transient back from PAUSED: the flow returns smoothly (~0.4 s). */
    public static final String RESUMING = "resuming";
    /** Transient: confirmation pulse, then a calm return (~0.4 s). */
    public static final String COMPLETING = "completing";
    /** Full perimeter, held calm after the success pulse. */
    public static final String COMPLETE = "complete";
    /** Restrained error state with a soft retry-ready feel (0.3 s pulse, sheet 6). */
    public static final String FAILED = "failed";

    /** Every state the debug channel accepts, in the order the design sheet shows them. */
    public static final String[] ALL = {
            IDLE, WAKE, DETECTED, PRESSED, DRAGGING, SNAPPED,
            PROGRESS, PAUSED, RESUMING, COMPLETING, COMPLETE, FAILED
    };

    /** True when the perimeter carries a real download's progress in this state. */
    public static boolean showsProgress(String s) {
        return PROGRESS.equals(s) || PAUSED.equals(s) || RESUMING.equals(s)
                || COMPLETING.equals(s) || COMPLETE.equals(s);
    }

    /** True when the state is a short transition the host has to animate into the next one. */
    public static boolean isTransient(String s) {
        return WAKE.equals(s) || PRESSED.equals(s) || SNAPPED.equals(s)
                || RESUMING.equals(s) || COMPLETING.equals(s);
    }

    public static boolean isKnown(String s) {
        if (s == null) return false;
        for (String v : ALL) if (v.equals(s)) return true;
        return false;
    }

    /** Pipe-joined list for the log line / command help. */
    public static String list() {
        StringBuilder b = new StringBuilder();
        for (String v : ALL) {
            if (b.length() > 0) b.append('|');
            b.append(v);
        }
        return b.toString();
    }

    private CoreStates() {}
}
