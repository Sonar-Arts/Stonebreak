package com.stonebreak.world.leaves;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.operations.WorldConfiguration;

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

    /**
     * Writes a block (AIR when a leaf decays). The production adapter routes
     * this through the world's block-change funnel, so co-simulations (water),
     * meshing and replication all observe the removal; called on the sim tick
     * thread.
     */
    void setBlock(int x, int y, int z, BlockType type);

    /**
     * Exclusive upper Y bound worth scanning for foliage in a column, for the
     * chunk-load rescan. Everything above the tallest tree a column can hold is
     * open sky, and reading it back block by block costs the loading thread real
     * time on a tall world — this world is 1024 blocks deep, four times what the
     * rescan was originally written against.
     *
     * <p>Only the top is bounded, never the bottom: the rescan still sweeps from
     * y=0, so foliage below the surface (a cave, or anything a player built there)
     * is found exactly as before. The default scans the whole column.
     */
    default int foliageScanTop(int x, int z) {
        return WorldConfiguration.WORLD_HEIGHT;
    }

    /** Called once after each logical sim tick — adapters flush batched work here. */
    default void onTickComplete() {
    }

    /** Chunk eviction hook so adapters can drop per-chunk caches. */
    default void onChunkUnloaded(int chunkX, int chunkZ) {
    }
}
