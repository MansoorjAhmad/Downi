package com.omnidownloader.app.fetcher;

/**
 * The sheet-scroll gesture's geometry (Wave 1, owner report "the reel scrolled").
 *
 * The old swipe was defined by SCREEN fractions (80% -> 64% of the display height) —
 * when the sheet's window did not consume the stroke, those coordinates landed on the
 * FEED and the reel advanced. `endpoints()` now derives the swipe from the SHARE
 * SURFACE WINDOW'S OWN bounds: both endpoints live inside the sheet, so even a
 * fall-through can only ever land on the sheet itself. Pure math, unit-tested
 * (`SheetSwipeTest`) — no Android types.
 */
public final class SheetSwipe {

    private SheetSwipe() {}

    /**
     * Upward scroll endpoints inside the sheet's own bounds, or null when the sheet is too
     * small to scroll safely (a stroke that short would be a tap, not a scroll).
     *
     * @param l t r b  the share-surface window's bounds in screen coordinates
     * @param dp       density (for absolute insets)
     * @return {x0, y0, x1, y1} — start low inside the sheet, end above it, still inside
     */
    public static float[] endpoints(int l, int t, int r, int b, float dp) {
        if (r <= l || b <= t) return null;
        float inset = 24f * dp;
        float cx = (l + r) / 2f;
        float yStart = b - inset;                       // low inside the sheet, off its edge margins
        float travel = 220f * dp;                       // a comfortable scroll, not a fling
        float yEnd = Math.max(t + inset, yStart - travel);
        if (yEnd >= yStart - 40f * dp) return null;     // sheet too short: refuse rather than tap
        if (yStart <= t || yEnd <= t) return null;      // paranoia: never above the sheet
        return new float[]{cx, yStart, cx, yEnd};
    }
}
