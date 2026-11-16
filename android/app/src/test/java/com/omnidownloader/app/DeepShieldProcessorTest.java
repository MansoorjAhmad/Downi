package com.omnidownloader.app;

import org.junit.Test;

import static org.junit.Assert.*;

public class DeepShieldProcessorTest {

    @Test
    public void normalizesShieldTierSafely() {
        assertEquals("deep", DeepShieldProcessor.normalizeTier("deep"));
        assertEquals("deep", DeepShieldProcessor.normalizeTier("  DEEP "));
        assertEquals("standard", DeepShieldProcessor.normalizeTier("standard"));
        assertEquals("standard", DeepShieldProcessor.normalizeTier(""));
        assertEquals("standard", DeepShieldProcessor.normalizeTier(null));
        assertEquals("standard", DeepShieldProcessor.normalizeTier("unknown"));
        assertTrue(VideoUniquifier.Result.isAppliedMode("deep"));
        assertTrue(VideoUniquifier.Result.isAppliedMode("remux"));
        assertTrue(VideoUniquifier.Result.isAppliedMode("identity"));
        assertFalse(VideoUniquifier.Result.isAppliedMode("skipped"));
    }

    @Test
    public void computesEvenDimensionsBitrateFloorsAndStartTrim() {
        assertEquals(1080, DeepShieldProcessor.evenDimension(1081));
        assertEquals(1920, DeepShieldProcessor.evenDimension(1920));
        assertEquals(16, DeepShieldProcessor.evenDimension(5));

        assertEquals(0L, DeepShieldProcessor.computeStartTrimUs(2_500_000L));
        assertEquals(100_000L, DeepShieldProcessor.computeStartTrimUs(5_000_000L));

        assertEquals(5_000_000, DeepShieldProcessor.computeTargetBitrate(1920, 1080, 0));
        assertEquals(10_000_000, DeepShieldProcessor.computeTargetBitrate(2560, 1440, 4_000_000));
        assertEquals(16_000_000, DeepShieldProcessor.computeTargetBitrate(3840, 2160, 12_000_000));
        assertEquals(35_000_000, DeepShieldProcessor.computeTargetBitrate(3840, 2160, 60_000_000));
    }

    @Test
    public void buildsMicroCropMatrixWithSymmetricEdgeTrim() {
        float[] m = DeepShieldProcessor.buildMicroCropStMatrix(0.015f);
        assertEquals(16, m.length);
        // Column-major: u' = m[0]*u + m[12], v' = m[5]*v + m[13]
        float u0 = m[0] * 0.0f + m[12];
        float u1 = m[0] * 1.0f + m[12];
        float v0 = m[5] * 0.0f + m[13];
        float v1 = m[5] * 1.0f + m[13];
        assertEquals(0.015f, u0, 1e-6f);
        assertEquals(0.985f, u1, 1e-6f);
        assertEquals(0.015f, v0, 1e-6f);
        assertEquals(0.985f, v1, 1e-6f);
    }

    @Test
    public void validatesEligibilityAndDurationTolerance() {
        assertTrue(DeepShieldProcessor.isDeepModeAllowed(true, 5_000_000L, 1920, 1080, false));
        assertTrue(DeepShieldProcessor.isDeepModeAllowed(true, 5_000_000L, 2160, 3840, false)); // portrait 4K
        assertFalse(DeepShieldProcessor.isDeepModeAllowed(false, 5_000_000L, 1920, 1080, false)); // non-MP4
        assertFalse(DeepShieldProcessor.isDeepModeAllowed(true, 900_000L, 1920, 1080, false)); // <= 1s
        assertFalse(DeepShieldProcessor.isDeepModeAllowed(true, 5_000_000L, 7680, 4320, false)); // 8K
        assertFalse(DeepShieldProcessor.isDeepModeAllowed(true, 5_000_000L, 1920, 1080, true)); // HDR

        assertTrue(DeepShieldProcessor.isOutputDurationAcceptable(4_900_000L, 4_850_000L));
        assertFalse(DeepShieldProcessor.isOutputDurationAcceptable(10_000_000L, 2_000_000L));
        assertFalse(DeepShieldProcessor.isOutputDurationAcceptable(5_000_000L, 0L));
    }
}
