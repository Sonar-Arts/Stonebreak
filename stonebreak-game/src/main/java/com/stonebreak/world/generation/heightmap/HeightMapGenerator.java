package com.stonebreak.world.generation.heightmap;

import com.stonebreak.world.generation.diffusion.TerrainTile;
import com.stonebreak.world.generation.diffusion.TerrainTileSource;
import com.stonebreak.world.operations.WorldConfiguration;

/**
 * Terrain height, sourced from the diffusion terrain bridge (plan.md Phase 2)
 * instead of the old continentalness/peaks-valleys/erosion noise splines.
 *
 * <p>A diffusion tile carries a single elevation value per column — there is
 * no equivalent of the old layered base/shape/detail decomposition, so
 * {@link #baseHeight}, {@link #shapedHeight}, and {@link #generateHeight} all
 * return the same tile-sourced value now. They remain three separate methods
 * only because {@code BiomeManager}, the cave carvers, surface decoration,
 * and the terrain-mapper debug visualizers still address them by name.
 */
public class HeightMapGenerator {
    private static final int WORLD_HEIGHT = WorldConfiguration.WORLD_HEIGHT;
    private static final int CHUNK_SIZE = WorldConfiguration.CHUNK_SIZE;

    private final TerrainTileSource tileSource;

    public HeightMapGenerator(TerrainTileSource tileSource) {
        this.tileSource = tileSource;
    }

    /** Same value as {@link #generateHeight} — see class javadoc. */
    public int baseHeight(int x, int z) {
        return generateHeight(x, z);
    }

    /** Same value as {@link #generateHeight} — see class javadoc. */
    public int shapedHeight(int x, int z) {
        return generateHeight(x, z);
    }

    /** Final surface height, read directly from the bridge's tile data. */
    public int generateHeight(int x, int z) {
        return clampToWorld(tileSource.getTile(x, z).heightAt(x, z));
    }

    /**
     * Fills a 16x16 final-height grid for the given chunk, indexed [x*16+z].
     * A chunk (16 blocks) always fits inside a single bridge tile (256
     * blocks by default, always a multiple of CHUNK_SIZE), so this resolves
     * one tile for the whole chunk rather than one HTTP round trip per column.
     */
    public void populateChunkHeights(int chunkX, int chunkZ, int[] out) {
        int baseX = chunkX * CHUNK_SIZE;
        int baseZ = chunkZ * CHUNK_SIZE;
        TerrainTile tile = tileSource.getTile(baseX, baseZ);
        for (int x = 0; x < CHUNK_SIZE; x++) {
            for (int z = 0; z < CHUNK_SIZE; z++) {
                out[x * CHUNK_SIZE + z] = clampToWorld(tile.heightAt(baseX + x, baseZ + z));
            }
        }
    }

    private static int clampToWorld(int height) {
        return Math.max(1, Math.min(height, WORLD_HEIGHT - 1));
    }
}
