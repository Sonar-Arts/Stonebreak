package com.stonebreak.world;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TimeOfDay clock behavior: fractional-tick accumulation (frame-rate updates must still
 * advance time) and {@code nudgeTo} sync correction (snap on large error, converge on small,
 * shortest path across the midnight wrap), and rejection of values that would break or
 * overflow the clock.
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
        t.nudgeTo(24_050); // day 1, tick 50: delta is +100 — must converge forward, not snap
        long after = t.getTicks();
        assertTrue(after >= 23_950 || after <= 50,
            "expected forward wrap convergence, got " + after);
        assertEquals(0, t.getDay(), "still on day 0 until the clock crosses midnight");
        for (int i = 0; i < 200; i++) {
            t.nudgeTo(24_050);
        }
        assertEquals(50, t.getTicks());
        assertEquals(1, t.getDay());
        assertEquals(24_050, t.getTotalTicks());
    }

    @Test
    void updateCountsDaysAcrossTheWrap() {
        TimeOfDay t = new TimeOfDay(TimeOfDay.TICKS_PER_DAY - 10);
        t.update(10f / TICK_RATE + 0.01f);
        assertEquals(1, t.getDay());
        assertTrue(t.getTicks() < 10);
    }

    @Test
    void absoluteTicksSplitIntoDayAndTick() {
        TimeOfDay old = new TimeOfDay(6_000); // a save from before the day count
        assertEquals(0, old.getDay());
        assertEquals(6_000, old.getTicks());

        TimeOfDay t = new TimeOfDay(3L * TimeOfDay.TICKS_PER_DAY + 18_000);
        assertEquals(3, t.getDay());
        assertEquals(18_000, t.getTicks());
        t.setTimeOfDayTicks(TimeOfDay.NOON);
        assertEquals(3, t.getDay(), "setting the time of day keeps the day");
        assertEquals(3L * TimeOfDay.TICKS_PER_DAY + TimeOfDay.NOON, t.getTotalTicks());
    }

    @Test
    void nudgeAlwaysMakesProgressOnTinyError() {
        TimeOfDay t = new TimeOfDay(1_000);
        t.nudgeTo(1_003); // 10% of 3 rounds to 0 — must still step by 1
        assertTrue(t.getTicks() > 1_000);
    }

    @Test
    void nudgeBehindServersFirstTicksSnapsInsteadOfGoingToDayMinusOne() {
        TimeOfDay t = new TimeOfDay(TimeOfDay.TICKS_PER_DAY - 5); // day 0, tick 23995
        t.nudgeTo(10); // server on day 0, tick 10: converging would leave this clock on day -1
        assertEquals(10, t.getTotalTicks());
        assertEquals(0, t.getDay());
    }

    @Test
    void invalidDeltaTimeIsRejected() {
        TimeOfDay t = new TimeOfDay(1_000);
        assertThrows(IllegalArgumentException.class, () -> t.update(Float.NaN));
        assertThrows(IllegalArgumentException.class, () -> t.update(Float.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> t.update(-0.05f));
        // The clock still works afterwards: nothing was poisoned.
        t.update(1f);
        assertEquals(1_000 + (long) TICK_RATE, t.getTicks());
    }

    @Test
    void invalidTimeSpeedIsRejected() {
        TimeOfDay t = new TimeOfDay();
        assertThrows(IllegalArgumentException.class, () -> t.setTimeSpeed(Float.NaN));
        assertThrows(IllegalArgumentException.class, () -> t.setTimeSpeed(Float.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> t.setTimeSpeed(-1f));
        assertThrows(IllegalArgumentException.class, () -> t.setTimeSpeed(TimeOfDay.MAX_TIME_SPEED + 1f));
        assertEquals(1f, t.getTimeSpeed(), "a rejected speed leaves the old one");
        assertDoesNotThrow(() -> t.setTimeSpeed(0f));
        assertDoesNotThrow(() -> t.setTimeSpeed(TimeOfDay.MAX_TIME_SPEED));
    }

    @Test
    void outOfRangeTimeIsRejected() {
        TimeOfDay t = new TimeOfDay(500);
        assertThrows(IllegalArgumentException.class, () -> t.setTicks(-1));
        assertThrows(IllegalArgumentException.class, () -> t.setTicks(TimeOfDay.MAX_TOTAL_TICKS + 1));
        assertThrows(IllegalArgumentException.class, () -> t.setTicks(Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> t.nudgeTo(Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> new TimeOfDay(-TimeOfDay.TICKS_PER_DAY));
        assertEquals(500, t.getTotalTicks(), "a rejected time leaves the clock untouched");

        assertFalse(TimeOfDay.isValidTotalTicks(-1));
        assertTrue(TimeOfDay.isValidTotalTicks(TimeOfDay.MAX_TOTAL_TICKS));
        assertFalse(TimeOfDay.isValidTimeSpeed(Float.NaN));
    }

    @Test
    void lastValidTickCannotOverflow() {
        TimeOfDay t = new TimeOfDay(TimeOfDay.MAX_TOTAL_TICKS);
        assertEquals(TimeOfDay.MAX_DAY, t.getDay());
        assertEquals(TimeOfDay.TICKS_PER_DAY - 1, t.getTicks());
        assertThrows(IllegalArgumentException.class, () -> t.update(1f),
            "advancing past the last day fails loudly rather than wrapping negative");
    }

    @Test
    void everyClockMinuteShowsBackAsItself() {
        for (int hour = 0; hour < 24; hour++) {
            for (int minute = 0; minute < 60; minute++) {
                TimeOfDay t = new TimeOfDay(TimeOfDay.ticksAtClock(hour, minute));
                int displayHour = hour % 12 == 0 ? 12 : hour % 12;
                String expected = String.format("%d:%02d %s", displayHour, minute, hour >= 12 ? "PM" : "AM");
                assertEquals(expected, t.getTimeString());
            }
        }
    }

    @Test
    void setTimeOfDayWrapsAndKeepsTheDay() {
        TimeOfDay t = new TimeOfDay(2L * TimeOfDay.TICKS_PER_DAY + 100);
        t.setTimeOfDayTicks(-1); // wraps to 23999 on the same day
        assertEquals(2, t.getDay());
        assertEquals(TimeOfDay.TICKS_PER_DAY - 1, t.getTicks());
    }

    // --- The sun's path ------------------------------------------------------------

    private static float elevationAt(float dayProgress) {
        return (float) Math.toDegrees(Math.asin(TimeOfDay.directionAt(dayProgress).y));
    }

    @Test
    void theSunRisesEastCrossesTheSouthAndSetsWest() {
        Vector3f dawn = TimeOfDay.directionAt(0f), noon = TimeOfDay.directionAt(0.25f);
        Vector3f dusk = TimeOfDay.directionAt(0.5f), midnight = TimeOfDay.directionAt(0.75f);
        assertEquals(-1f, dawn.x, 1e-6f, "rises due east (-X)");
        assertEquals(1f, dusk.x, 1e-6f, "sets due west (+X)");
        assertEquals(90f - TimeOfDay.LATITUDE_DEG, elevationAt(0.25f), 1e-3f, "culminates 90 - latitude up");
        assertTrue(noon.z > 0f && Math.abs(noon.x) < 1e-6f, "toward +Z at noon, got " + noon);
        assertEquals(-(90f - TimeOfDay.LATITUDE_DEG), elevationAt(0.75f), 1e-3f);
        assertEquals(1f, midnight.length(), 1e-6f);
    }

    @Test
    void theSkyTurnsAboutTheCelestialPole() {
        Vector3f pole = TimeOfDay.celestialPole();
        assertEquals(TimeOfDay.LATITUDE_DEG, (float) Math.toDegrees(Math.asin(pole.y)), 1e-4f, "pole stands latitude up");
        for (float p = 0f; p < 1f; p += 0.01f) {
            assertEquals(0f, TimeOfDay.directionAt(p).dot(pole), 1e-5f, "the sun keeps the equinox circle at " + p);
        }
        // A positive turn about the pole carries the sun forward along its path.
        Vector3f ahead = TimeOfDay.directionAt(0.1f).rotateAxis(0.05f, pole.x, pole.y, pole.z);
        float h = TimeOfDay.skyAngle(0.1f) + 0.05f;
        assertEquals((float) Math.sin(h), ahead.x, 1e-5f);
    }

    @Test
    void twilightIsSlowedAndNoonAndMidnightStayPut() {
        // Real seconds for the sun to sink from the horizon to -6 degrees: civil twilight.
        float secondsPerProgress = TimeOfDay.DAY_LENGTH_SECONDS;
        float p = 0.5f;
        while (elevationAt(p) > -6f) p += 1e-5f;
        float civil = (p - 0.5f) * secondsPerProgress;
        assertTrue(civil > 75f && civil < 95f, "civil twilight lasts about 85 s, got " + civil);
        // The warp keeps the day's four milestones on their ticks.
        assertEquals(0f, TimeOfDay.skyAngle(0.5f) - (float) (Math.PI / 2), 1e-5f);
        assertEquals(0f, TimeOfDay.skyAngle(0.25f), 1e-5f);
        // And the sun only ever moves forward.
        float previous = TimeOfDay.skyAngle(0f);
        for (float q = 0.001f; q < 1f; q += 0.001f) {
            float angle = TimeOfDay.skyAngle(q);
            assertTrue(angle > previous, "sky angle went back at " + q);
            previous = angle;
        }
    }

    @Test
    void ambientLightFollowsTheSunsElevation() {
        assertEquals(1f, new TimeOfDay(TimeOfDay.NOON).getAmbientLightLevel(), 1e-6f);
        assertEquals(TimeOfDay.HORIZON_AMBIENT, new TimeOfDay(TimeOfDay.DUSK).getAmbientLightLevel(), 1e-4f);
        assertEquals(TimeOfDay.HORIZON_AMBIENT, new TimeOfDay(TimeOfDay.DAWN).getAmbientLightLevel(), 1e-4f);
        assertEquals(TimeOfDay.MIDNIGHT_AMBIENT, new TimeOfDay(TimeOfDay.MIDNIGHT).getAmbientLightLevel(), 1e-4f);
        // Dawn mirrors dusk, and it only rises from midnight to noon.
        float previous = 0f;
        for (long tick = TimeOfDay.MIDNIGHT; tick <= TimeOfDay.MIDNIGHT + 12_000; tick += 100) {
            long t = tick % TimeOfDay.TICKS_PER_DAY;
            float a = new TimeOfDay(t).getAmbientLightLevel();
            assertTrue(a >= previous - 1e-6f, "ambient fell at tick " + t);
            long mirror = Math.floorMod(TimeOfDay.DUSK - (t - TimeOfDay.DAWN), TimeOfDay.TICKS_PER_DAY);
            assertEquals(a, new TimeOfDay(mirror).getAmbientLightLevel(), 1e-3f, "tick " + t);
            previous = a;
        }
    }
}
