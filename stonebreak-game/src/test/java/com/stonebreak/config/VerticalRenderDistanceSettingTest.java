package com.stonebreak.config;

import com.stonebreak.world.operations.WorldConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** "Vertical Render Distance": blocks, whole cubes, clamped to the setting's range; default = the world default. */
class VerticalRenderDistanceSettingTest {

    @Test
    void defaultMatchesTheWorldDefault() {
        assertEquals(WorldConfiguration.DEFAULT_VERTICAL_RENDER_DISTANCE, 128);
        assertEquals(128, Settings.clampVerticalRenderDistance(WorldConfiguration.DEFAULT_VERTICAL_RENDER_DISTANCE));
    }

    @Test
    void clampsAndSnapsToCubes() {
        assertEquals(Settings.MIN_VERTICAL_RENDER_DISTANCE, Settings.clampVerticalRenderDistance(0));
        assertEquals(Settings.MAX_VERTICAL_RENDER_DISTANCE, Settings.clampVerticalRenderDistance(10_000));
        assertEquals(160, Settings.clampVerticalRenderDistance(150));
        assertEquals(128, Settings.clampVerticalRenderDistance(140));
        assertEquals(0, Settings.clampVerticalRenderDistance(200) % Settings.VERTICAL_RENDER_DISTANCE_STEP);
    }

    @Test
    void theWorldConfigTakesIt() {
        WorldConfiguration config = new WorldConfiguration();
        config.setVerticalRenderDistance(Settings.clampVerticalRenderDistance(256));
        assertEquals(256, config.getVerticalRenderDistance());
    }
}
