package com.omnidownloader.app.fetcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Locks the per-platform profiles (Wave 1): the packages that open sessions, which
 * platform the passive ledger may serve, and the ordered routes a tap tries. These
 * values are device-measured (DEVICE_TEST.md §5 route attribution) — the resolver's
 * behavior follows them, so a silent change here would change where taps go.
 */
public class PlatformProfileTest {

    @Test public void packagesOpenTheRightSessions() {
        assertEquals(PlatformProfile.INSTAGRAM, PlatformProfile.forPackage("com.instagram.android"));
        assertEquals(PlatformProfile.TIKTOK, PlatformProfile.forPackage("com.zhiliaoapp.musically"));
        assertEquals(PlatformProfile.TIKTOK, PlatformProfile.forPackage("com.ss.android.ugc.trill"));
        assertNull(PlatformProfile.forPackage("com.android.chrome"));
        assertNull(PlatformProfile.forPackage(null));
    }

    @Test public void isTargetMatchesTheServiceTargetSet() {
        assertTrue(PlatformProfile.isTarget("com.instagram.android"));
        assertTrue(PlatformProfile.isTarget("com.zhiliaoapp.musically"));
        assertFalse(PlatformProfile.isTarget("com.whatsapp"));
    }

    @Test public void instagramIsPassiveCapableTikTokIsNot() {
        assertTrue(PlatformProfile.INSTAGRAM.passiveCapable);
        assertFalse(PlatformProfile.TIKTOK.passiveCapable);
    }

    @Test public void routeOrdersFollowTheMeasuredAttribution() {
        String[] ig = PlatformProfile.INSTAGRAM.routes;
        assertEquals(Route.LEDGER, ig[0]);
        assertEquals(Route.SHEET_TREE, ig[1]);
        assertEquals(Route.COPY_LINK, ig[2]);

        String[] tt = PlatformProfile.TIKTOK.routes;
        assertEquals(Route.SHEET_TREE, tt[0]);
        assertEquals(Route.COPY_LINK, tt[1]);
        assertFalse(Route.isKnown(tt[0]) && tt.length > 3);   // no invented routes
    }

    @Test public void budgetsMatchTheDeviceTuning() {
        assertEquals(1, PlatformProfile.INSTAGRAM.sheetTreeRetries);   // the 350 ms webview beat
        assertEquals(0, PlatformProfile.TIKTOK.sheetTreeRetries);      // TikTok's sheet never leaks
        assertEquals(2, PlatformProfile.INSTAGRAM.maxSheetSwipes);
        assertEquals(2, PlatformProfile.TIKTOK.maxSheetSwipes);
    }

    @Test public void forUrlRoutesTheCanonicalizationHost() {
        assertEquals(PlatformProfile.INSTAGRAM, PlatformProfile.forUrl("https://www.instagram.com/reel/AbCd/"));
        assertEquals(PlatformProfile.TIKTOK, PlatformProfile.forUrl("https://vt.tiktok.com/AbC123/"));
        assertNull(PlatformProfile.forUrl("https://youtube.com/watch?v=x"));
        assertNull(PlatformProfile.forUrl(null));
    }
}
