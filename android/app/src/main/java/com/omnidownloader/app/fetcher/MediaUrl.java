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
 *
 * `canonicalize()` (Wave 1) normalizes a media URL BEFORE the duplicate checks: Instagram's
 * copy link and tree sightings carry per-share tracking parameters and, on carousel posts,
 * the slide index (`img_index`) — the same post produced different strings and defeated the
 * dedup layers. Only known-varying parameters are removed; the media path is untouched.
 *
 * D-g ruling (Wave 1, owner plan §3.10): Instagram `/p/` URLs are ACCEPTED. A `/p/` path
 * cannot be distinguished photo-vs-video from the URL shape alone (unlike TikTok's
 * `/photo/`), so flagging every `/p/` would refuse real video posts. The engine stays the
 * honest gate: a photo post fails there with a plain reason, and the Core shows its
 * restrained failure — never a silent wrong file.
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

    /**
     * C6's **unsupported read**: the link is not a video the Fetcher can grab, as opposed to a grab
     * that failed to resolve (a transient failure, which keeps the rose FAILED look and the
     * retry-ready feel).
     *
     * Sheet C6 names both halves — *"link not from TikTok/Instagram, or can't be resolved"*:
     *
     *   host_not_supported   a host that is neither TikTok nor Instagram  (C6: "not from ...")
     *   not_a_media_path     a profile / bio / redirect page, not a video  (C6: "can't be resolved")
     *   tt_photo_post        a TikTok photo post (recognised, not a video)
     *
     * Everything else {@link #reason} can say about a *string* — `not_http`, `unparseable`, `empty`,
     * `no_host`, `null` — is a clipboard that is not a link at all; it stays out of this set, and so
     * does every resolver failure (`no_share_row`, `clipboard_empty`, `root_null`, …). That boundary is
     * pinned in {@link com.omnidownloader.app.fetcher.MediaUrlTest}, not assumed.
     *
     * Callers reach this through `resolverFailed`, which PREFIXES the reason (`rejected_<why>`), so the
     * match is a `contains` over the reason's own tokens and never an equality — the reason
     * `rejected_host_not_supported` is the case that matters, and an equality check silently read it
     * as rose FAILED until 2026-09-28.
     */
    public static boolean isUnsupportedReason(String why) {
        if (why == null) return false;
        return why.contains("host_not_supported")
                || why.contains("not_a_media_path")
                || why.contains("tt_photo_post");
    }

    /**
     * Strip known per-share tracking parameters so the same post always yields the same
     * string for the dedup layers (DeliveryGuard, activeStateFor, the engine's own name
     * dedup). The media path is never touched; anything unparseable returns unchanged.
     * Instagram's `img_index` is included deliberately: it marks the carousel SLIDE, not a
     * different post, so two sightings of one post dedup to one string (D-g, Wave 1).
     */
    public static String canonicalize(String url) {
        if (url == null) return null;
        String s = url.trim();
        int q = s.indexOf('?');
        if (q < 0) return s;
        String base = s.substring(0, q);
        String query = s.substring(q + 1);
        StringBuilder kept = new StringBuilder();
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) continue;
            String name = pair;
            int eq = pair.indexOf('=');
            if (eq >= 0) name = pair.substring(0, eq);
            String n = name.toLowerCase(Locale.ROOT);
            if (TRACKING_PARAMS.contains(n)) continue;
            if (kept.length() > 0) kept.append('&');
            kept.append(pair);
        }
        return kept.length() > 0 ? base + "?" + kept : base;
    }

    /** Per-share parameters that vary without changing what the post IS. */
    private static final java.util.Set<String> TRACKING_PARAMS = new java.util.HashSet<>(
            java.util.Arrays.asList(
                    "img_index",       // carousel slide index (D-g)
                    "igsh", "igshid",  // Instagram share tracking
                    "_r", "is_from_webapp", "sender_device", "sender_device_id",
                    "web_id", "share_url_id", "utm_source", "utm_medium", "utm_campaign"));
}
