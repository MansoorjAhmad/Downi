package com.omnidownloader.app.fetcher;

import java.util.ArrayDeque;

/**
 * The Fetcher's black box: the last {@value #CAPACITY} events, oldest first, in every build.
 *
 * Why it exists (measured 2026-09-28): `log()` writes `Log.i(TAG, …)`, and on this ROM logcat returns
 * **zero lines for a release build**, so a release install that "did nothing" left no evidence at all
 * — the owner's vendor-kill and Home-feed questions both had to be diagnosed from source. The debug
 * forensic file log is gated to debug builds on purpose (Phase G); this is the release-safe half:
 * a bounded in-memory ring, flushed to prefs every few events by the service, and read back through
 * the Core's settings card (or exported as a file) instead of through adb.
 *
 * It also has to survive the case it is most needed for — the process being killed mid-run. So:
 *   - the ring is flushed by the service every {@link #shouldFlush()} events and at every milestone;
 *   - on the next bind, the service re-seeds the ring from the last flush (so the events *before* the
 *     kill are still in the box) and adds a marker when the previous session never stopped cleanly.
 *
 * Pure (no Android types), bounded, and cheap: 50 short strings in RAM, no I/O here at all.
 * `EventRingTest` locks the capacity, the order and the flush cadence.
 */
public final class EventRing {

    /**
     * How many events the box keeps. The first cut was 50 (the owner's spec), and the measurement
     * corrected it: while the chain waits for Instagram's sheet it emits ~50 real events per minute
     * even with the per-poll chatter filtered, so 50 slots held less than two grabs — the TikTok
     * run's `ENGINE_*` lines were still there, Instagram's were gone (2026-09-28 20:04). 100 short
     * strings is still ~15 KB in RAM and one prefs entry.
     */
    public static final int CAPACITY = 100;
    /** Longest a single stored line may be (the UI and the prefs entry stay readable). */
    public static final int MAX_LINE = 220;
    /** Flush cadence: at most this many trailing events are lost if the process is killed. */
    public static final int FLUSH_EVERY = 4;

    /**
     * Per-poll chatter that answers nothing and evicts the milestones this box exists for. Measured
     * 2026-09-28: while the chain waited for Instagram's sheet it logged `DUMP reason=…` every ~600 ms,
     * and two grabs' `ENGINE_*` lines were pushed out of a 50-slot ring within half a minute. These
     * stay in logcat and in the debug build's forensic log; the box keeps the story.
     */
    private static final String[] NOISE = {
            " identical_to_previous",
            "DUMP reason=",
    };

    private final ArrayDeque<String> lines = new ArrayDeque<>();
    private int sinceFlush;
    private int dropped;

    /** Adds one event, dropping the oldest when full. Blank lines and per-poll noise are ignored. */
    public synchronized void add(String line) {
        String clean = clean(line);
        if (clean.isEmpty()) return;
        if (isNoise(clean)) return;
        if (lines.size() >= CAPACITY) {
            lines.removeFirst();
            dropped++;
        }
        lines.addLast(clean);
        sinceFlush++;
    }

    /**
     * True for events that are per-poll chatter rather than history (see {@link #NOISE}). Kept public
     * so the reason a line is missing from the box can be checked and tested, not just asserted.
     */
    public static boolean isNoise(String line) {
        if (line == null) return false;
        for (String n : NOISE) {
            if (line.contains(n)) return true;
        }
        return false;
    }

    /** True when the service should persist the ring (see {@link #FLUSH_EVERY}). */
    public synchronized boolean shouldFlush() {
        return sinceFlush >= FLUSH_EVERY;
    }

    /** Called by the service after it has persisted the ring. */
    public synchronized void markFlushed() {
        sinceFlush = 0;
    }

    /** Oldest → newest, at most {@link #CAPACITY} entries. */
    public synchronized String[] snapshot() {
        return lines.toArray(new String[0]);
    }

    /** The same events as one newline-joined blob (what the service persists). */
    public synchronized String dump() {
        return String.join("\n", lines);
    }

    /**
     * Loads a previous session's dump so the events *before* a kill stay readable, then keeps going.
     * Returns how many entries were seeded.
     */
    public synchronized int seed(String dump) {
        if (dump == null || dump.isEmpty()) return 0;
        int n = 0;
        for (String raw : dump.split("\n")) {
            String clean = clean(raw);
            if (clean.isEmpty()) continue;
            if (lines.size() >= CAPACITY) {
                lines.removeFirst();
                dropped++;
            }
            lines.addLast(clean);
            n++;
        }
        return n;
    }

    public synchronized int size() { return lines.size(); }

    /** How many events have fallen out of the box since it was created. */
    public synchronized int dropped() { return dropped; }

    public synchronized boolean isEmpty() { return lines.isEmpty(); }

    public synchronized void clear() {
        lines.clear();
        sinceFlush = 0;
        dropped = 0;
    }

    /** One event as it will be stored: single-line, whitespace collapsed, length capped. */
    public static String clean(String line) {
        if (line == null) return "";
        String s = line.replace('\r', ' ').replace('\n', ' ').replaceAll("\\s+", " ").trim();
        if (s.length() > MAX_LINE) s = s.substring(0, MAX_LINE - 1) + "…";
        return s;
    }

    /** Newline-joined events, for the settings card and the export file. */
    public String asText() { return dump(); }
}
