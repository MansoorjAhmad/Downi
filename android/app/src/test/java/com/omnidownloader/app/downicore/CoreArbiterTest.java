package com.omnidownloader.app.downicore;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Locks the arbiter's precedence (Wave 0): a real job beats the resolver, the resolver
 * beats detection, detection beats idle. These rules are why a Core that is mid-download
 * no longer forgets its job when a run resets, and why a resolver dead-end holds its
 * FAILED state for the full 3 s hold instead of being clobbered back to idle.
 */
public class CoreArbiterTest {

    @Test public void aRealJobBeatsEverything() {
        assertEquals(CoreStates.PROGRESS,
                CoreArbiter.baseState(CoreStates.PROGRESS, true, true));
        assertEquals(CoreStates.FAILED,
                CoreArbiter.baseState(CoreStates.FAILED, true, false));
        assertEquals(CoreStates.PAUSED,
                CoreArbiter.baseState(CoreStates.PAUSED, false, true));
        assertEquals(CoreStates.COMPLETING,
                CoreArbiter.baseState(CoreStates.COMPLETING, false, false));
    }

    @Test public void resolvingBeatsDetection() {
        assertEquals(CoreStates.RESOLVING,
                CoreArbiter.baseState(CoreStates.IDLE, true, true));
        assertEquals(CoreStates.RESOLVING,
                CoreArbiter.baseState(CoreStates.IDLE, true, false));
    }

    @Test public void detectionBeatsIdle() {
        assertEquals(CoreStates.DETECTED,
                CoreArbiter.baseState(CoreStates.IDLE, false, true));
        assertEquals(CoreStates.IDLE,
                CoreArbiter.baseState(CoreStates.IDLE, false, false));
    }

    @Test public void aNullJobStateIsNoJob() {
        assertEquals(CoreStates.RESOLVING,
                CoreArbiter.baseState(null, true, false));
        assertEquals(CoreStates.DETECTED,
                CoreArbiter.baseState(null, false, true));
    }
}
