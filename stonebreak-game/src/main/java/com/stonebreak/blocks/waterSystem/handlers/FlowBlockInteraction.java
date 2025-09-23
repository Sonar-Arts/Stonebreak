package com.stonebreak.blocks.waterSystem.handlers;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.core.Game;
import com.stonebreak.util.DropUtil;
import com.stonebreak.world.World;
import org.joml.Vector3f;
import org.joml.Vector3i;

/**
 * Handles interactions between flowing water and other blocks.
 * Implements the rule that flow blocks can break flower blocks
 * and other destructible blocks.
 */
public class FlowBlockInteraction {

    /**
     * Determines if flowing water can destroy a block at the given position.
     *
     * @param blockType The type of block to check
     * @return true if the block can be destroyed by water flow
     */
    public static boolean canFlowDestroy(BlockType blockType) {
        if (blockType == null) {
            return false;
        }

        // Flow blocks can destroy flower blocks
        if (blockType.isFlower()) {
            return true;
        }

        // Add other destructible blocks here in future phases
        // For Phase 1, only flowers are destructible
        return false;
    }

    /**
     * Attempts to destroy a block at the given position due to water flow.
     * This method handles the actual destruction and any item dropping.
     *
     * @param position Position of the block to destroy
     * @param world The world instance
     * @return true if the block was successfully destroyed
     */
    public static boolean destroyBlock(Vector3i position, World world) {
        if (world == null) {
            return false;
        }

        BlockType blockType = world.getBlockAt(position.x, position.y, position.z);
        if (!canFlowDestroy(blockType)) {
            return false;
        }

        // Create item drops before destroying the block
        if (blockType.isFlower()) {
            // Convert block position to world position for drop
            Vector3f dropPosition = new Vector3f(
                position.x + 0.5f,
                position.y + 0.1f,
                position.z + 0.5f
            );

            // Drop the flower as an item
            DropUtil.createBlockDrop(world, dropPosition, blockType);
        }

        // Destroy the block by setting it to air
        world.setBlockAt(position.x, position.y, position.z, BlockType.AIR);

        return true;
    }

    /**
     * Checks if water can flow into a position, potentially destroying blocks.
     *
     * @param position Position to check
     * @param world The world instance
     * @return true if water can flow to this position
     */
    public static boolean canFlowTo(Vector3i position, World world) {
        if (world == null) {
            return false;
        }

        BlockType blockType = world.getBlockAt(position.x, position.y, position.z);

        // Water can flow to air spaces
        if (blockType == BlockType.AIR) {
            return true;
        }

        // Water can flow to existing water
        if (blockType == BlockType.WATER) {
            return true;
        }

        // Water can flow to destructible blocks (flowers)
        return canFlowDestroy(blockType);
    }

    /**
     * Attempts to flow water to a position, destroying any destructible blocks.
     *
     * @param position Position to flow to
     * @param world The world instance
     * @return true if water successfully flowed to the position
     */
    public static boolean flowTo(Vector3i position, World world) {
        if (!canFlowTo(position, world)) {
            return false;
        }

        BlockType currentBlock = world.getBlockAt(position.x, position.y, position.z);

        // If there's a destructible block, destroy it first
        if (canFlowDestroy(currentBlock)) {
            if (!destroyBlock(position, world)) {
                return false;
            }
        }

        // Water can now flow to this position
        // Note: The actual water placement is handled by other components
        return true;
    }

    /**
     * Gets a user-friendly description of why a block can be destroyed by water.
     *
     * @param blockType The block type to check
     * @return A description string, or null if the block cannot be destroyed
     */
    public static String getDestroyReason(BlockType blockType) {
        if (blockType == null) {
            return null;
        }

        if (blockType.isFlower()) {
            return "Flowers are fragile and can be washed away by water flow";
        }

        return null;
    }

    /**
     * Checks if a block type is considered fragile (easily destroyed by water).
     * This is a more general category than just flowers.
     *
     * @param blockType The block type to check
     * @return true if the block is fragile
     */
    public static boolean isFragile(BlockType blockType) {
        if (blockType == null) {
            return false;
        }

        // Currently only flowers are fragile
        // Future phases may add more fragile block types
        return blockType.isFlower();
    }
}