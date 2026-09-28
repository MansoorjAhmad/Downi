package com.omnidownloader.app.fetcher;

import java.util.Locale;

/**
 * The per-platform profile (ruling D2 made pluggable; Wave 1 makes it code).
 *
 * Everything that DIFFERS between Instagram and TikTok in the Fetcher lives here, so
 * the resolver never grows another `"instagram".equals(...)` branch: the packages that
 * open a session, whether the passive attention ledger can serve this platform, the
 * ordered routes a tap tries, and the per-route budgets measured on device.
 *
 * Pure, unit-tested (`PlatformProfileTest`) — no Android types. Adding a future
 * platform (D2: nothing else is advertised) becomes data, not surgery.
 *
 * Device evidence behind the values:
 *   - Instagram's tree historically leaks media URLs (3/3 morning handoffs, and the
 *     open sheet's window carries the page URL), so it is passive-capable and gets the
 *     ledger lane plus one sheet-tree retry while the sheet's web content loads.
 *   - TikTok's tree is opaque (61/61 and 21/21 clean dumps; 12/12 no_url_or_id_in_tree),
 *     so its routes are sheet_tree (harmless if silent) and the copy-link chain.
 */
public final class PlatformProfile {

    /**
     * Instagram and TikTok, videos only (locked ruling D2).
     *
     * Route order, corrected 2026-09-28 (§0z-7, owner ruling): **copy_link before sheet_tree**. The
     * order is the tap's advertised plan (`RUN_START plan=…`), and it now matches what the chain
     * measures best: the copy-link route delivered the RIGHT reel in 5.01 s while the sheet-tree URL
     * surfaced a DIFFERENT video 8 s later in the same run (0z-7-6 — benign only because handoff is
     * disabled). The sheet tree stays in the plan as the fallback for a sheet that shows no Copy link.
     */
    public static final PlatformProfile INSTAGRAM = new PlatformProfile(
            "instagram", new String[]{"com.instagram.android"},
            true /* passiveCapable */, 1 /* sheetTreeRetries */, 2 /* maxSheetSwipes */,
            new String[]{Route.LEDGER, Route.COPY_LINK, Route.SHEET_TREE});

    public static final PlatformProfile TIKTOK = new PlatformProfile(
            "tiktok", new String[]{"com.zhiliaoapp.musically", "com.ss.android.ugc.trill"},
            false /* passiveCapable */, 0 /* sheetTreeRetries */, 2 /* maxSheetSwipes */,
            new String[]{Route.SHEET_TREE, Route.COPY_LINK});

    public final String key;
    public final String[] packages;
    public final boolean passiveCapable;   // the attention ledger may serve taps for this platform
    public final int sheetTreeRetries;     // extra scans while the sheet's web content loads
    public final int maxSheetSwipes;       // sheet-scroll budget for reaching "Copy link"
    public final String[] routes;          // ordered, first viable wins

    private PlatformProfile(String key, String[] packages, boolean passiveCapable,
                            int sheetTreeRetries, int maxSheetSwipes, String[] routes) {
        this.key = key;
        this.packages = packages;
        this.passiveCapable = passiveCapable;
        this.sheetTreeRetries = sheetTreeRetries;
        this.maxSheetSwipes = maxSheetSwipes;
        this.routes = routes;
    }

    /** The profile for a foreground package, or null when it is not a Fetcher target. */
    public static PlatformProfile forPackage(String pkg) {
        if (pkg == null) return null;
        for (PlatformProfile p : new PlatformProfile[]{INSTAGRAM, TIKTOK}) {
            for (String candidate : p.packages) {
                if (candidate.equals(pkg)) return p;
            }
        }
        return null;
    }

    /** The profile for a URL's platform, or null for non-target hosts. */
    public static PlatformProfile forUrl(String url) {
        String u = url == null ? "" : url.toLowerCase(Locale.US);
        if (u.contains("instagram")) return INSTAGRAM;
        if (u.contains("tiktok")) return TIKTOK;
        return null;
    }

    /** True when the package opens a Fetcher session (same rule the service's TARGETS had). */
    public static boolean isTarget(String pkg) {
        return forPackage(pkg) != null;
    }
}
