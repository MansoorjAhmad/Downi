package com.omnidownloader.app.fetcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Locks the black box (Wave 5, 2026-09-28): fifty events, oldest first, flushed every four, with the
 * previous session carried over so a vendor kill can still be read afterwards.
 */
public class EventRingTest {

    private static String n(int i) { return "event " + i; }

    @Test public void keepsTheLastCapacityEventsOldestFirst() {
        EventRing r = new EventRing();
        int extra = 10;
        for (int i = 1; i <= EventRing.CAPACITY + extra; i++) r.add(n(i));
        String[] s = r.snapshot();
        assertEquals(EventRing.CAPACITY, s.length);
        assertEquals("event " + (extra + 1), s[0]);                  // the oldest 10 fell out
        assertEquals("event " + (EventRing.CAPACITY + extra), s[s.length - 1]);
        assertEquals(extra, r.dropped());
        assertEquals(EventRing.CAPACITY, r.size());
    }

    @Test public void exactlyCapacityFitsWithoutDroppingAnything() {
        EventRing r = new EventRing();
        for (int i = 1; i <= EventRing.CAPACITY; i++) r.add(n(i));
        assertEquals(EventRing.CAPACITY, r.snapshot().length);
        assertEquals(0, r.dropped());
        assertEquals("event 1", r.snapshot()[0]);
    }

    @Test public void flushIsAskedForEveryFourEvents() {
        EventRing r = new EventRing();
        r.add(n(1)); assertFalse(r.shouldFlush());
        r.add(n(2)); assertFalse(r.shouldFlush());
        r.add(n(3)); assertFalse(r.shouldFlush());
        r.add(n(4)); assertTrue("the fourth event asks to be persisted", r.shouldFlush());
        r.markFlushed();
        assertFalse("and the cadence restarts", r.shouldFlush());
        r.add(n(5)); r.add(n(6)); r.add(n(7)); r.add(n(8));
        assertTrue(r.shouldFlush());
    }

    @Test public void longLinesAreCappedAndFlattened() {
        EventRing r = new EventRing();
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 400; i++) big.append('x');
        r.add("  a\nb\tc  " + big);
        String one = r.snapshot()[0];
        assertEquals(EventRing.MAX_LINE, one.length());
        assertTrue(one.startsWith("a b c "));
        assertTrue(one.endsWith("…"));
        assertFalse("no newline survives", one.contains("\n"));
    }

    @Test public void blankEventsAreIgnored() {
        EventRing r = new EventRing();
        r.add(null); r.add(""); r.add("   "); r.add("\n");
        assertEquals(0, r.size());
        assertTrue(r.isEmpty());
    }

    @Test public void aPreviousSessionIsCarriedOverSoTheKillStaysReadable() {
        EventRing r = new EventRing();
        // The kill test: these were the events before the process died.
        int seeded = r.seed("10:00:01 CHAIN_SHARE_CLICK route=action\n10:00:02 CHAIN_CLIPBOARD got=null");
        assertEquals(2, seeded);
        r.add("10:05:00 SESSION_DIED_UNEXPECTEDLY prev_last=CHAIN_CLIPBOARD got=null");
        String[] s = r.snapshot();
        assertEquals(3, s.length);
        assertTrue("the pre-kill events are still first", s[0].contains("CHAIN_SHARE_CLICK"));
        assertTrue("and the marker is last", s[2].contains("SESSION_DIED_UNEXPECTEDLY"));
    }

    @Test public void seedingRespectsTheCapacity() {
        EventRing r = new EventRing();
        StringBuilder dump = new StringBuilder();
        for (int i = 1; i <= EventRing.CAPACITY + 10; i++) dump.append(n(i)).append('\n');
        r.seed(dump.toString());
        assertEquals(EventRing.CAPACITY, r.size());
        assertEquals("event 11", r.snapshot()[0]);                                  // 10 fell out
        assertEquals("event " + (EventRing.CAPACITY + 10), r.snapshot()[EventRing.CAPACITY - 1]);
    }

    @Test public void dumpAndSnapshotAgree() {
        EventRing r = new EventRing();
        r.add("first"); r.add("second");
        assertEquals("first\nsecond", r.dump());
        assertEquals(r.dump(), r.asText());
        assertEquals(2, r.snapshot().length);
    }

    @Test public void perPollChatterNeverEvictsTheMilestones() {
        // The 2026-09-28 finding: 15 `DUMP reason=… identical_to_previous` lines (one per ~600 ms of
        // waiting) pushed two grabs' ENGINE lines out of the box. Noise is not history.
        EventRing r = new EventRing();
        r.add("10:00:00.000 CHAIN_SCAN pkg=com.instagram.android share=1 copylink=0 downi=0 overflow=1");
        for (int i = 0; i < 80; i++) {
            r.add("10:00:0" + (i % 10) + ".000 DUMP reason=content_changed nodes=70 identical_to_previous");
        }
        r.add("10:00:10.000 ENGINE_JOB_START platform=instagram fmt=best job=drop123");
        String[] s = r.snapshot();
        assertEquals("only the milestones are kept", 2, s.length);
        assertTrue(s[0].contains("CHAIN_SCAN"));
        assertTrue(s[1].contains("ENGINE_JOB_START"));
        assertEquals("and nothing was aged out by the chatter", 0, r.dropped());
    }

    @Test public void theNoiseFilterIsNarrowEnoughToKeepTheVerdicts() {
        assertTrue(EventRing.isNoise("10:00:00.000 DUMP reason=content_changed nodes=70 identical_to_previous"));
        assertFalse("the numbered dump carries the verdict",
                EventRing.isNoise("10:00:00.000 STEP3_DUMP_15 confidence=HIGH source=tree url=https://…"));
        assertFalse(EventRing.isNoise("10:00:00.000 ENGINE_FIRST_NUMBER ms=412 total=7333756"));
        assertFalse(EventRing.isNoise("10:00:00.000 CHAIN_SHARE_CLICK route=gesture_bounds_ok=true"));
        assertFalse(EventRing.isNoise(null));
    }

    @Test public void clearEmptiesTheBoxAndItsCounters() {
        EventRing r = new EventRing();
        for (int i = 1; i <= 55; i++) r.add(n(i));
        r.clear();
        assertEquals(0, r.size());
        assertEquals(0, r.dropped());
        assertFalse(r.shouldFlush());
        assertEquals("", r.dump());
    }
}
