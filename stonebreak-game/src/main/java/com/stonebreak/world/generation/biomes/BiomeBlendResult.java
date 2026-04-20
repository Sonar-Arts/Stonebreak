package com.stonebreak.world.generation.biomes;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * Weighted blend of biome influences at a single position. Backed by a small
 * float[] indexed by {@link BiomeType#ordinal()} to avoid boxing and hashing
 * on the terrain-generation hot path.
 *
 * Weights are expected to be already normalized so that the sum is 1.0.
 */
public final class BiomeBlendResult {

    private static final BiomeType[] BIOMES = BiomeType.values();

    /**
     * Normalized weights, one slot per BiomeType ordinal. The blender transfers
     * ownership on construction - callers must not mutate it afterwards.
     */
    private final float[] weightsByOrdinal;
    private final BiomeType dominantBiome;
    private final float dominantWeight;

    public BiomeBlendResult(float[] weightsByOrdinal) {
        this.weightsByOrdinal = weightsByOrdinal;

        int dominantIndex = 0;
        float maxWeight = weightsByOrdinal[0];
        for (int i = 1; i < weightsByOrdinal.length; i++) {
            if (weightsByOrdinal[i] > maxWeight) {
                maxWeight = weightsByOrdinal[i];
                dominantIndex = i;
            }
        }
        this.dominantBiome = BIOMES[dominantIndex];
        this.dominantWeight = maxWeight;
    }

    public BiomeType getDominantBiome() {
        return dominantBiome;
    }

    public float getWeight(BiomeType biome) {
        return weightsByOrdinal[biome.ordinal()];
    }

    /**
     * Direct array access for hot-path consumers that iterate all biomes.
     * Read-only by convention; do not mutate.
     */
    public float[] getWeightsByOrdinal() {
        return weightsByOrdinal;
    }

    /**
     * Allocates an immutable EnumMap view. Intended for debug / non-hot-path use.
     */
    public Map<BiomeType, Float> getWeights() {
        EnumMap<BiomeType, Float> map = new EnumMap<>(BiomeType.class);
        for (int i = 0; i < weightsByOrdinal.length; i++) {
            if (weightsByOrdinal[i] > 0f) {
                map.put(BIOMES[i], weightsByOrdinal[i]);
            }
        }
        return Collections.unmodifiableMap(map);
    }

    /** True if the dominant biome's weight exceeds the threshold. */
    public boolean isStronglyDominant(float threshold) {
        return dominantWeight >= threshold;
    }

    @Override
    public String toString() {
        return "BiomeBlendResult{dominant=" + dominantBiome + ", weight=" + dominantWeight + '}';
    }
}
