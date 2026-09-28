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

    @Test public void keepsTheLastFiftyEventsOldestFirst() {
        EventRing r = new EventRing();
        for (int i = 1; i <= 60; i++) r.add(n(i));
        String[] s = r.snapshot();
        assertEquals(EventRing.CAPACITY, s.length);
        assertEquals("event 11", s[0]);                       // 1..10 fell out
        assertEquals("event 60", s[s.length - 1]);
        assertEquals(10, r.dropped());
        assertEquals(EventRing.CAPACITY, r.size());
    }

    @Test public void exactlyFiftyFitsWithoutDroppingAnything() {
        EventRing r = new EventRing();
        for (int i = 1; i <= 50; i++) r.add(n(i));
        assertEquals(50, r.snapshot().length);
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
        for (int i = 1; i <= 60; i++) dump.append(n(i)).append('\n');
        r.seed(dump.toString());
        assertEquals(EventRing.CAPACITY, r.size());
        assertEquals("event 11", r.snapshot()[0]);
        assertEquals("event 60", r.snapshot()[49]);
    }

    @Test public void dumpAndSnapshotAgree() {
        EventRing r = new EventRing();
        r.add("first"); r.add("second");
        assertEquals("first\nsecond", r.dump());
        assertEquals(r.dump(), r.asText());
        assertEquals(2, r.snapshot().length);
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
