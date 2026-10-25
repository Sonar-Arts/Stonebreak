package com.stonebreak.rendering.gameWorld.clouds;

import com.stonebreak.rendering.lighting.SkyLight;
import com.stonebreak.world.MoonCycle;
import com.stonebreak.world.TimeOfDay;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The sky's light on the cloud deck never outshines the sky while the sun is down. */
class CloudAmbientTest {

    @Test
    void nightDeckIsNoBrighterThanTheHorizon() {
        for (long day = 0; day < MoonCycle.CYCLE_DAYS; day++) {
            for (long tick = 0; tick < TimeOfDay.TICKS_PER_DAY; tick += 100) {
                SkyLight sky = SkyLight.of(new TimeOfDay(day * TimeOfDay.TICKS_PER_DAY + tick));
                if (sky.daylight() > 0f) {
                    continue;
                }
                Vector3f deck = CloudRenderer.ambientColor(sky, sky.skyColor(), sky.ambient(), new Vector3f());
                assertTrue(SkyLight.luminance(deck) <= SkyLight.luminance(sky.horizon()) + 1e-5f,
                        "day " + day + " tick " + tick + ": " + deck + " vs horizon " + sky.horizon());
            }
        }
    }

    @Test
    void middayDeckIsAsBefore() {
        SkyLight noon = SkyLight.of(new TimeOfDay(TimeOfDay.NOON));
        Vector3f sky = new Vector3f(0.53f, 0.81f, 0.92f);
        Vector3f deck = CloudRenderer.ambientColor(noon, sky, 1f, new Vector3f());
        assertEquals(0.85f * (0.6f + 0.4f * sky.x), deck.x, 1e-6);
        assertEquals(0.85f * (0.6f + 0.4f * sky.z), deck.z, 1e-6);
    }
}
