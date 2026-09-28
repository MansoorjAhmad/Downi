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
    /** The tap fired and the resolver is working (sheet language: the rim orbit). Hold state. */
    public static final String RESOLVING = "resolving";
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
    /**
     * C6's recovery, played on the tap that retries a failed grab: the rose reads back to teal
     * THROUGH the C3 press/rebound (sheet C6: "tap-to-retry does a press/rebound and transitions
     * rose → teal as it re-resolves"), then settles into RESOLVING — the resolver's own orbit.
     * A transient, so the arbiter's next push cannot cut the acknowledgement short.
     */
    public static final String RETRY = "retry";

    /** Every state the debug channel accepts, in the order the design sheet shows them. */
    public static final String[] ALL = {
            IDLE, WAKE, DETECTED, RESOLVING, PRESSED, DRAGGING, SNAPPED,
            PROGRESS, PAUSED, RESUMING, COMPLETING, COMPLETE, FAILED, RETRY
    };

    /**
     * What KIND of thing a state is — the explicit type M3 asked for (`V3.3_PLAN.md` §8 row 3).
     * Until now this was implicit: it lived in whichever predicate list a name happened to sit in, so
     * "is this a beat or a look?" was answered by different methods with no shared source.
     */
    public enum Kind {
        /** Calm. The app's resting contract (cell K-A5): nothing animates here. */
        REST,
        /** A one-shot beat: plays once, then settles into {@link #settleTarget(String)} — never a loop. */
        TRANSIENT,
        /** A look that holds until the truth changes. This is the arbiter's own vocabulary. */
        HOLD,
        /** The finger owns it (sheet C3). The touch path drives these; the arbiter must not. */
        TOUCH
    }

    /*
     * ONE TABLE, FOUR ANSWERS (M3, 2026-09-28), read top to bottom with ALL above: kind, what a beat
     * settles into, and whether the arbiter may cut it. Before this table the same knowledge existed
     * twice — `isTransient()`'s list and the hardcoded chain inside `CoreHost.settle()` — and nothing
     * checked the two agreed, so a new transient with no settle branch would have frozen on screen
     * instead of failing loudly.
     */
    private static final Kind[] KIND = {
            Kind.REST,      // IDLE
            Kind.TRANSIENT, // WAKE
            Kind.HOLD,      // DETECTED
            Kind.HOLD,      // RESOLVING
            Kind.TOUCH,     // PRESSED
            Kind.TOUCH,     // DRAGGING
            Kind.TOUCH,     // SNAPPED
            Kind.HOLD,      // PROGRESS
            Kind.HOLD,      // PAUSED
            Kind.TRANSIENT, // RESUMING
            Kind.TRANSIENT, // COMPLETING
            Kind.HOLD,      // COMPLETE
            Kind.HOLD,      // FAILED
            Kind.TRANSIENT, // RETRY
    };

    /** The state a beat settles into on its own; null where the state does not settle by itself. */
    private static final String[] SETTLE = {
            null,           // IDLE is not a beat
            DETECTED,       // WAKE -> the settled "a video is here" look
            null,           // DETECTED holds
            null,           // RESOLVING holds until the resolver says otherwise
            null,           // PRESSED leaves on the finger's own terms (drag or release)
            null,           // DRAGGING likewise
            null,           // SNAPPED likewise
            null,           // PROGRESS holds
            null,           // PAUSED holds
            PROGRESS,       // RESUMING -> the flow is back
            COMPLETE,       // COMPLETING -> the success pulse has played
            null,           // COMPLETE holds
            null,           // FAILED holds (leaving it is what clears the rose: CoreLook)
            RESOLVING,      // RETRY -> the resolver's own orbit (sheet C6: "as it re-resolves")
    };

    /**
     * True when the arbiter must not cut the state: every TRANSIENT beat, plus the finger's two
     * one-shot beats (PRESSED, SNAPPED), which leave on their own the way WAKE does. This is exactly
     * the set `isTransient()` has always returned — M3 made it a column instead of a chain of
     * `equals` calls, so `CoreHost.settle()` and this predicate can no longer drift apart.
     */
    private static final boolean[] BEAT = {
            false,          // IDLE
            true,           // WAKE
            false,          // DETECTED
            false,          // RESOLVING
            true,           // PRESSED
            false,          // DRAGGING  (the finger is holding it; it is not a beat)
            true,           // SNAPPED
            false,          // PROGRESS
            false,          // PAUSED
            true,           // RESUMING
            true,           // COMPLETING
            false,          // COMPLETE
            false,          // FAILED
            true,           // RETRY
    };

    private static int indexOf(String s) {
        if (s == null) return -1;
        for (int i = 0; i < ALL.length; i++) if (ALL[i].equals(s)) return i;
        return -1;
    }

    /** True when the perimeter carries a real download's progress in this state. */
    public static boolean showsProgress(String s) {
        return PROGRESS.equals(s) || PAUSED.equals(s) || RESUMING.equals(s)
                || COMPLETING.equals(s) || COMPLETE.equals(s);
    }

    /** The kind of a state, or null when the name is not in the vocabulary. */
    public static Kind kindOf(String s) {
        int i = indexOf(s);
        return i < 0 ? null : KIND[i];
    }

    /** What a beat settles into when its animation ends; null for every non-beat (and unknown) state. */
    public static String settleTarget(String s) {
        int i = indexOf(s);
        return i < 0 ? null : SETTLE[i];
    }

    /** True for the looks that hold until the truth changes — the arbiter's own vocabulary. */
    public static boolean isHold(String s) {
        return kindOf(s) == Kind.HOLD;
    }

    /**
     * True for the states the finger owns (sheet C3). The arbiter must not name these: while a finger
     * is down the touch path is the truth, and `DowniCore.setBaseState`'s own `interacting` flag is
     * what keeps a drag from being overwritten mid-gesture.
     */
    public static boolean isTouch(String s) {
        return kindOf(s) == Kind.TOUCH;
    }

    /**
     * The transition contract, in one place. Legal means: a re-assert of the same state (the service
     * re-pushes freely — it is idempotent), a change out of a state that is holding, or a beat handing
     * over to its own settle target. Illegal is the one thing the code has always promised cannot
     * happen: cutting a beat with some other state — and now it is checkable rather than asserted in a
     * comment (`DowniCore.setState` reports a violation on `CORE_TRANSITION`).
     */
    public static boolean isLegal(String from, String to) {
        if (from == null || to == null) return true;        // nothing to compare: callers guard anyway
        if (from.equals(to)) return true;                   // idempotent re-assert
        int f = indexOf(from), t = indexOf(to);
        if (f < 0 || t < 0) return true;                    // unknown names never reach the view
        if (!BEAT[f]) return true;                          // a hold (or a touch state) may change
        return to.equals(SETTLE[f]);                        // a beat may only hand over to its own target
    }

    /** True when the state is a short beat the host has to animate into the next one. */
    public static boolean isTransient(String s) {
        int i = indexOf(s);
        return i >= 0 && BEAT[i];
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
