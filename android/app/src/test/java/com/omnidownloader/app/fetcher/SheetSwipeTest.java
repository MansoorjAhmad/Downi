package com.omnidownloader.app.fetcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Locks the sheet-scroll geometry (Wave 1, owner report "the reel scrolled"): BOTH swipe
 * endpoints must live INSIDE the share surface's own bounds — never on the feed behind it.
 * The old code used screen fractions (80% -> 64% of the display), which could fall through
 * to the feed when the sheet's window didn't consume the stroke.
 */
public class SheetSwipeTest {

    private static final float DP = 2.75f;   // the vivo V2058's density
    private static final int PAD = Math.round(24f * DP);

    @Test public void bothEndpointsStayInsideTheSheet() {
        // A bottom sheet occupying the lower ~40% of a 1080x2275 display.
        // endpoints = {x0, y0, x1, y1}: start low inside the sheet, end above it, still inside.
        float[] e = SheetSwipe.endpoints(0, 1365, 1080, 2275, DP);
        assertNotNull(e);
        assertTrue("start y inside the sheet", e[1] > 1365 && e[1] < 2275);
        assertTrue("end y inside the sheet", e[3] > 1365 && e[3] < 2275);
        assertTrue("swipe goes upward", e[3] < e[1]);
        assertTrue("horizontally centered", Math.abs(e[0] - 540f) < 0.5f);
        assertEquals(e[0], e[2], 0.5f);                       // vertical stroke
        assertTrue("start sits above the bottom edge", e[1] <= 2275 - PAD);
        assertTrue("end sits below the sheet's top edge", e[3] >= 1365 + PAD);
    }

    @Test public void travelIsACatchableScrollNotAFling() {
        float[] e = SheetSwipe.endpoints(0, 1365, 1080, 2275, DP);
        assertNotNull(e);
        float travel = e[1] - e[3];
        assertTrue("at least ~100dp of travel", travel >= 100f * DP);
        assertTrue("at most ~220dp of travel", travel <= 220f * DP + 0.5f);
    }

    @Test public void aTallSheetNeverScrollsAboveItsTop() {
        // IG's tall sheet: top edge near mid-screen — the clamp must stop at the top inset.
        float[] e = SheetSwipe.endpoints(0, 1200, 1080, 2275, DP);
        assertNotNull(e);
        assertTrue(e[3] >= 1200 + PAD);
    }

    @Test public void aShortSheetIsRefusedRatherThanTapped() {
        // A tiny surface (e.g. a thin chooser bar): a stroke that short is a tap — refuse.
        assertNull(SheetSwipe.endpoints(0, 2200, 1080, 2275, DP));
    }

    @Test public void degenerateBoundsAreRefused() {
        assertNull(SheetSwipe.endpoints(0, 0, 0, 0, DP));
        assertNull(SheetSwipe.endpoints(10, 100, 10, 900, DP));
        assertNull(SheetSwipe.endpoints(10, 900, 400, 900, DP));
        assertNull(SheetSwipe.endpoints(0, 0, 1080, 2275, 0f) == null ? null
                : SheetSwipe.endpoints(0, 0, 1080, 2275, 0f));   // zero density: still inside
    }
}
