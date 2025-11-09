package com.stonebreak.ui.terrainmapper.visualization.impl;

import com.stonebreak.ui.terrainmapper.visualization.NoiseVisualizer;
import com.stonebreak.world.generation.config.TerrainGenerationConfig;
import com.stonebreak.world.generation.noise.NoiseRouter;

/**
 * Visualizes the humidity (moisture) noise parameter.
 *
 * Humidity determines biome wetness (dry to wet):
 * - 0.0 = Arid (Desert, Red Sand Desert, Badlands, Tundra)
 * - 0.3 = Dry (Plains, Snowy Plains, Stony Peaks)
 * - 0.6 = Moderate (Gravel Beach, Taiga)
 * - 1.0 = Wet (Ice Fields)
 *
 * Humidity is purely noise-based and works together with temperature to
 * determine biome selection in the multi-noise system.
 *
 * Visualization:
 * - Black: Arid (0.0)
 * - Dark gray: Dry (0.25)
 * - Mid gray: Moderate (0.5)
 * - Light gray: Moist (0.75)
 * - White: Wet (1.0)
 *
 * Thread Safety: Safe for concurrent use (NoiseRouter is thread-safe)
 */
public class HumidityVisualizer implements NoiseVisualizer {

    private final NoiseRouter noiseRouter;

    /**
     * Creates a new humidity visualizer.
     *
     * @param seed World seed for deterministic generation
     * @param config Terrain generation configuration
     */
    public HumidityVisualizer(long seed, TerrainGenerationConfig config) {
        this.noiseRouter = new NoiseRouter(seed, config);
    }

    /**
     * Samples humidity at the given world position.
     *
     * @param worldX World X coordinate
     * @param worldZ World Z coordinate
     * @param seed World seed (unused - seed already set in constructor)
     * @return Humidity value in range [0.0, 1.0]
     */
    @Override
    public double sample(int worldX, int worldZ, long seed) {
        return noiseRouter.getHumidity(worldX, worldZ);
    }

    /**
     * Gets the display name for this visualizer.
     *
     * @return "Humidity"
     */
    @Override
    public String getName() {
        return "Humidity";
    }

    /**
     * Gets the minimum possible humidity value.
     *
     * @return 0.0 (arid)
     */
    @Override
    public double getMinValue() {
        return 0.0;
    }

    /**
     * Gets the maximum possible humidity value.
     *
     * @return 1.0 (wet)
     */
    @Override
    public double getMaxValue() {
        return 1.0;
    }
}
