package com.stonebreak.ui.terrainmapper.visualization.impl;

import com.stonebreak.ui.terrainmapper.visualization.NoiseVisualizer;
import com.stonebreak.world.generation.config.TerrainGenerationConfig;
import com.stonebreak.world.generation.noise.NoiseRouter;

/**
 * Visualizes the temperature noise parameter.
 *
 * Temperature determines biome climate zones (hot to cold):
 * - 0.0 = Frozen (Ice Fields, Tundra, Snowy Plains)
 * - 0.3 = Cold (Taiga, Stony Peaks)
 * - 0.6 = Temperate (Plains, Gravel Beach)
 * - 1.0 = Hot (Desert, Red Sand Desert, Badlands)
 *
 * Temperature is purely noise-based without altitude adjustment in the current system,
 * allowing biomes to be controlled by the multi-noise parameter system.
 *
 * Visualization:
 * - Black: Frozen (0.0)
 * - Dark gray: Cold (0.25)
 * - Mid gray: Temperate (0.5)
 * - Light gray: Warm (0.75)
 * - White: Hot (1.0)
 *
 * Thread Safety: Safe for concurrent use (NoiseRouter is thread-safe)
 */
public class TemperatureVisualizer implements NoiseVisualizer {

    private final NoiseRouter noiseRouter;

    /**
     * Creates a new temperature visualizer.
     *
     * @param seed World seed for deterministic generation
     * @param config Terrain generation configuration
     */
    public TemperatureVisualizer(long seed, TerrainGenerationConfig config) {
        this.noiseRouter = new NoiseRouter(seed, config);
    }

    /**
     * Samples temperature at the given world position.
     *
     * @param worldX World X coordinate
     * @param worldZ World Z coordinate
     * @param seed World seed (unused - seed already set in constructor)
     * @return Temperature value in range [0.0, 1.0]
     */
    @Override
    public double sample(int worldX, int worldZ, long seed) {
        return noiseRouter.getTemperature(worldX, worldZ);
    }

    /**
     * Gets the display name for this visualizer.
     *
     * @return "Temperature"
     */
    @Override
    public String getName() {
        return "Temperature";
    }

    /**
     * Gets the minimum possible temperature value.
     *
     * @return 0.0 (frozen)
     */
    @Override
    public double getMinValue() {
        return 0.0;
    }

    /**
     * Gets the maximum possible temperature value.
     *
     * @return 1.0 (hot)
     */
    @Override
    public double getMaxValue() {
        return 1.0;
    }
}
