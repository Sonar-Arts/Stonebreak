package com.stonebreak.ui.terrainMapper.visualization;

import com.stonebreak.ui.terrainMapper.visualization.impl.HeightVisualizer;
import com.stonebreak.world.generation.NativeCubeGenerator.TerrainScalePreset;
import com.stonebreak.world.generation.NativeTerrain;
import com.stonebreak.world.generation.TerrainGeneratorType;
import com.stonebreak.world.operations.WorldConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The terrain mapper's Standard Height mode is the native generator's own column height, at the chosen scale. */
class NativeStandardPreviewTest {

    private static final long SEED = 20260820L;

    @Test
    void heightIsTheWorldGeneratorsColumnHeight() {
        assumeTrue(NativeTerrain.isAvailable(), "libstonebreak_worldgen not built");
        for (TerrainScalePreset scale : TerrainScalePreset.values()) {
            try (NativeStandardColumns columns = NativeStandardColumns.open(SEED, scale);
                 NativeTerrain terrain = NativeTerrain.create(SEED, TerrainGeneratorType.STANDARD, scale, null)) {
                for (int i = 0; i < 16; i++) {
                    int x = i * 977 - 5000, z = i * -613 + 2000;
                    assertEquals(terrain.queryColumn(x, z).height(), columns.height(x, z), 0f,
                        scale + " at " + x + "," + z);
                }
            }
        }
    }

    @Test
    void theScaleChangesThePreview() {
        assumeTrue(NativeTerrain.isAvailable(), "libstonebreak_worldgen not built");
        try (NativeStandardColumns quarter = NativeStandardColumns.open(SEED, TerrainScalePreset.ONE_TO_FOUR);
             NativeStandardColumns half = NativeStandardColumns.open(SEED, TerrainScalePreset.ONE_TO_TWO)) {
            int differing = 0;
            for (int i = 0; i < 64; i++) {
                int x = i * 331 - 9000, z = i * 173;
                if (quarter.height(x, z) != half.height(x, z)) differing++;
            }
            assertNotEquals(0, differing);
        }
    }

    @Test
    void aClosedSourceAnswersNaN() {
        assumeTrue(NativeTerrain.isAvailable(), "libstonebreak_worldgen not built");
        NativeStandardColumns columns = NativeStandardColumns.open(SEED, TerrainScalePreset.ONE_TO_FOUR);
        columns.close();
        assertTrue(Float.isNaN(columns.height(0, 0)));
        columns.close(); // idempotent
    }

    @Test
    void heightColoursSpanTheWholeWorld() {
        assertEquals(0f, HeightVisualizer.normalizeWorldY(WorldConfiguration.MIN_Y));
        assertEquals(1f, HeightVisualizer.normalizeWorldY(WorldConfiguration.MAX_Y));
        assertEquals((64f - WorldConfiguration.MIN_Y) / WorldConfiguration.HEIGHT,
            HeightVisualizer.normalizeWorldY(64), 1e-6f);
        assertEquals(0f, HeightVisualizer.normalizeWorldY(Float.NaN));
        assertEquals(1f, HeightVisualizer.normalizeWorldY(5000));
    }
}
