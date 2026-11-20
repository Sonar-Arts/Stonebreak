package com.stonebreak.world.generation.diffusion.biomes;

import com.stonebreak.world.generation.biomes.BiomeType;

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
    private final BeachRule beach;

    public BiomeManager(TerrainTileSource tileSource) {
        this(tileSource, BeachRule.LEGACY);
    }

    /** Biomes with the world's own beach rule ({@link BeachRule#of}: height-curve v2 worlds size it in metres). */
    public BiomeManager(TerrainTileSource tileSource, BeachRule beach) {
        this.tileSource = tileSource;
        this.beach = beach;
    }

    public BiomeType getBiome(int x, int z) {
        TerrainTile tile = tileSource.getTile(x, z);
        int h = tile.heightAt(x, z);
        return DiffusionBiomeMapper.map(tile.biomeIdAt(x, z), h, seaLevelNear(tile, x, z, h), beach.bandBlocks());
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
                        seaLevelNear(tile, worldX, worldZ, heights[idx]), beach.bandBlocks());
            }
        }
    }

    /**
     * The biome of one column of {@code tile} with ground height {@code height}, by the same rule as
     * {@link #populateChunkBiomes} (the coarse-LOD profiles read tiles in sample units through it).
     */
    public static BiomeType biomeAt(TerrainTile tile, int x, int z, int height) {
        return DiffusionBiomeMapper.map(tile.biomeIdAt(x, z), height, nearbySeaLevel(tile, x, z, 1, null));
    }

    /**
     * {@link #biomeAt(TerrainTile, int, int, int)} with a world's beach height band (coarse LOD: one sample of reach,
     * since a sample is already several blocks, and no cross-tile reads).
     */
    public static BiomeType biomeAt(TerrainTile tile, int x, int z, int height, BeachRule rule) {
        return DiffusionBiomeMapper.map(tile.biomeIdAt(x, z), height, nearbySeaLevel(tile, x, z, 1, null),
                rule.bandBlocks());
    }

    /**
     * The sea level next to a column for the beach test, or {@link TerrainTile#NO_WATER}: skipped (no neighbour
     * reads) for columns that cannot become beach anyway - not eligible, or outside [sea level, sea level + band) -
     * so the cross-tile reads of the v2 rule only happen along low coasts.
     */
    private short seaLevelNear(TerrainTile tile, int x, int z, int height) {
        int sea = WorldConfiguration.SEA_LEVEL;
        if (height >= sea + beach.bandBlocks() || !DiffusionBiomeMapper.beachEligible(tile.biomeIdAt(x, z))) {
            return TerrainTile.NO_WATER;
        }
        return nearbySeaLevel(tile, x, z, beach.reach(), beach.crossTile() ? tileSource : null);
    }

    /**
     * Highest water level among the OCEAN columns within {@code reach} of {@code (worldX, worldZ)} (a disc:
     * dx^2 + dz^2 <= reach^2 + reach, i.e. the 3x3 block at reach 1), or {@link TerrainTile#NO_WATER} if none of
     * them is ocean. Lakes and rivers do not count: only the true ocean makes beaches (sand rings around every
     * sea-level pit and along lowland rivers did). A dry column's own water level is always {@code NO_WATER}, so
     * detecting "next to the sea" needs a neighbour's value.
     *
     * <p>A neighbour outside this tile is read from its own tile when {@code others} is given (v2 worlds), else
     * skipped: the legacy rule, whose miss left a one-column notch in the sand wherever a coast crossed a tile edge
     * (256 blocks apart by default).
     */
    private static short nearbySeaLevel(TerrainTile tile, int worldX, int worldZ, int reach, TerrainTileSource others) {
        short best = TerrainTile.NO_WATER;
        int r2 = reach * reach + reach;
        TerrainTile other = null;
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                if (dx * dx + dz * dz > r2) {
                    continue;
                }
                int nx = worldX + dx;
                int nz = worldZ + dz;
                TerrainTile t = tile;
                if (nx < tile.worldI1() || nx >= tile.worldI2() || nz < tile.worldJ1() || nz >= tile.worldJ2()) {
                    if (others == null) {
                        continue;
                    }
                    if (other == null || nx < other.worldI1() || nx >= other.worldI2() || nz < other.worldJ1()
                            || nz >= other.worldJ2()) {
                        other = others.getTile(nx, nz);
                    }
                    t = other;
                }
                if (!DiffusionBiomeMapper.isOceanId(t.biomeIdAt(nx, nz))) {
                    continue;
                }
                short level = t.waterLevelAt(nx, nz);
                if (level != TerrainTile.NO_WATER && level > best) {
                    best = level;
                }
            }
        }
        return best;
    }
}
