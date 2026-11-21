package com.stonebreak.world.generation.trees;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.World;
import com.stonebreak.world.operations.WorldConfiguration;

/**
 * Block placer used by every tree shape (sapling growth; worldgen trees are native now, #446). Writes through
 * {@link World#setBlockRaw}: no per-block sim funnel, but layers cleared and physics invalidated per cell;
 * the stream re-meshes the touched cubes natively. Writes into unloaded cubes are dropped.
 */
public final class TreeBlockPlacer implements TreeBlockSink {

    private final World world;

    public TreeBlockPlacer(World world) {
        this.world = world;
    }

    @Override
    public void placeBlock(int worldX, int worldY, int worldZ, BlockType blockType) {
        if (!WorldConfiguration.inBounds(worldY)) return;
        world.setBlockRaw(worldX, worldY, worldZ, blockType);
    }

    /** Kept for callers: nothing is batched any more. */
    public void complete() {
    }
}
