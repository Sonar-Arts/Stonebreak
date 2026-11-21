package com.stonebreak.core;

import com.stonebreak.world.generation.NativeCubeGenerator.TerrainScalePreset;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The {@code stonebreak.autoworld} spec: name, seed and the terrain scale (whose own colon must not eat the seed). */
class AutoWorldSpecTest {

    @Test
    void nameAlone() {
        AutoWorldSpec s = AutoWorldSpec.parse("Lab", null);
        assertEquals("Lab", s.name());
        assertEquals(AutoWorldSpec.DEFAULT_SEED, s.seed());
        assertNull(s.scale());
        assertFalse(s.badSeed());
    }

    @Test
    void nameAndSeed() {
        AutoWorldSpec s = AutoWorldSpec.parse("Lab:-42", null);
        assertEquals("Lab", s.name());
        assertEquals(-42L, s.seed());
    }

    @Test
    void scaleInTheSpecKeepsTheSeed() {
        AutoWorldSpec s = AutoWorldSpec.parse("Lab:4242:scale=1:2", null);
        assertEquals("Lab", s.name());
        assertEquals(4242L, s.seed());
        assertEquals(TerrainScalePreset.ONE_TO_TWO, s.scale());
    }

    @Test
    void scaleWithoutSeed() {
        AutoWorldSpec s = AutoWorldSpec.parse("Lab:scale=1:4", null);
        assertEquals("Lab", s.name());
        assertEquals(AutoWorldSpec.DEFAULT_SEED, s.seed());
        assertEquals(TerrainScalePreset.ONE_TO_FOUR, s.scale());
    }

    @Test
    void separateAutoscaleProperty() {
        assertEquals(TerrainScalePreset.ONE_TO_TWO, AutoWorldSpec.parse("Lab:7", "1:2").scale());
        assertEquals(TerrainScalePreset.ONE_TO_FOUR, AutoWorldSpec.parse("Lab:7:scale=1:4", "1:2").scale(),
            "the spec's own scale wins");
    }

    @Test
    void badSeedIsReported() {
        AutoWorldSpec s = AutoWorldSpec.parse("Lab:notanumber", null);
        assertTrue(s.badSeed());
        assertEquals(AutoWorldSpec.DEFAULT_SEED, s.seed());
    }
}
