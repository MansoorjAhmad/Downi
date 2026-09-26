package com.omnidownloader.app.fetcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Locks the rule that stops the Fetcher grabbing the wrong thing (defect D-b).
 *
 * The two URLs that matter most here were seen on the device on 2026-09-25: a real TikTok share link
 * that *must* pass (`vt.tiktok.com/ZSbL4MgaG/`, the 16:51 run) and a bio link that must now fail
 * (`fikrfreeapp.onelink.me/xoBT/tdnrp3bc`, handed to the pipeline at 11:06).
 */
public class MediaUrlTest {

    @Test public void acceptsRealInstagramMedia() {
        assertTrue(MediaUrl.isMedia("https://www.instagram.com/reel/DdU-2-4R7W-/?igsh=abc"));
        assertTrue(MediaUrl.isMedia("https://www.instagram.com/p/DdsM_GHEXAE/?img_index=14&stkn=x"));
        assertTrue(MediaUrl.isMedia("https://instagram.com/reels/Cx1y2z3/"));
        assertTrue(MediaUrl.isMedia("https://www.instagram.com/tv/AbCdEf12/"));
    }

    @Test public void acceptsRealTikTokMedia() {
        assertTrue(MediaUrl.isMedia("https://vt.tiktok.com/ZSbL4MgaG/"));
        assertTrue(MediaUrl.isMedia("https://vt.tiktok.com/ZSbLQEA22"));
        assertTrue(MediaUrl.isMedia("https://www.tiktok.com/@someone/video/7456123456789012345"));
    }

    @Test public void rejectsTheBioLinkThatWasGrabbedOnDevice() {
        assertEquals("host_not_supported",
                MediaUrl.reason("https://fikrfreeapp.onelink.me/xoBT/tdnrp3bc"));
        assertFalse(MediaUrl.isMedia("https://fikrfreeapp.onelink.me/xoBT/tdnrp3bc"));
    }

    @Test public void rejectsProfilesBiosAndRedirects() {
        assertEquals("not_a_media_path", MediaUrl.reason("https://www.instagram.com/mansoorj_ahmad/"));
        assertEquals("not_a_media_path", MediaUrl.reason("https://www.tiktok.com/@someone"));
        assertEquals("host_not_supported", MediaUrl.reason("https://linktr.ee/someone"));
        assertEquals("host_not_supported", MediaUrl.reason("https://youtu.be/dQw4w9WgXcQ"));
    }

    @Test public void reportsPhotoPostsAsUnsupportedRatherThanBroken() {
        // Recognised as media, but saving a photo post is deferred work (ROADMAP) — say so.
        assertEquals("tt_photo_post",
                MediaUrl.reason("https://www.tiktok.com/@someone/photo/7456123456789012345"));
    }

    @Test public void rejectsJunkWithoutThrowing() {
        assertNull(MediaUrl.reason("https://vt.tiktok.com/ZSbL4MgaG/"));   // the accepted case inverts
        assertEquals("null", MediaUrl.reason(null));
        assertEquals("empty", MediaUrl.reason("   "));
        assertEquals("not_http", MediaUrl.reason("vt.tiktok.com/ZSbL4MgaG/"));
        assertEquals("unparseable", MediaUrl.reason("https://[not a url"));
    }

    @Test public void isCaseInsensitiveOnHost() {
        assertTrue(MediaUrl.isMedia("https://WWW.Instagram.com/reel/DdU-2-4R7W-/"));
    }

    @Test public void canonicalizeStripsPerShareTracking() {
        // Wave 1: the same post must carry one string through the dedup layers, no matter
        // which share produced it. Real tracking shape from a device clipboard (D-k).
        assertEquals("https://www.instagram.com/reel/DdtJHggzNGA/",
                MediaUrl.canonicalize("https://www.instagram.com/reel/DdtJHggzNGA/"
                        + "?igsh=MXJteXQxdWE3Z3JuZQ==&utm_source=ig_web_copy_link"));
    }

    @Test public void canonicalizeStripsTheCarouselSlideIndex() {
        // D-g: img_index marks the SLIDE, not a different post — two sightings of one
        // carousel dedup to one string instead of grabbing the post twice.
        assertEquals("https://www.instagram.com/p/DdsM_GHEXAE/",
                MediaUrl.canonicalize("https://www.instagram.com/p/DdsM_GHEXAE/?img_index=14"));
    }

    @Test public void canonicalizeKeepsNonVaryingParametersAndPlainUrls() {
        // A parameter we do not know must survive (never over-strip).
        assertEquals("https://www.instagram.com/reel/AbC/?a=1",
                MediaUrl.canonicalize("https://www.instagram.com/reel/AbC/?a=1&_r=1"));
        // TikTok short links carry the code in the PATH — nothing to strip, byte-identical.
        assertEquals("https://vt.tiktok.com/ZSbL4MgaG/", MediaUrl.canonicalize("https://vt.tiktok.com/ZSbL4MgaG/"));
        // Junk in, junk out — canonicalize never throws and never invents.
        assertEquals("not a url", MediaUrl.canonicalize("not a url"));
        assertNull(MediaUrl.canonicalize(null));
    }
}
