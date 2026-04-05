package com.stonebreak.world.chunk.api.voxel;

import com.openmason.engine.voxel.IBlockType;
import com.stonebreak.blocks.BlockType;

/**
 * Adapts the game's {@link BlockType} enum to the engine's {@link IBlockType} interface.
 *
 * <p>Wraps each enum constant so engine systems can operate on blocks
 * without depending on stonebreak-game's BlockType directly.
 */
public record BlockTypeAdapter(BlockType blockType) implements IBlockType {

    @Override
    public int getId() {
        return blockType.getId();
    }

    @Override
    public String getName() {
        return blockType.getName();
    }

    @Override
    public boolean isSolid() {
        return blockType.isSolid();
    }

    @Override
    public boolean isBreakable() {
        return blockType.isBreakable();
    }

    @Override
    public boolean isTransparent() {
        return blockType.isTransparent();
    }

    @Override
    public boolean isAir() {
        return blockType == BlockType.AIR;
    }

    /** Unwrap back to the game BlockType. */
    public BlockType unwrap() {
        return blockType;
    }
}
