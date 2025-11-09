package com.stonebreak.ui.terrainmapper.visualization.impl;

import com.stonebreak.ui.terrainmapper.visualization.NoiseVisualizer;
import com.stonebreak.world.generation.config.TerrainGenerationConfig;
import com.stonebreak.world.generation.noise.NoiseRouter;

/**
 * Visualizes the erosion noise parameter.
 *
 * Erosion determines terrain flatness vs mountainousness:
 * - -1.0 = Sharp mountains (1.5x height variation)
 * - -0.5 = Moderate mountains
 * -  0.0 = Normal terrain
 * - +0.5 = Rolling hills
 * - +1.0 = Flat plains (0.6x height variation)
 *
 * Visualization:
 * - Black: Sharp mountains (-1.0)
 * - Dark gray: Moderate mountains (-0.5)
 * - Mid gray: Normal terrain (0.0)
 * - Light gray: Rolling hills (0.5)
 * - White: Flat plains (1.0)
 *
 * Thread Safety: Safe for concurrent use (NoiseRouter is thread-safe)
 */
public class ErosionVisualizer implements NoiseVisualizer {

    private final NoiseRouter noiseRouter;

    /**
     * Creates a new erosion visualizer.
     *
     * @param seed World seed for deterministic generation
     * @param config Terrain generation configuration
     */
    public ErosionVisualizer(long seed, TerrainGenerationConfig config) {
        this.noiseRouter = new NoiseRouter(seed, config);
    }

    /**
     * Samples erosion at the given world position.
     *
     * @param worldX World X coordinate
     * @param worldZ World Z coordinate
     * @param seed World seed (unused - seed already set in constructor)
     * @return Erosion value in range [-1.0, 1.0]
     */
    @Override
    public double sample(int worldX, int worldZ, long seed) {
        return noiseRouter.getErosion(worldX, worldZ);
    }

    /**
     * Gets the display name for this visualizer.
     *
     * @return "Erosion"
     */
    @Override
    public String getName() {
        return "Erosion";
    }

    /**
     * Gets the minimum possible erosion value.
     *
     * @return -1.0 (sharp mountains)
     */
    @Override
    public double getMinValue() {
        return -1.0;
    }

    /**
     * Gets the maximum possible erosion value.
     *
     * @return 1.0 (flat plains)
     */
    @Override
    public double getMaxValue() {
        return 1.0;
    }
}
