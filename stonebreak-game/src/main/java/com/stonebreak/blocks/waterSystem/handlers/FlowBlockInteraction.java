package com.stonebreak.blocks.waterSystem.handlers;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.util.DropUtil;
import com.stonebreak.world.World;
import com.stonebreak.world.operations.WorldConfiguration;
import org.joml.Vector3f;

/**
 * Minimal helper providing the handful of interactions water needs with other
 * blocks. The intent is to keep policy decisions (what can be displaced, what
 * drops items) in one place so the core simulation stays focused on flow.
 */
public final class FlowBlockInteraction {

    private FlowBlockInteraction() {
    }

    public static boolean canDisplace(BlockType blockType) {
        if (blockType == null) {
            return false;
        }
        return blockType == BlockType.AIR ||
               blockType == BlockType.WATER ||
               blockType == BlockType.SNOW ||
               isFragile(blockType);
    }

    public static boolean isFragile(BlockType blockType) {
        return blockType != null
                && (blockType.isFlower() || com.stonebreak.blocks.torch.TorchBlock.isTorch(blockType));
    }

    public static void dropFragile(World world, long x, long y, long z, BlockType blockType) {
        if (!isFragile(blockType)) {
            return;
        }
        org.joml.Vector3d dropPosition = new org.joml.Vector3d(x + 0.5, y + 0.1, z + 0.5);
        if (com.stonebreak.blocks.torch.TorchBlock.isTorch(blockType)) {
            // The torch drops its ITEM (SBO drops table), not the placed block.
            DropUtil.handleBlockBroken(world, dropPosition, blockType);
            return;
        }
        DropUtil.createBlockDrop(world, dropPosition, blockType);
    }

    public static boolean supportsSource(World world, long x, long y, long z) {
        if (y <= WorldConfiguration.MIN_Y) {
            return true;
        }
        if (!world.isLoaded(x, y - 1, z)) {
            return false;
        }
        BlockType below = world.getBlockAt(x, y - 1, z);
        if (below == BlockType.WATER) {
            return true;
        }
        return below != BlockType.AIR && below != null && below.isSolid();
    }
}
