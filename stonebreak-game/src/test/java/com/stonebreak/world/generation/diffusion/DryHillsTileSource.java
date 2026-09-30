package com.stonebreak.world.generation.diffusion;

import com.stonebreak.world.operations.WorldConfiguration;

import java.util.HashMap;
import java.util.Map;

/**
 * Dry inland hills, well above sea level, with no water anywhere — the terrain caves are
 * meant to occupy.
 *
 * <p>Deliberately not {@link FakeTerrainTileSource}, which oscillates its heights around
 * {@code SEA_LEVEL} so roughly half its columns are ocean. {@code WaterGuard} then seals
 * nearly every near-surface column as somebody's bank — correctly — and no cave may approach
 * the surface anywhere in that world. Measuring cave shape or reachability there measures
 * the fake's coastline, not the carvers. Water behaviour has its own tests, and those use
 * the wetter fake on purpose.
 */
public final class DryHillsTileSource implements TerrainTileSource {

    private static final int TILE_SIZE = 256;
    private static final int BASE = WorldConfiguration.SEA_LEVEL + 60;

    private final Map<Long, TerrainTile> cache = new HashMap<>();

    /** Surface height at a column, exposed so tests can predict it without a tile fetch. */
    public static int height(int worldX, int worldZ) {
        double offset = Math.sin(worldX * 0.05) * 12 + Math.cos(worldZ * 0.07) * 12;
        return BASE + (int) Math.round(offset);
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
                biomes[idx] = 1;
                waterLevels[idx] = TerrainTile.NO_WATER;
            }
        }
        return new TerrainTile(tileX, tileZ, i1, j1, i1 + TILE_SIZE, j1 + TILE_SIZE,
                TILE_SIZE, TILE_SIZE, heights, biomes, waterLevels);
    }
}
