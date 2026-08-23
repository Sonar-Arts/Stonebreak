package com.stonebreak.blocks;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The brick / stone-brick building blocks (SBO: {@code bricks_block} /
 * {@code stone_bricks}) are first-class BlockTypes and wire into the
 * pickaxe tool set via {@code ToolMiningRules}.
 */
class StoneBrickBlocksTest {

    @Test
    void brickBlockIsRegistered() {
        assertSame(BlockType.BRICKS_BLOCK, BlockType.getByObjectId("stonebreak:bricks_block"));
        assertEquals("Block of Bricks", BlockType.BRICKS_BLOCK.getName());
        assertTrue(BlockType.BRICKS_BLOCK.isSolid());
        assertTrue(BlockType.BRICKS_BLOCK.isBreakable());
        assertEquals(1.0f, BlockType.BRICKS_BLOCK.getHardness());
    }

    @Test
    void stoneBricksBlockIsRegistered() {
        assertSame(BlockType.STONE_BRICKS, BlockType.getByObjectId("stonebreak:stone_bricks"));
        assertEquals("Stone Bricks", BlockType.STONE_BRICKS.getName());
        assertTrue(BlockType.STONE_BRICKS.isSolid());
        assertTrue(BlockType.STONE_BRICKS.isBreakable());
        assertEquals(1.0f, BlockType.STONE_BRICKS.getHardness());
    }

    @Test
    void bothBlocksAreReachableViaGlobalRegistry() {
        boolean sawBricks = false;
        boolean sawStoneBricks = false;
        for (BlockType b : BlockType.values()) {
            if (b == BlockType.BRICKS_BLOCK) sawBricks = true;
            if (b == BlockType.STONE_BRICKS) sawStoneBricks = true;
        }
        assertTrue(sawBricks && sawStoneBricks, "brick blocks must appear in BlockType.values()");
    }
}
