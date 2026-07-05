package com.stonebreak.blocks.waterSystem;

import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.openmason.engine.voxel.cco.operations.CcoBlockReader;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.waterSystem.handlers.FlowBlockInteraction;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.operations.WorldConfiguration;

/**
 * Production {@link FlowWorld} over a {@link World}: CCO block access with a
 * per-chunk reader cache, chunk-owned water-layer state, batched mesh
 * dirtying (flushed once per logical tick, spilling to border neighbors so
 * corner-sewn water heights stay seamless), and the server replication funnel
 * for sim-driven block mutations.
 */
public final class WorldFlowWorld implements FlowWorld {

    private final World world;
    private final Map<Long, CcoBlockReader> readerCache = new ConcurrentHashMap<>();
    private final Set<Long> dirtyChunks = new HashSet<>();

    public WorldFlowWorld(World world) {
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
        chunk.setBlock(Math.floorMod(x, WorldConfiguration.CHUNK_SIZE), y,
                       Math.floorMod(z, WorldConfiguration.CHUNK_SIZE), type);
        markDirty(x, z);

        // Report the mutation to the integrated server's replication funnel
        // (installed on the headless server world only) so flow reaches clients
        // through the same per-section batches as player edits.
        World.ServerBlockMutationCallback sink = world.serverMutationCallback();
        if (sink != null) {
            sink.onServerBlockChange(x, y, z, type);
        }
    }

    @Override
    public int getWater(int x, int y, int z) {
        Chunk chunk = chunkAt(x, z);
        if (chunk == null) {
            return 0;
        }
        return chunk.getWaterLayer().get(Math.floorMod(x, WorldConfiguration.CHUNK_SIZE), y,
                                         Math.floorMod(z, WorldConfiguration.CHUNK_SIZE));
    }

    @Override
    public void setWater(int x, int y, int z, int value) {
        Chunk chunk = chunkAt(x, z);
        if (chunk == null) {
            return;
        }
        chunk.getWaterLayer().set(Math.floorMod(x, WorldConfiguration.CHUNK_SIZE), y,
                                  Math.floorMod(z, WorldConfiguration.CHUNK_SIZE), value);
    }

    @Override
    public boolean isSolid(int x, int y, int z) {
        BlockType block = getBlock(x, y, z);
        return block != null && block.isSolid();
    }

    @Override
    public void dropFragile(int x, int y, int z, BlockType type) {
        FlowBlockInteraction.dropFragile(world, x, y, z, type);
    }

    @Override
    public void markWaterChanged(int x, int y, int z, int newValue) {
        markDirty(x, z);

        // Report the layer change to the integrated server's water replication funnel
        // (installed on the headless server world only) so clients receive live flow
        // levels as BlockMetaS2C (KIND_WATER_LEVEL). Value 0 = removed / became source.
        World.ServerWaterMutationCallback sink = world.serverWaterCallback();
        if (sink != null) {
            sink.onServerWaterChange(x, y, z, newValue);
        }
    }

    /**
     * Flushes batched mesh rebuilds. Mesh only — water flow is recomputable
     * state and must not mark chunks save-dirty; real block changes already
     * do that through {@link Chunk#setBlock}.
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
     * on a border — water corner heights are sewn across chunk seams, so a
     * level change at local x/z 0 or 15 changes the neighbor's mesh too.
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
