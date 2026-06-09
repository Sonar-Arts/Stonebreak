package com.stonebreak.world.generation;

import com.stonebreak.world.generation.biomes.BiomeType;
import com.stonebreak.world.operations.WorldConfiguration;

/**
 * Per-column terrain profile (heights + biomes) computed once during terrain
 * generation and carried to deferred feature population, so the noise stack
 * is not resampled a second time for the same chunk.
 *
 * <p>Arrays are indexed {@code x * CHUNK_SIZE + z}, matching
 * {@code HeightMapGenerator.populateChunkHeights}.
 */
public record ColumnProfile(int[] heights, BiomeType[] biomes) {

    /** Biome at the chunk's center column — the feature pass's "dominant" biome. */
    public BiomeType dominantBiome() {
        int center = (WorldConfiguration.CHUNK_SIZE / 2) * WorldConfiguration.CHUNK_SIZE
                + (WorldConfiguration.CHUNK_SIZE / 2);
        return biomes[center];
    }
}
