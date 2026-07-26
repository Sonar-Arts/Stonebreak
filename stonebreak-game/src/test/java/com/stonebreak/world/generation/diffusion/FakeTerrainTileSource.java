package com.stonebreak.world.generation.diffusion;

import com.stonebreak.world.operations.WorldConfiguration;

import java.util.HashMap;
import java.util.Map;

/**
 * Deterministic, fully offline {@link TerrainTileSource} for tests. Mirrors
 * the real bridge's tile-bucketing shape (256-block tiles, floorDiv bucketing)
 * without any network call, so terrain-shape logic (cave carving, mesh
 * consistency) can be exercised without a live terrain bridge.
 *
 * <p>Since Phase 8 it also carries a water plane, and the inland part of it is a
 * lattice of lakes rather than a flat sea. Water that only ever sat at
 * {@code SEA_LEVEL} would let a regression in the per-column path pass every offline
 * test, because at sea level the new rule and the rule it replaced agree by
 * construction. These lakes sit <em>above</em> sea level, where they do not.
 *
 * <p>Every column is a pure function of its own world coordinates, so tiles agree at
 * their seams the way real ones do, and the lakes satisfy the containment invariant
 * (plan section 4.5) by construction: the bed is cut a block under the surface and the
 * rim is raised to meet it. A fake that leaked would make the leak tests pass or fail
 * for reasons that had nothing to do with the code under test.
 */
public final class FakeTerrainTileSource implements TerrainTileSource {

    private static final int TILE_SIZE = 256;
    private static final int WORLD_HEIGHT = WorldConfiguration.WORLD_HEIGHT;
    private static final int SEA_LEVEL = WorldConfiguration.SEA_LEVEL;

    /** Lake lattice: a {@link #LAKE_SPAN}-block square every {@link #LAKE_PITCH}. */
    static final int LAKE_PITCH = 64;
    static final int LAKE_SPAN = 24;
    static final int LAKE_LEVEL = SEA_LEVEL + 12;

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
        short[] waterLevels = new short[TILE_SIZE * TILE_SIZE];
        // Row-major with row = i = world X, col = j = world Z — the upstream
        // layout TerrainTile decodes.
        for (int row = 0; row < TILE_SIZE; row++) {
            int worldX = i1 + row;
            for (int col = 0; col < TILE_SIZE; col++) {
                int worldZ = j1 + col;
                int idx = row * TILE_SIZE + col;
                heights[idx] = (short) height(worldX, worldZ);
                biomes[idx] = 1;
                waterLevels[idx] = (short) waterLevel(worldX, worldZ);
            }
        }
        return new TerrainTile(tileX, tileZ, i1, j1, i1 + TILE_SIZE, j1 + TILE_SIZE,
                TILE_SIZE, TILE_SIZE, heights, biomes, waterLevels);
    }

    /** Cheap rolling-hills height field, well inside world bounds, with the lakes cut in. */
    public static int height(int worldX, int worldZ) {
        double offset = Math.sin(worldX * 0.05) * 10 + Math.cos(worldZ * 0.07) * 10;
        int h = SEA_LEVEL + (int) Math.round(offset);
        if (inLake(worldX, worldZ)) {
            h = Math.min(h, LAKE_LEVEL - 1);          // a bed, one block under the surface
        } else if (touchesLake(worldX, worldZ)) {
            h = Math.max(h, LAKE_LEVEL);              // a rim tall enough to hold it back
        }
        return Math.max(1, Math.min(h, WORLD_HEIGHT - 1));
    }

    /** @see TerrainTile#NO_WATER */
    public static int waterLevel(int worldX, int worldZ) {
        if (inLake(worldX, worldZ)) {
            return LAKE_LEVEL;
        }
        return height(worldX, worldZ) < SEA_LEVEL ? SEA_LEVEL : TerrainTile.NO_WATER;
    }

    static boolean inLake(int worldX, int worldZ) {
        return Math.floorMod(worldX, LAKE_PITCH) < LAKE_SPAN
                && Math.floorMod(worldZ, LAKE_PITCH) < LAKE_SPAN;
    }

    /** True for a dry column with a lake column as a 4-neighbour. */
    private static boolean touchesLake(int worldX, int worldZ) {
        return inLake(worldX - 1, worldZ) || inLake(worldX + 1, worldZ)
                || inLake(worldX, worldZ - 1) || inLake(worldX, worldZ + 1);
    }
}
