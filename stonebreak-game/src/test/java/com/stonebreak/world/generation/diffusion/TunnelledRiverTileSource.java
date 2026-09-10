package com.stonebreak.world.generation.diffusion;

import com.stonebreak.world.operations.WorldConfiguration;

import java.util.HashMap;
import java.util.Map;

/**
 * A river running west to east that meets a hill and TUNNELS under it, as
 * {@code ck_carve_water} now stamps one.
 *
 * <p>This fake exists because the tunnel planes cannot be reached from
 * {@link FakeTerrainTileSource}: that one has no rivers at all, so every column
 * it emits reports {@link TerrainTile#NO_TUNNEL} and the whole branch the block
 * loop grew for tunnels would never execute in an offline test.
 *
 * <p>The shape, along +X at a fixed Z band:
 *
 * <pre>
 *   x &lt; HILL_X0            open reach: the bed is cut, water sits on it
 *   HILL_X0 &lt;= x &lt;= HILL_X1  the hill: ground UNTOUCHED, river in a tunnel
 *   x &gt; HILL_X1            open reach again
 * </pre>
 *
 * so a single chunk straddles a mouth and the transition is covered rather than
 * only the two steady states.
 *
 * <p>Every column is a pure function of its own world coordinates, so tiles
 * agree at their seams the way real ones do, and the fixture satisfies the
 * containment invariant by construction — the banks beside the open reach stand
 * at {@link #SURFACE} + 2, and the ground beside the tunnel is the hill itself.
 * A fake that leaked would make the leak assertions pass or fail for reasons
 * unrelated to the code under test.
 */
public final class TunnelledRiverTileSource implements TerrainTileSource {

    private static final int TILE_SIZE = 256;
    private static final int WORLD_HEIGHT = WorldConfiguration.WORLD_HEIGHT;
    private static final int SEA_LEVEL = WorldConfiguration.SEA_LEVEL;

    /** The river's water surface: well above sea level, where it cannot be confused for ocean. */
    public static final int SURFACE = SEA_LEVEL + 40;
    /** Bed depth, so an open reach's ground sits here. */
    public static final int BED = 3;
    public static final int CHANNEL_FLOOR = SURFACE - BED;
    /** Bank height on the plain: tall enough to hold the open reach in. */
    public static final int PLAIN = SURFACE + 2;
    /** The hill the river goes under — 44 blocks of ground that used to be deleted. */
    public static final int HILL = SURFACE + 44;

    /** Mirrors the kernel's {@code tunnel_min_roof} / {@code tunnel_headroom} defaults. */
    public static final int MIN_ROOF = 4;
    public static final int HEADROOM = 5;
    public static final int TUNNEL_ROOF = Math.min(SURFACE + HEADROOM, HILL - MIN_ROOF);

    /** The channel's centre line and half-width, in world Z. */
    public static final int RIVER_Z = 8;
    public static final int HALF_WIDTH = 3;

    public static final int HILL_X0 = 8;
    public static final int HILL_X1 = 120;

    private record TileKey(int tileX, int tileZ) {}

    private final Map<TileKey, TerrainTile> cache = new HashMap<>();

    @Override
    public synchronized TerrainTile getTile(int worldX, int worldZ) {
        int tileX = Math.floorDiv(worldX, TILE_SIZE);
        int tileZ = Math.floorDiv(worldZ, TILE_SIZE);
        return cache.computeIfAbsent(new TileKey(tileX, tileZ),
                key -> buildTile(key.tileX(), key.tileZ()));
    }

    /** True where the column lies inside the channel's cross-section. */
    public static boolean inChannel(int worldZ) {
        return Math.abs(worldZ - RIVER_Z) <= HALF_WIDTH;
    }

    /** True where the ground stands high enough that the river must go under it. */
    public static boolean underHill(int worldX) {
        return worldX >= HILL_X0 && worldX <= HILL_X1;
    }

    /** True for a column that carries the river through solid ground. */
    public static boolean isTunnel(int worldX, int worldZ) {
        return inChannel(worldZ) && underHill(worldX);
    }

    /** True for a column where the river runs at grade, in the open. */
    public static boolean isOpenChannel(int worldX, int worldZ) {
        return inChannel(worldZ) && !underHill(worldX);
    }

    /**
     * The terrain surface. Note what is NOT here: a tunnelled column keeps the
     * full {@link #HILL}. That is the whole point — before the fix the stamp
     * wrote {@link #CHANNEL_FLOOR} here and the hill ceased to exist.
     */
    public static int height(int worldX, int worldZ) {
        if (isOpenChannel(worldX, worldZ)) {
            return CHANNEL_FLOOR;
        }
        return underHill(worldX) ? HILL : PLAIN;
    }

    public static int waterLevel(int worldX, int worldZ) {
        return inChannel(worldZ) ? SURFACE : TerrainTile.NO_WATER;
    }

    public static int riverFloor(int worldX, int worldZ) {
        return isTunnel(worldX, worldZ) ? CHANNEL_FLOOR : TerrainTile.NO_TUNNEL;
    }

    public static int riverRoof(int worldX, int worldZ) {
        return isTunnel(worldX, worldZ) ? TUNNEL_ROOF : TerrainTile.NO_TUNNEL;
    }

    private static TerrainTile buildTile(int tileX, int tileZ) {
        int i1 = tileX * TILE_SIZE;
        int j1 = tileZ * TILE_SIZE;
        short[] heights = new short[TILE_SIZE * TILE_SIZE];
        short[] biomes = new short[TILE_SIZE * TILE_SIZE];
        short[] waterLevels = new short[TILE_SIZE * TILE_SIZE];
        short[] riverFloors = new short[TILE_SIZE * TILE_SIZE];
        short[] riverRoofs = new short[TILE_SIZE * TILE_SIZE];
        // Row-major with row = i = world X, col = j = world Z.
        for (int row = 0; row < TILE_SIZE; row++) {
            int worldX = i1 + row;
            for (int col = 0; col < TILE_SIZE; col++) {
                int worldZ = j1 + col;
                int idx = row * TILE_SIZE + col;
                heights[idx] = (short) Math.max(1, Math.min(height(worldX, worldZ), WORLD_HEIGHT - 1));
                biomes[idx] = 1;
                waterLevels[idx] = (short) waterLevel(worldX, worldZ);
                riverFloors[idx] = (short) riverFloor(worldX, worldZ);
                riverRoofs[idx] = (short) riverRoof(worldX, worldZ);
            }
        }
        return new TerrainTile(tileX, tileZ, i1, j1, i1 + TILE_SIZE, j1 + TILE_SIZE,
                TILE_SIZE, TILE_SIZE, heights, biomes, waterLevels, riverFloors, riverRoofs);
    }
}
