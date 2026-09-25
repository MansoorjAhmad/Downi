package com.omnidownloader.app.fetcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Locks the resolver's self-awareness math: rolling window, honest rates, no-data honesty. */
public class StrategyLedgerTest {

    @Test public void rateOverTheWindowIsExact() {
        StrategyLedger l = new StrategyLedger();
        l.record("tiktok", "clipboard", true, 1);
        l.record("tiktok", "clipboard", true, 2);
        l.record("tiktok", "clipboard", false, 3);
        l.record("tiktok", "clipboard", true, 4);
        assertEquals(0.75, l.rate("tiktok", "clipboard"), 0.0001);
    }

    @Test public void windowIsRollingAtTwenty() {
        StrategyLedger l = new StrategyLedger();
        for (int i = 0; i < 25; i++) l.record("instagram", "tree", true, i);      // 20 oldest trues
        l.record("instagram", "tree", false, 100);                                // pushes one true out
        l.record("instagram", "tree", false, 101);
        assertEquals(18.0 / 20.0, l.rate("instagram", "tree"), 0.0001);
    }

    @Test public void platformsAndStrategiesAreIndependent() {
        StrategyLedger l = new StrategyLedger();
        l.record("tiktok", "clipboard", false, 1);
        assertEquals(-1, l.rate("instagram", "clipboard"), 0.0001);
        assertEquals(-1, l.rate("tiktok", "tree"), 0.0001);
        assertEquals(0.0, l.rate("tiktok", "clipboard"), 0.0001);
    }

    @Test public void summaryIsHonestAboutNoData() {
        StrategyLedger l = new StrategyLedger();
        assertEquals("clipboard no-data", l.summary("tiktok", "clipboard"));
        l.record("tiktok", "clipboard", true, 1);
        assertTrue(l.summary("tiktok", "clipboard").contains("100% (1/1)"));
    }
}
