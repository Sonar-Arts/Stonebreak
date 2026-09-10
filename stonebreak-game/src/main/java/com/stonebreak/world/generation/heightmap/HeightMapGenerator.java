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

    /** Water level at a column, or {@link TerrainTile#NO_WATER}. */
    public int waterLevel(int x, int z) {
        return tileSource.getTile(x, z).waterLevelAt(x, z);
    }

    /**
     * Floor of the river tunnel through a column, or {@link TerrainTile#NO_TUNNEL}.
     *
     * <p>This, not {@link #generateHeight}, is the bed of a tunnelled column:
     * the height there is the ground standing over the river, so a cave guard
     * measuring from the height leaves the tunnel itself open.
     */
    public int riverFloor(int x, int z) {
        return tileSource.getTile(x, z).riverFloorAt(x, z);
    }

    /** @see #riverFloor */
    public int riverRoof(int x, int z) {
        return tileSource.getTile(x, z).riverRoofAt(x, z);
    }

    /**
     * Fills a 16x16 final-height grid for the given chunk, indexed [x*16+z].
     * A chunk (16 blocks) always fits inside a single bridge tile (256
     * blocks by default, always a multiple of CHUNK_SIZE), so this resolves
     * one tile for the whole chunk rather than one HTTP round trip per column.
     */
    public void populateChunkHeights(int chunkX, int chunkZ, int[] out) {
        populateChunkHeights(chunkX, chunkZ, out, null);
    }

    /**
     * As {@link #populateChunkHeights(int, int, int[])}, and fills the co-located water
     * levels when {@code outWaterLevels} is non-null.
     *
     * <p>One method rather than two passes because the two planes come from the same
     * resolved tile and must not be able to disagree about which tile that was — the
     * same reason {@code BiomeManager.populateChunkBiomes} reuses the clamped heights
     * rather than re-resolving them.
     *
     * <p>The water level is <em>not</em> clamped to the world column the way the height
     * is: the bridge already emits it inside {@code [0, world_height)} or as
     * {@link TerrainTile#NO_WATER}, and clamping a negative sentinel to 1 would turn
     * "no water here" into "one block of water at bedrock".
     */
    public void populateChunkHeights(int chunkX, int chunkZ, int[] out, int[] outWaterLevels) {
        populateChunkHeights(chunkX, chunkZ, out, outWaterLevels, null, null);
    }

    /**
     * As {@link #populateChunkHeights(int, int, int[], int[])}, and fills the
     * co-located river-tunnel planes when they are non-null: the void a river
     * runs through where it passes under standing ground, as
     * {@code outRiverFloors < y < outRiverRoofs}, or
     * {@link TerrainTile#NO_TUNNEL} in both for a column that has none.
     *
     * <p>All four planes come from the one resolved tile for the same reason the
     * first two do: they describe one column between them and must not be able
     * to disagree about which tile that column came from.
     */
    public void populateChunkHeights(int chunkX, int chunkZ, int[] out, int[] outWaterLevels,
                                     int[] outRiverFloors, int[] outRiverRoofs) {
        int baseX = chunkX * CHUNK_SIZE;
        int baseZ = chunkZ * CHUNK_SIZE;
        TerrainTile tile = tileSource.getTile(baseX, baseZ);
        for (int x = 0; x < CHUNK_SIZE; x++) {
            for (int z = 0; z < CHUNK_SIZE; z++) {
                int idx = x * CHUNK_SIZE + z;
                int worldX = baseX + x;
                int worldZ = baseZ + z;
                out[idx] = clampToWorld(tile.heightAt(worldX, worldZ));
                if (outWaterLevels != null) {
                    outWaterLevels[idx] = tile.waterLevelAt(worldX, worldZ);
                }
                if (outRiverFloors != null) {
                    outRiverFloors[idx] = tile.riverFloorAt(worldX, worldZ);
                }
                if (outRiverRoofs != null) {
                    outRiverRoofs[idx] = tile.riverRoofAt(worldX, worldZ);
                }
            }
        }
    }

    /**
     * Fills an arbitrary rectangle of final heights, indexed {@code [(x-minX)*sizeZ + (z-minZ)]}.
     *
     * <p>Unlike {@link #populateChunkHeights} this may span tiles, so it holds the last tile it
     * resolved and re-resolves only when a column falls outside it. That is the whole point of
     * the method: {@code getTile} on the production cache is a concurrent-map lookup plus LRU
     * bookkeeping per call, and a caller that needs a haloed patch around a chunk would
     * otherwise pay it thousands of times per chunk on the generation threads instead of once
     * per tile the patch actually touches.
     */
    public void populateHeightPatch(int minX, int minZ, int sizeX, int sizeZ, int[] out) {
        TerrainTile tile = null;
        for (int x = 0; x < sizeX; x++) {
            int worldX = minX + x;
            for (int z = 0; z < sizeZ; z++) {
                int worldZ = minZ + z;
                if (tile == null || worldX < tile.worldI1() || worldX >= tile.worldI2()
                        || worldZ < tile.worldJ1() || worldZ >= tile.worldJ2()) {
                    tile = tileSource.getTile(worldX, worldZ);
                }
                out[x * sizeZ + z] = clampToWorld(tile.heightAt(worldX, worldZ));
            }
        }
    }

    private static int clampToWorld(int height) {
        return Math.max(1, Math.min(height, WORLD_HEIGHT - 1));
    }
}
