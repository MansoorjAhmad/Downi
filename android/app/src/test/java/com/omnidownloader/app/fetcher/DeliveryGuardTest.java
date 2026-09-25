package com.omnidownloader.app.fetcher;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Locks the cross-run duplicate guard: a rapid retap on the same video must not start a second
 * identical job (the file-level symptom was D-a's "Video (1).mp4" twin), while a deliberate
 * re-fetch after the window still passes. Deterministic — the clock is an argument, never a sleep.
 */
public class DeliveryGuardTest {

    private static final long WINDOW = 8_000L;
    private static final String TIKTOK = "https://vt.tiktok.com/ZSbL4MgaG/";
    private static final String INSTAGRAM = "https://www.instagram.com/reel/DdU-2-4R7W-/";

    @Test public void firstDeliveryIsAlwaysAllowed() {
        assertTrue(new DeliveryGuard(WINDOW).allow(TIKTOK, 1_000L));
    }

    @Test public void sameUrlInsideTheWindowIsSuppressed() {
        DeliveryGuard g = new DeliveryGuard(WINDOW);
        g.record(TIKTOK, 1_000L);
        assertFalse("a rapid retap must not start a second identical job",
                g.allow(TIKTOK, 1_000L + WINDOW - 1));
    }

    @Test public void sameUrlAfterTheWindowIsAllowedAgain() {
        DeliveryGuard g = new DeliveryGuard(WINDOW);
        g.record(TIKTOK, 1_000L);
        assertTrue("a deliberate re-fetch after the window must pass",
                g.allow(TIKTOK, 1_000L + WINDOW));
    }

    @Test public void aDifferentUrlIsNeverBlockedByTheWindow() {
        DeliveryGuard g = new DeliveryGuard(WINDOW);
        g.record(TIKTOK, 1_000L);
        assertTrue(g.allow(INSTAGRAM, 1_001L));
    }

    @Test public void boundaryIsInclusiveAtExactlyTheWindow() {
        DeliveryGuard g = new DeliveryGuard(WINDOW);
        g.record(TIKTOK, 1_000L);
        assertFalse(g.allow(TIKTOK, 1_000L + WINDOW - 1));
        assertTrue(g.allow(TIKTOK, 1_000L + WINDOW));
    }

    @Test public void emptyAndNullUrlsBypassTheGuard() {
        DeliveryGuard g = new DeliveryGuard(WINDOW);
        assertTrue(g.allow(null, 0L));
        assertTrue(g.allow("", 0L));
        g.record(null, 0L);
        g.record("", 0L);
        assertTrue("recording nothing must not arm the guard", g.allow(TIKTOK, 1L));
    }
}
