package com.omnidownloader.app.fetcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Locks the row matcher (owner report 2026-09-28: the Fetcher is reliable on Instagram's Reels tab
 * but "inconsistent" on the Home feed).
 *
 * The Home feed is the reason this class exists: its share row is `row_feed_button_share`, whose own
 * description is "Send post. Button. Double tap to choose who to send this post to." — it contains no
 * "share" at all, so the old matcher never even listed it as a candidate. It is also
 * `clickable=false` in the tree (measured on the phone), which is why the chain taps its bounds as a
 * fallback and has the post's overflow as a second route.
 *
 * Every string below was copied from a real dump of this phone's Instagram/TikTok trees.
 */
public class ShareRowsTest {

    private static final String FEED_ROW_DESC =
            "Send post. Button. Double tap to choose who to send this post to.";
    private static final String FEED_ROW_ID = "com.instagram.android:id/row_feed_button_share";
    private static final String OVERFLOW_DESC = "More actions for this post";
    private static final String OVERFLOW_ID = "com.instagram.android:id/media_option_button";

    @Test public void theInstagramHomeFeedRowIsAShareRow() {
        assertEquals(ShareRows.SHARE, ShareRows.classify(FEED_ROW_DESC, FEED_ROW_ID));
        // …and it must still classify without the id, because ids are the platform's to rename.
        assertEquals(ShareRows.SHARE, ShareRows.classify(FEED_ROW_DESC, ""));
    }

    @Test public void theOldMatcherWouldHaveMissedTheFeedRow() {
        // The regression lock: the deleted rule was "text contains 'share' and not 'reshare'".
        assertFalse("the feed's own description never says 'share'",
                FEED_ROW_DESC.toLowerCase().contains("share"));
        assertTrue(ShareRows.isShare(FEED_ROW_DESC, FEED_ROW_ID));
    }

    @Test public void reelsAndTiktokShareRowsStillClassify() {
        assertEquals(ShareRows.SHARE, ShareRows.classify("Share", "com.instagram.android:id/direct_share_button"));
        assertEquals(ShareRows.SHARE, ShareRows.classify("Share video 116.3K shares", ""));
    }

    @Test public void reshareIsNotTheShareRow() {
        assertEquals(ShareRows.NONE, ShareRows.classify("Reshare", ""));
        assertFalse(ShareRows.isShare("Reshare", ""));
    }

    @Test public void theOverflowIsTheFeedsFallbackRoute() {
        assertEquals(ShareRows.OVERFLOW, ShareRows.classify(OVERFLOW_DESC, OVERFLOW_ID));
        assertEquals(ShareRows.OVERFLOW, ShareRows.classify(OVERFLOW_DESC, ""));
        assertTrue(ShareRows.isOverflow("More options for this post", ""));
    }

    @Test public void copyLinkIsTheTransport() {
        assertEquals(ShareRows.COPY_LINK, ShareRows.classify("Copy link", ""));
        assertTrue(ShareRows.isCopyLink("Copy link", ""));
    }

    @Test public void aRowNamingDowniIsNeverClickedAsTheShareRow() {
        // The 2026-09-25 ruling: clicking DOWNI in the chooser is Downi Drop's transport, not the
        // Fetcher's — so it stays out of every list the chain acts on, even worded as "Send to".
        assertEquals(ShareRows.DOWNI, ShareRows.classify("Send to DOWNI", ""));
        assertEquals(ShareRows.DOWNI, ShareRows.classify("DOWNI", ""));
        assertFalse(ShareRows.isShare("Send to DOWNI", ""));
    }

    @Test public void unrelatedRowsAreNotCandidates() {
        assertEquals(ShareRows.NONE, ShareRows.classify("Like video 454K likes", ""));
        assertEquals(ShareRows.NONE, ShareRows.classify("Read or add comments. 9,642 comments", ""));
        assertEquals(ShareRows.NONE, ShareRows.classify("", ""));
        assertEquals(ShareRows.NONE, ShareRows.classify(null, null));
    }

    @Test public void theNamesMatchTheLogVocabulary() {
        assertEquals("share", ShareRows.name(ShareRows.SHARE));
        assertEquals("copy_link", ShareRows.name(ShareRows.COPY_LINK));
        assertEquals("overflow", ShareRows.name(ShareRows.OVERFLOW));
        assertEquals("downi", ShareRows.name(ShareRows.DOWNI));
        assertEquals("none", ShareRows.name(ShareRows.NONE));
    }
}
