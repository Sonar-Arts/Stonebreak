package com.stonebreak.ui.terrainmapper.visualization.impl;

import com.stonebreak.ui.terrainmapper.visualization.NoiseVisualizer;
import com.stonebreak.world.generation.config.TerrainGenerationConfig;
import com.stonebreak.world.generation.noise.MultiNoiseParameters;
import com.stonebreak.world.generation.noise.NoiseRouter;

/**
 * Visualizes the peaks & valleys noise parameter.
 *
 * Peaks & Valleys amplifies height extremes:
 * - -1.0 = Deep valleys (lower areas become lower)
 * -  0.0 = No amplification
 * - +1.0 = Sharp peaks (higher areas become higher)
 *
 * Only affects areas with significant height variation (>20 blocks from sea level).
 * Flat areas near sea level remain unaffected.
 *
 * Visualization:
 * - Black: Deep valleys (-1.0)
 * - Mid gray: No amplification (0.0)
 * - White: Sharp peaks (1.0)
 *
 * Thread Safety: Safe for concurrent use (NoiseRouter is thread-safe)
 */
public class PeaksValleysVisualizer implements NoiseVisualizer {

    private final NoiseRouter noiseRouter;

    /**
     * Creates a new peaks & valleys visualizer.
     *
     * @param seed World seed for deterministic generation
     * @param config Terrain generation configuration
     */
    public PeaksValleysVisualizer(long seed, TerrainGenerationConfig config) {
        this.noiseRouter = new NoiseRouter(seed, config);
    }

    /**
     * Samples peaks & valleys at the given world position.
     *
     * @param worldX World X coordinate
     * @param worldZ World Z coordinate
     * @param seed World seed (unused - seed already set in constructor)
     * @return Peaks & valleys value in range [-1.0, 1.0]
     */
    @Override
    public double sample(int worldX, int worldZ, long seed) {
        MultiNoiseParameters params = noiseRouter.sampleParameters(worldX, worldZ);
        return params.peaksValleys;
    }

    /**
     * Gets the display name for this visualizer.
     *
     * @return "Peaks & Valleys"
     */
    @Override
    public String getName() {
        return "Peaks & Valleys";
    }

    /**
     * Gets the minimum possible peaks & valleys value.
     *
     * @return -1.0 (deep valleys)
     */
    @Override
    public double getMinValue() {
        return -1.0;
    }

    /**
     * Gets the maximum possible peaks & valleys value.
     *
     * @return 1.0 (sharp peaks)
     */
    @Override
    public double getMaxValue() {
        return 1.0;
    }
}
