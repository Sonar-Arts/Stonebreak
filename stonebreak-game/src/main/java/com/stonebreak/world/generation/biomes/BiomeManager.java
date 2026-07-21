package com.stonebreak.world.generation.biomes;

import com.stonebreak.world.generation.diffusion.TerrainTile;
import com.stonebreak.world.generation.diffusion.TerrainTileSource;
import com.stonebreak.world.operations.WorldConfiguration;

/**
 * Resolves the biome for a column from the diffusion bridge's own tile data
 * (plan.md Phase 4): each {@link TerrainTile} already carries a
 * classifier-decided vanilla-Minecraft biome id per column, so this class's
 * job is translation ({@link DiffusionBiomeMapper}) rather than climate
 * classification — that already happened server-side. Superseded design:
 * this used to sample {@code NoiseRouter} and run a noise-tuple decision
 * tree ({@code BiomeSelector}); both are gone, along with the temperature/
 * moisture debug probes that had no tile-sourced equivalent to replace them
 * with (the tile carries a finished biome id, not raw climate).
 */
public class BiomeManager {
    private static final int CHUNK_SIZE = WorldConfiguration.CHUNK_SIZE;

    private final TerrainTileSource tileSource;

    public BiomeManager(TerrainTileSource tileSource) {
        this.tileSource = tileSource;
    }

    public BiomeType getBiome(int x, int z) {
        TerrainTile tile = tileSource.getTile(x, z);
        return DiffusionBiomeMapper.map(tile.biomeIdAt(x, z), tile.heightAt(x, z));
    }

    /**
     * Fills a 16x16 biome grid for the given chunk, indexed [x*16+z]. Reuses
     * the chunk's precomputed heights (same values {@code HeightMapGenerator}
     * wrote into the world) rather than re-reading height off the tile, so
     * biome and terrain shape can never disagree about a column's height.
     */
    public void populateChunkBiomes(int chunkX, int chunkZ, int[] heights, BiomeType[] out) {
        int baseX = chunkX * CHUNK_SIZE;
        int baseZ = chunkZ * CHUNK_SIZE;
        TerrainTile tile = tileSource.getTile(baseX, baseZ);
        for (int x = 0; x < CHUNK_SIZE; x++) {
            for (int z = 0; z < CHUNK_SIZE; z++) {
                int idx = x * CHUNK_SIZE + z;
                int worldX = baseX + x;
                int worldZ = baseZ + z;
                out[idx] = DiffusionBiomeMapper.map(tile.biomeIdAt(worldX, worldZ), heights[idx]);
            }
        }
    }
}
