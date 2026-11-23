package com.stonebreak.audio;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Shade's nightfall theme plays once a night, on the Shade's surface, and only after dusk. */
class ShadeNightMusicTest {

    private static final float STEP = ShadeNightMusic.CHECK_INTERVAL;

    @Test
    void neverByDay() {
        ShadeNightMusic rule = new ShadeNightMusic();
        AtomicInteger asked = new AtomicInteger();
        for (int i = 0; i < 10; i++) {
            assertFalse(rule.shouldStart(0f, STEP, () -> { asked.incrementAndGet(); return true; }));
        }
        assertEquals(0, asked.get(), "no place query by day");
    }

    @Test
    void startsAsNightFallsOverAPlayerAlreadyThere() {
        ShadeNightMusic rule = new ShadeNightMusic();
        assertFalse(rule.shouldStart(0f, STEP, () -> true));
        assertTrue(rule.shouldStart(0.01f, STEP, () -> true), "first wisps of fog");
    }

    @Test
    void startsWhenEnteringAfterDark() {
        ShadeNightMusic rule = new ShadeNightMusic();
        assertFalse(rule.shouldStart(1f, STEP, () -> false), "outside the Shade");
        assertFalse(rule.shouldStart(1f, STEP, () -> false));
        assertTrue(rule.shouldStart(1f, STEP, () -> true), "walked in");
    }

    @Test
    void onlyOnceANight() {
        ShadeNightMusic rule = new ShadeNightMusic();
        assertTrue(rule.shouldStart(1f, STEP, () -> true));
        assertFalse(rule.shouldStart(1f, STEP, () -> false), "left");
        for (int i = 0; i < 20; i++) {
            assertFalse(rule.shouldStart(1f, STEP, () -> true), "came back the same night");
        }
        assertFalse(rule.shouldStart(0f, STEP, () -> true), "dawn");
        assertTrue(rule.shouldStart(0.5f, STEP, () -> true), "next night");
    }

    @Test
    void placeQueriesAreThrottled() {
        ShadeNightMusic rule = new ShadeNightMusic();
        AtomicInteger asked = new AtomicInteger();
        float frame = STEP / 10f;
        for (int i = 0; i < 100; i++) { // ten check intervals of frames, never in the Shade
            rule.shouldStart(1f, frame, () -> { asked.incrementAndGet(); return false; });
        }
        assertTrue(asked.get() <= 11, "asked " + asked.get() + " times");
    }

    @Test
    void resetArmsItAgain() {
        ShadeNightMusic rule = new ShadeNightMusic();
        assertTrue(rule.shouldStart(1f, STEP, () -> true));
        rule.reset();
        assertTrue(rule.shouldStart(1f, STEP, () -> true), "a fresh world at night");
    }
}
