package com.omnidownloader.app.fetcher;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Locks the fast-read contract (device 2026-09-28, §0z-7): the chain may read the clipboard the
 * INSTANT the platform's Copy link is clicked — but only because the rule set here can tell a fresh
 * write from the clipboard's previous value. A wrong pick downloads the wrong video, so the tests
 * below are the cage for that, not a description of the happy path.
 *
 * The numbers are pinned too (cadence and the focus-hold budget derived from it), because the whole
 * point of the change is a measured latency: if a cadence moves, the budget that covers it must move
 * with it, and this is where that shows up as a failure instead of as a flaky grab.
 */
public class ClipboardGateTest {

    private static final String REEL_A = "https://www.instagram.com/reel/DdbMO05yk3x/";
    private static final String REEL_B = "https://www.instagram.com/reel/Dd0MTnfvKn5/";

    @Test public void nothingReadYet_waits() {
        assertEquals(ClipboardGate.Verdict.WAIT, ClipboardGate.accept(null, REEL_A, 120));
        assertEquals(ClipboardGate.Verdict.WAIT, ClipboardGate.accept("", REEL_A, 120));
    }

    /** The whole reason the 1.2 s sleep could be deleted: a changed value is proof. */
    @Test public void changedValue_isAcceptedImmediately() {
        assertEquals(ClipboardGate.Verdict.ACCEPT, ClipboardGate.accept(REEL_B, REEL_A, 0));
        assertEquals(ClipboardGate.Verdict.ACCEPT, ClipboardGate.accept(REEL_B, REEL_A, 1));
    }

    /** An empty baseline means there was nothing to compare against — accept what can be read. */
    @Test public void noBaseline_behavesLikeTheOldChain() {
        assertEquals(ClipboardGate.Verdict.ACCEPT, ClipboardGate.accept(REEL_A, null, 0));
        assertEquals(ClipboardGate.Verdict.ACCEPT, ClipboardGate.accept(REEL_A, "", 0));
    }

    /**
     * The dangerous case the guard exists for: the read beat the platform's write, so the clipboard
     * still holds the PREVIOUS reel. It must not be accepted while the write may still land.
     */
    @Test public void unchangedValue_waitsOutTheGrace() {
        assertEquals(ClipboardGate.Verdict.SAME, ClipboardGate.accept(REEL_A, REEL_A, 0));
        assertEquals(ClipboardGate.Verdict.SAME,
                ClipboardGate.accept(REEL_A, REEL_A, ClipboardGate.SAME_VALUE_GRACE_MS - 1));
    }

    /** Past the grace, an unchanged value is a same-reel re-copy — the honest reading, not a stall. */
    @Test public void unchangedValue_isAcceptedAfterTheGrace() {
        assertEquals(ClipboardGate.Verdict.ACCEPT,
                ClipboardGate.accept(REEL_A, REEL_A, ClipboardGate.SAME_VALUE_GRACE_MS));
    }

    @Test public void cadenceIsFastFirstThenPatient() {
        assertEquals(120L, ClipboardGate.retryDelayMs(0));
        assertEquals(120L, ClipboardGate.retryDelayMs(2));
        assertEquals(250L, ClipboardGate.retryDelayMs(3));
        assertEquals(250L, ClipboardGate.retryDelayMs(99));
    }

    /** The focus hold is DERIVED from the cadence — this pins them together. */
    @Test public void budgetMatchesTheCadenceItCovers() {
        long ready = 120L, tail = 400L;
        int tries = 6;
        // 120 + (120 + 120 + 120 + 250 + 250) + 400
        assertEquals(1_380L, ClipboardGate.budgetMs(ready, tries, tail));
    }
}