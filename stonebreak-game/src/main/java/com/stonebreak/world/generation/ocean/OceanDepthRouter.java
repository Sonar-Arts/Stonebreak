package com.stonebreak.world.generation.ocean;

import com.stonebreak.world.generation.NoiseGenerator;
import com.stonebreak.world.generation.biomes.BiomeManager;
import com.stonebreak.world.generation.biomes.BiomeType;
import com.stonebreak.world.generation.config.NoiseConfig;

/**
 * Ocean Depth Router - Calculates ocean depth offsets with proximity-based fading
 *
 * Creates varied ocean floors with trenches, ridges, and natural beach transitions.
 * Ocean depth is applied as a negative offset that pulls terrain down below sea level.
 *
 * Architecture:
 * - Three-layer noise system for realistic ocean floor variation
 * - Proximity-based fade prevents abrupt cliffs at ocean edges
 * - Works with both SplineTerrainGenerator and HybridSdfTerrainGenerator
 *
 * Depth Targets:
 * - OCEAN / FROZEN_OCEAN: 40-50 blocks below sea level (Y=14-24)
 * - DEEP_OCEAN: 50-60 blocks below sea level (Y=4-14)
 * - Beach areas (proximity fade): Gradual transition to normal terrain
 *
 * Design Principles:
 * - Single Responsibility: Only calculates ocean depth offsets
 * - Dependency Injection: BiomeManager injected for proximity detection
 * - KISS: Simple grid-based proximity detection, additive blending
 * - YAGNI: Hardcoded constants (no config until proven needed)
 *
 * Performance:
 * - O(25) biome samples for proximity (5x5 grid at 32-block spacing)
 * - 3 noise samples for depth variation
 * - Total: ~28 operations per column (negligible overhead)
 *
 * @see BiomeManager
 * @see com.stonebreak.world.generation.TerrainGenerationSystem
 */
public class OceanDepthRouter {

    // Three-layer noise system for ocean floor variation
    private final NoiseGenerator oceanTrenchNoise;    // Large-scale deep trenches
    private final NoiseGenerator oceanRidgeNoise;     // Medium-scale underwater ridges
    private final NoiseGenerator oceanFloorVariation; // Small-scale bumps and dips

    // Reference to BiomeManager for proximity detection
    private final BiomeManager biomeManager;

    // Depth targets (blocks below sea level Y=64)
    private static final int OCEAN_MIN_DEPTH = 40;        // Shallow ocean floor
    private static final int OCEAN_MAX_DEPTH = 50;        // Deep ocean floor
    private static final int DEEP_OCEAN_MIN_DEPTH = 50;   // Deep ocean minimum
    private static final int DEEP_OCEAN_MAX_DEPTH = 60;   // Deep ocean maximum

    // Proximity fade parameters
    private static final int PROXIMITY_GRID_SIZE = 5;     // 5x5 grid (25 samples)
    private static final int PROXIMITY_GRID_SPACING = 32; // 32 blocks between samples
    private static final int PROXIMITY_FULL_DEPTH = 64;   // Full depth within 64 blocks of ocean center
    private static final int PROXIMITY_FADE_START = 64;   // Start fading at 64 blocks
    private static final int PROXIMITY_FADE_END = 128;    // Complete fade at 128 blocks

    /**
     * Creates a new ocean depth router.
     *
     * Initializes three noise generators for ocean floor variation:
     * - Trench noise: 800-block scale for large trenches and basins
     * - Ridge noise: 300-block scale for underwater mountain ridges
     * - Floor variation: 80-block scale for local bumps and dips
     *
     * @param seed World seed for deterministic generation
     * @param biomeManager BiomeManager for proximity detection
     */
    public OceanDepthRouter(long seed, BiomeManager biomeManager) {
        this.biomeManager = biomeManager;

        // Trench noise: 800-block scale, 3 octaves for smooth deep trenches
        // Creates large-scale depth variation (ocean basins vs shallows)
        NoiseConfig trenchConfig = new NoiseConfig(3, 0.5, 2.0);
        this.oceanTrenchNoise = new NoiseGenerator(seed + 600, trenchConfig);

        // Ridge noise: 300-block scale, 3 octaves for underwater ridges
        // Creates medium-scale underwater mountain ranges
        NoiseConfig ridgeConfig = new NoiseConfig(3, 0.5, 2.0);
        this.oceanRidgeNoise = new NoiseGenerator(seed + 601, ridgeConfig);

        // Floor variation: 80-block scale, 2 octaves for local bumps
        // Creates small-scale detail visible during underwater exploration
        NoiseConfig floorConfig = new NoiseConfig(2, 0.5, 2.0);
        this.oceanFloorVariation = new NoiseGenerator(seed + 602, floorConfig);
    }

    /**
     * Calculate ocean depth offset for a given position.
     *
     * Returns a negative offset for ocean biomes (pulls terrain down below sea level).
     * Returns 0 for non-ocean biomes (no depth modification).
     *
     * Process:
     * 1. Early exit if not ocean biome
     * 2. Calculate proximity to land (distance-based fade)
     * 3. Sample three noise layers (trench, ridge, floor)
     * 4. Composite depth calculation with noise variation
     * 5. Apply proximity fade for smooth beach transitions
     * 6. Return negative offset (pulls terrain down)
     *
     * @param biome Biome type at this position
     * @param worldX World X coordinate
     * @param worldZ World Z coordinate
     * @return Negative offset in blocks (e.g., -45 pulls terrain down 45 blocks)
     */
    public float getOceanDepthOffset(BiomeType biome, int worldX, int worldZ) {
        // Early exit: non-ocean biomes get no depth offset
        if (!isOceanBiome(biome)) {
            return 0.0f;
        }

        // Calculate proximity factor (1.0 at ocean center, 0.0 at beach)
        float proximityFactor = calculateProximityFactor(worldX, worldZ);

        // Early exit: if at ocean edge (proximity too low), no depth effect
        if (proximityFactor < 0.01f) {
            return 0.0f;
        }

        // Determine depth range based on biome type
        int minDepth, maxDepth;
        if (biome == BiomeType.DEEP_OCEAN) {
            minDepth = DEEP_OCEAN_MIN_DEPTH;  // 50 blocks
            maxDepth = DEEP_OCEAN_MAX_DEPTH;  // 60 blocks
        } else {
            // OCEAN and FROZEN_OCEAN use same depth range
            minDepth = OCEAN_MIN_DEPTH;  // 40 blocks
            maxDepth = OCEAN_MAX_DEPTH;  // 50 blocks
        }

        // Sample three noise layers for ocean floor variation

        // Layer 1: Trench noise (large-scale depth variation)
        // Creates ocean basins (deep areas) vs shallows
        float trenchNoise = oceanTrenchNoise.noise(worldX / 800.0f, worldZ / 800.0f);
        float trenchFactor = trenchNoise * 0.5f + 0.5f;  // Map [-1, 1] to [0, 1]

        // Layer 2: Ridge noise (medium-scale underwater ridges)
        // Creates underwater mountain ranges (high at noise=0, low at extremes)
        float ridgeNoise = oceanRidgeNoise.noise(worldX / 300.0f, worldZ / 300.0f);
        float ridgeFactor = 1.0f - Math.abs(ridgeNoise);  // Ridge at 0, valley at ±1

        // Layer 3: Floor variation (small-scale bumps and dips)
        // Adds fine detail visible during underwater exploration
        float floorNoise = oceanFloorVariation.noise(worldX / 80.0f, worldZ / 80.0f);

        // Composite depth calculation:
        // 1. Base depth from trench noise (interpolate between min/max depth)
        float baseDepth = minDepth + (trenchFactor * (maxDepth - minDepth));

        // 2. Ridge modification (±8 blocks)
        // Ridges push up (shallower), valleys push down (deeper)
        float ridgeModification = (ridgeFactor - 0.5f) * 16.0f;

        // 3. Floor variation (±3 blocks)
        // Small bumps and dips for local detail
        float floorModification = floorNoise * 3.0f;

        // 4. Combine all layers
        float totalDepth = baseDepth + ridgeModification + floorModification;

        // 5. Apply proximity fade (smooth transition to beaches)
        float effectiveDepth = totalDepth * proximityFactor;

        // 6. Return negative offset (pulls terrain DOWN below sea level)
        return -effectiveDepth;
    }

    /**
     * Check if biome is an ocean biome.
     *
     * Ocean biomes: OCEAN, DEEP_OCEAN, FROZEN_OCEAN
     *
     * @param biome Biome type to check
     * @return True if ocean biome, false otherwise
     */
    private boolean isOceanBiome(BiomeType biome) {
        return biome == BiomeType.OCEAN ||
               biome == BiomeType.DEEP_OCEAN ||
               biome == BiomeType.FROZEN_OCEAN;
    }

    /**
     * Calculate proximity factor based on distance to nearest non-ocean biome.
     *
     * Uses 5x5 grid sampling at 32-block spacing (covers 128-block radius).
     * Finds nearest non-ocean biome and calculates fade factor.
     *
     * Fade zones:
     * - Distance 0-64 blocks: factor = 1.0 (full ocean depth)
     * - Distance 64-128 blocks: factor = linear fade 1.0 → 0.0
     * - Distance >128 blocks: factor = 0.0 (no ocean depth, beach area)
     *
     * Performance: 25 biome samples (5x5 grid), each cached at sea level
     *
     * @param centerX World X coordinate of position being evaluated
     * @param centerZ World Z coordinate of position being evaluated
     * @return Proximity factor [0.0, 1.0] where 1.0 = full depth, 0.0 = no depth
     */
    private float calculateProximityFactor(int centerX, int centerZ) {
        float minDistanceToLand = Float.MAX_VALUE;

        // Sample biomes in 5x5 grid at 32-block spacing
        // Grid centered on position, covers 128 blocks in each direction
        int halfGrid = PROXIMITY_GRID_SIZE / 2;  // 2 blocks on each side of center

        for (int gridX = -halfGrid; gridX <= halfGrid; gridX++) {
            for (int gridZ = -halfGrid; gridZ <= halfGrid; gridZ++) {
                // Calculate sample position (32-block spacing)
                int sampleX = centerX + (gridX * PROXIMITY_GRID_SPACING);
                int sampleZ = centerZ + (gridZ * PROXIMITY_GRID_SPACING);

                // Sample biome at this grid position
                BiomeType sampledBiome = biomeManager.getBiome(sampleX, sampleZ);

                // If non-ocean biome found, calculate distance
                if (!isOceanBiome(sampledBiome)) {
                    // Euclidean distance from center to this sample point
                    float dx = sampleX - centerX;
                    float dz = sampleZ - centerZ;
                    float distance = (float) Math.sqrt(dx * dx + dz * dz);

                    // Track minimum distance to any land
                    minDistanceToLand = Math.min(minDistanceToLand, distance);
                }
            }
        }

        // If no land found in entire grid, we're deep in ocean (full depth)
        if (minDistanceToLand == Float.MAX_VALUE) {
            return 1.0f;
        }

        // Apply linear fade based on distance to land
        if (minDistanceToLand <= PROXIMITY_FULL_DEPTH) {
            // Close to ocean center (0-64 blocks from land): full depth
            return 1.0f;
        } else if (minDistanceToLand >= PROXIMITY_FADE_END) {
            // Far from ocean center (>128 blocks from land): no depth (beach)
            return 0.0f;
        } else {
            // Transition zone (64-128 blocks from land): linear fade
            float fadeRange = PROXIMITY_FADE_END - PROXIMITY_FADE_START;
            float fadeProgress = (minDistanceToLand - PROXIMITY_FADE_START) / fadeRange;
            return 1.0f - fadeProgress;  // Fade from 1.0 to 0.0
        }
    }
}
