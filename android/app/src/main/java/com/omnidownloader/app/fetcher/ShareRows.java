package com.omnidownloader.app.fetcher;

import java.util.Locale;

/**
 * Which platform row is which — the matcher behind the chain's candidate lists, extracted so it can
 * be tested without a device.
 *
 * Two measured reasons this exists:
 *
 *   1. **Instagram's Home feed names its share row "Send post"** — `row_feed_button_share`'s own
 *      description is *"Send post. Button. Double tap to choose who to send this post to."* — and the
 *      old matcher only looked for the word "share", so on the feed the row was not even a candidate
 *      (owner report 2026-09-28: "on the Home feed it is inconsistent — sometimes it fails to detect").
 *      The row is ALSO `clickable=false` in the tree, which is why a bounds tap exists as a fallback.
 *   2. **The feed's second route is the post's own overflow** (`media_option_button`,
 *      *"More actions for this post"*) whose menu carries the platform's own **Copy link** — the same
 *      transport the chain already trusts, reached without opening a share sheet at all.
 *
 * Matching is by the node's own text/description first and by its view id second, because resource
 * ids are the platform's to change and the descriptions are what a human (and the a11y tree) sees.
 * Keep this class pure: `ShareRowsTest` locks every string below, including the exact feed row.
 */
public final class ShareRows {

    public static final int NONE = 0;
    /** A row that opens the platform's share sheet ("Share", "Send post", "Send to…"). */
    public static final int SHARE = 1;
    /** The platform's own "Copy link" row. */
    public static final int COPY_LINK = 2;
    /** The post's overflow ("More actions for this post") — the feed's fallback route. */
    public static final int OVERFLOW = 3;
    /** Anything naming DOWNI — never used as a transport by the Fetcher (that is Downi Drop). */
    public static final int DOWNI = 4;

    private ShareRows() {}

    /** Classifies one node. `text` is text + contentDescription; `viewId` may be empty. */
    public static int classify(String text, String viewId) {
        String t = text == null ? "" : text.toLowerCase(Locale.US);
        String id = viewId == null ? "" : viewId.toLowerCase(Locale.US);

        // View ids first: they name the control exactly, when the platform still uses them.
        if (id.endsWith("/row_feed_button_share") || id.endsWith("/direct_share_button")) return SHARE;
        if (id.endsWith("/media_option_button")) return OVERFLOW;

        if (t.contains("copy link")) return COPY_LINK;
        if (t.contains("more actions") || t.contains("more options") || t.contains("post options")) {
            return OVERFLOW;
        }
        // Before any "send to…" pattern: a row that names DOWNI is Drop's transport, and the ruling
        // of 2026-09-25 is that the Fetcher never clicks it — not even when it reads "Send to DOWNI".
        if (t.contains("downi")) return DOWNI;
        if (t.contains("send post") || t.contains("send this post") || t.contains("send to")) {
            return SHARE;
        }
        if (t.contains("share") && !t.contains("reshare")) return SHARE;
        return NONE;
    }

    public static boolean isShare(String text, String viewId) { return classify(text, viewId) == SHARE; }
    public static boolean isCopyLink(String text, String viewId) { return classify(text, viewId) == COPY_LINK; }
    public static boolean isOverflow(String text, String viewId) { return classify(text, viewId) == OVERFLOW; }

    /** The name the bench log uses for a classification (so a run explains its own routing). */
    public static String name(int kind) {
        switch (kind) {
            case SHARE: return "share";
            case COPY_LINK: return "copy_link";
            case OVERFLOW: return "overflow";
            case DOWNI: return "downi";
            default: return "none";
        }
    }
}
