package com.stonebreak.config;

import com.stonebreak.world.generation.NativeCubeGenerator.TerrainScalePreset;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TerrainScaleOptionTest {

    @Test
    void labelsAndParsingRoundTrip() {
        for (TerrainScalePreset p : TerrainScalePreset.values()) {
            assertEquals(p, TerrainScaleOption.parse(TerrainScaleOption.label(p)));
            assertEquals(p, TerrainScaleOption.parse(p.name().toLowerCase()));
        }
        assertEquals("1:4", TerrainScaleOption.label(TerrainScalePreset.ONE_TO_FOUR));
        assertEquals("1:2", TerrainScaleOption.label(TerrainScalePreset.ONE_TO_TWO));
        assertEquals(TerrainScalePreset.ONE_TO_TWO, TerrainScaleOption.parse(" 2 "));
    }

    @Test
    void unknownIsNull() {
        assertNull(TerrainScaleOption.parse(null));
        assertNull(TerrainScaleOption.parse(""));
        assertNull(TerrainScaleOption.parse("1:8"));
    }

    @Test
    void defaultIsOneToFourAndNextCycles() {
        assertEquals(TerrainScalePreset.ONE_TO_FOUR, TerrainScaleOption.DEFAULT);
        assertEquals(TerrainScalePreset.ONE_TO_TWO, TerrainScaleOption.next(TerrainScalePreset.ONE_TO_FOUR));
        assertEquals(TerrainScalePreset.ONE_TO_FOUR, TerrainScaleOption.next(TerrainScalePreset.ONE_TO_TWO));
    }
}
