package com.stonebreak.world.generation.biomes;

import com.stonebreak.world.generation.config.TerrainGenerationConfig;

import java.util.HashMap;
import java.util.Map;

/**
 * Blends biomes using multi-sample weighted interpolation to create smooth transitions.
 *
 * Phase 1 Enhancement: Supports altitude-based temperature for realistic mountain snow.
 * Phase 3 Enhancement: Eliminates harsh biome borders by sampling multiple surrounding positions
 *                      and blending their biome influences based on distance.
 *
 * Algorithm:
 * 1. Sample biomes in a grid around the target position (configurable grid size)
 * 2. Calculate weight for each sample based on distance (inverse square falloff)
 * 3. Accumulate weights per biome type
 * 4. Normalize weights to sum to 1.0
 * 5. Return BiomeBlendResult with weighted biome influences
 *
 * Performance:
 * - Default 5x5 grid = 25 biome lookups per position
 * - Can be reduced to 3x3 (9 lookups) for better performance
 * - Recommended: cache results per chunk column
 *
 * Follows Dependency Inversion Principle - configuration injected via constructor.
 */
public class BiomeBlender {

    private final int sampleRadius;
    private final int sampleSpacing;
    private final float blendDistance;
    private final float minWeightThreshold;

    /**
     * Creates a biome blender with the specified configuration.
     *
     * @param config Terrain generation configuration
     */
    public BiomeBlender(TerrainGenerationConfig config) {
        this.sampleRadius = config.biomeBlendSampleRadius;
        this.sampleSpacing = config.biomeBlendSampleSpacing;
        this.blendDistance = config.biomeBlendDistance;
        this.minWeightThreshold = config.biomeBlendMinWeightThreshold;
    }

    /**
     * Calculates blended biome influences at a specific position and height.
     *
     * Phase 1: Uses height for altitude-based temperature calculation.
     * Approximates all samples at the same height for performance
     * (terrain doesn't vary dramatically within the 8-block sample spacing).
     *
     * Samples biomes in a grid around the position and computes weighted
     * influences based on distance. Returns a BiomeBlendResult containing
     * all biomes with significant influence.
     *
     * @param biomeManager The biome manager for querying biomes
     * @param x            World X coordinate
     * @param z            World Z coordinate
     * @param height       Terrain height at this position (affects temperature)
     * @return BiomeBlendResult with weighted biome influences
     */
    public BiomeBlendResult getBlendedBiomeAtHeight(BiomeManager biomeManager, int x, int z, int height) {
        Map<BiomeType, Float> biomeWeights = new HashMap<>();

        // Sample surrounding positions in a grid
        for (int dx = -sampleRadius; dx <= sampleRadius; dx++) {
            for (int dz = -sampleRadius; dz <= sampleRadius; dz++) {
                // Calculate sample position
                int sampleX = x + dx * sampleSpacing;
                int sampleZ = z + dz * sampleSpacing;

                // Get biome at sample position with altitude-adjusted temperature
                // Use center point's height for all samples (reasonable approximation)
                BiomeType biome = biomeManager.getBiomeAtHeight(sampleX, sampleZ, height);

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
        biomeWeights.entrySet().removeIf(entry -> entry.getValue() < minWeightThreshold);

        return new BiomeBlendResult(biomeWeights);
    }

    /**
     * Calculates the weight for a sample based on distance.
     *
     * Uses inverse square falloff: weight decreases with distance squared.
     * This creates smooth, natural-looking transitions.
     *
     * Formula: weight = max(0, 1 - (distance / blendDistance))
     *
     * @param distance Distance from target position in grid units
     * @return Weight in range [0.0, 1.0]
     */
    private float calculateWeight(float distance) {
        // Linear falloff (can be changed to other falloff functions)
        float weight = Math.max(0, 1.0f - (distance / blendDistance));

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
}
