package com.stonebreak.ui.terrainmapper.visualization.impl;

import com.stonebreak.ui.terrainmapper.visualization.NoiseVisualizer;
import com.stonebreak.world.generation.config.TerrainGenerationConfig;
import com.stonebreak.world.generation.noise.MultiNoiseParameters;
import com.stonebreak.world.generation.noise.NoiseRouter;

/**
 * Visualizes the weirdness noise parameter.
 *
 * Weirdness creates unique terrain features and rare biome variants:
 * - -1.0 to -0.7 = (Reserved for future features: spires, arches)
 * - -0.7 to 0.7 = Normal terrain
 * - +0.7 to 1.0 = Terracing effect (plateaus, mesas, 8-block steps)
 *
 * High weirdness values (> 0.7) create Badlands-style stepped terrain.
 * Also used to trigger rare biome variants in biome selection.
 *
 * Visualization:
 * - Black: High negative weirdness (-1.0)
 * - Mid gray: Normal terrain (0.0)
 * - White: High positive weirdness (1.0, mesas/plateaus)
 *
 * Thread Safety: Safe for concurrent use (NoiseRouter is thread-safe)
 */
public class WeirdnessVisualizer implements NoiseVisualizer {

    private final NoiseRouter noiseRouter;

    /**
     * Creates a new weirdness visualizer.
     *
     * @param seed World seed for deterministic generation
     * @param config Terrain generation configuration
     */
    public WeirdnessVisualizer(long seed, TerrainGenerationConfig config) {
        this.noiseRouter = new NoiseRouter(seed, config);
    }

    /**
     * Samples weirdness at the given world position.
     *
     * @param worldX World X coordinate
     * @param worldZ World Z coordinate
     * @param seed World seed (unused - seed already set in constructor)
     * @return Weirdness value in range [-1.0, 1.0]
     */
    @Override
    public double sample(int worldX, int worldZ, long seed) {
        MultiNoiseParameters params = noiseRouter.sampleParameters(worldX, worldZ);
        return params.weirdness;
    }

    /**
     * Gets the display name for this visualizer.
     *
     * @return "Weirdness"
     */
    @Override
    public String getName() {
        return "Weirdness";
    }

    /**
     * Gets the minimum possible weirdness value.
     *
     * @return -1.0
     */
    @Override
    public double getMinValue() {
        return -1.0;
    }

    /**
     * Gets the maximum possible weirdness value.
     *
     * @return 1.0 (plateaus/mesas)
     */
    @Override
    public double getMaxValue() {
        return 1.0;
    }
}
