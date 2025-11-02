package com.stonebreak.world.generation.caves;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.generation.NoiseGenerator;
import com.stonebreak.world.generation.config.NoiseConfig;
import com.stonebreak.world.operations.WorldConfiguration;

/**
 * Generates underground aquifer systems for water and magma pools.
 *
 * Inspired by Minecraft 1.18+ aquifer system, this generator creates
 * regional variations in underground water levels, resulting in:
 * - Underground lakes at various depths
 * - Waterfalls where aquifers intersect vertical caves
 * - Magma pools in deepest caverns near bedrock (Y < 15)
 * - Dry caves in some regions
 *
 * Key Innovation: Regional Water Tables
 * Unlike simple "fill below sea level" approaches, this uses low-frequency
 * noise to create regional variation in water table heights. Each region
 * can have a different aquifer level, creating variety in cave exploration.
 *
 * Architecture:
 * The generator samples 2D noise at very large scales (100+ blocks) to
 * determine the local water table height. This creates coherent regions
 * with similar water levels rather than random per-block variation.
 *
 * Water vs Magma:
 * - Above Y=15: Water in flooded caves
 * - Below Y=15: Magma in deepest caverns near bedrock (simulating volcanic/geothermal activity)
 * - Surface level (Y=63): Normal ocean/lake water (independent of aquifers)
 *
 * Thread Safety:
 * - Immutable after construction
 * - Noise generator is thread-safe
 * - Safe for concurrent sampling from multiple chunk generation threads
 *
 * Follows SOLID Principles:
 * - Single Responsibility: Only determines aquifer water/lava placement
 * - Dependency Inversion: Configuration injected via constructor
 */
public class AquiferGenerator {

    private final NoiseGenerator aquiferNoise;
    private final int seaLevel;

    // Aquifer parameters
    private static final float AQUIFER_SCALE = 120.0f;  // Large scale for regional coherence
    private static final int BASE_AQUIFER_LEVEL = 20;  // Base water table height
    private static final int AQUIFER_VARIATION = 30;  // How much aquifer height varies (+/-)

    // Magma threshold (how deep before magma instead of water)
    private static final int LAVA_THRESHOLD = 15;  // Y < 15 gets magma (deep caves near bedrock)

    /**
     * Creates a new aquifer generator with the given seed.
     *
     * Initializes low-frequency noise generator for regional water table
     * variation. Uses very large scale to create coherent aquifer regions.
     *
     * @param seed World seed for deterministic generation
     */
    public AquiferGenerator(long seed) {
        this.seaLevel = WorldConfiguration.SEA_LEVEL;

        // Initialize aquifer noise with very low frequency for regional variation
        NoiseConfig aquiferConfig = new NoiseConfig(
            3,      // octaves - fewer octaves for smoother regional variation
            0.5,    // persistence
            2.0     // lacunarity
        );
        this.aquiferNoise = new NoiseGenerator(seed + 500, aquiferConfig);
    }

    /**
     * Gets the local aquifer water level at a position.
     *
     * Samples low-frequency noise to determine the regional water table height.
     * Different regions will have different water levels, creating variety.
     *
     * The returned level represents the Y coordinate below which caves should
     * be filled with water (or lava if deep enough).
     *
     * @param worldX World X coordinate
     * @param worldZ World Z coordinate
     * @return Local aquifer level (Y coordinate of water table)
     */
    public int getAquiferLevel(int worldX, int worldZ) {
        // Sample very low-frequency noise for regional coherence
        float noise = aquiferNoise.noise(
            worldX / AQUIFER_SCALE,
            worldZ / AQUIFER_SCALE
        );

        // Map noise from [-1, 1] to aquifer height variation
        // noise = -1 → lowest aquifer level
        // noise = 1 → highest aquifer level
        int variation = (int)(noise * AQUIFER_VARIATION);

        return BASE_AQUIFER_LEVEL + variation;
    }

    /**
     * Determines if a cave block should be filled with fluid (water or magma).
     *
     * Checks if the position is below the local aquifer level and in a cave.
     * If so, returns the appropriate fluid type based on depth.
     *
     * Integration:
     * Call this AFTER determining that a block is air (cave), but BEFORE
     * placing the final block. This fills caves with water/magma as needed.
     *
     * @param worldX World X coordinate
     * @param y World Y coordinate
     * @param worldZ World Z coordinate
     * @param isCave Whether this block is in a cave (already determined to be air)
     * @return BlockType.WATER, BlockType.MAGMA, or BlockType.AIR
     */
    public BlockType applyAquifer(int worldX, int y, int worldZ, boolean isCave) {
        // Only apply aquifers to cave blocks
        if (!isCave) {
            return BlockType.AIR;  // Not a cave, don't modify
        }

        // Get local aquifer level for this region
        int aquiferLevel = getAquiferLevel(worldX, worldZ);

        // Check if below aquifer level
        if (y >= aquiferLevel) {
            return BlockType.AIR;  // Above water table, stay air
        }

        // Below aquifer level - fill with fluid
        // Deep caves (Y < 15) get magma, shallow caves get water
        if (y < LAVA_THRESHOLD) {
            return BlockType.MAGMA;
        } else {
            return BlockType.WATER;
        }
    }

    /**
     * Checks if a position is below the local aquifer level.
     *
     * Simple helper to quickly determine if a position should be flooded
     * without determining the specific fluid type.
     *
     * @param worldX World X coordinate
     * @param y World Y coordinate
     * @param worldZ World Z coordinate
     * @return true if below local water table
     */
    public boolean isBelowAquifer(int worldX, int y, int worldZ) {
        int aquiferLevel = getAquiferLevel(worldX, worldZ);
        return y < aquiferLevel;
    }

    /**
     * Gets the fluid type that should appear at a depth.
     *
     * Helper method to determine water vs magma based only on Y coordinate,
     * ignoring the regional aquifer level.
     *
     * @param y World Y coordinate
     * @return BlockType.WATER for shallow, BlockType.MAGMA for deep
     */
    public BlockType getFluidTypeForDepth(int y) {
        return (y < LAVA_THRESHOLD) ? BlockType.MAGMA : BlockType.WATER;
    }

    /**
     * Gets the base aquifer level before regional variation.
     *
     * @return Base aquifer level in blocks
     */
    public int getBaseAquiferLevel() {
        return BASE_AQUIFER_LEVEL;
    }

    /**
     * Gets the maximum aquifer variation range.
     *
     * @return Maximum variation above/below base level
     */
    public int getAquiferVariation() {
        return AQUIFER_VARIATION;
    }
}
