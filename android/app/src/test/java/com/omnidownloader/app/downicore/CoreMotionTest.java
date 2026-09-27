package com.omnidownloader.app.downicore;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Sheet C3's touch physics, as numbers.
 *
 * These used to live at their call sites — a View's clamp (`4f * dp`), a touch listener's literals
 * (`0.06f`, `12 * dp`) and an animator's inline envelope (`sin(PI * t) * 0.10f`) — where no test could
 * see them and a free-model edit could change how the Core feels without touching a design table.
 * They are constants on {@link CoreMotion} now (the class the codebase already treats as the only
 * home for motion numbers) and pinned here.
 *
 * The device half of C3 — that a press really compresses, a drag really follows, and a release
 * really magnetises — is a pixel gate, not a JVM one; see `DEVICE_TEST.md` §0d.
 */
public class CoreMotionTest {

    private static final float EPS = 0.001f;

    @Test public void c3TouchPhysicsMatchesTheSheet() {
        // THE PRESS: ~10 % compression inward while the finger is down, and the look starts at rest
        assertEquals(0.10f, CoreMotion.PRESS_SQUASH, EPS);
        assertEquals(1f, CoreLook.of(CoreStates.PRESSED, 0f, 0f, true).scale, EPS);
        assertEquals(0.90f, CoreLook.of(CoreStates.PRESSED, 1f, 0f, true).scale, EPS);

        // THE DRAG'S SLOSH: at most 4 dp of lag, at 6 % of the finger's own travel — and CLAMPED, so
        // a 200 px flick still trails by 4 dp (that clamp is CoreHost.setMarkLag's, by this constant)
        assertEquals(4f, CoreMotion.MARK_LAG_DP, EPS);
        assertEquals(0.06f, CoreMotion.MARK_LAG_FRACTION, EPS);
        float flickLag = 200f * CoreMotion.MARK_LAG_FRACTION;
        assertEquals(CoreMotion.MARK_LAG_DP, Math.min(CoreMotion.MARK_LAG_DP, flickLag), EPS);

        // THE MAGNET: only a release inside 12 dp of an edge moves, so the middle stays draggable
        assertEquals(12f, CoreMotion.EDGE_MAGNET_DP, EPS);

        // THE SNAP: the gel flattens along the contact axis, bulges half of that perpendicular to it,
        // and both are gone when it lands — 0 and 1 are exactly at rest (no trace after the snap)
        assertEquals(0.10f, CoreMotion.SNAP_SQUASH, EPS);
        assertEquals(0.5f, CoreMotion.SNAP_BULGE_FRACTION, EPS);
        assertEquals(0f, CoreMotion.snapSquash(0f), EPS);
        assertEquals(0.10f, CoreMotion.snapSquash(0.5f), EPS);
        assertEquals(0f, CoreMotion.snapSquash(1f), EPS);
        for (float t = 0f; t <= 1.0001f; t += 0.01f) {
            float e = CoreMotion.snapSquash(t);
            assertTrue("the snap never flattens past its cap (t=" + t + "): " + e,
                    e >= 0f && e <= 0.1001f);
            assertTrue("the perpendicular axis can only bulge (t=" + t + "): "
                            + e * CoreMotion.SNAP_BULGE_FRACTION,
                    e * CoreMotion.SNAP_BULGE_FRACTION <= 0.0501f);
        }

        // THE RELEASE SWELL (ruling R5, C3 §2) — stated as the constant the cap is enforced by
        assertEquals(0.05f, CoreMotion.RELEASE_SWELL, EPS);
        assertEquals(1f + CoreMotion.RELEASE_SWELL, CoreMotion.releaseSettle(0.5f), EPS);
    }
}
