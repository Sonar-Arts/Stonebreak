package com.stonebreak.audio;

import com.stonebreak.world.weather.CloudColumn;
import com.stonebreak.world.weather.CloudType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rain is heard only where it falls: below the cloud that drops it, never
 * from inside or above it.
 */
class WeatherAudioTest {

    private static final int BASE = 152;
    private static final int TOP = BASE + 96;
    /** A fully grown, fully dense storm column: the heaviest rain there is. */
    private static final CloudColumn STORM =
            new CloudColumn(1f, CloudType.CUMULONIMBUS, BASE, TOP, 1f, 1f);

    @Test
    void belowTheCloudHearsAllOfItsRain() {
        float rain = STORM.precipitation();
        assertTrue(rain > 0f, "a storm column rains");
        assertEquals(rain, WeatherAudio.rainHeard(STORM, 64f), 1e-6f);
        assertEquals(rain, WeatherAudio.rainHeard(STORM, BASE), 1e-6f);
    }

    @Test
    void aboveTheCloudHearsNone() {
        assertEquals(0f, WeatherAudio.rainHeard(STORM, TOP + 1f));
        assertEquals(0f, WeatherAudio.rainHeard(STORM, 400f));
    }

    @Test
    void fadesOutGoingUpIntoTheCloud() {
        float rain = STORM.precipitation();
        float half = WeatherAudio.rainHeard(STORM, BASE + WeatherAudio.CLOUD_FADE / 2f);
        assertEquals(rain / 2f, half, 1e-5f);
        assertEquals(0f, WeatherAudio.rainHeard(STORM, BASE + WeatherAudio.CLOUD_FADE));
    }

    @Test
    void aThinCloudIsSilentAtItsTop() {
        CloudColumn thin = new CloudColumn(1f, CloudType.CUMULONIMBUS, BASE, BASE + 8, 1f, 1f);
        assertEquals(0f, WeatherAudio.rainHeard(thin, BASE + 8f));
        assertEquals(thin.precipitation() / 2f, WeatherAudio.rainHeard(thin, BASE + 4f), 1e-5f);
    }

    @Test
    void clearSkyIsSilentAtAnyHeight() {
        assertEquals(0f, WeatherAudio.rainHeard(CloudColumn.CLEAR, 64f));
        assertEquals(0f, WeatherAudio.rainHeard(CloudColumn.CLEAR, -10f));
    }

    @Test
    void rainIsMuffledUnderARoofAndGoneDeepUnderground() {
        assertEquals(1f, WeatherAudio.rainShelter(0.0), "open sky");
        assertEquals(1f, WeatherAudio.rainShelter(1.0), "a block of cover is still open");
        assertEquals(WeatherAudio.ROOFED, WeatherAudio.rainShelter(3.0), "a roof");
        assertEquals(WeatherAudio.ROOFED, WeatherAudio.rainShelter(WeatherAudio.ROOF_DEPTH));
        float mid = WeatherAudio.rainShelter((WeatherAudio.ROOF_DEPTH + WeatherAudio.RAIN_SILENT_DEPTH) / 2);
        assertEquals(WeatherAudio.ROOFED / 2f, mid, 1e-6f, "fading with depth");
        assertEquals(0f, WeatherAudio.rainShelter(WeatherAudio.RAIN_SILENT_DEPTH));
        assertEquals(0f, WeatherAudio.rainShelter(200.0), "deep underground");
    }

    @Test
    void thunderCarriesFurtherDownButNotForever() {
        assertEquals(1f, WeatherAudio.thunderShelter(0.0));
        assertEquals(1f, WeatherAudio.thunderShelter(WeatherAudio.ROOF_DEPTH), "a roof does not dull thunder");
        assertTrue(WeatherAudio.thunderShelter(WeatherAudio.RAIN_SILENT_DEPTH) > 0f, "heard where rain is not");
        assertEquals(0f, WeatherAudio.thunderShelter(WeatherAudio.THUNDER_SILENT_DEPTH));
        assertEquals(0f, WeatherAudio.thunderShelter(200.0));
    }
}
