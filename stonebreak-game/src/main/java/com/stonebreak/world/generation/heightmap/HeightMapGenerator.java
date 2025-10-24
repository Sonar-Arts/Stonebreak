package com.stonebreak.world.generation.heightmap;

import com.stonebreak.util.SplineInterpolator;
import com.stonebreak.world.generation.NoiseGenerator;
import com.stonebreak.world.operations.WorldConfiguration;

/**
 * Handles height map generation using noise functions and spline interpolation.
 * Generates terrain height based on continentalness values, producing varied landscapes
 * from deep oceans to high mountain peaks.
 *
 * Follows Single Responsibility Principle - only handles height calculations.
 */
public class HeightMapGenerator {
    private static final int WORLD_HEIGHT = WorldConfiguration.WORLD_HEIGHT;

    private final long seed;
    private final NoiseGenerator continentalnessNoise;
    private final SplineInterpolator terrainSpline;

    /**
     * Creates a new height map generator with the given seed.
     *
     * @param seed World seed for deterministic generation
     */
    public HeightMapGenerator(long seed) {
        this.seed = seed;
        this.continentalnessNoise = new NoiseGenerator(seed + 2);
        this.terrainSpline = new SplineInterpolator();
        initializeTerrainSpline();
    }

    /**
     * Initializes the terrain spline with height control points.
     * Maps continentalness values (-1.0 to 1.0) to terrain heights.
     */
    private void initializeTerrainSpline() {
        terrainSpline.addPoint(-1.0, 70);  // Islands (above sea level)
        terrainSpline.addPoint(-0.8, 20);  // Deep ocean
        terrainSpline.addPoint(-0.4, 60);  // Approaching coast
        terrainSpline.addPoint(-0.2, 70);  // Just above sea level
        terrainSpline.addPoint(0.1, 75);   // Lowlands
        terrainSpline.addPoint(0.3, 120);  // Mountain foothills
        terrainSpline.addPoint(0.7, 140);  // Common foothills
        terrainSpline.addPoint(1.0, 200);  // High peaks
    }

    /**
     * Generates terrain height for the specified world position.
     *
     * @param x World X coordinate
     * @param z World Z coordinate
     * @return Terrain height at the given position (clamped to world bounds)
     */
    public int generateHeight(int x, int z) {
        float continentalness = getContinentalness(x, z);
        int height = (int) terrainSpline.interpolate(continentalness);
        return Math.max(1, Math.min(height, WORLD_HEIGHT - 1));
    }

    /**
     * Gets the continentalness value at the specified world position.
     * Continentalness determines whether terrain is ocean, coast, or land.
     *
     * @param x World X coordinate
     * @param z World Z coordinate
     * @return Continentalness value in range [-1.0, 1.0]
     */
    public float getContinentalness(int x, int z) {
        return continentalnessNoise.noise(x / 800.0f, z / 800.0f);
    }

    /**
     * Gets the world seed used by this generator.
     *
     * @return World seed
     */
    public long getSeed() {
        return seed;
    }
}
