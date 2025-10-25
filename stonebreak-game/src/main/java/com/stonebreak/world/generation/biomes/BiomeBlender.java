package com.stonebreak.world.generation.biomes;

import java.util.HashMap;
import java.util.Map;

/**
 * Blends biomes using multi-sample weighted interpolation to create smooth transitions.
 *
 * Phase 3 of the biome enhancement system: eliminates harsh biome borders by sampling
 * multiple surrounding positions and blending their biome influences based on distance.
 *
 * Algorithm:
 * 1. Sample biomes in a grid around the target position (default: 5x5 grid)
 * 2. Calculate weight for each sample based on distance (inverse square falloff)
 * 3. Accumulate weights per biome type
 * 4. Normalize weights to sum to 1.0
 * 5. Return BiomeBlendResult with weighted biome influences
 *
 * Configuration:
 * - SAMPLE_RADIUS: Grid size for sampling (2 = 5x5 grid, 1 = 3x3 grid)
 * - SAMPLE_SPACING: Distance between samples in blocks (default: 8)
 * - BLEND_DISTANCE: Maximum distance for blending influence in grid units
 *
 * Performance:
 * - Default 5x5 grid = 25 biome lookups per position
 * - Can be reduced to 3x3 (9 lookups) for better performance
 * - Recommended: cache results per chunk column
 */
public class BiomeBlender {

    /**
     * Radius of the sampling grid.
     * - 2 creates a 5x5 grid (25 samples)
     * - 1 creates a 3x3 grid (9 samples)
     */
    private static final int SAMPLE_RADIUS = 2;

    /**
     * Spacing between samples in world blocks.
     * Larger values create broader transitions but may miss small biomes.
     * Default: 8 blocks (good balance of smoothness and detail)
     */
    private static final int SAMPLE_SPACING = 8;

    /**
     * Maximum distance for blending influence in grid units.
     * Samples farther than this have zero weight.
     * Default: 32.0 grid units
     */
    private static final float BLEND_DISTANCE = 32.0f;

    /**
     * Minimum weight threshold for including a biome in the result.
     * Biomes with weight below this are excluded to reduce noise.
     * Default: 0.01 (1% influence)
     */
    private static final float MIN_WEIGHT_THRESHOLD = 0.01f;

    /**
     * Calculates blended biome influences at a specific position.
     *
     * Samples biomes in a grid around the position and computes weighted
     * influences based on distance. Returns a BiomeBlendResult containing
     * all biomes with significant influence.
     *
     * @param biomeManager The biome manager for querying biomes
     * @param x            World X coordinate
     * @param z            World Z coordinate
     * @return BiomeBlendResult with weighted biome influences
     */
    public BiomeBlendResult getBlendedBiome(BiomeManager biomeManager, int x, int z) {
        Map<BiomeType, Float> biomeWeights = new HashMap<>();

        // Sample surrounding positions in a grid
        for (int dx = -SAMPLE_RADIUS; dx <= SAMPLE_RADIUS; dx++) {
            for (int dz = -SAMPLE_RADIUS; dz <= SAMPLE_RADIUS; dz++) {
                // Calculate sample position
                int sampleX = x + dx * SAMPLE_SPACING;
                int sampleZ = z + dz * SAMPLE_SPACING;

                // Get biome at sample position
                BiomeType biome = biomeManager.getBiome(sampleX, sampleZ);

                // Calculate weight based on distance
                float distance = (float) Math.sqrt(dx * dx + dz * dz);
                float weight = calculateWeight(distance);

                // Accumulate weight for this biome
                if (weight > 0) {
                    biomeWeights.merge(biome, weight, Float::sum);
                }
            }
        }

        // Normalize weights to sum to 1.0
        normalizeWeights(biomeWeights);

        // Remove biomes with negligible influence
        biomeWeights.entrySet().removeIf(entry -> entry.getValue() < MIN_WEIGHT_THRESHOLD);

        return new BiomeBlendResult(biomeWeights);
    }

    /**
     * Calculates the weight for a sample based on distance.
     *
     * Uses inverse square falloff: weight decreases with distance squared.
     * This creates smooth, natural-looking transitions.
     *
     * Formula: weight = max(0, 1 - (distance / BLEND_DISTANCE))
     *
     * @param distance Distance from target position in grid units
     * @return Weight in range [0.0, 1.0]
     */
    private float calculateWeight(float distance) {
        // Linear falloff (can be changed to other falloff functions)
        float weight = Math.max(0, 1.0f - (distance / BLEND_DISTANCE));

        // Square the weight for smoother falloff (optional, can be removed for linear)
        // Squared falloff creates more distinct biome centers with gentler edges
        weight = weight * weight;

        return weight;
    }

    /**
     * Normalizes weights to sum to 1.0 for proper blending.
     *
     * Divides each weight by the total sum, ensuring all weights
     * are in range [0.0, 1.0] and sum to exactly 1.0.
     *
     * @param biomeWeights Map of biome weights to normalize (modified in-place)
     */
    private void normalizeWeights(Map<BiomeType, Float> biomeWeights) {
        // Calculate total weight
        float totalWeight = biomeWeights.values().stream()
                .reduce(0.0f, Float::sum);

        // Avoid division by zero
        if (totalWeight <= 0) {
            // If no valid weights, assign equal weight to all biomes
            float equalWeight = 1.0f / Math.max(1, biomeWeights.size());
            biomeWeights.replaceAll((biome, weight) -> equalWeight);
            return;
        }

        // Normalize each weight
        biomeWeights.replaceAll((biome, weight) -> weight / totalWeight);
    }

    /**
     * Gets the sample radius used for blending.
     * Useful for debugging or visualization.
     *
     * @return Sample radius (grid cells from center)
     */
    public static int getSampleRadius() {
        return SAMPLE_RADIUS;
    }

    /**
     * Gets the sample spacing in world blocks.
     * Useful for debugging or visualization.
     *
     * @return Sample spacing in blocks
     */
    public static int getSampleSpacing() {
        return SAMPLE_SPACING;
    }

    /**
     * Gets the blend distance.
     * Useful for debugging or visualization.
     *
     * @return Blend distance in grid units
     */
    public static float getBlendDistance() {
        return BLEND_DISTANCE;
    }
}
