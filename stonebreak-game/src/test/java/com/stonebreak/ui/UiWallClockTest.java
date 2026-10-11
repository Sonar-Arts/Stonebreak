package com.stonebreak.ui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pinnable wall clock of UI presentation (#296). */
class UiWallClockTest {

    @AfterEach
    void release() {
        UiWallClock.release();
    }

    @Test
    void unpinnedFollowsTheSystemClock() {
        assertFalse(UiWallClock.isPinned());
        long before = System.currentTimeMillis();
        long now = UiWallClock.millis();
        assertTrue(now >= before && now <= System.currentTimeMillis());
        double a = UiWallClock.seconds();
        assertTrue(UiWallClock.seconds() >= a, "monotonic");
    }

    @Test
    void pinnedTimeStandsStill() {
        UiWallClock.pin(1.25, 7);
        assertTrue(UiWallClock.isPinned());
        assertEquals(1.25, UiWallClock.seconds());
        assertEquals(1_250_000_000L, UiWallClock.nanos());
        assertEquals(UiWallClock.PINNED_EPOCH_MILLIS + 1250, UiWallClock.millis());
        assertEquals(UiWallClock.millis(), UiWallClock.millis());
    }

    @Test
    void pinnedRandomnessIsSeeded() {
        UiWallClock.pin(0, 42);
        assertEquals(new java.util.Random(42).nextLong(), UiWallClock.random().nextLong());
        assertEquals(UiWallClock.random().nextInt(), UiWallClock.random().nextInt(), "every source starts alike");
    }

    @Test
    void theDevPropertyParsesSecondsAndAnOptionalSeed() {
        assertEquals(2.5, UiWallClock.parsedSeconds("2.5"));
        assertEquals(0, UiWallClock.parsedSeconds(" 0:99 "));
        assertTrue(Double.isNaN(UiWallClock.parsedSeconds("")));
        assertTrue(Double.isNaN(UiWallClock.parsedSeconds(null)));
        assertThrows(IllegalArgumentException.class, () -> UiWallClock.parsedSeconds("soon"));
        assertThrows(IllegalArgumentException.class, () -> UiWallClock.parsedSeconds("-1"));
        assertThrows(IllegalArgumentException.class, () -> UiWallClock.pin(Double.NaN, 0));
    }

    @Test
    void aScopedPinRestoresTheOuterPinInsteadOfUnpinning() {
        UiWallClock.pin(7, 1); // a live -Dstonebreak.ui.pinclock run, or an outer test
        try (UiWallClock.Restore inner = UiWallClock.pinScoped(12.5, 296)) {
            assertEquals(12.5, UiWallClock.seconds());
        }
        assertTrue(UiWallClock.isPinned(), "a nested capture must not wipe the outer pin");
        assertEquals(7, UiWallClock.seconds());

        UiWallClock.release();
        UiWallClock.pinScoped(1, 1).close();
        assertTrue(!UiWallClock.isPinned(), "and an unpinned clock stays unpinned");
    }
}
