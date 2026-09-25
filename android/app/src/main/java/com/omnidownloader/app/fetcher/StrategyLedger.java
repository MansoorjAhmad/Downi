package com.omnidownloader.app.fetcher;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-platform, per-strategy success ledger — the resolver's self-awareness.
 *
 * The resolver is the most fragile part of the Fetcher: platform updates change sheets, move
 * buttons, rename rows. When a strategy breaks, the user must never be left thinking "the app
 * is broken" while the app doesn't even know. This ledger records each attempt's outcome and
 * computes a recent-window success rate per (platform, strategy), so:
 *   - the log can say `STRATEGY tiktok clipboard rate=0.83 (5/6)`,
 *   - the settings card can one day say "TikTok route unhealthy since Tuesday",
 *   - a future revision can reorder strategies from evidence instead of hope.
 *
 * Pure logic — outcomes and time are arguments; unit-tested (`StrategyLedgerTest`).
 */
public final class StrategyLedger {

    /** Rolling window size per (platform, strategy). */
    public static final int WINDOW = 20;

    private static final class Record {
        final boolean ok;
        final long at;
        Record(boolean ok, long at) { this.ok = ok; this.at = at; }
    }

    private final Map<String, Deque<Record>> outcomes = new LinkedHashMap<>();

    private static String key(String platform, String strategy) {
        return (platform == null ? "?" : platform) + "/" + (strategy == null ? "?" : strategy);
    }

    public void record(String platform, String strategy, boolean ok, long at) {
        Deque<Record> q = outcomes.get(key(platform, strategy));
        if (q == null) { q = new ArrayDeque<>(); outcomes.put(key(platform, strategy), q); }
        q.addLast(new Record(ok, at));
        while (q.size() > WINDOW) q.removeFirst();
    }

    /** Success fraction over the window, or -1 when there is no data. */
    public double rate(String platform, String strategy) {
        Deque<Record> q = outcomes.get(key(platform, strategy));
        if (q == null || q.isEmpty()) return -1d;
        int ok = 0;
        for (Record r : q) if (r.ok) ok++;
        return (double) ok / q.size();
    }

    /** `name 0.83 (5/6)` or `name no-data` — one line per strategy, for the log. */
    public String summary(String platform, String strategy) {
        double r = rate(platform, strategy);
        if (r < 0) return strategy + " no-data";
        Deque<Record> q = outcomes.get(key(platform, strategy));
        return strategy + " " + Math.round(r * 100) + "% (" + Math.round(r * q.size()) + "/" + q.size() + ")";
    }
}
