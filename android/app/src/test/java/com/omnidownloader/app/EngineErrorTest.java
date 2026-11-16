package com.omnidownloader.app;

import org.junit.Test;

import static org.junit.Assert.*;

public class EngineErrorTest {

    @Test
    public void extractsTaggedCauseAndRawReason() {
        String msg = "[cause:rate_limited] ERROR: HTTP Error 429: Too Many Requests";
        assertEquals("rate_limited", EngineError.cause(msg));
        assertEquals("ERROR: HTTP Error 429: Too Many Requests", EngineError.raw(msg));
    }

    @Test
    public void untaggedOrNullDefaultsToUnknown() {
        assertEquals("unknown", EngineError.cause(null));
        assertEquals("", EngineError.raw(null));
        assertEquals("unknown", EngineError.cause("Something failed"));
        assertEquals("Something failed", EngineError.raw("Something failed"));
        assertEquals("unknown", EngineError.cause("[cause:] bad"));
    }

    @Test
    public void phrasesMatchAllKnownCauses() {
        assertEquals("The site refused DOWNI's request.", EngineError.phrase("blocked"));
        assertEquals("The site is rate-limiting this device.", EngineError.phrase("rate_limited"));
        assertEquals("That video needs an account, or it is private.", EngineError.phrase("private"));
        assertEquals("That video isn't available in your country.", EngineError.phrase("geo"));
        assertEquals("That quality isn't offered for this video.", EngineError.phrase("format"));
        assertEquals("DOWNI couldn't read a video from that link.", EngineError.phrase("link"));
        assertEquals("DOWNI couldn't reach the network.", EngineError.phrase("network"));
        assertEquals("Grab canceled.", EngineError.phrase("cancelled"));
        assertEquals("DOWNI's engine couldn't fetch this stream.", EngineError.phrase("unknown"));
        assertEquals("DOWNI couldn't finish this grab.", EngineError.phrase(null));
    }
}
