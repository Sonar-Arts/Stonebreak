package com.stonebreak.world.chunk.utils;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import com.openmason.engine.voxel.cco.operations.CcoBlockReader;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.operations.WorldConfiguration;

/**
 * Shared chunk-adapter plumbing for block simulations (water flow, leaf decay):
 * world-coordinate CCO block reads through a per-chunk reader cache, plus the
 * loaded-chunk lookups every sim seam needs.
 *
 * <p>Extracted from {@code WorldFlowWorld}/{@code WorldLeafWorld}, which
 * carried near-identical copies of this and had already begun to drift — a
 * reader-cache or lookup fix now lands in both simulations at once.
 *
 * <p>Unloaded chunks and out-of-world heights read as AIR; each simulation
 * decides for itself how to treat that (water treats it as a wall via its
 * scheduling rules, leaf decay refuses to destroy blocks near an unloaded
 * seam). Call {@link #onChunkUnloaded} from the owner's eviction hook so a
 * re-loaded chunk gets a fresh reader.
 */
public final class CachedChunkAccess {

    private final World world;
    private final Map<Long, CcoBlockReader> readerCache = new ConcurrentHashMap<>();

    public CachedChunkAccess(World world) {
        this.world = Objects.requireNonNull(world, "world");
    }

    /** Block at world coords; AIR outside the world or in unloaded chunks. */
    public BlockType getBlock(int x, int y, int z) {
        if (y < 0 || y >= WorldConfiguration.WORLD_HEIGHT) {
            return BlockType.AIR;
        }
        CcoBlockReader reader = readerFor(Math.floorDiv(x, WorldConfiguration.CHUNK_SIZE),
                                          Math.floorDiv(z, WorldConfiguration.CHUNK_SIZE));
        if (reader == null) {
            return BlockType.AIR;
        }
        return (BlockType) reader.get(Math.floorMod(x, WorldConfiguration.CHUNK_SIZE), y,
                                      Math.floorMod(z, WorldConfiguration.CHUNK_SIZE));
    }

    /** Whether the position is inside the world and its chunk is resident. */
    public boolean isLoaded(int x, int y, int z) {
        return y >= 0 && y < WorldConfiguration.WORLD_HEIGHT
            && chunkAt(x, z) != null;
    }

    /** The resident chunk containing world column (x, z), or null. */
    public Chunk chunkAt(int x, int z) {
        return world.getChunkIfLoaded(Math.floorDiv(x, WorldConfiguration.CHUNK_SIZE),
                                      Math.floorDiv(z, WorldConfiguration.CHUNK_SIZE));
    }

    /** Drops the cached reader for an evicted chunk. */
    public void onChunkUnloaded(int chunkX, int chunkZ) {
        readerCache.remove(chunkKey(chunkX, chunkZ));
    }

    /** Packs chunk coordinates into one map key (high 32 bits = chunkX). */
    public static long chunkKey(int chunkX, int chunkZ) {
        return (((long) chunkX) << 32) ^ (chunkZ & 0xFFFFFFFFL);
    }

    private CcoBlockReader readerFor(int chunkX, int chunkZ) {
        long key = chunkKey(chunkX, chunkZ);
        CcoBlockReader reader = readerCache.get(key);
        if (reader == null) {
            Chunk chunk = world.getChunkIfLoaded(chunkX, chunkZ);
            if (chunk != null) {
                reader = chunk.getBlockReader();
                readerCache.put(key, reader);
            }
        }
        return reader;
    }
}
