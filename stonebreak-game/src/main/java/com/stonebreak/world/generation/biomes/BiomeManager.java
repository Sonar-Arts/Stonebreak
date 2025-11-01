package com.stonebreak.world.generation.biomes;

import com.stonebreak.world.generation.config.TerrainGenerationConfig;
import com.stonebreak.world.generation.noise.MultiNoiseParameters;
import com.stonebreak.world.generation.noise.NoiseRouter;
import com.stonebreak.world.operations.WorldConfiguration;

/**
 * Manages biome determination using multi-noise parameter selection.
 *
 * NEW SYSTEM: Biomes selected based on 6 parameters instead of just 2.
 * - Continentalness: Inland vs coastal
 * - Erosion: Flat vs mountainous
 * - Peaks & Valleys: Height variation
 * - Weirdness: Normal vs rare variants
 * - Temperature: Cold to hot
 * - Humidity: Dry to wet
 *
 * This replaces the old Whittaker diagram + climate region system with a more
 * flexible multi-dimensional lookup that allows:
 * - Same biome in different terrain (flat desert AND hilly desert)
 * - Rare biome variants (high weirdness)
 * - Better biome-terrain alignment (mountains get mountain biomes)
 *
 * Architecture:
 * - NoiseRouter: Samples all 6 parameters at any world position
 * - BiomeParameterTable: Defines acceptable ranges for each biome
 * - This class: Coordinates sampling and lookup
 *
 * Follows Single Responsibility Principle - only handles biome selection logic.
 * Follows Dependency Inversion Principle - configuration and router injected.
 *
 * Implements IBiomeManager for interface compatibility.
 */
public class BiomeManager implements IBiomeManager {

    private final NoiseRouter noiseRouter;
    private final BiomeParameterTable parameterTable;
    private final int seaLevel;
    private final boolean useVoronoi;
    private final BiomeVoronoiGrid voronoiGrid;

    /**
     * Creates a new biome manager with multi-noise parameter selection.
     *
     * @param seed World seed for deterministic generation
     * @param config Terrain generation configuration
     */
    public BiomeManager(long seed, TerrainGenerationConfig config) {
        this.noiseRouter = new NoiseRouter(seed, config);
        this.parameterTable = new BiomeParameterTable();
        this.seaLevel = WorldConfiguration.SEA_LEVEL;
        this.useVoronoi = config.enableVoronoiBiomes;

        // Initialize voronoi grid if enabled
        if (useVoronoi) {
            // Create distortion field if enabled (hides cellular pattern)
            VoronoiDistortionField distortionField = null;
            if (config.enableVoronoiDistortion) {
                distortionField = new VoronoiDistortionField(
                        seed,
                        config.voronoiDistortionStrength,
                        config.voronoiDistortionScale
                );
            }

            this.voronoiGrid = new BiomeVoronoiGrid(
                    noiseRouter,
                    parameterTable,
                    config.biomeVoronoiCellSize,
                    seaLevel,
                    distortionField  // Pass distortion field for organic boundaries
            );
        } else {
            this.voronoiGrid = null;
        }
    }

    /**
     * Determines the biome type at a world position.
     *
     * Samples all 6 parameters from noise and selects matching biome from parameter table.
     *
     * @param x World X coordinate
     * @param z World Z coordinate
     * @return The biome type at the given position
     */
    @Override
    public BiomeType getBiome(int x, int z) {
        return getBiomeAtHeight(x, z, seaLevel);
    }

    /**
     * Determines the biome type at a world position.
     *
     * Supports two modes:
     * - Voronoi mode: Returns biome from nearest voronoi grid point (discrete regions)
     * - Continuous mode: Samples parameters and selects biome at this exact position
     *
     * Multi-Noise System (continuous mode):
     * 1. Sample all 6 parameters via NoiseRouter
     * 2. All parameters sampled from noise (temperature is purely 2D noise-based)
     * 3. Lookup biome in parameter table using 6D point
     * 4. If multiple matches, choose closest by weighted distance
     * 5. If no matches, fall back to nearest biome (should be rare)
     *
     * @param x World X coordinate
     * @param z World Z coordinate
     * @param height Terrain height (unused, kept for API compatibility)
     * @return The biome type at the given position
     */
    @Override
    public BiomeType getBiomeAtHeight(int x, int z, int height) {
        // Use voronoi grid if enabled (discrete biome regions)
        if (useVoronoi) {
            return voronoiGrid.getBiome(x, z);
        }

        // Otherwise use continuous multi-noise selection
        // Sample all 6 parameters from noise
        MultiNoiseParameters params = noiseRouter.sampleParameters(x, z, height);

        // Select biome from parameter table
        return parameterTable.selectBiome(params);
    }

    /**
     * Gets multi-noise parameters at a position (for external use/debugging).
     *
     * @param x World X coordinate
     * @param z World Z coordinate
     * @param height Terrain height
     * @return All 6 parameters at this position
     */
    public MultiNoiseParameters getParameters(int x, int z, int height) {
        return noiseRouter.sampleParameters(x, z, height);
    }

    /**
     * Gets the moisture/humidity value at a position.
     *
     * @param x World X coordinate
     * @param z World Z coordinate
     * @return Humidity value in range [0.0, 1.0]
     */
    @Override
    public float getMoisture(int x, int z) {
        return noiseRouter.getHumidity(x, z);
    }

    /**
     * Gets the temperature value at a position.
     *
     * Temperature is purely noise-based without altitude adjustment.
     *
     * @param x World X coordinate
     * @param z World Z coordinate
     * @return Temperature value in range [0.0, 1.0] based on noise
     */
    @Override
    public float getTemperature(int x, int z) {
        return noiseRouter.getTemperature(x, z);
    }

    /**
     * Gets the temperature value at a position.
     *
     * Temperature is purely noise-based without altitude adjustment.
     * This method exists for API compatibility and returns the same value
     * as getTemperature(x, z) regardless of height.
     *
     * @param x World X coordinate
     * @param z World Z coordinate
     * @param height Terrain height (unused, kept for API compatibility)
     * @return Temperature value in range [0.0, 1.0] based on noise
     */
    @Override
    public float getTemperatureAtHeight(int x, int z, int height) {
        MultiNoiseParameters params = noiseRouter.sampleParameters(x, z, height);
        return params.temperature;
    }

    /**
     * Gets the continentalness value at a position.
     *
     * @param x World X coordinate
     * @param z World Z coordinate
     * @return Continentalness in range [-1.0, 1.0]
     */
    public float getContinentalness(int x, int z) {
        return noiseRouter.getContinentalness(x, z);
    }

    /**
     * Gets the erosion value at a position.
     *
     * @param x World X coordinate
     * @param z World Z coordinate
     * @return Erosion in range [-1.0, 1.0]
     */
    public float getErosion(int x, int z) {
        return noiseRouter.getErosion(x, z);
    }

    /**
     * Gets the noise router (for external systems that need direct parameter access).
     *
     * @return The noise router
     */
    public NoiseRouter getNoiseRouter() {
        return noiseRouter;
    }

    /**
     * Gets the biome parameter table (for debugging/visualization).
     *
     * @return The biome parameter table
     */
    public BiomeParameterTable getParameterTable() {
        return parameterTable;
    }
}
