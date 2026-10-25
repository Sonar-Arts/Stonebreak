package com.stonebreak.rendering.gameWorld.weather;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rain and snow fog follows Koschmieder's law: contrast falls to 2% at the
 * visibility, it thickens with the precipitation rate, and snow hides more
 * than rain does.
 */
class WeatherEffectsTest {

    /** Contrast left at distance {@code d}: Beer-Lambert, as the shaders apply it. */
    private static double contrast(float extinction, float d) {
        return Math.exp(-extinction * d);
    }

    @Test
    void heaviestPrecipitationLeavesTwoPercentContrastAtItsVisibility() {
        assertEquals(0.02, contrast(WeatherEffects.extinction(1f, 0f), WeatherEffects.HEAVY_RAIN_VISIBILITY), 1e-3);
        assertEquals(0.02, contrast(WeatherEffects.extinction(1f, 1f), WeatherEffects.HEAVY_SNOW_VISIBILITY), 1e-3);
    }

    @Test
    void noPrecipitationNoFog() {
        assertEquals(0f, WeatherEffects.extinction(0f, 0f));
        assertEquals(0f, WeatherEffects.extinction(0f, 1f));
    }

    @Test
    void fogThickensWithRateAndSnowHidesMoreThanRain() {
        float previous = 0f;
        for (float rate = 0.1f; rate <= 1f; rate += 0.1f) {
            float rain = WeatherEffects.extinction(rate, 0f);
            assertTrue(rain > previous, "rain fog must thicken with rate");
            assertTrue(WeatherEffects.extinction(rate, 1f) > rain, "snow must hide more than rain");
            previous = rain;
        }
    }
}
