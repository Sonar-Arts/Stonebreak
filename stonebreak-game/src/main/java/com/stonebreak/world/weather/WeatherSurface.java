package com.stonebreak.world.weather;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.operations.WorldConfiguration;

/**
 * Where precipitation lands, read from loaded chunks only (never waits on
 * terrain generation): the height rain stops at, and whether it is snow.
 */
public final class WeatherSurface {

    /** Returned by {@link #groundY} when the column's chunk isn't loaded. */
    public static final int UNKNOWN = Integer.MIN_VALUE;

    private WeatherSurface() {}

    /** First open block above the column's highest opaque block, or {@link #UNKNOWN}. */
    public static int groundY(World world, int x, int z) {
        Chunk chunk = chunkAt(world, x, z);
        return chunk == null ? UNKNOWN : chunk.getHeightMap().getHeight(local(x), local(z));
    }

    /** Snow falls on snow and ice: the surface block, or a snow layer lying on it. */
    public static boolean isSnow(World world, int x, int z) {
        Chunk chunk = chunkAt(world, x, z);
        if (chunk == null) return false;
        int lx = local(x), lz = local(z);
        int ground = chunk.getHeightMap().getHeight(lx, lz);
        if (ground < WorldConfiguration.WORLD_HEIGHT && chunk.getBlock(lx, ground, lz) == BlockType.SNOW) return true;
        if (ground <= 0) return false;
        BlockType surface = chunk.getBlock(lx, ground - 1, lz);
        return surface == BlockType.SNOW || surface == BlockType.SNOWY_DIRT || surface == BlockType.ICE;
    }

    private static Chunk chunkAt(World world, int x, int z) {
        return world.getChunkIfLoaded(Math.floorDiv(x, WorldConfiguration.CHUNK_SIZE),
                Math.floorDiv(z, WorldConfiguration.CHUNK_SIZE));
    }

    private static int local(int v) {
        return Math.floorMod(v, WorldConfiguration.CHUNK_SIZE);
    }
}
