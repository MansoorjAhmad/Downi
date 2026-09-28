package com.omnidownloader.app.fetcher;

import java.util.List;
import java.util.Locale;

/**
 * What counts as a share surface — the gate that keeps the chain's BACK press (and its
 * sheet-scroll) OFF the feed.
 *
 * OWNER REPORT 2026-09-28 ("the video jumped to the next one", TikTok, 5/5): the Wave 1 guard
 * asked "does ANY window exist that is not ours, not `com.android.systemui` and not the target
 * app?" — and on this ROM that question is answered **yes, always**, because `com.vivo.upslide`
 * hosts BOTH the share sheet *and* the always-present `SideSlideGestureBar-Bottom` (and the IME
 * is a window too, whenever the keyboard is up). TikTok's sheet auto-dismisses after "Copy link",
 * so the BACK press meant to close a sheet that was already gone landed on the FEED and advanced
 * the reel — deterministically, because the gate could not tell "sheet" from "gesture bar".
 *
 * The decision here is therefore **per window**, not per package: a window is a share surface
 * only when its type, its title and its geometry all say "panel", and the decoration list is the
 * belt to geometry's braces. Keep this class pure (no Android types) — `ShareSurfaceTest` locks
 * every rule below, and the service only maps `AccessibilityWindowInfo` into {@link Win}s.
 */
public final class ShareSurface {

    /** Window types we care about (mirrors android.view.accessibility.AccessibilityWindowInfo). */
    public static final int TYPE_APPLICATION = 1;
    public static final int TYPE_INPUT_METHOD = 2;
    public static final int TYPE_SYSTEM = 3;
    public static final int TYPE_ACCESSIBILITY_OVERLAY = 4;

    /** A sheet spans most of the width and at least a fifth of the display's height. */
    private static final float MIN_WIDTH_FRAC = 0.50f;
    private static final float MIN_HEIGHT_FRAC = 0.20f;

    /** How far a window may drift (px) and still count as the same window across a poll. */
    private static final int SAME_WINDOW_SLOP = 120;

    /**
     * Decoration windows this ROM (and its neighbours) always have on screen. Matched as a
     * lower-cased substring of the window title, because the package cannot separate them:
     * `com.vivo.upslide` carries the gesture bar AND the sheet.
     */
    private static final String[] DECORATION_TITLES = {
            "sideslide", "side slide", "gesturebar", "gesture bar", "navbar", "nav bar",
            "navigationbar", "navigation bar", "statusbar", "status bar", "keyguard",
            "volume", "magnif", "assist", "toast", "tooltip", "screenshot", "screen record",
            "power menu", "split screen", "input method", "keyboard", "spinner", "ime",
            "dialog title",
    };

    private ShareSurface() {}

    /** One window as the policy needs to see it — filled by the service from AccessibilityWindowInfo. */
    public static final class Win {
        public final String pkg;
        public final String title;
        public final int type;
        public final int l, t, r, b;
        public final int displayW, displayH;

        public Win(String pkg, String title, int type, int l, int t, int r, int b,
                   int displayW, int displayH) {
            this.pkg = pkg == null ? "" : pkg;
            this.title = title == null ? "" : title;
            this.type = type;
            this.l = l;
            this.t = t;
            this.r = r;
            this.b = b;
            this.displayW = displayW;
            this.displayH = displayH;
        }

        public int width() { return Math.max(0, r - l); }
        public int height() { return Math.max(0, b - t); }
        public float widthFrac() { return displayW <= 0 ? 0f : (float) width() / displayW; }
        public float heightFrac() { return displayH <= 0 ? 0f : (float) height() / displayH; }
    }

    /** Convenience factory, so the service's mapping stays one line. */
    public static Win win(String pkg, String title, int type, int l, int t, int r, int b,
                          int displayW, int displayH) {
        return new Win(pkg, title, type, l, t, r, b, displayW, displayH);
    }

    // ---- policy methods ----

    /** True when the title says "ROM furniture" rather than "a panel the user opened". */
    public static boolean isDecoration(Win w) {
        if (w == null) return true;
        String t = w.title.toLowerCase(Locale.US);
        for (String d : DECORATION_TITLES) {
            if (t.contains(d)) return true;
        }
        return false;
    }

    /**
     * Why this window is NOT a share surface — "" when it is one. The reason is logged, so a
     * bench run can explain a refusal instead of implying it (house rule: written, not implied).
     */
    public static String rejectReason(Win w, String... excludedPkgs) {
        if (w == null) return "null";
        for (String x : excludedPkgs) {
            if (x != null && x.equals(w.pkg)) {
                return x.equals("com.android.systemui") ? "systemui" : "excluded";
            }
        }
        if (w.type == TYPE_INPUT_METHOD) return "ime";              // the keyboard is never a sheet
        if (isDecoration(w)) return "decoration";                   // gesture bar / nav / toast…
        if (w.heightFrac() < MIN_HEIGHT_FRAC) return "too_short";    // slivers, strips, bars
        if (w.widthFrac() < MIN_WIDTH_FRAC) return "too_narrow";     // side panels
        return "";
    }

    /** True when this window could BE the share surface this run is looking for. */
    public static boolean countsAsSurface(Win w, String... excludedPkgs) {
        return rejectReason(w, excludedPkgs).isEmpty();
    }

    /**
     * The index of the share surface in a top-to-bottom window list, or -1 when none is there.
     * First acceptable wins, which is the topmost one on screen (AccessibilityService#getWindows
     * lists windows from the top-most down) — the same preference the old code had.
     */
    public static int pickIndex(List<Win> windows, String... excludedPkgs) {
        if (windows == null) return -1;
        for (int i = 0; i < windows.size(); i++) {
            if (countsAsSurface(windows.get(i), excludedPkgs)) return i;
        }
        return -1;
    }

    /** True when `now` is the same window as the one we recorded (identity, not "any window"). */
    public static boolean sameWindow(Win recorded, Win now) {
        if (recorded == null || now == null) return false;
        if (isDecoration(now)) return false;
        if (!recorded.pkg.equals(now.pkg)) return false;
        if (!recorded.title.equalsIgnoreCase(now.title)) return false;
        return Math.abs(recorded.l - now.l) <= SAME_WINDOW_SLOP
                && Math.abs(recorded.t - now.t) <= SAME_WINDOW_SLOP
                && Math.abs(recorded.r - now.r) <= SAME_WINDOW_SLOP
                && Math.abs(recorded.b - now.b) <= SAME_WINDOW_SLOP;
    }

    /**
     * True while the sheet WE saw open is still on screen. This is the question the close gate
     * must ask: after TikTok's "Copy link" the sheet is gone while the gesture bar stays, so
     * `stillOpen` is false and no BACK is pressed — the feed is never touched.
     */
    public static boolean stillOpen(Win recorded, List<Win> windows) {
        if (recorded == null || windows == null) return false;
        for (Win w : windows) {
            if (sameWindow(recorded, w)) return true;
        }
        return false;
    }

    /** One-line identity for the logs: what we saw, and why it does/does not count. */
    public static String describe(Win w, String... excludedPkgs) {
        if (w == null) return "none";
        String reason = rejectReason(w, excludedPkgs);
        return "pkg=" + w.pkg
                + " title=" + (w.title.isEmpty() ? "-" : w.title)
                + " type=" + w.type
                + " bounds=[" + w.l + "," + w.t + "][" + w.r + "," + w.b + "]"
                + " hFrac=" + String.format(Locale.US, "%.2f", w.heightFrac())
                + " wFrac=" + String.format(Locale.US, "%.2f", w.widthFrac())
                + " verdict=" + (reason.isEmpty() ? "surface" : reason);
    }
}

