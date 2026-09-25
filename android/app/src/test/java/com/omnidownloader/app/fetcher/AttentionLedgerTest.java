package com.omnidownloader.app.fetcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * Locks the attention model's safety contract: a candidate only becomes READY when every piece
 * of evidence agrees — visible, dwelled, same feed-page (no scroll since), present in recent
 * dumps, fresh. Anything less is PREPARING or STALE and can never serve a silent download.
 * A wrong instant-download is the one unforgivable sin; these tests are that sin's cage.
 */
public class AttentionLedgerTest {

    private static final String URL = "https://www.instagram.com/reel/DdU-2-4R7W-/";
    private static final long T0 = 1_000_000L;

    @Test public void visibleAndDWELLED_becomesReady() {
        AttentionLedger l = new AttentionLedger();
        l.beginDump(T0);
        l.offer(URL, true, T0);
        assertEquals(AttentionLedger.Grade.PREPARING, l.gradeOf(URL, T0 + 100));   // too fresh
        l.beginDump(T0 + 900);
        l.offer(URL, true, T0 + 900);                                              // re-offered = still on screen
        assertEquals(AttentionLedger.Grade.READY, l.gradeOf(URL, T0 + 950));
    }

    @Test public void neverVisibleStopsAtPreparing() {
        AttentionLedger l = new AttentionLedger();
        l.beginDump(T0);
        l.offer(URL, false, T0);
        l.beginDump(T0 + 2_000);
        l.offer(URL, false, T0 + 2_000);
        assertEquals(AttentionLedger.Grade.PREPARING, l.gradeOf(URL, T0 + 5_000));
        assertNull("an invisible candidate must never serve a download", l.best(T0 + 5_000));
    }

    @Test public void scrollTransitionDemotesEverythingSeenBeforeIt() {
        AttentionLedger l = new AttentionLedger();
        l.beginDump(T0);
        l.offer(URL, true, T0);
        l.beginDump(T0 + 1_000);
        l.offer(URL, true, T0 + 1_000);                                            // dwelled, READY...
        assertEquals(AttentionLedger.Grade.READY, l.gradeOf(URL, T0 + 1_100));
        l.onScrollTransition();                                                    // the feed moved on
        assertEquals("a pre-scroll candidate is a DIFFERENT video now",
                AttentionLedger.Grade.STALE, l.gradeOf(URL, T0 + 1_200));
        assertNull(l.best(T0 + 1_200));
    }

    @Test public void goingMissingFromConsecutiveDumpsDemotes() {
        AttentionLedger l = new AttentionLedger();
        l.beginDump(T0);
        l.offer(URL, true, T0);
        l.beginDump(T0 + 1_000);
        l.offer(URL, true, T0 + 1_000);                                            // READY
        l.beginDump(T0 + 2_000);                                                   // absent #1 (auto-advance)
        l.beginDump(T0 + 3_000);                                                   // absent #2
        assertEquals("two absences = the feed moved on",
                AttentionLedger.Grade.STALE, l.gradeOf(URL, T0 + 3_100));
    }

    @Test public void singleAbsenceIsNotEnoughToDemote() {
        AttentionLedger l = new AttentionLedger();
        l.beginDump(T0);
        l.offer(URL, true, T0);
        l.beginDump(T0 + 900);
        l.offer(URL, true, T0 + 900);                                              // READY
        l.beginDump(T0 + 1_800);                                                   // one quiet dump
        assertEquals(AttentionLedger.Grade.READY, l.gradeOf(URL, T0 + 1_900));
    }

    @Test public void bestPrefersTheMostRecentSighting() {
        AttentionLedger l = new AttentionLedger();
        String older = "https://www.instagram.com/reel/Old12345678/";
        String newer = "https://www.instagram.com/reel/New98765432/";
        l.beginDump(T0);
        l.offer(older, true, T0);
        l.beginDump(T0 + 900);
        l.offer(older, true, T0 + 900);
        l.beginDump(T0 + 1_800);
        l.offer(newer, true, T0 + 1_800);
        l.beginDump(T0 + 2_700);
        l.offer(newer, true, T0 + 2_700);
        assertEquals(newer, l.best(T0 + 2_800).url);
    }

    @Test public void maxAgeBoundsEvenAQuietSession() {
        AttentionLedger l = new AttentionLedger();
        l.beginDump(T0);
        l.offer(URL, true, T0);
        l.beginDump(T0 + 900);
        l.offer(URL, true, T0 + 900);
        assertEquals(AttentionLedger.Grade.READY, l.gradeOf(URL, T0 + 170_000));
        assertEquals(AttentionLedger.Grade.STALE, l.gradeOf(URL, T0 + AttentionLedger.MAX_AGE_MS + 1_000));
    }

    @Test public void clearWipesEverything() {
        AttentionLedger l = new AttentionLedger();
        l.beginDump(T0);
        l.offer(URL, true, T0);
        l.clear();
        assertEquals(0, l.size());
        assertNull(l.best(T0 + 1));
    }
}
