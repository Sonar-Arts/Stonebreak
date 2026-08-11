package com.stonebreak.world.leaves;

import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.openmason.engine.voxel.cco.operations.CcoBlockReader;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.ServerMutationSinks;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.operations.WorldConfiguration;

/**
 * Production {@link LeafWorld} over a {@link World}: CCO block access with a
 * per-chunk reader cache, batched mesh dirtying (flushed once per logical tick,
 * spilling to border neighbors so seam-crossing canopy removal stays seamless),
 * and the server replication funnel for decay-driven block mutations.
 */
public final class WorldLeafWorld implements LeafWorld {

    private final World world;
    private final Map<Long, CcoBlockReader> readerCache = new ConcurrentHashMap<>();
    private final Set<Long> dirtyChunks = new HashSet<>();

    public WorldLeafWorld(World world) {
        this.world = Objects.requireNonNull(world, "world");
    }

    @Override
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

    @Override
    public boolean isLoaded(int x, int y, int z) {
        return y >= 0 && y < WorldConfiguration.WORLD_HEIGHT
            && chunkAt(x, z) != null;
    }

    @Override
    public void setBlock(int x, int y, int z, BlockType type) {
        Chunk chunk = chunkAt(x, z);
        if (chunk == null || y < 0 || y >= WorldConfiguration.WORLD_HEIGHT) {
            return;
        }
        BlockType previous = (BlockType) chunk.getBlock(
            Math.floorMod(x, WorldConfiguration.CHUNK_SIZE), y,
            Math.floorMod(z, WorldConfiguration.CHUNK_SIZE));
        if (previous == type) {
            return;
        }
        chunk.setBlock(Math.floorMod(x, WorldConfiguration.CHUNK_SIZE), y,
                       Math.floorMod(z, WorldConfiguration.CHUNK_SIZE), type);
        markDirty(x, z);
        markChanged(x, y, z, type);
    }

    @Override
    public void markChanged(int x, int y, int z, BlockType type) {
        markDirty(x, z);

        // Report the mutation to the integrated server's replication funnel
        // (installed on the headless server world only) so decay reaches clients
        // through the same per-section batches as player edits and water flow.
        ServerMutationSinks.BlockSink sink = world.serverSinks().blocks();
        if (sink != null) {
            sink.onServerBlockChange(x, y, z, type);
        }
    }

    /**
     * Flushes batched mesh rebuilds. Mesh only — decay is recomputable state; the
     * persistent block change already marks the chunk save-dirty via Chunk#setBlock.
     */
    @Override
    public void onTickComplete() {
        if (dirtyChunks.isEmpty()) {
            return;
        }
        for (long key : dirtyChunks) {
            int chunkX = (int) (key >> 32);
            int chunkZ = (int) key;
            if (world.getChunkIfLoaded(chunkX, chunkZ) != null) {
                world.triggerChunkRebuild(chunkX * WorldConfiguration.CHUNK_SIZE, 0,
                                          chunkZ * WorldConfiguration.CHUNK_SIZE);
            }
        }
        dirtyChunks.clear();
    }

    @Override
    public void onChunkUnloaded(int chunkX, int chunkZ) {
        readerCache.remove(chunkKey(chunkX, chunkZ));
    }

    /**
     * Marks the containing chunk dirty, plus adjacent chunks when the cell sits
     * on a border — canopy faces are shared across chunk seams, so a removal at
     * local x/z 0 or 15 changes the neighbor's mesh too.
     */
    private void markDirty(int x, int z) {
        int chunkX = Math.floorDiv(x, WorldConfiguration.CHUNK_SIZE);
        int chunkZ = Math.floorDiv(z, WorldConfiguration.CHUNK_SIZE);
        dirtyChunks.add(chunkKey(chunkX, chunkZ));

        int localX = Math.floorMod(x, WorldConfiguration.CHUNK_SIZE);
        int localZ = Math.floorMod(z, WorldConfiguration.CHUNK_SIZE);
        if (localX == 0) {
            dirtyChunks.add(chunkKey(chunkX - 1, chunkZ));
        } else if (localX == WorldConfiguration.CHUNK_SIZE - 1) {
            dirtyChunks.add(chunkKey(chunkX + 1, chunkZ));
        }
        if (localZ == 0) {
            dirtyChunks.add(chunkKey(chunkX, chunkZ - 1));
        } else if (localZ == WorldConfiguration.CHUNK_SIZE - 1) {
            dirtyChunks.add(chunkKey(chunkX, chunkZ + 1));
        }
    }

    private Chunk chunkAt(int x, int z) {
        return world.getChunkIfLoaded(Math.floorDiv(x, WorldConfiguration.CHUNK_SIZE),
                                      Math.floorDiv(z, WorldConfiguration.CHUNK_SIZE));
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

    private static long chunkKey(int chunkX, int chunkZ) {
        return (((long) chunkX) << 32) ^ (chunkZ & 0xFFFFFFFFL);
    }
}
