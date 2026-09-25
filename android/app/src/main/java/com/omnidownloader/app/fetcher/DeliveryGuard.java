package com.omnidownloader.app.fetcher;

/**
 * The Fetcher's cross-run duplicate guard — sheet 8 §5's "URL matching: prevents resubmitting job".
 *
 * Why it exists (audit 2026-09-25): the one-delivery budget per run (`chainDelivered`) guarantees
 * one *tap* delivers at most once, but nothing stopped two successive taps on the same video —
 * seconds apart, the clipboard still holding the same link — from starting two identical engine
 * jobs, which save "Video.mp4" and then "Video (1).mp4". `DowniDownloadService.startShared` has no
 * URL-level dedup by design (the engine is not modified), so the Fetcher owns this check the same
 * way it owns the media-page rule. A short same-URL window closes the rapid-retap hole without
 * blocking a deliberate later re-fetch; full job-liveness dedup stays with Phase D's
 * `CoreJobBinding` (it can see whether the earlier job is still running).
 *
 * Pure and clock-injected, so the window is unit-testable without Android or sleeps — the same
 * discipline as {@link MediaUrl}.
 */
public final class DeliveryGuard {

    private final long windowMs;
    private String lastUrl;
    private long lastAt;

    public DeliveryGuard(long windowMs) {
        this.windowMs = windowMs;
    }

    /** True when this url may start a job at {@code nowMs}. */
    public boolean allow(String url, long nowMs) {
        if (url == null || url.isEmpty()) return true;
        return !url.equals(lastUrl) || nowMs - lastAt >= windowMs;
    }

    /** Record a url that was actually handed to the pipeline (never a rejected candidate). */
    public void record(String url, long nowMs) {
        if (url == null || url.isEmpty()) return;
        lastUrl = url;
        lastAt = nowMs;
    }
}
