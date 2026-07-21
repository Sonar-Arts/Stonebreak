package com.stonebreak.world.generation.diffusion;

import com.stonebreak.world.operations.WorldConfiguration;

import java.util.HashMap;
import java.util.Map;

/**
 * Deterministic, fully offline {@link TerrainTileSource} for tests. Mirrors
 * the real bridge's tile-bucketing shape (256-block tiles, floorDiv bucketing)
 * without any network call, so terrain-shape logic (cave carving, mesh
 * consistency) can be exercised without a live terrain bridge.
 */
public final class FakeTerrainTileSource implements TerrainTileSource {

    private static final int TILE_SIZE = 256;
    private static final int WORLD_HEIGHT = WorldConfiguration.WORLD_HEIGHT;
    private static final int SEA_LEVEL = WorldConfiguration.SEA_LEVEL;

    private record TileKey(int tileX, int tileZ) {}

    private final Map<TileKey, TerrainTile> cache = new HashMap<>();

    @Override
    public synchronized TerrainTile getTile(int worldX, int worldZ) {
        int tileX = Math.floorDiv(worldX, TILE_SIZE);
        int tileZ = Math.floorDiv(worldZ, TILE_SIZE);
        return cache.computeIfAbsent(new TileKey(tileX, tileZ), key -> buildTile(key.tileX(), key.tileZ()));
    }

    private static TerrainTile buildTile(int tileX, int tileZ) {
        int i1 = tileX * TILE_SIZE;
        int j1 = tileZ * TILE_SIZE;
        short[] heights = new short[TILE_SIZE * TILE_SIZE];
        short[] biomes = new short[TILE_SIZE * TILE_SIZE];
        // Row-major with row = i = world X, col = j = world Z — the upstream
        // layout TerrainTile decodes.
        for (int row = 0; row < TILE_SIZE; row++) {
            int worldX = i1 + row;
            for (int col = 0; col < TILE_SIZE; col++) {
                int worldZ = j1 + col;
                heights[row * TILE_SIZE + col] = (short) height(worldX, worldZ);
                biomes[row * TILE_SIZE + col] = 1;
            }
        }
        return new TerrainTile(tileX, tileZ, i1, j1, i1 + TILE_SIZE, j1 + TILE_SIZE, TILE_SIZE, TILE_SIZE, heights, biomes);
    }

    /** Cheap rolling-hills height field, well inside world bounds. */
    private static int height(int worldX, int worldZ) {
        double offset = Math.sin(worldX * 0.05) * 10 + Math.cos(worldZ * 0.07) * 10;
        int h = SEA_LEVEL + (int) Math.round(offset);
        return Math.max(1, Math.min(h, WORLD_HEIGHT - 1));
    }
}
