package com.omnidownloader.app.fetcher;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The Fetcher's attention model — what the user is looking at RIGHT NOW, with confidence.
 *
 * The master-package question this answers: can Fetcher know the user's intent without guessing
 * dangerously? The ledger's contract is that a candidate becomes READY only when every piece of
 * evidence agrees:
 *
 *   - it was seen in a node that was VISIBLE on screen,
 *   - it has DWELLED (offered across ≥ DWELL_MS — a scroll storm produces nothing),
 *   - no SCROLL TRANSITION has happened since it was last seen (the feed moved on = it's gone),
 *   - it has not gone missing from consecutive dumps (auto-advanced away = demoted),
 *   - it is younger than MAX_AGE_MS.
 *
 * Anything less is PREPARING (the Core's soft glow), never a silent download. Tap is still the
 * only trigger — the ledger only makes the tap INSTANT when it can be right.
 *
 * Pure logic: time is an argument, no Android types — unit-tested (`AttentionLedgerTest`).
 */
public final class AttentionLedger {

    public static final long DWELL_MS = 800L;
    public static final long MAX_AGE_MS = 180_000L;
    /** A candidate missing from this many consecutive dumps is demoted (the feed moved on). */
    public static final int DEMOTE_AFTER_ABSENCES = 2;

    public static final class Candidate {
        public final String url;
        public final boolean visibleEver;
        public final long firstSeen;
        public final long lastSeen;
        public final long offerEpoch;
        public final int absences;

        Candidate(String url, boolean visibleEver, long firstSeen, long lastSeen, long offerEpoch, int absences) {
            this.url = url;
            this.visibleEver = visibleEver;
            this.firstSeen = firstSeen;
            this.lastSeen = lastSeen;
            this.offerEpoch = offerEpoch;
            this.absences = absences;
        }
    }

    /** What the ledger thinks a candidate is worth right now. */
    public enum Grade { READY, PREPARING, STALE }

    private long epoch = 0L;
    private final Map<String, Candidate> candidates = new LinkedHashMap<>();

    /** Call at the start of every tree dump: un-offered candidates accrue absence. */
    public void beginDump(long now) {
        LinkedHashMap<String, Candidate> next = new LinkedHashMap<>();
        for (Map.Entry<String, Candidate> e : candidates.entrySet()) {
            Candidate c = e.getValue();
            next.put(e.getKey(), new Candidate(c.url, c.visibleEver, c.firstSeen, c.lastSeen, c.offerEpoch, c.absences + 1));
        }
        candidates.clear();
        candidates.putAll(next);
    }

    /** One sighting. Call for every media URL found in the tree, with its node's visibility. */
    public void offer(String url, boolean visible, long now) {
        if (url == null || url.isEmpty()) return;
        Candidate prev = candidates.get(url);
        if (prev == null) {
            candidates.put(url, new Candidate(url, visible, now, now, epoch, 0));
            return;
        }
        // Refresh: same epoch (offer implies still present), absences reset, visibility is sticky.
        candidates.put(url, new Candidate(url, prev.visibleEver || visible, prev.firstSeen, now, epoch, 0));
    }

    /** The feed paged — everything seen before a scroll is a DIFFERENT video now. */
    public void onScrollTransition() {
        epoch++;
    }

    public void clear() {
        candidates.clear();
        epoch = 0L;
    }

    public int size() {
        return candidates.size();
    }

    /** Grade one candidate at {@code now}. Pure. */
    public static Grade grade(Candidate c, long now, long currentEpoch) {
        if (c == null) return Grade.STALE;
        if (c.absences >= DEMOTE_AFTER_ABSENCES) return Grade.STALE;
        if (c.offerEpoch != currentEpoch) return Grade.STALE;
        if (now - c.lastSeen > MAX_AGE_MS) return Grade.STALE;
        if (!c.visibleEver) return Grade.PREPARING;
        if (now - c.firstSeen < DWELL_MS) return Grade.PREPARING;
        return Grade.READY;
    }

    public Grade gradeOf(String url, long now) {
        return grade(candidates.get(url), now, epoch);
    }

    /**
     * The best READY candidate, or null. Preference: most recent sighting — among READY
     * candidates recency is the safest proxy for "the one on screen".
     */
    public Candidate best(long now) {
        Candidate best = null;
        for (Candidate c : candidates.values()) {
            if (grade(c, now, epoch) != Grade.READY) continue;
            if (best == null || c.lastSeen > best.lastSeen) best = c;
        }
        return best;
    }
}
