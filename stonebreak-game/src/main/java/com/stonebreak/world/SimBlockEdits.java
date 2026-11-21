package com.stonebreak.world;

import com.stonebreak.blocks.BlockType;

/**
 * A block change the simulation makes on the authoritative world — leaves decaying, a sapling
 * growing, a goblin digging — written through the world's funnel and reported to the server's
 * replication sink, so clients see it in the same per-section batches as player edits.
 */
public final class SimBlockEdits {

    private SimBlockEdits() {
    }

    /** Sets the block; false when its chunk is not loaded. */
    public static boolean set(World world, int x, int y, int z, BlockType type) {
        if (!world.setBlockAt(x, y, z, type)) {
            return false;
        }
        ServerMutationSinks.BlockSink sink = world.serverSinks().blocks();
        if (sink != null) {
            sink.onServerBlockChange(x, y, z, type);
        }
        return true;
    }

    /**
     * Sets the block and then its state string (a spiked wall's lean), replicating both: the
     * state goes out behind the block batch, like a player placement's echo. A {@code null}
     * state is just {@link #set(World, int, int, int, BlockType)}.
     */
    public static boolean set(World world, int x, int y, int z, BlockType type, String state) {
        if (!set(world, x, y, z, type)) {
            return false;
        }
        if (state != null) {
            world.setBlockStateAt(x, y, z, state);
            ServerMutationSinks.StateSink sink = world.serverSinks().states();
            if (sink != null) {
                sink.onServerStateChange(x, y, z);
            }
        }
        return true;
    }
}
