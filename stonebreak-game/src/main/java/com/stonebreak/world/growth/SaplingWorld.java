package com.stonebreak.world.growth;

import com.openmason.engine.voxel.world.CubePos;
import com.stonebreak.blocks.BlockType;

/**
 * The world as {@link SaplingGrowthSystem} sees it. Production is {@link WorldSaplingWorld};
 * tests supply an in-memory grid. Block coordinates are {@code long} (#447).
 */
public interface SaplingWorld {

    BlockType getBlock(long x, long y, long z);

    /** Writes a block through the world's block-change funnel (replicated to clients). */
    void setBlock(long x, long y, long z, BlockType type);

    /**
     * True when every cell of the box {@code [x - radius, x + radius] x [y, y + height] x
     * [z - radius, z + radius]} is known (resident cube or known air): a tree grown there decides on
     * complete information.
     */
    boolean isAreaLoaded(long x, long y, long z, int radius, int height);

    /**
     * The sapling progress persisted with a cube ({@link SaplingGrowthSystem#encodeSection}), or null.
     * Production keeps it in the cube's blob section {@link SaplingGrowthSystem#BLOB_SECTION}.
     */
    byte[] readSaplings(CubePos cube);

    /** Stores (non-null) or clears (null) a cube's sapling progress; no-op for an unloaded cube. */
    void writeSaplings(CubePos cube, byte[] section);

    /** Called once by the system it serves (adapters register world hooks here). */
    default void attach(SaplingGrowthSystem system) {
    }
}
