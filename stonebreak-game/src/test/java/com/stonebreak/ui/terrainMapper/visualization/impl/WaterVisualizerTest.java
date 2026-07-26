package com.stonebreak.ui.terrainMapper.visualization.impl;

import com.stonebreak.ui.terrainMapper.config.TerrainMapperConfig;
import com.stonebreak.world.generation.diffusion.TerrainTile;
import com.stonebreak.world.generation.heightmap.HeightMapGenerator;
import com.stonebreak.world.operations.WorldConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers what makes the water overlay a distinct view from {@link TopographyVisualizer}:
 * it colors the water-level plane, not the height plane, so a dry column reads as background
 * regardless of its elevation and a wet column's color depends only on where its water surface
 * sits — sea, river, or lake alike.
 *
 * <p>Neither {@code colorFor} nor {@code formatValue} touches the height source, so the
 * generator is wired to a tile source that fails loudly if anything tries to sample through it.
 */
class WaterVisualizerTest {

    private static final int SEA_LEVEL = WorldConfiguration.SEA_LEVEL;
    private static final int CEILING = TerrainMapperConfig.TOPO_LAND_CEILING;
    private static final int NO_WATER = TerrainTile.NO_WATER;

    private final WaterVisualizer visualizer = new WaterVisualizer(
            new HeightMapGenerator((x, z) -> {
                throw new UnsupportedOperationException("color logic must not sample terrain");
            }));

    @Test
    void dryColumnsAreBackgroundRegardlessOfElevation() {
        int atSeaLevel = visualizer.colorFor(NO_WATER);
        // colorFor never reads elevation — NO_WATER is the only input that matters for "dry".
        assertEquals(atSeaLevel, visualizer.colorFor(NO_WATER));
        assertNotEquals(atSeaLevel, visualizer.colorFor(SEA_LEVEL), "a wet column must differ from dry");
    }

    @Test
    void seaLevelWaterAndHighLakeWaterReadDifferently() {
        int sea = visualizer.colorFor(SEA_LEVEL);
        int mountainLake = visualizer.colorFor(SEA_LEVEL + CEILING);
        assertNotEquals(sea, mountainLake, "a mountain lake must not read identically to the sea");
        // Higher water should lerp toward the bright end of the ramp, not away from it.
        assertTrue(luma(mountainLake) > luma(sea), "higher water level should be the lighter end of the ramp");
    }

    @Test
    void colorClampsAtAndBeyondTheCeiling() {
        int atCeiling = visualizer.colorFor(SEA_LEVEL + CEILING);
        int wayAboveCeiling = visualizer.colorFor(SEA_LEVEL + CEILING + 10_000);
        assertEquals(atCeiling, wayAboveCeiling, "water level far above the ceiling clamps rather than extrapolating");
    }

    @Test
    void formatValueReportsDryOrTheWaterLevel() {
        assertEquals("dry", visualizer.formatValue(NO_WATER));
        assertEquals(SEA_LEVEL + " blocks", visualizer.formatValue(SEA_LEVEL));
    }

    @Test
    void normalizeIsAPassThroughSoColorForSeesTheRealWaterLevel() {
        // Same trick as TopographyVisualizer: colorFor is defined in water-level space, not [0,1].
        assertEquals((float) NO_WATER, visualizer.normalize(NO_WATER));
        assertEquals((float) SEA_LEVEL, visualizer.normalize(SEA_LEVEL));
    }

    private static int red(int argb)   { return (argb >>> 16) & 0xFF; }
    private static int green(int argb) { return (argb >>> 8) & 0xFF; }
    private static int blue(int argb)  { return argb & 0xFF; }
    private static int luma(int argb)  { return (red(argb) + green(argb) + blue(argb)) / 3; }
}
