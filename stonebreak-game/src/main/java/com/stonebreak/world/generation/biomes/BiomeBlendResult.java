package com.stonebreak.world.generation.biomes;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Data class representing the result of biome blending calculations.
 *
 * Contains weighted biome influences at a specific position, used for smooth
 * terrain transitions between biomes. Instead of a single dominant biome,
 * this represents a blend of multiple nearby biomes with their respective weights.
 *
 * Used in Phase 3 of the biome enhancement system for:
 * - Smooth height transitions between biomes
 * - Gradual block type changes at biome boundaries
 * - Natural-looking terrain without harsh borders
 */
public class BiomeBlendResult {

    private final Map<BiomeType, Float> biomeWeights;
    private final BiomeType dominantBiome;

    /**
     * Creates a new biome blend result with the given biome weights.
     *
     * @param biomeWeights Map of biome types to their normalized weights [0.0, 1.0]
     *                     Weights should sum to 1.0 for proper blending
     */
    public BiomeBlendResult(Map<BiomeType, Float> biomeWeights) {
        this.biomeWeights = new HashMap<>(biomeWeights);
        this.dominantBiome = findDominantBiome();
    }

    /**
     * Finds the biome with the highest weight.
     *
     * @return The dominant biome type (highest weight)
     */
    private BiomeType findDominantBiome() {
        return biomeWeights.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(BiomeType.PLAINS); // Default fallback
    }

    /**
     * Gets the dominant biome (highest weight).
     *
     * @return The biome type with the highest influence
     */
    public BiomeType getDominantBiome() {
        return dominantBiome;
    }

    /**
     * Gets the weight for a specific biome.
     *
     * @param biome The biome type to query
     * @return The weight of this biome [0.0, 1.0], or 0.0 if not present
     */
    public float getWeight(BiomeType biome) {
        return biomeWeights.getOrDefault(biome, 0.0f);
    }

    /**
     * Gets all biome weights.
     *
     * @return Unmodifiable map of biome types to their weights
     */
    public Map<BiomeType, Float> getWeights() {
        return Map.copyOf(biomeWeights);
    }

    /**
     * Gets the top N biomes sorted by weight (descending).
     *
     * Useful for blending between the most influential biomes while ignoring
     * biomes with negligible weight.
     *
     * @param n Number of top biomes to return
     * @return List of top N biomes sorted by weight (highest first)
     */
    public List<BiomeType> getTopNBiomes(int n) {
        return biomeWeights.entrySet().stream()
                .sorted(Map.Entry.<BiomeType, Float>comparingByValue().reversed())
                .limit(n)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    /**
     * Checks if a single biome is strongly dominant (weight above threshold).
     *
     * When one biome has overwhelming influence, blending can be skipped for
     * performance optimization.
     *
     * @param threshold Weight threshold [0.0, 1.0] (e.g., 0.8 for 80% dominance)
     * @return true if the dominant biome's weight exceeds the threshold
     */
    public boolean isStronglyDominant(float threshold) {
        return getWeight(dominantBiome) >= threshold;
    }

    /**
     * Gets the number of biomes with non-zero weight.
     *
     * @return Count of biomes influencing this position
     */
    public int getBiomeCount() {
        return biomeWeights.size();
    }

    @Override
    public String toString() {
        return "BiomeBlendResult{" +
                "dominantBiome=" + dominantBiome +
                ", biomeWeights=" + biomeWeights +
                '}';
    }
}
