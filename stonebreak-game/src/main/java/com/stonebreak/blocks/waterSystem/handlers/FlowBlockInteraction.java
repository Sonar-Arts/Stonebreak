package com.stonebreak.blocks.waterSystem.handlers;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.util.DropUtil;
import com.stonebreak.world.World;
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
        return blockType != null && blockType.isFlower();
    }

    public static void dropFragile(World world, int x, int y, int z, BlockType blockType) {
        if (!isFragile(blockType)) {
            return;
        }
        Vector3f dropPosition = new Vector3f(x + 0.5f, y + 0.1f, z + 0.5f);
        DropUtil.createBlockDrop(world, dropPosition, blockType);
    }

    public static boolean supportsSource(World world, int x, int y, int z) {
        if (y <= 0) {
            return true;
        }
        BlockType below = world.getBlockAt(x, y - 1, z);
        if (below == BlockType.WATER) {
            return true;
        }
        return below != BlockType.AIR && below != null && below.isSolid();
    }
}
