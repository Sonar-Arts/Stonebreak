package com.stonebreak.world.leaves;

import com.stonebreak.blocks.BlockType;

/**
 * The world surface the leaf-decay simulation runs against. {@link LeafDecaySystem}
 * contains only the scheduling and reachability rules; everything it observes or
 * mutates goes through this seam, so the algorithm is unit-testable over a flat
 * array (see FakeLeafWorld in tests) and the production adapter
 * ({@link WorldLeafWorld}) owns all Chunk/CCO/replication plumbing.
 */
public interface LeafWorld {

    /** Block at world coords; AIR outside the world or in unloaded chunks. */
    BlockType getBlock(int x, int y, int z);

    /** Whether the position is inside the world and its chunk is resident. */
    boolean isLoaded(int x, int y, int z);

    /** Writes a block (AIR when a leaf decays). */
    void setBlock(int x, int y, int z, BlockType type);

    /**
     * Notifies that the visible block at a position changed. Adapters use it for
     * mesh dirtying and replication; called on the sim tick thread.
     */
    void markChanged(int x, int y, int z, BlockType type);

    /** Called once after each logical sim tick — adapters flush batched work here. */
    default void onTickComplete() {
    }

    /** Chunk eviction hook so adapters can drop per-chunk caches. */
    default void onChunkUnloaded(int chunkX, int chunkZ) {
    }
}
