package com.stonebreak.world.generation;

import com.stonebreak.world.SnowLayerManager;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.generation.biomes.BiomeType;
import com.stonebreak.world.operations.WorldConfiguration;

/**
 * Per-chunk generation state shared across feature generators.
 * Caches height and biome per column so each generator does not rescan.
 */
public final class ChunkGenerationContext {
    public static final int SIZE = WorldConfiguration.CHUNK_SIZE;

    public final World world;
    public final Chunk chunk;
    public final int chunkX;
    public final int chunkZ;
    public final SnowLayerManager snowLayerManager;
    public final BiomeType dominantBiome;

    private final int[] heightMap;
    private final BiomeType[] biomeMap;

    public ChunkGenerationContext(World world, Chunk chunk, SnowLayerManager snowLayerManager,
                                  int[] heightMap, BiomeType[] biomeMap, BiomeType dominantBiome) {
        this.world = world;
        this.chunk = chunk;
        this.chunkX = chunk.getChunkX();
        this.chunkZ = chunk.getChunkZ();
        this.snowLayerManager = snowLayerManager;
        this.heightMap = heightMap;
        this.biomeMap = biomeMap;
        this.dominantBiome = dominantBiome;
    }

    public int height(int localX, int localZ) {
        return heightMap[localX * SIZE + localZ];
    }

    public BiomeType biome(int localX, int localZ) {
        return biomeMap[localX * SIZE + localZ];
    }

    public int worldX(int localX) {
        return chunkX * SIZE + localX;
    }

    public int worldZ(int localZ) {
        return chunkZ * SIZE + localZ;
    }
}
