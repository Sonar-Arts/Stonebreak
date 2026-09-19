package com.stonebreak.world.generation.diffusion;

import com.stonebreak.world.operations.WorldConfiguration;

/**
 * An empty world: every column is void (height 0, no water, plains biome).
 *
 * <p>For a {@code World} that never generates terrain — the battle-test scene, which
 * answers AIR for every block out of its own arena data. Such a world still builds a
 * {@code TerrainGenerationSystem} (it owns the FastLOD lifecycle and the chunk store's
 * generator hook), and building the real one starts the terrain-diffusion services and
 * <b>re-pins the running pair to that world's seed</b> — which fails every in-flight
 * tile request the live world has, with {@code 400 "this bridge instance is pinned to
 * seed ..."}. This source keeps the scene world entirely off that chain.
 *
 * <p>Not a fallback for real generation: production terrain has no offline path
 * (plan.md Phase 2), and a world wired to this one renders nothing but its own scene.
 */
public final class VoidTileSource implements TerrainTileSource {

    public static final VoidTileSource INSTANCE = new VoidTileSource();

    /** Tiles are built per request and never cached — nothing asks this source twice. */
    private static final int TILE_SIZE = WorldConfiguration.CHUNK_SIZE;
    /** Plains: {@code DiffusionBiomeMapper} maps it without falling through to a default. */
    private static final short PLAINS = 1;

    private VoidTileSource() {
    }

    @Override
    public TerrainTile getTile(int worldX, int worldZ) {
        int tileX = Math.floorDiv(worldX, TILE_SIZE);
        int tileZ = Math.floorDiv(worldZ, TILE_SIZE);
        int i1 = tileX * TILE_SIZE;
        int j1 = tileZ * TILE_SIZE;

        int cells = TILE_SIZE * TILE_SIZE;
        short[] heights = new short[cells];           // 0 — void down to bedrock
        short[] biomes = new short[cells];
        short[] waterLevels = new short[cells];
        java.util.Arrays.fill(biomes, PLAINS);
        java.util.Arrays.fill(waterLevels, TerrainTile.NO_WATER);

        return new TerrainTile(tileX, tileZ, i1, j1, i1 + TILE_SIZE, j1 + TILE_SIZE,
                TILE_SIZE, TILE_SIZE, heights, biomes, waterLevels);
    }
}
