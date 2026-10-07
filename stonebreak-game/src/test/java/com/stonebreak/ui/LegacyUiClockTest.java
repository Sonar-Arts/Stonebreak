package com.stonebreak.ui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The legacy screens' time and randomness seam (#296). */
class LegacyUiClockTest {

    @AfterEach
    void release() {
        LegacyUiClock.release();
    }

    @Test
    void unpinnedFollowsTheSystemClock() {
        assertFalse(LegacyUiClock.isPinned());
        long before = System.currentTimeMillis();
        long now = LegacyUiClock.millis();
        assertTrue(now >= before && now <= System.currentTimeMillis());
        double a = LegacyUiClock.seconds();
        assertTrue(LegacyUiClock.seconds() >= a, "monotonic");
    }

    @Test
    void pinnedTimeStandsStill() {
        LegacyUiClock.pin(1.25, 7);
        assertTrue(LegacyUiClock.isPinned());
        assertEquals(1.25, LegacyUiClock.seconds());
        assertEquals(1_250_000_000L, LegacyUiClock.nanos());
        assertEquals(LegacyUiClock.PINNED_EPOCH_MILLIS + 1250, LegacyUiClock.millis());
        assertEquals(LegacyUiClock.millis(), LegacyUiClock.millis());
    }

    @Test
    void pinnedRandomnessIsSeeded() {
        LegacyUiClock.pin(0, 42);
        assertEquals(new java.util.Random(42).nextLong(), LegacyUiClock.random().nextLong());
        assertEquals(LegacyUiClock.random().nextInt(), LegacyUiClock.random().nextInt(), "every source starts alike");
    }

    @Test
    void theDevPropertyParsesSecondsAndAnOptionalSeed() {
        assertEquals(2.5, LegacyUiClock.parsedSeconds("2.5"));
        assertEquals(0, LegacyUiClock.parsedSeconds(" 0:99 "));
        assertTrue(Double.isNaN(LegacyUiClock.parsedSeconds("")));
        assertTrue(Double.isNaN(LegacyUiClock.parsedSeconds(null)));
        assertThrows(IllegalArgumentException.class, () -> LegacyUiClock.parsedSeconds("soon"));
        assertThrows(IllegalArgumentException.class, () -> LegacyUiClock.parsedSeconds("-1"));
        assertThrows(IllegalArgumentException.class, () -> LegacyUiClock.pin(Double.NaN, 0));
    }
}
