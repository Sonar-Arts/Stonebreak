package com.stonebreak.mobs.sbe;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlayerGaitClockTest {
    @Test
    void alternatingContactsFollowEachAuthoredCycle() {
        for (float duration : new float[]{0.96f, 0.68f}) {
            PlayerGaitClock clock = new PlayerGaitClock();
            assertTrue(clock.update(duration / 100, duration)); // initial planted foot
            for (int i = 2; i <= 100; i++) {
                assertEquals(i == 50 || i == 100, clock.update(duration / 100, duration),
                        "duration=" + duration + ", tick=" + i);
            }
            // Float-sized ticks can land just before the wrap; both endpoints are phase zero.
            assertEquals(0f, Math.min(clock.timeSeconds(), duration - clock.timeSeconds()), 1e-6f);
        }
    }

    @Test
    void changingPacePreservesFootPhaseWithoutAnExtraContact() {
        PlayerGaitClock clock = new PlayerGaitClock();
        assertTrue(clock.update(0.24f, 0.96f)); // quarter cycle
        assertFalse(clock.update(0.085f, 0.68f)); // eighth of a sprint cycle
        assertEquals(0.255f, clock.timeSeconds(), 1e-6f);
        assertTrue(clock.update(0.085f, 0.68f)); // opposite foot reaches ground
        assertEquals(0.34f, clock.timeSeconds(), 1e-6f);
        assertFalse(clock.update(0.12f, 0.96f)); // switch back without restarting
        assertEquals(0.6f, clock.timeSeconds(), 1e-6f);
    }

    @Test
    void stoppingResetsAndLongFramesDoNotQueueCatchUpSounds() {
        PlayerGaitClock clock = new PlayerGaitClock();
        assertTrue(clock.update(0.01f, 1f));
        assertTrue(clock.update(10.1f, 1f));
        assertFalse(clock.update(0.01f, 1f));
        assertFalse(clock.update(0.01f, 0f));
        assertFalse(clock.isActive());
        assertEquals(0f, clock.timeSeconds());
        assertTrue(clock.update(0.01f, 1f));
    }

    @Test
    void invalidTimesDoNotEmitContactsOrCorruptTheClock() {
        PlayerGaitClock clock = new PlayerGaitClock();
        for (float dt : new float[]{0f, -1f, Float.NaN, Float.POSITIVE_INFINITY}) {
            assertFalse(clock.update(dt, 1f));
            assertFalse(clock.isActive());
        }
        assertTrue(clock.update(0.1f, 1f));
        assertFalse(clock.update(0.1f, Float.NaN));
        assertFalse(clock.isActive());
    }
}
