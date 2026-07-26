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
        return DiffusionBiomeMapper.map(tile.biomeIdAt(x, z), tile.heightAt(x, z), nearbyWaterLevel(tile, x, z));
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
                out[idx] = DiffusionBiomeMapper.map(tile.biomeIdAt(worldX, worldZ), heights[idx],
                        nearbyWaterLevel(tile, worldX, worldZ));
            }
        }
    }

    /**
     * Highest water level among {@code (worldX, worldZ)} and its 8 neighbours, or
     * {@link TerrainTile#NO_WATER} if none of them hold water. A dry column's own
     * water level is always {@code NO_WATER} — a lake or river reports its surface
     * only for the columns it actually covers — so detecting "next to water" needs
     * a neighbour's value, not the column's own.
     *
     * <p>A neighbour that falls outside this tile is skipped rather than resolved
     * from a second tile: the shoreline test only needs to know water is nearby,
     * and the miss is confined to columns exactly on a tile boundary (256 blocks
     * apart by default).
     */
    private static short nearbyWaterLevel(TerrainTile tile, int worldX, int worldZ) {
        short best = TerrainTile.NO_WATER;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int nx = worldX + dx;
                int nz = worldZ + dz;
                if (nx < tile.worldI1() || nx >= tile.worldI2() || nz < tile.worldJ1() || nz >= tile.worldJ2()) {
                    continue;
                }
                short level = tile.waterLevelAt(nx, nz);
                if (level != TerrainTile.NO_WATER && level > best) {
                    best = level;
                }
            }
        }
        return best;
    }
}
