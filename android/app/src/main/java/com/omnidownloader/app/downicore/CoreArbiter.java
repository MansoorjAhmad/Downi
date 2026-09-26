package com.omnidownloader.app.downicore;

/**
 * V3.2 Downi Core — the state arbiter, extracted from DowniFetcherService (Wave 0).
 *
 * ONE OBJECT, MANY STATES, with honest precedence (master package §7):
 * a real download job is the loudest truth, the resolver working is next,
 * detection beats idle. Interaction (press/drag/snap) still physically overrides
 * everything, but returns to THIS verdict instead of a hardcoded idle — a Core
 * that is mid-download must not forget its job just because the user dragged it.
 *
 * Pure and unit-tested (`CoreArbiterTest`): no Android types, no clock.
 */
public final class CoreArbiter {

    private CoreArbiter() {}

    /**
     * The Core's base state right now.
     *
     * @param jobState      what the tracked download job says ({@link CoreStates#IDLE} = none)
     * @param chainRunning  true while the resolver is working behind a tap
     * @param videoDetected true while watching signals say a video is on screen
     */
    public static String baseState(String jobState, boolean chainRunning, boolean videoDetected) {
        if (jobState != null && !CoreStates.IDLE.equals(jobState)) {
            return jobState;                   // a real job is the loudest truth
        }
        if (chainRunning) {
            return CoreStates.RESOLVING;       // the tap fired; the resolver is working
        }
        return videoDetected ? CoreStates.DETECTED : CoreStates.IDLE;
    }
}
