package com.stonebreak.world;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The moon keeps real geometry on a shortened month: new on day 0, full half a
 * cycle later and opposite the sun, crescents beside the sun. Its brightness
 * follows the observed phase curve, not a Lambertian ball.
 */
class MoonCycleTest {

    private static final long FULL_DAY = MoonCycle.CYCLE_DAYS / 2;

    @Test
    void newOnDayZeroFullHalfACycleLater() {
        float newMoon = MoonCycle.phase(0, TimeOfDay.MIDNIGHT);
        assertEquals(0f, MoonCycle.illuminatedFraction(newMoon), 1e-6);
        assertEquals("New Moon", MoonCycle.phaseName(newMoon));
        float full = MoonCycle.phase(FULL_DAY, TimeOfDay.MIDNIGHT);
        assertEquals(1f, MoonCycle.illuminatedFraction(full), 1e-6);
        assertEquals(1f, MoonCycle.relativeBrightness(full), 1e-6);
        assertEquals("Full Moon", MoonCycle.phaseName(full));
        assertEquals(MoonCycle.phase(1, 0), MoonCycle.phase(1 + MoonCycle.CYCLE_DAYS, 0), 1e-6,
                "the cycle repeats");
    }

    @Test
    void quarterMoonIsAboutATenthOfFullNotHalf() {
        // Observed full/quarter ratio ~10 (opposition surge); a Lambertian sphere would give pi.
        float quarter = MoonCycle.relativeBrightness(0.25f);
        assertEquals(0.09f, quarter, 0.01f);
        assertEquals(0.5f, MoonCycle.illuminatedFraction(0.25f), 1e-6);
        assertTrue(MoonCycle.relativeBrightness(0.02f) < 0.01f, "a new moon gives next to no light");
    }

    @Test
    void fullMoonIsHighestAtMidnightOppositeTheSun() {
        TimeOfDay t = new TimeOfDay(FULL_DAY * TimeOfDay.TICKS_PER_DAY + TimeOfDay.MIDNIGHT);
        Vector3f moon = MoonCycle.direction(t);
        Vector3f sun = t.getSunDirection();
        // From latitude phi the equinox path culminates 90 - phi up, toward +Z.
        float culmination = (float) Math.cos(Math.toRadians(TimeOfDay.LATITUDE_DEG));
        assertEquals(-culmination, sun.y, 1e-4f, "the sun at its lowest");
        assertEquals(culmination, moon.y, 1e-3f, "full moon at midnight stands as high as the noon sun, got " + moon);
        assertTrue(moon.z > 0f, "on the meridian toward +Z, got " + moon);
        assertTrue(moon.dot(sun) < -0.99f, "full moon is opposite the sun");
    }

    @Test
    void theMoonTurnsAboutTheCelestialPoleLikeTheSun() {
        Vector3f pole = TimeOfDay.celestialPole();
        for (long tick = 0; tick < TimeOfDay.TICKS_PER_DAY; tick += 1000) {
            TimeOfDay t = new TimeOfDay(3 * TimeOfDay.TICKS_PER_DAY + tick);
            assertEquals(0f, MoonCycle.direction(t).dot(pole), 1e-4f, "tick " + tick);
            assertEquals(0f, t.getSunDirection().dot(pole), 1e-4f, "tick " + tick);
        }
    }

    @Test
    void waxingCrescentHangsInTheWestAfterDuskBesideTheSun() {
        // Day 1: a day past new. Just after dusk the sun has set in the west (+X).
        TimeOfDay t = new TimeOfDay(TimeOfDay.TICKS_PER_DAY + TimeOfDay.DUSK + 800);
        Vector3f moon = MoonCycle.direction(t);
        Vector3f sun = t.getSunDirection();
        assertTrue(sun.y < 0f && sun.x > 0f, "sun has set in the west");
        assertTrue(moon.y > 0f && moon.x > 0f, "crescent is up in the western sky, got " + moon);
        float elongation = (float) Math.toDegrees(Math.acos(moon.dot(sun)));
        assertTrue(elongation < 60f, "a crescent sits near the sun, got " + elongation + " deg");
    }

    @Test
    void moonRisesLaterEachDay() {
        // Find the tick the moon clears the horizon on two consecutive days.
        long first = riseTick(2), second = riseTick(3);
        long lag = Math.floorMod(second - first, TimeOfDay.TICKS_PER_DAY);
        // A cycle-day's share (3000 ticks) on average; more or less on a given day,
        // because the sky turns slower near the horizon crossings (TimeOfDay.TWILIGHT_DILATION).
        assertTrue(lag > 1000 && lag < TimeOfDay.TICKS_PER_DAY / 3,
                "the moon rises some hours later each day, got " + lag + " ticks");
    }

    private static long riseTick(long day) {
        boolean wasUp = MoonCycle.direction(new TimeOfDay(day * TimeOfDay.TICKS_PER_DAY)).y > 0f;
        for (long tick = 50; tick < TimeOfDay.TICKS_PER_DAY; tick += 50) {
            boolean up = MoonCycle.direction(new TimeOfDay(day * TimeOfDay.TICKS_PER_DAY + tick)).y > 0f;
            if (up && !wasUp) {
                return tick;
            }
            wasUp = up;
        }
        throw new AssertionError("moon never rose on day " + day);
    }
}
