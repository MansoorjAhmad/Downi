package com.omnidownloader.app.downicore;

import static org.junit.Assert.assertArrayEquals;
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
        assertEquals(1f, end.detected, EPS);
        assertTrue("the membrane must brighten through the wake", end.halo > start.halo);
        assertTrue("the rim must brighten through the wake", end.rim > start.rim);
    }

    @Test public void wakeNeverDrawsATransientRing() {
        // Sheet C2's ruling: the surge reads as the MEMBRANE brightening — never a separate
        // full circle appearing and vanishing. The wake's perimeter stays 0 through the whole
        // transition, and the rim carries the surge instead.
        for (float t = 0f; t <= 1.0001f; t += 0.1f) {
            assertEquals("wake perimeter must be 0 at t=" + t, 0f,
                    CoreLook.of(CoreStates.WAKE, t, 0f).perimeter, EPS);
            assertTrue("the rim must rise through the wake",
                    CoreLook.of(CoreStates.WAKE, t, 0f).rim >= 0.55f - EPS);
        }
    }

    @Test public void awareIsTheQuieterHonestWake() {
        // Sheet C2: same object, ~55% energy on every channel — READY vs AWARE.
        CoreLook.Look ready = CoreLook.of(CoreStates.DETECTED, 0f, 0f, true);
        CoreLook.Look aware = CoreLook.of(CoreStates.DETECTED, 0f, 0f, false);
        assertTrue("aware halo must be quieter", aware.halo < ready.halo);
        assertTrue("aware rim must be quieter", aware.rim < ready.rim);
        assertTrue("aware mark must be quieter", aware.mark < ready.mark);
        assertTrue("aware must still read as awake", aware.detected >= 0.5f);
        assertEquals(0.30f, aware.halo, EPS);
        assertEquals(0.78f, aware.rim, EPS);
        // an AWARE wake ends at the AWARE settled values, never the READY ones
        CoreLook.Look awareWakeEnd = CoreLook.of(CoreStates.WAKE, 1f, 0f, false);
        assertEquals(0.78f, awareWakeEnd.rim, EPS);
        assertTrue(awareWakeEnd.detected <= 0.56f);
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

    // ---------- Wave 4 / rulings R1, R5, R6 (approved 2026-09-26) ----------

    @Test public void wakeBeatsAreTheSheetC2Storyboard() {
        // Ruling R1: the five beats are real and WAKE_STEP_MS drives them — 100 awareness, 200
        // energy builds, 300 the membrane activates, 400 the core brightens, 600 settled. This
        // test IS the pin: the constant can never quietly become a dead literal again.
        assertEquals(100L, CoreMotion.WAKE_STEP_MS);
        assertArrayEquals(new long[]{100L, 200L, 300L, 400L, 600L}, CoreMotion.WAKE_BEATS_MS);
        assertEquals(CoreMotion.WAKE_MS, CoreMotion.WAKE_BEATS_MS[CoreMotion.WAKE_BEATS_MS.length - 1]);
        for (int i = 0; i < 4; i++) {
            assertEquals("beat " + i + " is one step further along",
                    (i + 1) * CoreMotion.WAKE_STEP_MS, CoreMotion.WAKE_BEATS_MS[i]);
        }
    }

    @Test public void wakeStageArrivesAtEachBeatAndStartsFromRest() {
        // The staged wake STARTS at rest, ARRIVES at the storyboard value of every beat, never
        // dims, and its settled values are exactly the settled look's own numbers.
        assertEquals(0f, CoreMotion.wakeStage(0f, CoreLook.WAKE_READY_RIM, 0f), EPS);
        float[] rimAt = {0.62f, 0.74f, 0.86f, 0.95f, 1.00f};
        for (int i = 0; i < CoreMotion.WAKE_BEATS_MS.length; i++) {
            float t = CoreMotion.WAKE_BEATS_MS[i] / (float) CoreMotion.WAKE_MS;
            assertEquals("beat " + i + " arrives on time",
                    rimAt[i], CoreMotion.wakeStage(0.55f, CoreLook.WAKE_READY_RIM, t), EPS);
        }
        float prevDetected = -1f;
        for (float t = 0f; t <= 1.0001f; t += 0.02f) {
            CoreLook.Look L = CoreLook.of(CoreStates.WAKE, t, 0f, true);
            assertTrue("the wake may never dim (t=" + t + ")", L.detected >= prevDetected - EPS);
            assertTrue("the rim must rise through the wake (t=" + t + ")", L.rim >= 0.55f - EPS);
            prevDetected = L.detected;
        }
        CoreLook.Look settled = CoreLook.of(CoreStates.WAKE, 1f, 0f, true);
        assertEquals(1.00f, settled.detected, EPS);
        assertEquals(0.85f, settled.halo, EPS);
        assertEquals(1.00f, settled.rim, EPS);
        assertEquals(0.16f, settled.track, EPS);
        assertEquals("a staged wake is still never a ring",
                0f, CoreLook.of(CoreStates.WAKE, 0.5f, 0f, true).perimeter, EPS);
    }

    @Test public void wakeStageRefusesAMissingBeat() {
        boolean threw = false;
        try {
            CoreMotion.wakeStage(0f, new float[]{1f}, 0.5f);
        } catch (IllegalArgumentException expected) {
            threw = true;                      // a missing beat is an error, not a silent default
        }
        assertTrue("wakeStage must reject a short beat table", threw);
    }

    @Test public void releaseSettleIsRestrainedAndCapped() {
        // Ruling R5 / sheet C3 §2: a release that does NOT snap settles through a small swell —
        // 180 ms, capped at 1.05 — then returns to exactly 1. Physical, never a spring toy.
        assertEquals(180L, CoreMotion.RELEASE_SETTLE_MS);
        assertEquals(1f, CoreMotion.releaseSettle(0f), EPS);
        assertEquals(1.05f, CoreMotion.releaseSettle(0.5f), EPS);
        assertEquals(1f, CoreMotion.releaseSettle(1f), EPS);
        for (float t = 0f; t <= 1.0001f; t += 0.01f) {
            float s = CoreMotion.releaseSettle(t);
            assertTrue("the settle never crosses its cap (t=" + t + "): " + s,
                    s >= 1f && s <= 1.0501f);
        }
    }

    @Test public void returnFadeFadesTheHeldLookIntoRest() {
        // Ruling R6 / sheet C5 RETURN: COMPLETE (or FAILED) -> IDLE is a subtle fade, never a
        // cut. t=0 is the held look; t=1 has fully arrived at the resting look.
        CoreLook.Look held = CoreLook.of(CoreStates.COMPLETE, 0f, 1f);
        CoreLook.Look atStart = CoreLook.returnFade(held, 0f);
        assertEquals(1f, atStart.perimeter, EPS);
        assertEquals(1f, atStart.mark, EPS);
        CoreLook.Look idle = CoreLook.of(CoreStates.IDLE, 0f, 0f);
        CoreLook.Look atEnd = CoreLook.returnFade(held, 1f);
        assertEquals(idle.perimeter, atEnd.perimeter, EPS);
        assertEquals(idle.rim, atEnd.rim, EPS);
        assertEquals(idle.mark, atEnd.mark, EPS);
        assertEquals(idle.halo, atEnd.halo, EPS);
        assertEquals(idle.track, atEnd.track, EPS);
        float prev = 1.1f;
        for (float t = 0f; t <= 1.0001f; t += 0.05f) {
            float p = CoreLook.returnFade(held, t).perimeter;
            assertTrue("the ring only ever recedes (t=" + t + ")", p <= prev + EPS);
            prev = p;
        }
        // a paused look's bars leave early and never strand a bar behind
        CoreLook.Look paused = CoreLook.of(CoreStates.PAUSED, 0f, 0.4f);
        assertTrue(CoreLook.returnFade(paused, 0f).bars);
        assertFalse(CoreLook.returnFade(paused, 0.5f).bars);
        assertEquals(0f, CoreLook.returnFade(paused, 1f).barAlpha, EPS);
        // the failure return fades the rose away too (the tint belongs to FAILED alone)
        CoreLook.Look failed = CoreLook.of(CoreStates.FAILED, 0f, 0.4f);
        assertEquals(0f, CoreLook.returnFade(failed, 1f).error, EPS);
    }

    @Test public void unsupportedTintCarriesOnlyThroughFailed() {
        // Ruling R2 / sheet C6: the neutral mood belongs to FAILED alone. Leaving FAILED clears
        // the flag, so a later failure renders rose unless it is explicitly marked again.
        assertTrue(CoreLook.unsupportedCarriesOver(CoreStates.FAILED, true));
        assertFalse(CoreLook.unsupportedCarriesOver(CoreStates.FAILED, false));
        for (String s : CoreStates.ALL) {
            if (CoreStates.FAILED.equals(s)) continue;
            assertFalse(s + " must clear the unsupported flag",
                    CoreLook.unsupportedCarriesOver(s, true));
        }
        assertFalse("no state at all must clear it too", CoreLook.unsupportedCarriesOver(null, true));
    }
}
