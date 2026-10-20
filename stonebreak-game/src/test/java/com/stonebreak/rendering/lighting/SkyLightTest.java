package com.stonebreak.rendering.lighting;

import com.stonebreak.rendering.gameWorld.weather.WeatherEffects;
import com.stonebreak.world.MoonCycle;
import com.stonebreak.world.TimeOfDay;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sky lights everything else, so nothing it lights may outshine it while
 * the sun is down. That was the bug the moon was added for: pre-dawn fog glowed
 * against a black sky above the cloud deck.
 */
class SkyLightTest {

    private static SkyLight at(long day, long tick) {
        return SkyLight.of(new TimeOfDay(day * TimeOfDay.TICKS_PER_DAY + tick));
    }

    @Test
    void fogIsNeverBrighterThanTheHorizonWithTheSunDown() {
        WeatherEffects weather = new WeatherEffects();
        for (long day = 0; day < MoonCycle.CYCLE_DAYS; day++) {
            for (long tick = 0; tick < TimeOfDay.TICKS_PER_DAY; tick += 100) {
                SkyLight sky = at(day, tick);
                if (sky.daylight() > 0f) {
                    continue;
                }
                weather.updateFogColor(sky);
                float fog = SkyLight.luminance(weather.fogColor());
                float horizon = SkyLight.luminance(sky.horizon());
                assertTrue(fog <= horizon + 1e-5f,
                        "day " + day + " tick " + tick + ": fog " + fog + " > horizon " + horizon);
            }
        }
    }

    @Test
    void preDawnFogIsBlueNotOrange() {
        SkyLight sky = at(MoonCycle.CYCLE_DAYS / 2, TimeOfDay.TICKS_PER_DAY - 300);
        WeatherEffects weather = new WeatherEffects();
        weather.updateFogColor(sky);
        assertTrue(weather.fogColor().z > weather.fogColor().x,
                "fog before sunrise takes the blue-hour zenith, got " + weather.fogColor());
    }

    @Test
    void fullMoonNightsAreBrighterThanNewMoonNights() {
        SkyLight full = at(MoonCycle.CYCLE_DAYS / 2, TimeOfDay.MIDNIGHT);
        SkyLight dark = at(0, TimeOfDay.MIDNIGHT);
        assertTrue(full.ambient() > dark.ambient() + 0.1f);
        assertTrue(SkyLight.luminance(full.zenith()) > 3f * SkyLight.luminance(dark.zenith()));
        assertEquals(SkyLight.NIGHT_AMBIENT, dark.ambient(), 0.005f);
    }

    @Test
    void dayIsUnchanged() {
        SkyLight noon = at(3, TimeOfDay.NOON);
        assertEquals(1f, noon.ambient(), 1e-6);
        assertEquals(1f, noon.daylight(), 1e-6);
        assertFalse(noon.keyIsMoon());
        assertEquals(1f, noon.keyLight().x, 1e-6);
    }

    @Test
    void theEveningGoldCoversMostOfTheSkyAtItsHeight() {
        SkyLight sunset = at(2, 12_000);
        // The pre-moon sunset colour overhead, and fog and clouds read it as before.
        assertEquals(1f, sunset.evening(), 1e-6);
        assertEquals(1.0f, sunset.zenith().x, 1e-6);
        assertEquals(0.5f, sunset.zenith().y, 1e-6);
        assertEquals(0.3f, sunset.zenith().z, 1e-6);
        assertEquals(sunset.zenith(), sunset.skyColor());
        float c = sunset.sunsetCoverage();
        assertEquals(1f, SkyLight.sunsetWeight(c, 0f), 1e-6, "gold under the sun");
        assertEquals(1f, SkyLight.sunsetWeight(c, 0.5f), 1e-6, "gold overhead and to the sides");
        float opposite = SkyLight.sunsetWeight(c, 1f);
        assertTrue(opposite > 0.7f && opposite < 1f, "a little thinner opposite the sun, got " + opposite);
        // Half-way from day to sunset at 5 PM, as the old sky had it.
        assertEquals(0.765f, at(2, 11_000).zenith().x, 1e-4);
        // Mornings keep the blue hour.
        assertEquals(0f, at(2, TimeOfDay.TICKS_PER_DAY - 300).evening(), 1e-6);
    }

    @Test
    void theGoldDrawsBackTowardTheSunFromTheOppositeHorizon() {
        float previousOpposite = 1f, previousOverhead = 1f, previousSunSide = 1f;
        boolean overheadOutlastsOpposite = false;
        for (long tick = 12_000; tick <= 14_000; tick += 100) {
            float c = at(2, tick).sunsetCoverage();
            float opposite = SkyLight.sunsetWeight(c, 1f);
            float overhead = SkyLight.sunsetWeight(c, 0.5f);
            float sunSide = SkyLight.sunsetWeight(c, 0f);
            assertTrue(opposite <= previousOpposite && overhead <= previousOverhead && sunSide <= previousSunSide,
                    "the gold only recedes, tick " + tick);
            assertTrue(opposite <= overhead && overhead <= sunSide, "it leaves the far side first, tick " + tick);
            overheadOutlastsOpposite |= opposite < 0.01f && overhead > 0.5f;
            previousOpposite = opposite;
            previousOverhead = overhead;
            previousSunSide = sunSide;
        }
        assertTrue(overheadOutlastsOpposite, "for a while the far horizon is blue under a gold zenith");
        assertEquals(0f, SkyLight.sunsetWeight(at(2, 13_990).sunsetCoverage(), 0f), 0.02f, "gone by 8 PM");
        assertEquals(0f, at(2, 14_000).evening(), 1e-6);
    }

    @Test
    void theMoonTakesOverOnlyOnceTheSunIsDown() {
        for (long tick = 0; tick < TimeOfDay.TICKS_PER_DAY; tick += 50) {
            SkyLight sky = at(MoonCycle.CYCLE_DAYS / 2, tick);
            if (sky.keyIsMoon()) {
                assertTrue(sky.sunDir().y <= SkyLight.KEY_SWITCH_Y, "tick " + tick);
            }
        }
        SkyLight midnight = at(MoonCycle.CYCLE_DAYS / 2, TimeOfDay.MIDNIGHT);
        assertTrue(midnight.keyIsMoon());
        assertTrue(midnight.keyLight().z > midnight.keyLight().x, "moonlight is cool");
        assertTrue(midnight.keyLight().z <= SkyLight.MOON_KEY + 1e-6f, "and faint");
        // At the hand-over the moon's light starts from nothing, so shadows never jump.
        SkyLight handover = SkyLight.compute(TimeOfDay.directionAt(0.5052f),
                midnight.moonDir(), 0.5f, 0.3f, 12_125);
        assertTrue(handover.keyIsMoon());
        assertTrue(handover.keyLight().length() < 0.02f);
    }
}
