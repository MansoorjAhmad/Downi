package com.omnidownloader.app.downicore;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Pure-logic tests for the Core's design tables. The visual half of Phase A is device-gated
 * (cells K-A1…K-A5), but the numbers behind it are not: these lock sheet 6's timings/easing and
 * sheet 4's progress rules — most importantly the invariant that progress is only ever carried
 * as a 0..1 float for the perimeter (never as text) and is clamped whatever a caller passes.
 */
public class CoreLookTest {

    private static final float EPS = 0.001f;

    @Test public void idleIsCalmAndShowsNoProgress() {
        CoreLook.Look L = CoreLook.of(CoreStates.IDLE, 0f, 0f);
        assertEquals(0f, L.perimeter, EPS);
        assertEquals(0f, L.error, EPS);
        assertFalse(L.bars);
        assertTrue("idle rim must be visible but not full", L.rim > 0f && L.rim < 1f);
    }

    @Test public void progressIsCarriedOnlyOnThePerimeterAndClamped() {
        assertEquals(0.42f, CoreLook.of(CoreStates.PROGRESS, 0f, 0.42f).perimeter, EPS);
        assertEquals(1f, CoreLook.of(CoreStates.PROGRESS, 0f, 1.7f).perimeter, EPS);
        assertEquals(0f, CoreLook.of(CoreStates.PROGRESS, 0f, -3f).perimeter, EPS);
    }

    @Test public void pausedFreezesProgressAndShowsBarsNotText() {
        CoreLook.Look paused = CoreLook.of(CoreStates.PAUSED, 0f, 0.42f);
        CoreLook.Look running = CoreLook.of(CoreStates.PROGRESS, 0f, 0.42f);
        assertEquals(0.42f, paused.perimeter, EPS);
        assertTrue(paused.bars);
        assertTrue("paused must read dimmer than running", paused.rim < running.rim);
        assertFalse(running.bars);
    }

    @Test public void completeFillsThePerimeterAndStaysCalm() {
        CoreLook.Look L = CoreLook.of(CoreStates.COMPLETE, 0f, 1f);
        assertEquals(1f, L.perimeter, EPS);
        assertEquals(0f, L.error, EPS);
        assertFalse(L.bars);
    }

    @Test public void failedTintsRedAndKeepsWhereItDied() {
        CoreLook.Look L = CoreLook.of(CoreStates.FAILED, 0f, 0.31f);
        assertTrue("failed must carry a visible error tint", L.error > 0.5f);
        assertEquals(0.31f, L.perimeter, EPS);
    }

    @Test public void wakeSweepsInAndSettlesToDetected() {
        CoreLook.Look start = CoreLook.of(CoreStates.WAKE, 0f, 0f);
        CoreLook.Look end = CoreLook.of(CoreStates.WAKE, 1f, 0f);
        assertEquals(0f, start.detected, EPS);
        assertEquals(0f, start.perimeter, EPS);
        assertEquals(1f, end.detected, EPS);
        assertEquals(1f, end.perimeter, EPS);
    }

    @Test public void easingIsMonotonicWithCleanEndpoints() {
        assertEquals(0f, CoreMotion.easeInOut(0f), EPS);
        assertEquals(1f, CoreMotion.easeInOut(1f), EPS);
        float prev = -1f;
        for (float t = 0f; t <= 1.0001f; t += 0.1f) {
            float v = CoreMotion.easeInOut(t);
            assertTrue("easeInOut must not go backwards", v >= prev - EPS);
            prev = v;
        }
        assertEquals(1f, CoreMotion.easeOutBack(1f), EPS);
        assertEquals(0f, CoreMotion.easeOutBack(0f), EPS);
    }

    @Test public void pulseStartsAndEndsAtZero() {
        assertEquals(0f, CoreMotion.pulse(0f), EPS);
        assertEquals(1f, CoreMotion.pulse(0.5f), EPS);
        assertEquals(0f, CoreMotion.pulse(1f), EPS);
    }

    @Test public void timingsMatchSheetSix() {
        assertEquals(100L, CoreMotion.QUICK_MS);
        assertEquals(600L, CoreMotion.WAKE_MS);
        assertEquals(300L, CoreMotion.SNAP_MS);
        assertEquals(400L, CoreMotion.PROGRESS_MS);
        assertEquals(400L, CoreMotion.COMPLETE_MS);
        assertEquals(300L, CoreMotion.ERROR_MS);
    }

    @Test public void roseTintBelongsToFailedAlone() {
        // The device audit (tools/core_state_audit.py, cell K-A3) reads the rim's hue off a
        // screenshot and calls any state but `failed` rose a defect - that only holds while
        // CoreLook sets L.error in the FAILED branch alone (CoreHost picks the rose rim at
        // error > 0.25). Locked here so a mood tweak cannot make PAUSED look like an error.
        for (String s : CoreStates.ALL) {
            for (float t = 0f; t <= 1.0001f; t += 0.25f) {
                float error = CoreLook.of(s, t, 0.5f).error;
                if (CoreStates.FAILED.equals(s)) {
                    assertTrue("failed must stay tinted (t=" + t + ")", error > 0.25f);
                } else {
                    assertEquals(s + " must not carry the error tint (t=" + t + ")", 0f, error, EPS);
                }
            }
        }
    }

    @Test public void stateVocabularyIsClosedAndHonest() {
        assertEquals(13, CoreStates.ALL.length);
        assertTrue(CoreStates.isKnown(CoreStates.DETECTED));
        assertTrue(CoreStates.isKnown(CoreStates.RESOLVING));
        assertFalse("there is no 'armed' state — a video is either detected or it is not",
                CoreStates.isKnown("armed"));
        assertTrue(CoreStates.isTransient(CoreStates.WAKE));
        assertFalse(CoreStates.isTransient(CoreStates.IDLE));
        assertFalse("resolving is a hold state with its own visible progress (the orbit)",
                CoreStates.isTransient(CoreStates.RESOLVING));
        assertTrue(CoreStates.showsProgress(CoreStates.PAUSED));
        assertFalse(CoreStates.showsProgress(CoreStates.IDLE));
    }

    @Test public void downloadingLendsLightToTheRing() {
        // The energy law (§M-1): while a job runs, the mark dims and the perimeter speaks.
        CoreLook.Look run = CoreLook.of(CoreStates.PROGRESS, 0f, 0.42f);
        CoreLook.Look idle = CoreLook.of(CoreStates.IDLE, 0f, 0f);
        assertTrue("downloading mark must be dimmed", run.mark < idle.mark);
        assertEquals(0.42f, run.perimeter, EPS);
    }

    @Test public void completionReturnsTheLightToTheMark() {
        CoreLook.Look start = CoreLook.of(CoreStates.COMPLETING, 0f, 1f);
        CoreLook.Look end = CoreLook.of(CoreStates.COMPLETING, 1f, 1f);
        assertTrue("the ring collapses inward during the pulse", end.perimeter < start.perimeter);
        assertTrue("the mark blooms back to full", end.mark > start.mark);
        assertEquals(1f, end.mark, EPS);
    }

    @Test public void pressingSinksTheMarkIntoTheGel() {
        assertEquals(0f, CoreLook.of(CoreStates.IDLE, 0f, 0f).markSink, EPS);
        assertEquals(1f, CoreLook.of(CoreStates.PRESSED, 1f, 0f).markSink, EPS);
    }
}
