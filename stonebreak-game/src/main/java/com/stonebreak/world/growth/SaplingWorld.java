package com.stonebreak.world.growth;

import com.stonebreak.blocks.BlockType;

/**
 * The world as {@link SaplingGrowthSystem} sees it. Production is {@link WorldSaplingWorld};
 * tests supply an in-memory grid.
 */
public interface SaplingWorld {

    BlockType getBlock(int x, int y, int z);

    /** Writes a block through the world's block-change funnel (replicated to clients). */
    void setBlock(int x, int y, int z, BlockType type);

    String getState(int x, int y, int z);

    void setState(int x, int y, int z, String state);

    /** True when every chunk within {@code radius} blocks of the column is resident. */
    boolean isAreaLoaded(int x, int z, int radius);
}
