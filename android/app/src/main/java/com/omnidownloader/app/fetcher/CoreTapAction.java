package com.omnidownloader.app.fetcher;

import com.omnidownloader.app.downicore.CoreStates;

/**
 * The tap grammar (Wave 2, decision D-V2-3): the Core's tap MEANING follows its state.
 * One gesture, four honest meanings — the Core always acts on the video situation:
 *
 *   PAUSED   + a tracked paused job -> RESUME  ("continue from exactly here")
 *   COMPLETE + a tracked done job   -> PEEK    ("show me the file")
 *   FAILED                          -> RETRY   ("try again" — the tracked URL if the
 *                                             engine failed it, otherwise a fresh run)
 *   anything else                   -> FETCH   (the one rule that defined the product)
 *
 * Pure and unit-tested (`CoreTapActionTest`) — the service maps the action to intents.
 * The busy guard (B4) and the D-i stale-window guards are upstream of this and unchanged.
 */
public final class CoreTapAction {

    public enum Action { FETCH, RESUME, PEEK, RETRY }

    private CoreTapAction() {}

    /**
     * @param baseState      the arbiter's current base state for the Core
     * @param hasTrackedJob  true when the Core is tracking a job (a delivered URL exists)
     */
    public static Action of(String baseState, boolean hasTrackedJob) {
        if (CoreStates.PAUSED.equals(baseState)) return hasTrackedJob ? Action.RESUME : Action.FETCH;
        if (CoreStates.COMPLETE.equals(baseState)) return hasTrackedJob ? Action.PEEK : Action.FETCH;
        if (CoreStates.FAILED.equals(baseState)) return Action.RETRY;
        return Action.FETCH;
    }
}
