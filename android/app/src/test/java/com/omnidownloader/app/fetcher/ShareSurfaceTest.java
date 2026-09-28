package com.omnidownloader.app.fetcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;

import org.junit.Test;

/**
 * Locks the share-surface policy (owner report 2026-09-28: on TikTok the video jumped to the next
 * one after every grab, 5/5).
 *
 * The deleted rule was "any window that is not ours, not `com.android.systemui` and not the target
 * app is a share surface". This ROM always has such a window — `com.vivo.upslide`'s
 * `SideSlideGestureBar-Bottom` (and the IME whenever a keyboard is up) — so the BACK press that was
 * supposed to close the sheet landed on the FEED after TikTok's sheet had already dismissed itself,
 * and advanced the reel. Every test below is one of the reasons that can no longer happen.
 *
 * Numbers are this phone's own: 1080 x 2408 display, a bottom sheet occupying the lower ~38%.
 */
public class ShareSurfaceTest {

    private static final int DW = 1080, DH = 2408;
    private static final String US = "com.omnidownloader.app";
    private static final String SYS_UI = "com.android.systemui";
    private static final String IG = "com.instagram.android";
    private static final String[] EXCLUDED = {US, SYS_UI, IG};

    /** The real window from this phone's dump: base strip, full width, ~2.5% tall. */
    private static ShareSurface.Win gestureBar() {
        return ShareSurface.win("com.vivo.upslide", "SideSlideGestureBar-Bottom",
                ShareSurface.TYPE_SYSTEM, 0, 2348, 1080, 2408, DW, DH);
    }

    /** The sheet this ROM opens for a share tap (same package as the gesture bar). */
    private static ShareSurface.Win sheet() {
        return ShareSurface.win("com.vivo.upslide", "UpslideBottomSheet",
                ShareSurface.TYPE_APPLICATION, 0, 1498, 1080, 2408, DW, DH);
    }

    private static ShareSurface.Win keyboard() {
        return ShareSurface.win("com.google.android.inputmethod.latin", "",
                ShareSurface.TYPE_INPUT_METHOD, 0, 1400, 1080, 2408, DW, DH);
    }

    @Test public void theGestureBarIsNeverAShareSurface() {
        ShareSurface.Win w = gestureBar();
        assertFalse(ShareSurface.countsAsSurface(w, EXCLUDED));
        assertEquals("decoration", ShareSurface.rejectReason(w, EXCLUDED));
        assertTrue("the title test catches it even with unknown geometry",
                ShareSurface.isDecoration(ShareSurface.win("com.vivo.upslide",
                        "SideSlideGestureBar-Bottom", ShareSurface.TYPE_SYSTEM, 0, 0, 1080, 2408, DW, DH)));
    }

    @Test public void aBarWithNoTitleIsCaughtByGeometryAlone() {
        // Same strip, but some other ROM calls it something we have never seen.
        ShareSurface.Win w = ShareSurface.win("com.vivo.upslide", "",
                ShareSurface.TYPE_SYSTEM, 0, 2348, 1080, 2408, DW, DH);
        assertFalse(ShareSurface.countsAsSurface(w, EXCLUDED));
        assertEquals("too_short", ShareSurface.rejectReason(w, EXCLUDED));
    }

    @Test public void theKeyboardIsNeverAShareSurfaceEvenThoughItIsTall() {
        ShareSurface.Win w = keyboard();
        assertTrue("big enough to fool a geometry-only rule", w.heightFrac() > 0.2f);
        assertFalse(ShareSurface.countsAsSurface(w, EXCLUDED));
        assertEquals("ime", ShareSurface.rejectReason(w, EXCLUDED));
    }

    @Test public void aBottomSheetIsAShareSurface() {
        ShareSurface.Win w = sheet();
        assertTrue(ShareSurface.countsAsSurface(w, EXCLUDED));
        assertEquals("", ShareSurface.rejectReason(w, EXCLUDED));
        assertTrue("sheet-sized", w.heightFrac() > 0.3f && w.widthFrac() > 0.9f);
    }

    @Test public void theSystemChooserStillCounts() {
        // The system resolver is full-screen and must keep working (pickShareRoot prefers it).
        ShareSurface.Win w = ShareSurface.win("android",
                "Share with", ShareSurface.TYPE_SYSTEM, 0, 0, 1080, 2408, DW, DH);
        assertTrue(ShareSurface.countsAsSurface(w, EXCLUDED));
    }

    @Test public void ourOwnOverlayAndTheSystemUiAreExcluded() {
        ShareSurface.Win ours = ShareSurface.win(US, "DOWNI",
                ShareSurface.TYPE_ACCESSIBILITY_OVERLAY, 0, 0, 1080, 2408, DW, DH);
        ShareSurface.Win shade = ShareSurface.win(SYS_UI, "NotificationShade",
                ShareSurface.TYPE_SYSTEM, 0, 0, 1080, 2408, DW, DH);
        assertFalse(ShareSurface.countsAsSurface(ours, EXCLUDED));
        assertEquals("excluded", ShareSurface.rejectReason(ours, EXCLUDED));
        assertFalse(ShareSurface.countsAsSurface(shade, EXCLUDED));
        assertEquals("systemui", ShareSurface.rejectReason(shade, EXCLUDED));
    }

    @Test public void aToastSliverIsNotASurface() {
        // A third-party app's toast (not an excluded package, so geometry is the only thing that
        // can reject it) — a sliver of a window must never be mistaken for a sheet.
        ShareSurface.Win w = ShareSurface.win("com.example.other", "",
                ShareSurface.TYPE_APPLICATION, 200, 2100, 800, 2220, DW, DH);
        assertFalse(ShareSurface.countsAsSurface(w, EXCLUDED));
        assertEquals("too_short", ShareSurface.rejectReason(w, EXCLUDED));
    }

    @Test public void aNarrowSidePanelIsNotASurface() {
        ShareSurface.Win w = ShareSurface.win("com.vivo.upslide", "",
                ShareSurface.TYPE_SYSTEM, 0, 400, 200, 2000, DW, DH);
        assertFalse(ShareSurface.countsAsSurface(w, EXCLUDED));
        assertEquals("too_narrow", ShareSurface.rejectReason(w, EXCLUDED));
    }

    // ---- part 2 ----

    @Test public void pickIgnoresFurnitureAndFindsTheSheet() {
        ArrayList<ShareSurface.Win> topDown = new ArrayList<>(Arrays.asList(gestureBar(), sheet()));
        assertEquals("the sheet is the surface, not the bar above it", 1,
                ShareSurface.pickIndex(topDown, EXCLUDED));

        ArrayList<ShareSurface.Win> sheetFirst = new ArrayList<>(Arrays.asList(sheet(), gestureBar()));
        assertEquals(0, ShareSurface.pickIndex(sheetFirst, EXCLUDED));
    }

    @Test public void theOldRuleWouldHaveSaidYesWhereTheNewRuleSaysNo() {
        // This is the regression lock. The deleted rule looked for "any window that is not ours,
        // not systemui and not the target app" — this list contains exactly such a window (the
        // gesture bar), so it answered YES and the chain pressed BACK on the feed.
        ArrayList<ShareSurface.Win> furnitureOnly =
                new ArrayList<>(Arrays.asList(gestureBar(), keyboard()));
        assertEquals(-1, ShareSurface.pickIndex(furnitureOnly, EXCLUDED));
    }

    @Test public void stillOpenIsFalseOnceTheSheetIsGoneEvenThoughFurnitureRemains() {
        // TikTok's sheet auto-dismisses after "Copy link": the recorded sheet is gone, the gesture
        // bar stays. The close gate must therefore NOT press BACK (the 2026-09-28 defect).
        ShareSurface.Win recorded = sheet();
        ArrayList<ShareSurface.Win> now = new ArrayList<>(Arrays.asList(gestureBar(), keyboard()));
        assertFalse(ShareSurface.stillOpen(recorded, now));
    }

    @Test public void stillOpenIsTrueWhileTheSheetIsStillThere() {
        ShareSurface.Win recorded = sheet();
        ArrayList<ShareSurface.Win> now = new ArrayList<>(Arrays.asList(gestureBar(), sheet()));
        assertTrue(ShareSurface.stillOpen(recorded, now));
    }

    @Test public void stillOpenToleratesASettlingSheet() {
        // The sheet animates into place; a few pixels of drift is still the same window.
        ShareSurface.Win recorded = sheet();
        ShareSurface.Win settled = ShareSurface.win("com.vivo.upslide", "UpslideBottomSheet",
                ShareSurface.TYPE_APPLICATION, 0, 1498 + 20, 1080, 2408 + 20, DW, DH);
        assertTrue(ShareSurface.stillOpen(recorded, new ArrayList<>(Arrays.asList(settled))));
    }

    @Test public void stillOpenRejectsARecycledPackageWithADifferentWindow() {
        ShareSurface.Win recorded = sheet();
        ShareSurface.Win other = ShareSurface.win("com.vivo.upslide", "SomeOtherPanel",
                ShareSurface.TYPE_APPLICATION, 0, 1498, 1080, 2408, DW, DH);
        assertFalse("same package and bounds, different window", ShareSurface.stillOpen(
                recorded, new ArrayList<>(Arrays.asList(other))));
    }

    @Test public void stillOpenRejectsAMovedWindowOfTheSameName() {
        ShareSurface.Win recorded = ShareSurface.win("com.vivo.upslide", "",
                ShareSurface.TYPE_APPLICATION, 0, 1498, 1080, 2408, DW, DH);
        ShareSurface.Win moved = ShareSurface.win("com.vivo.upslide", "",
                ShareSurface.TYPE_APPLICATION, 0, 600, 1080, 1500, DW, DH);
        assertFalse(ShareSurface.stillOpen(recorded,
                new ArrayList<>(Arrays.asList(moved))));
    }

    @Test public void describeNamesTheVerdictForTheLog() {
        String line = ShareSurface.describe(sheet(), EXCLUDED);
        assertNotNull(line);
        assertTrue("says what it saw", line.contains("com.vivo.upslide"));
        assertTrue("says what it decided", line.contains("verdict=surface"));
        assertTrue("names the reason a refusal happened",
                ShareSurface.describe(gestureBar(), EXCLUDED).contains("verdict=decoration"));
    }
}
