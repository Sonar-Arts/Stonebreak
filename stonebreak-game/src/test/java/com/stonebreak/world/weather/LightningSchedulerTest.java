package com.stonebreak.world.weather;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lightning is never sent over the network, so every client must derive the
 * same strikes from the seed and weather clock alone — however it slices time
 * into frames — and strikes may only come out of storm clouds.
 */
class LightningSchedulerTest {

    private static final long SEED = 987654321L;
    private static final double RANGE = 1500;

    @Test
    void sameSeedAndWindowGiveTheSameStrikes() {
        List<LightningScheduler.Strike> a = scheduler().strikesBetween(1000, 1010, 0, 0, RANGE, storm());
        List<LightningScheduler.Strike> b = scheduler().strikesBetween(1000, 1010, 0, 0, RANGE, storm());
        assertEquals(a, b);
    }

    @Test
    void frameSlicingDoesNotChangeTheStrikes() {
        LightningScheduler s = scheduler();
        List<LightningScheduler.Strike> whole = s.strikesBetween(500, 510, 0, 0, RANGE, storm());
        List<LightningScheduler.Strike> sliced = new ArrayList<>();
        for (double t = 500; t < 510; t += 1.0 / 60) {
            sliced.addAll(s.strikesBetween(t, Math.min(t + 1.0 / 60, 510), 0, 0, RANGE, storm()));
        }
        assertEquals(whole, sliced);
    }

    @Test
    void stormsStrikeAndClearSkiesDoNot() {
        LightningScheduler s = scheduler();
        // One query evaluates at most MAX_WINDOW_SECONDS, so walk ten minutes in steps.
        List<LightningScheduler.Strike> strikes = new ArrayList<>();
        for (double t = 0; t < 600; t += LightningScheduler.MAX_WINDOW_SECONDS) {
            strikes.addAll(s.strikesBetween(t, t + LightningScheduler.MAX_WINDOW_SECONDS, 0, 0, RANGE, storm()));
        }
        // Real storms flash a few times a minute; a sky full of them, tens at most.
        double perMinute = strikes.size() / 10.0;
        assertTrue(perMinute >= 2 && perMinute <= 20, "STORM strikes per minute: " + perMinute);
        assertTrue(s.strikesBetween(0, 600, 0, 0, RANGE, WeatherMode.CLEAR.fixedBias()).isEmpty());
        CloudField field = new CloudField(SEED);
        for (LightningScheduler.Strike strike : strikes) {
            CloudColumn col = field.columnAt(strike.x(), strike.z(), strike.seconds(), storm());
            assertTrue(!col.isClear() && col.type().storm, "strike outside a storm cell: " + strike);
            assertTrue(Math.hypot(strike.x(), strike.z()) <= RANGE);
        }
    }

    private static LightningScheduler scheduler() {
        return new LightningScheduler(new CloudField(SEED), SEED);
    }

    private static WeatherBias storm() {
        return WeatherMode.STORM.fixedBias();
    }
}
