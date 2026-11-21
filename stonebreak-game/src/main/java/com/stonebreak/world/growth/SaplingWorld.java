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

    /** Called once by the blueberry bush system it serves (adapters register world hooks here). */
    default void attach(BlueberryBushGrowthSystem system) {
    }

    /** The block-state string at a cell, or null. */
    default String getState(long x, long y, long z) {
        return null;
    }

    /** Writes a cell's block-state string (null clears it). */
    default void setState(long x, long y, long z, String state) {
    }

    /** A named blob section persisted with a cube, or null. */
    default byte[] readSection(CubePos cube, String section) {
        return null;
    }

    /** Stores (non-null) or clears (null) a named blob section of a cube; no-op for an unloaded cube. */
    default void writeSection(CubePos cube, String section, byte[] bytes) {
    }
}
