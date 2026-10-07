package com.stonebreak.ui.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** One sample per frame; the game clock follows simulated time (#295 review). */
class UiFrameClockTest {

    @Test
    void uiTimeIsTheClampedFrameTimeAndGameTimeTheSimulatedSteps() {
        long[] now = {1_000_000_000L};
        UiFrameClock c = new UiFrameClock(() -> now[0]);
        c.beginFrame();
        assertEquals(0, c.uiDt(), "the first frame has no previous one");
        now[0] += 16_000_000L;
        c.simulated(0.016);
        c.beginFrame();
        assertEquals(0.016, c.uiDt(), 1e-9);
        assertEquals(0.016, c.gameDt(), 1e-9);
        now[0] += 16_000_000L;
        c.beginFrame();
        assertEquals(0.016, c.uiDt(), 1e-9);
        assertEquals(0, c.gameDt(), "paused: the world did not step, the game clock stands still");
        now[0] += 3_000_000_000L;
        c.simulated(5);
        c.beginFrame();
        assertEquals(UiFrameClock.MAX_STEP, c.uiDt(), 1e-9, "a hitch never fast-forwards animations");
        assertEquals(UiFrameClock.MAX_STEP, c.gameDt(), 1e-9);
        c.simulated(Double.NaN);
        c.simulated(-1);
        c.beginFrame();
        assertEquals(0, c.gameDt());
    }
}
