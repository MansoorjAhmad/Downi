package com.omnidownloader.app.fetcher;

/**
 * The clipboard read's rule set, as pure logic — the part of the chain's fastest possible read that
 * can be reasoned about without a device.
 *
 * WHY this exists (device 2026-09-28, §0z-7): the Instagram chain waited a **fixed 1200 ms** after
 * clicking the platform's "Copy link" before it even started reading, then another **350 ms** before
 * the first attempt. The platform copies in ~100 ms. Those two sleeps are ~1.4 s of the owner's
 * felt "5× slower than TikTok" — and the only reason they were there is that a read which is *too*
 * early returns the clipboard's **previous** value, which would download the **wrong video**.
 *
 * That danger is real and this class is what removes it: before the click the chain remembers what
 * the clipboard held ({@code baseline}); after the click a value that **differs** from that baseline
 * is proof the platform just wrote, so it can be delivered the moment it appears. A value that
 * **equals** the baseline is ambiguous (the user re-grabbed the same reel, or the platform has not
 * written yet), so it is only accepted after {@link #SAME_VALUE_GRACE_MS} — never instantly.
 *
 * With that guard the waits become unnecessary: the read starts immediately and retries fast
 * ({@link #retryDelayMs}), and the whole budget ({@link #budgetMs}) is derived from those same
 * numbers instead of being hand-added next to them (the drift M8 removed once already).
 *
 * Pure: no Android types, unit-tested (`ClipboardGateTest`).
 */
public final class ClipboardGate {

    /** What the rule set says about one read. */
    public enum Verdict {
        /** Fresh, usable evidence — deliver it. */
        ACCEPT,
        /** Nothing readable yet (clipboard empty, or Android refused the read). Keep trying. */
        WAIT,
        /** Still the pre-click value: a same-reel re-copy or a read that beat the platform's write. */
        SAME
    }

    /**
     * How long an unchanged clipboard may be believed. IG copies the link within ~100 ms of the
     * click (measured: the first read after the click succeeded, §0z-7-6), so a value still equal
     * to the pre-click baseline after this grace is a *re-copy of the same reel* rather than a
     * lost race — and re-grabbing the reel the user is looking at is the honest reading.
     */
    public static final long SAME_VALUE_GRACE_MS = 400;

    /** Retry cadence: fast while the platform's copy is expected, patient after that. */
    private static final long FAST_RETRY_MS = 120;
    private static final int FAST_TRIES = 3;
    private static final long SLOW_RETRY_MS = 250;

    private ClipboardGate() {}

    /**
     * Grade one read.
     *
     * @param text      the clipboard's text, or null when unreadable
     * @param baseline  the text observed BEFORE the platform's Copy link was clicked, or null when
     *                  there was nothing (a fresh device/empty clipboard) — in which case any
     *                  readable value is accepted, exactly as the chain always did
     * @param elapsedMs time since the copy click
     */
    public static Verdict accept(String text, String baseline, long elapsedMs) {
        if (text == null || text.isEmpty()) return Verdict.WAIT;
        if (baseline == null || baseline.isEmpty()) return Verdict.ACCEPT;
        if (!text.equals(baseline)) return Verdict.ACCEPT;
        return elapsedMs >= SAME_VALUE_GRACE_MS ? Verdict.ACCEPT : Verdict.SAME;
    }

    /** Delay before retry number {@code triesDone + 1}: fast first, then patient. */
    public static long retryDelayMs(int triesDone) {
        return triesDone < FAST_TRIES ? FAST_RETRY_MS : SLOW_RETRY_MS;
    }

    /**
     * The total time the first read plus every retry can occupy — used for the Core's focus hold so
     * the hold can never drift away from the cadence it is covering.
     */
    public static long budgetMs(long readyMs, int maxTries, long tailMs) {
        long total = readyMs;
        for (int i = 0; i < maxTries - 1; i++) total += retryDelayMs(i);
        return total + tailMs;
    }
}