package com.stonebreak.world.generation.diffusion;

import com.stonebreak.world.operations.WorldConfiguration;

import java.util.HashMap;
import java.util.Map;

/**
 * Dry plateaus separated by sheer 60-block cliffs — the terrain cliff carving is meant to
 * occupy, reduced to its essentials.
 *
 * <p>{@link DryHillsTileSource} is deliberately smooth, which is right for measuring cave
 * volume and wrong for measuring anything about faces: its steepest grade is gentle enough
 * that {@code CliffExposure} reads near zero across the whole world, so a test built on it
 * would measure flat-ground behaviour and call it a cliff.
 *
 * <p>The step is a square wave on world X with period {@link #PERIOD}: half plateau, half
 * lowland, both flat and both well above sea level so no {@code WaterGuard} bank seals the
 * columns under test. Flat halves matter as much as the step — they give a test both
 * saturated columns (within a tap radius of a lip) and columns at exactly zero exposure, in
 * one world, so the two can be compared without changing anything else.
 */
public final class CliffTileSource implements TerrainTileSource {

    private static final int TILE_SIZE = 256;

    /** Lowland floor, high enough above sea level that nothing here is coastal. */
    public static final int LOWLAND = WorldConfiguration.SEA_LEVEL + 20;
    /** Height of the step. Sheer: one column of plateau abuts one column of lowland. */
    public static final int CLIFF_HEIGHT = 60;
    public static final int PLATEAU = LOWLAND + CLIFF_HEIGHT;

    /**
     * Full period of the square wave; the plateau occupies the first half. 128 leaves the
     * middle of each half far enough from both lips to be genuinely unexposed.
     */
    public static final int PERIOD = 128;

    private final Map<Long, TerrainTile> cache = new HashMap<>();

    /** Surface height at a column, exposed so tests can predict it without a tile fetch. */
    public static int height(int worldX, int worldZ) {
        return Math.floorMod(worldX, PERIOD) < PERIOD / 2 ? PLATEAU : LOWLAND;
    }

    /** Distance in blocks from a column to the nearest cliff lip, ignoring which side it is on. */
    public static int distanceToLip(int worldX) {
        int phase = Math.floorMod(worldX, PERIOD / 2);
        return Math.min(phase, PERIOD / 2 - 1 - phase);
    }

    /** True where the column stands on the plateau rather than the lowland floor. */
    public static boolean onPlateau(int worldX) {
        return height(worldX, 0) == PLATEAU;
    }

    @Override
    public synchronized TerrainTile getTile(int worldX, int worldZ) {
        int tileX = Math.floorDiv(worldX, TILE_SIZE);
        int tileZ = Math.floorDiv(worldZ, TILE_SIZE);
        return cache.computeIfAbsent((((long) tileX) << 32) ^ (tileZ & 0xFFFFFFFFL),
                k -> build(tileX, tileZ));
    }

    private static TerrainTile build(int tileX, int tileZ) {
        int i1 = tileX * TILE_SIZE;
        int j1 = tileZ * TILE_SIZE;
        short[] heights = new short[TILE_SIZE * TILE_SIZE];
        short[] biomes = new short[TILE_SIZE * TILE_SIZE];
        short[] waterLevels = new short[TILE_SIZE * TILE_SIZE];
        for (int row = 0; row < TILE_SIZE; row++) {
            for (int col = 0; col < TILE_SIZE; col++) {
                int idx = row * TILE_SIZE + col;
                heights[idx] = (short) height(i1 + row, j1 + col);
                // 19 = windswept_hills -> STONY_PEAKS, the biome that actually carries cliffs
                // and the one with the highest overhangIntensity (0.35). A plains world would
                // produce almost no openings to measure the rock behind.
                biomes[idx] = 19;
                waterLevels[idx] = TerrainTile.NO_WATER;
            }
        }
        return new TerrainTile(tileX, tileZ, i1, j1, i1 + TILE_SIZE, j1 + TILE_SIZE,
                TILE_SIZE, TILE_SIZE, heights, biomes, waterLevels);
    }
}
