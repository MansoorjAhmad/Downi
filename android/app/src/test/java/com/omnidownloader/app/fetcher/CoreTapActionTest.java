package com.omnidownloader.app.fetcher;

import static org.junit.Assert.assertEquals;

import com.omnidownloader.app.fetcher.CoreTapAction.Action;

import org.junit.Test;

/**
 * Locks the tap grammar (Wave 2, D-V2-3): PAUSED taps resume, COMPLETE taps peek, FAILED
 * taps retry, everything else fetches. A state-dependent meaning may only fire when the
 * Core actually tracks that job — otherwise the tap falls back to a fresh fetch, so a
 * PAUSED look inherited from a stale snapshot can never hijack a fetch for a NEW video.
 */
public class CoreTapActionTest {

    @Test public void pausedCoreResumesItsJob() {
        assertEquals(Action.RESUME, CoreTapAction.of("paused", true));
    }

    @Test public void completedCorePeeks() {
        assertEquals(Action.PEEK, CoreTapAction.of("complete", true));
    }

    @Test public void failedCoreRetries() {
        assertEquals(Action.RETRY, CoreTapAction.of("failed", false));
        assertEquals(Action.RETRY, CoreTapAction.of("failed", true));
    }

    @Test public void staleStatesFallBackToFetch() {
        // No tracked job: the paused/complete look cannot hijack a fetch for a new video.
        assertEquals(Action.FETCH, CoreTapAction.of("paused", false));
        assertEquals(Action.FETCH, CoreTapAction.of("complete", false));
        assertEquals(Action.FETCH, CoreTapAction.of("detected", true));
        assertEquals(Action.FETCH, CoreTapAction.of("idle", false));
        assertEquals(Action.FETCH, CoreTapAction.of("progress", true));   // busy-guard's territory upstream
    }
}
