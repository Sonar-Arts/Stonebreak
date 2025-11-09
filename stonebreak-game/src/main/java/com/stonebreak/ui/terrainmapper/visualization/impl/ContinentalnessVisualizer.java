package com.stonebreak.ui.terrainmapper.visualization.impl;

import com.stonebreak.ui.terrainmapper.visualization.NoiseVisualizer;
import com.stonebreak.world.generation.config.TerrainGenerationConfig;
import com.stonebreak.world.generation.noise.NoiseRouter;

/**
 * Visualizes the continentalness noise parameter.
 *
 * Continentalness determines base terrain height and ocean vs inland distribution:
 * - -1.0 = Deep ocean
 * - -0.5 = Shallow ocean / coastline
 * -  0.0 = Coast / beach
 * - +0.5 = Inland / lowlands
 * - +1.0 = Far inland / highlands
 *
 * Visualization:
 * - Black: Ocean (-1.0)
 * - Dark gray: Coastline (-0.5 to 0.0)
 * - Mid gray: Lowlands (0.0 to 0.5)
 * - Light gray: Highlands (0.5 to 1.0)
 * - White: Far inland (1.0)
 *
 * Thread Safety: Safe for concurrent use (NoiseRouter is thread-safe)
 */
public class ContinentalnessVisualizer implements NoiseVisualizer {

    private final NoiseRouter noiseRouter;

    /**
     * Creates a new continentalness visualizer.
     *
     * @param seed World seed for deterministic generation
     * @param config Terrain generation configuration
     */
    public ContinentalnessVisualizer(long seed, TerrainGenerationConfig config) {
        this.noiseRouter = new NoiseRouter(seed, config);
    }

    /**
     * Samples continentalness at the given world position.
     *
     * @param worldX World X coordinate
     * @param worldZ World Z coordinate
     * @param seed World seed (unused - seed already set in constructor)
     * @return Continentalness value in range [-1.0, 1.0]
     */
    @Override
    public double sample(int worldX, int worldZ, long seed) {
        return noiseRouter.getContinentalness(worldX, worldZ);
    }

    /**
     * Gets the display name for this visualizer.
     *
     * @return "Continentalness"
     */
    @Override
    public String getName() {
        return "Continentalness";
    }

    /**
     * Gets the minimum possible continentalness value.
     *
     * @return -1.0 (deep ocean)
     */
    @Override
    public double getMinValue() {
        return -1.0;
    }

    /**
     * Gets the maximum possible continentalness value.
     *
     * @return 1.0 (far inland)
     */
    @Override
    public double getMaxValue() {
        return 1.0;
    }
}
