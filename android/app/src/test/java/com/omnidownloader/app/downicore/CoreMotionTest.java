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

    /**
     * M8 — the ambient budget. The Core's one looping look (`core_idle_ready`, the DETECTED file;
     * {@link CoreLottieWiringTest} pins that it is the only loop) used to repaint a floating window at
     * the display's own rate for as long as the phone sat on a video, which is what the vendor power
     * manager ended the process over. The budget is three numbers, and each one is a claim:
     *
     *   · the loop does NOT run at the display's rate (60 fps = 16 ms);
     *   · it ENDS by itself, and late enough that the M4 control step (a 2.6 s READY hold, the pass
     *     that measures "the looping look moves") still watches it move;
     *   · the DOWNLOADING sheen's cap is ADDITIVE to the display's own ~16 ms, so a constant written as
     *     if it replaced them would run the sheen at half the intended rate.
     */
    @Test public void theAmbientLoopIsBudgetedAndEnds() {
        // 24 fps, not 16 ms: the cadence has to be a real cap, and stay recognisable as ~24 fps
        assertTrue("the ambient cadence must be slower than the display's 60 fps: "
                        + CoreMotion.AMBIENT_FRAME_MS, CoreMotion.AMBIENT_FRAME_MS >= 30L);
        assertEquals(23L, 1000L / CoreMotion.AMBIENT_FRAME_MS);        // 42 ms -> 23.8 fps

        // ...and it is a window, not a permanent drain. The floor is the device rig's own control
        // step (CoreHost's DETECTED hold is 2.6 s): a budget that ended sooner would freeze the
        // READY look before the gate could see that it moves, and the gate would read a budget as a
        // broken composition.
        assertTrue("the breath must outlast the M4 control step (2.6 s): "
                        + CoreMotion.AMBIENT_LOOP_WINDOW_MS,
                CoreMotion.AMBIENT_LOOP_WINDOW_MS > 2_600L);
        assertTrue("...and it must actually end, in seconds rather than minutes: "
                        + CoreMotion.AMBIENT_LOOP_WINDOW_MS,
                CoreMotion.AMBIENT_LOOP_WINDOW_MS <= 10_000L);

        // ValueAnimator.setFrameDelay is added on TOP of the Choreographer's frames, so the sheen's
        // constant is a delta: it must leave room for a frame and still be a fraction of the cadence.
        assertEquals(CoreMotion.AMBIENT_FRAME_MS - 16L, CoreMotion.AMBIENT_EXTRA_DELAY_MS);
        assertTrue("the extra delay must be positive: " + CoreMotion.AMBIENT_EXTRA_DELAY_MS,
                CoreMotion.AMBIENT_EXTRA_DELAY_MS > 0L);
        assertTrue("...and smaller than the cadence it is pacing: " + CoreMotion.AMBIENT_EXTRA_DELAY_MS,
                CoreMotion.AMBIENT_EXTRA_DELAY_MS < CoreMotion.AMBIENT_FRAME_MS);
    }
}
