package com.stonebreak.world.generation.legacy;

import com.stonebreak.world.generation.TerrainGenerator;
import com.stonebreak.world.generation.TerrainGeneratorType;
import com.stonebreak.world.generation.config.TerrainGenerationConfig;
import com.stonebreak.world.generation.debug.HeightCalculationDebugInfo;
import com.stonebreak.world.generation.heightmap.HeightMapGenerator;
import com.stonebreak.world.generation.noise.MultiNoiseParameters;

/**
 * Legacy terrain generator that preserves the original multi-noise terrain system.
 * <p>
 * This generator wraps the existing {@link HeightMapGenerator} to maintain backwards
 * compatibility with existing worlds. It uses the original terrain hint system
 * (MESA, SHARP_PEAKS, GENTLE_HILLS, FLAT_PLAINS, NORMAL) for terrain classification
 * and generation.
 * <p>
 * The generation flow is:
 * <ol>
 *   <li>Get base height from continentalness spline</li>
 *   <li>Classify terrain hint based on multi-noise parameters</li>
 *   <li>Apply hint-specific terrain generation logic</li>
 *   <li>Apply erosion factor (flat vs mountainous)</li>
 *   <li>Apply peaks & valleys amplification</li>
 *   <li>Apply weirdness terracing</li>
 * </ol>
 * <p>
 * This implementation uses composition (wrapping) rather than inheritance to
 * preserve the original HeightMapGenerator intact, allowing it to be used
 * independently if needed.
 */
public class LegacyTerrainGenerator implements TerrainGenerator {

    private final HeightMapGenerator heightMapGenerator;
    private final long seed;

    /**
     * Create a new legacy terrain generator.
     *
     * @param seed World seed for deterministic generation
     * @param config Terrain generation configuration
     */
    public LegacyTerrainGenerator(long seed, TerrainGenerationConfig config) {
        this.seed = seed;
        this.heightMapGenerator = new HeightMapGenerator(seed, config);
    }

    @Override
    public int generateHeight(int x, int z, MultiNoiseParameters params) {
        // Delegate to the existing HeightMapGenerator
        // This ensures 100% backwards compatibility with existing terrain generation
        return heightMapGenerator.generateHeight(x, z, params);
    }

    @Override
    public String getName() {
        return "Legacy Multi-Noise Generator";
    }

    @Override
    public String getDescription() {
        return "Original terrain system with hint-based generation (MESA, SHARP_PEAKS, GENTLE_HILLS, etc.)";
    }

    @Override
    public long getSeed() {
        return seed;
    }

    /**
     * Get the underlying HeightMapGenerator instance.
     * <p>
     * This method is provided for backwards compatibility with code that
     * needs direct access to HeightMapGenerator methods (e.g., getContinentalness).
     *
     * @return The wrapped HeightMapGenerator instance
     */
    public HeightMapGenerator getHeightMapGenerator() {
        return heightMapGenerator;
    }

    @Override
    public TerrainGeneratorType getType() {
        return TerrainGeneratorType.LEGACY;
    }

    @Override
    public HeightCalculationDebugInfo getHeightCalculationDebugInfo(int x, int z, MultiNoiseParameters params) {
        // Delegate to HeightMapGenerator to collect debug information
        return heightMapGenerator.getHeightCalculationDebugInfo(x, z, params);
    }
}
