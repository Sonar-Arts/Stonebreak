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
}
