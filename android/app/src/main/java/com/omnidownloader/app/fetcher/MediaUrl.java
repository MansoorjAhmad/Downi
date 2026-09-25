package com.omnidownloader.app.fetcher;

import java.net.URI;
import java.util.Locale;

/**
 * Is this URL a **media page** — a specific video/post — rather than a profile, bio or redirect link?
 *
 * Defect D-b (found on device 2026-09-25): the spike handed `https://fikrfreeapp.onelink.me/xoBT/…
 * tdnrp3bc` to the download pipeline as if it were a video. The dump path accepted *any* URL found
 * in the accessibility tree, and a bio link is just another string in that tree. Grabbing the wrong
 * thing is the one failure the owner's rules cannot tolerate, so the rule lives here as pure logic —
 * no Android types — and is unit-tested (`MediaUrlTest`), not buried in the spike.
 *
 * Deliberately narrow: only the two platforms the Fetcher targets (Instagram, TikTok — the owner's
 * Phase 0 scope) and only media paths. TikTok photo posts are recognised but reported as unsupported
 * (`tt_photo_post`), because "save a photo post" is deferred work (see `ROADMAP.md`), and a silent
 * failure there would look like the Fetcher being broken.
 *
 * `reason()` returns `null` when the URL is acceptable, otherwise a short machine-readable reason for
 * the log — so a rejected candidate says *why* it was rejected instead of vanishing.
 */
public final class MediaUrl {

    private MediaUrl() {}

    /** True when the URL names a specific video/post we can hand to the pipeline. */
    public static boolean isMedia(String url) {
        return reason(url) == null;
    }

    /**
     * `null` = media page. Otherwise a reason code: {@code null|empty|not_http|unparseable|no_host|
     * host_not_supported|not_a_media_path|tt_photo_post}.
     */
    public static String reason(String url) {
        if (url == null) return "null";
        String s = url.trim();
        if (s.isEmpty()) return "empty";
        String lower = s.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return "not_http";

        String host;
        String path;
        try {
            URI u = URI.create(s);
            host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
            path = u.getPath() == null ? "" : u.getPath();
        } catch (Throwable t) {
            return "unparseable";
        }
        if (host.isEmpty()) return "no_host";
        String h = host.startsWith("www.") ? host.substring(4) : host;

        if (h.equals("instagram.com") || h.endsWith(".instagram.com")) {
            // /p/<shortcode>/  /reel/<shortcode>/  /reels/<shortcode>/  /tv/<shortcode>/
            if (path.matches("^/(p|reel|reels|tv)/[A-Za-z0-9_-]+/?$")) return null;
            return "not_a_media_path";
        }
        if (h.equals("vt.tiktok.com") || h.equals("vm.tiktok.com")) {
            // the share shortener: /<code>/ or /<code>
            if (path.matches("^/[A-Za-z0-9]+/?$")) return null;
            return "not_a_media_path";
        }
        if (h.equals("tiktok.com") || h.endsWith(".tiktok.com")) {
            // /@user/video/<id>  /@user/photo/<id>  /t/<code>
            if (path.matches("^/@[^/]+/video/\\d+/?$")) return null;
            if (path.matches("^/@[^/]+/photo/\\d+/?$")) return "tt_photo_post";
            if (path.matches("^/t/[A-Za-z0-9]+/?$")) return null;
            return "not_a_media_path";
        }
        return "host_not_supported";
    }
}
