package com.stonebreak.world.generation.trees;

import com.stonebreak.blocks.BlockType;

/**
 * Where a tree shape writes its blocks. {@link TreeBlockPlacer} is the production
 * implementation (batched chunk writes + mesh rebuilds); tests supply an in-memory
 * grid so generated shapes can be checked against invariants such as leaf-decay reach.
 */
@FunctionalInterface
public interface TreeBlockSink {

    void placeBlock(int worldX, int worldY, int worldZ, BlockType blockType);
}
