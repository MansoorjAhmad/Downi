package com.omnidownloader.app.downicore;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

/**
 * M3 (V3.3_PLAN.md §8 row 3): the state vocabulary has an explicit TYPE and a transition rule, and
 * this suite is what stops either from drifting.
 *
 * The refactor behind it was deliberately behaviour-free: `isTransient` used to be a chain of six
 * `equals` calls and `CoreHost.settle()` kept its own copy of what each transient becomes. Both now
 * read one table in `CoreStates`, and the tests below pin the answers that table must give — the
 * names, the order, the beats, the settle targets, the arbiter's vocabulary and the illegal pairs.
 *
 * Wire names are part of the contract: `CoreStates.list()` is printed by `CORE_STATES` and every
 * `CORE_STATE` line carries one of these spellings, so a rename here is a rename for every gate.
 */
public class CoreStatesTest {

    /** The fourteen names, in the sheet's own order — frozen on purpose. */
    private static final String[] VOCABULARY = {
            "idle", "wake", "detected", "resolving", "pressed", "dragging", "snapped",
            "progress", "paused", "resuming", "completing", "complete", "failed", "retry"
    };

    /** What `isTransient` returned before the table existed — the refactor must not move it. */
    private static final String[] BEATS = {
            "wake", "pressed", "snapped", "resuming", "completing", "retry"
    };

    /** Every state the arbiter can hand the Core (its verdicts come from the job's own vocabulary). */
    private static final String[] ARBITER_MAY_NAME = {
            "idle", "detected", "resolving", "progress", "paused", "completing", "complete", "failed"
    };

    @Test public void theVocabularyCannotChangeSilently() {
        assertEquals(14, CoreStates.ALL.length);
        assertEquals("the vocabulary's order is the sheet's order",
                Arrays.toString(VOCABULARY), Arrays.toString(CoreStates.ALL));
        assertEquals("idle|wake|detected|resolving|pressed|dragging|snapped|progress|paused|resuming"
                + "|completing|complete|failed|retry", CoreStates.list());
        for (String s : VOCABULARY) assertTrue("known: " + s, CoreStates.isKnown(s));
        assertFalse(CoreStates.isKnown("armed"));           // the Fetcher 1.0 word, retired
        assertFalse(CoreStates.isKnown(null));
    }

    @Test public void everyBeatHasASettleTargetAndItIsNotABeat() {
        for (String s : VOCABULARY) {
            boolean beat = CoreStates.isTransient(s);
            String to = CoreStates.settleTarget(s);
            if (beat) {
                // PRESSED and SNAPPED are the finger's own beats: they end in the touch path, not here.
                if ("pressed".equals(s) || "snapped".equals(s)) {
                    assertNull("the finger ends " + s, to);
                } else {
                    assertNotNull("a beat with no settle target would freeze on screen: " + s, to);
                    assertTrue("the target is in the vocabulary: " + s + " -> " + to, CoreStates.isKnown(to));
                    assertFalse("a beat must not settle into another beat: " + s + " -> " + to,
                            CoreStates.isTransient(to));
                }
            } else {
                assertNull("only a beat settles by itself: " + s, to);
            }
        }
    }

    @Test public void thePredicatesAnswerWhatTheyAlwaysDid() {
        Set<String> beats = new HashSet<>(Arrays.asList(BEATS));
        Set<String> progress = new HashSet<>(Arrays.asList("progress", "paused", "resuming",
                "completing", "complete"));
        for (String s : VOCABULARY) {
            assertEquals("isTransient " + s, beats.contains(s), CoreStates.isTransient(s));
            assertEquals("showsProgress " + s, progress.contains(s), CoreStates.showsProgress(s));
        }
        assertFalse("an unknown name is never a beat", CoreStates.isTransient("armed"));
        assertNull("an unknown name has no kind", CoreStates.kindOf("armed"));
    }

    @Test public void theKindIsTheExplicitType() {
        assertEquals(CoreStates.Kind.REST, CoreStates.kindOf(CoreStates.IDLE));
        for (String s : new String[] { "resolving", "detected", "progress", "paused", "complete", "failed" }) {
            assertTrue("holds: " + s, CoreStates.isHold(s));
        }
        for (String s : new String[] { "pressed", "dragging", "snapped" }) {
            assertTrue("the finger owns: " + s, CoreStates.isTouch(s));
            assertFalse("and it is not a hold: " + s, CoreStates.isHold(s));
        }
        // Exactly one resting state: the K-A5 calm contract ("nothing animates while idle").
        int rests = 0;
        for (String s : VOCABULARY) if (CoreStates.kindOf(s) == CoreStates.Kind.REST) rests++;
        assertEquals(1, rests);
    }

    /**
     * Every state a live job can hand the arbiter — `CoreJobBinding.JobView`'s own returns (progress,
     * paused, completing, failed, and idle for "nothing live"). The arbiter passes a job's state
     * through verbatim, so ITS vocabulary is the job's vocabulary, and that is the domain this test
     * has to walk: handing `baseState` a touch state would be a bug in the caller, not a rule here.
     */
    private static final String[] JOB_VOCABULARY = {
            "idle", "progress", "paused", "completing", "failed"
    };

    @Test public void theArbiterOnlyNamesStatesItOwns() {
        Set<String> allowed = new HashSet<>(Arrays.asList(ARBITER_MAY_NAME));
        String[] domain = { null, "idle", "progress", "paused", "completing", "failed" };
        for (String job : domain) {
            for (boolean chain : new boolean[] { false, true }) {
                for (boolean hot : new boolean[] { false, true }) {
                    String verdict = CoreArbiter.baseState(job, chain, hot);
                    assertTrue("the arbiter named an unknown state: " + verdict, CoreStates.isKnown(verdict));
                    assertTrue("the arbiter named a state that is not its to name: " + verdict
                            + " (job=" + job + ", chain=" + chain + ", video=" + hot + ")",
                            allowed.contains(verdict));
                    assertFalse("the arbiter must never hand back a touch state: " + verdict,
                            CoreStates.isTouch(verdict));
                    assertFalse("nor a beat the service owns (only the job's completion beat is its): "
                                    + verdict,
                            CoreStates.isTransient(verdict) && !CoreStates.COMPLETING.equals(verdict));
                }
            }
        }
        // A job's own state is a pass-through — the arbiter is not a judge of its input, so an
        // out-of-domain state arrives verbatim. Recorded here because it is the reason the loop above
        // walks the JOB vocabulary: a caller handing it PRESSED would see PRESSED, and the bug would
        // be the caller's.
        assertEquals(CoreStates.PRESSED, CoreArbiter.baseState(CoreStates.PRESSED, false, false));
        // the table and the expectations above must not drift: every arbiter name is a hold, a rest,
        // or the job's own completion beat, and every job state is one the arbiter may name.
        for (String s : ARBITER_MAY_NAME) {
            boolean ok = CoreStates.isHold(s) || s.equals(CoreStates.IDLE)
                    || s.equals(CoreStates.COMPLETING);
            assertTrue("the arbiter's vocabulary no longer matches the kind table: " + s, ok);
        }
        for (String s : JOB_VOCABULARY) {
            assertTrue("a job state the arbiter may not name: " + s, allowed.contains(s));
        }
        // and the two branches the arbiter owns are the resolver's hold and detection's hold
        assertTrue(CoreStates.isHold(CoreArbiter.baseState(CoreStates.IDLE, true, false)));
        assertTrue(CoreStates.isHold(CoreArbiter.baseState(CoreStates.IDLE, false, true)));
        assertEquals(CoreStates.IDLE, CoreArbiter.baseState(CoreStates.IDLE, false, false));
        assertEquals(CoreStates.IDLE, CoreArbiter.baseState(null, false, false));
    }

    @Test public void aBeatIsNeverCutAndEverythingElseMayMove() {
        // idempotent re-assert: the service re-pushes its verdict freely
        for (String s : VOCABULARY) assertTrue("re-assert " + s, CoreStates.isLegal(s, s));
        // a beat may only hand over to its own settle target
        assertTrue(CoreStates.isLegal("wake", "detected"));
        assertTrue(CoreStates.isLegal("resuming", "progress"));
        assertTrue(CoreStates.isLegal("completing", "complete"));
        assertTrue(CoreStates.isLegal("retry", "resolving"));
        assertFalse("cutting the wake with a job push", CoreStates.isLegal("wake", "progress"));
        assertFalse("cutting the retry acknowledgement", CoreStates.isLegal("retry", "progress"));
        assertFalse("cutting the completion pulse", CoreStates.isLegal("completing", "progress"));
        assertFalse("cutting the resume", CoreStates.isLegal("resuming", "paused"));
        assertFalse("cutting the press", CoreStates.isLegal("pressed", "progress"));
        // a hold, or a state the finger owns, may change into anything in the vocabulary
        for (String to : VOCABULARY) {
            assertTrue("out of a hold: " + to, CoreStates.isLegal("progress", to));
            assertTrue("out of the finger's own state: " + to, CoreStates.isLegal("dragging", to));
            assertTrue("out of the rest state: " + to, CoreStates.isLegal("idle", to));
        }
        // nulls and unknown names are not a transition (the callers guard, and so does the view)
        assertTrue(CoreStates.isLegal(null, "idle"));
        assertTrue(CoreStates.isLegal("idle", null));
        assertTrue(CoreStates.isLegal("armed", "idle"));
    }
}
