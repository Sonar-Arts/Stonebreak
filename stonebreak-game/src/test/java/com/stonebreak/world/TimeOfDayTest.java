package com.stonebreak.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TimeOfDay clock behavior: fractional-tick accumulation (frame-rate updates must still
 * advance time) and {@code nudgeTo} sync correction (snap on large error, converge on small,
 * shortest path across the midnight wrap).
 */
class TimeOfDayTest {

    /** Ticks per real-time second at timeSpeed 1.0, derived from the day-length knob. */
    private static final float TICK_RATE = TimeOfDay.TICKS_PER_DAY / TimeOfDay.DAY_LENGTH_SECONDS;

    @Test
    void updateAccumulatesFractionalTicks() {
        TimeOfDay t = new TimeOfDay(0);
        // 60 fps: each update adds TICK_RATE/60 (< 1) ticks — pre-fix this truncated to zero forever.
        for (int i = 0; i < 60; i++) {
            t.update(1f / 60f);
        }
        // One second of updates ⇒ ~TICK_RATE ticks (allow float slack).
        assertTrue(t.getTicks() >= TICK_RATE - 1 && t.getTicks() <= TICK_RATE + 1,
            "expected ~" + TICK_RATE + " ticks after 1 s of 60 fps updates, got " + t.getTicks());
    }

    @Test
    void serverStyleFixedStepsAccumulateExactly() {
        TimeOfDay t = new TimeOfDay(100);
        for (int i = 0; i < 40; i++) {
            t.update(0.05f); // 20 Hz server step
        }
        // 2 s of exact fixed steps ⇒ exactly 2·TICK_RATE ticks, carry leaves no residue.
        assertEquals(100 + (long) (2 * TICK_RATE), t.getTicks());
    }

    @Test
    void nudgeSnapsOnLargeError() {
        TimeOfDay t = new TimeOfDay(0);
        t.nudgeTo(12_000);
        assertEquals(12_000, t.getTicks());
    }

    @Test
    void nudgeConvergesOnSmallError() {
        TimeOfDay t = new TimeOfDay(1_000);
        t.nudgeTo(1_100); // delta 100 < snap threshold — converge 10%
        long after = t.getTicks();
        assertTrue(after > 1_000 && after < 1_100, "expected partial convergence, got " + after);
        // Repeated nudges reach the target.
        for (int i = 0; i < 200; i++) {
            t.nudgeTo(1_100);
        }
        assertEquals(1_100, t.getTicks());
    }

    @Test
    void nudgeTakesShortestPathAcrossMidnightWrap() {
        TimeOfDay t = new TimeOfDay(23_950);
        t.nudgeTo(50); // wrapped delta is +100, not -23900 — must converge forward, not snap
        long after = t.getTicks();
        assertTrue(after >= 23_950 || after <= 50,
            "expected forward wrap convergence, got " + after);
        for (int i = 0; i < 200; i++) {
            t.nudgeTo(50);
        }
        assertEquals(50, t.getTicks());
    }

    @Test
    void nudgeAlwaysMakesProgressOnTinyError() {
        TimeOfDay t = new TimeOfDay(1_000);
        t.nudgeTo(1_003); // 10% of 3 rounds to 0 — must still step by 1
        assertTrue(t.getTicks() > 1_000);
    }
}
