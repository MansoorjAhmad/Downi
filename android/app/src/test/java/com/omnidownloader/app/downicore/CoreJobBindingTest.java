package com.omnidownloader.app.downicore;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Locks the living Core's job mapping (master package §30/§31: the Core is the face of the real
 * download job, never a decoration). The service's own snapshot state — running / done / failed /
 * canceled, with the real percent — must map to exactly one honest Core state, and a terminal row
 * must age out instead of lingering forever (the service only prunes rows on its next write).
 */
public class CoreJobBindingTest {

    private static final long NOW = 1_000_000L;

    @Test public void runningMapsToProgressWithTheRealPercent() {
        CoreJobBinding.JobView v = CoreJobBinding.viewFor("running", 72, NOW - 500, NOW);
        assertEquals(CoreStates.PROGRESS, v.coreState);
        assertEquals(0.72f, v.progress, 0.0001f);
        assertFalse(v.terminal);
    }

    @Test public void doneMapsToTheCompletionPulseAtFullPerimeter() {
        CoreJobBinding.JobView v = CoreJobBinding.viewFor("done", 99, NOW - 500, NOW);
        assertEquals(CoreStates.COMPLETING, v.coreState);   // the host settles it to COMPLETE
        assertEquals(1f, v.progress, 0.0001f);
        assertFalse("the finished state holds calmly for the TTL", v.terminal);
    }

    @Test public void failedShowsRestrainedErrorWhereTheJobDied() {
        CoreJobBinding.JobView v = CoreJobBinding.viewFor("failed", 61, NOW - 500, NOW);
        assertEquals(CoreStates.FAILED, v.coreState);
        assertEquals(0.61f, v.progress, 0.0001f);
    }

    @Test public void canceledReturnsQuietlyToIdle() {
        CoreJobBinding.JobView v = CoreJobBinding.viewFor("canceled", 30, NOW - 500, NOW);
        assertEquals(CoreStates.IDLE, v.coreState);
        assertTrue(v.terminal);
    }

    @Test public void terminalRowsAgeOutInsteadOfLingeringForever() {
        // The service prunes terminal rows only on its NEXT write; nothing may write after the
        // last grab — so the binding must apply the TTL itself.
        CoreJobBinding.JobView fresh = CoreJobBinding.viewFor("done", 100, NOW - 19_999, NOW);
        assertEquals(CoreStates.COMPLETING, fresh.coreState);
        CoreJobBinding.JobView stale = CoreJobBinding.viewFor("done", 100, NOW - 20_001, NOW);
        assertEquals(CoreStates.IDLE, stale.coreState);
        assertTrue("tracking stops once the row has aged out", stale.terminal);
    }

    @Test public void percentIsClampedWhateverTheSnapshotSays() {
        assertEquals(1f, CoreJobBinding.viewFor("running", 140, NOW, NOW).progress, 0.0001f);
        assertEquals(0f, CoreJobBinding.viewFor("running", -5, NOW, NOW).progress, 0.0001f);
    }

    @Test public void unknownOrMissingStateIsTreatedAsStillRunning() {
        // Honesty default: if the snapshot says something we do not know, assume the job is
        // alive with its percent — never declare a finished download that did not finish.
        CoreJobBinding.JobView v = CoreJobBinding.viewFor("weird", 42, NOW, NOW);
        assertEquals(CoreStates.PROGRESS, v.coreState);
        assertEquals(CoreStates.PROGRESS, CoreJobBinding.viewFor(null, 42, NOW, NOW).coreState);
    }
}
