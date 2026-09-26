package com.omnidownloader.app.fetcher;

/**
 * The resolver's observable contract (Wave 1).
 *
 * The resolution chain is the most fragile part of the Fetcher, and until now it was
 * also the least *narrated*: the log told the story, but nothing else could react to
 * it. A ChainObserver receives the named beats of a run as they happen, so the Core can
 * choreograph them (Wave 2, sheet C4: the Reach), haptics can narrate the capture
 * moment, and the strategy ledger can attribute every attempt — without the resolver
 * knowing who is listening.
 *
 * Steps are short machine names; they extend the Route vocabulary with run mechanics:
 * run_start → share_found → sheet_open → copy_link_clicked → panel_closed → captured.
 * A sheet_tree lane delivery replaces the middle of that sequence with sheet_tree_hit.
 */
public interface ChainObserver {

    /** A run began (a tap, or a bench chain.cmd click). {@code routePlan} names the profile's route order. */
    void onRunStarted(String platform, String routePlan, boolean interactive);

    /** One named mechanic of the run happened. {@code detail} is a compact log fragment or "". */
    void onStep(String step, String detail);

    /** The link is in hand (the clipboard route read it, or a tree lane served it). */
    void onCaptured(String route, String url);

    /** The run is over. {@code route} is the route that delivered, or null when none did. */
    void onRunEnded(boolean delivered, String route, long durationMs);
}
