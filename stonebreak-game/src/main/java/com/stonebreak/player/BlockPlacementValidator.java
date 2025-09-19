package com.stonebreak.player;

import org.joml.Vector3f;
import org.joml.Vector3i;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.World;

/**
 * Handles validation and collision detection for block placement operations.
 * Follows SOLID principles by separating block placement concerns from Player class.
 */
public class BlockPlacementValidator implements IBlockPlacementService {

    // Constants for player collision detection
    private static final float PLAYER_HEIGHT = 1.8f;
    private static final float PLAYER_WIDTH = 0.6f;

    private final World world;

    /**
     * Creates a new BlockPlacementValidator for the given world.
     *
     * @param world The world to validate placements in
     */
    public BlockPlacementValidator(World world) {
        this.world = world;
    }

    /**
     * Validates whether a block can be placed at the given position.
     *
     * @param blockPos The position to place the block
     * @param playerPos The current player position
     * @param blockType The type of block to place
     * @return ValidationResult containing whether placement is valid and any adjustments needed
     */
    public PlacementValidationResult validatePlacement(Vector3i blockPos, Vector3f playerPos, BlockType blockType) {
        // Check if target position is valid for placement
        if (!isValidPlacementLocation(blockPos, blockType)) {
            return new PlacementValidationResult(false, PlacementValidationResult.FailureReason.INVALID_LOCATION);
        }

        // Check if block would intersect with player
        if (wouldIntersectWithPlayer(blockPos, playerPos, blockType)) {
            return new PlacementValidationResult(false, PlacementValidationResult.FailureReason.PLAYER_COLLISION);
        }

        // Check if block has proper support (for non-water placements)
        if (!hasAdjacentSolidBlock(blockPos) && world.getBlockAt(blockPos.x, blockPos.y, blockPos.z) != BlockType.WATER) {
            return new PlacementValidationResult(false, PlacementValidationResult.FailureReason.NO_SUPPORT);
        }

        // All checks passed
        return new PlacementValidationResult(true);
    }

    /**
     * Checks if the target location is valid for block placement.
     */
    private boolean isValidPlacementLocation(Vector3i blockPos, BlockType blockType) {
        BlockType existingBlock = world.getBlockAt(blockPos.x, blockPos.y, blockPos.z);

        // Can place in air or water (replacing water)
        if (existingBlock == BlockType.AIR || existingBlock == BlockType.WATER) {
            return true;
        }

        // Special case: snow stacking
        if (blockType == BlockType.SNOW && existingBlock == BlockType.SNOW) {
            return world.getSnowLayers(blockPos.x, blockPos.y, blockPos.z) < 8;
        }

        return false;
    }

    /**
     * Checks if a block at the specified position would intersect with the player.
     * Uses precise AABB (Axis-Aligned Bounding Box) collision detection.
     */
    @Override
    public boolean wouldIntersectWithPlayer(Vector3i blockPos, Vector3f playerPos) {
        return wouldIntersectWithPlayer(blockPos, playerPos, null);
    }

    /**
     * Checks if placing a specific block type would intersect with the player.
     * Uses precise AABB (Axis-Aligned Bounding Box) collision detection.
     */
    @Override
    public boolean wouldIntersectWithPlayer(Vector3i blockPos, Vector3f playerPos, BlockType blockType) {
        // Player's physics bounding box
        float halfPlayerWidth = PLAYER_WIDTH / 2;
        float pMinX = playerPos.x - halfPlayerWidth;
        float pMaxX = playerPos.x + halfPlayerWidth;
        float pMinY = playerPos.y; // Player's feet
        float pMaxY = playerPos.y + PLAYER_HEIGHT; // Player's head
        float pMinZ = playerPos.z - halfPlayerWidth;
        float pMaxZ = playerPos.z + halfPlayerWidth;

        // Block's bounding box for the block we're trying to place
        float blockHeight = 1.0f; // Default to full block height

        // Handle special block types with different collision heights
        if (blockType != null) {
            if (blockType == BlockType.SNOW) {
                // Snow blocks start with 1 layer (1/8 block height)
                blockHeight = 0.125f;
            } else {
                // For other blocks, use their standard collision height
                blockHeight = blockType.getCollisionHeight();
            }
        }

        float bMinX = blockPos.x;
        float bMaxX = blockPos.x + 1.0f;
        float bMinY = blockPos.y;
        float bMaxY = blockPos.y + blockHeight;
        float bMinZ = blockPos.z;
        float bMaxZ = blockPos.z + 1.0f;

        // AABB collision check
        boolean collisionX = (pMinX < bMaxX) && (pMaxX > bMinX);
        boolean collisionY = (pMinY < bMaxY) && (pMaxY > bMinY);
        boolean collisionZ = (pMinZ < bMaxZ) && (pMaxZ > bMinZ);

        boolean hasCollision = collisionX && collisionY && collisionZ;

        // Debug logging for troubleshooting
        if (hasCollision) {
            System.out.println("DEBUG: Block placement collision detected at " + blockPos +
                             " with block height " + blockHeight +
                             " (player at " + String.format("%.2f,%.2f,%.2f", playerPos.x, playerPos.y, playerPos.z) + ")");
        }

        return hasCollision;
    }


    /**
     * Checks if a block position has at least one adjacent solid block.
     */
    @Override
    public boolean hasProperSupport(Vector3i blockPos) {
        return hasAdjacentSolidBlock(blockPos);
    }

    /**
     * Internal method to check if a block position has at least one adjacent solid block.
     */
    private boolean hasAdjacentSolidBlock(Vector3i blockPos) {
        Vector3i[] adjacentPositions = {
            new Vector3i(blockPos.x, blockPos.y + 1, blockPos.z), // Above
            new Vector3i(blockPos.x, blockPos.y - 1, blockPos.z), // Below
            new Vector3i(blockPos.x + 1, blockPos.y, blockPos.z), // East
            new Vector3i(blockPos.x - 1, blockPos.y, blockPos.z), // West
            new Vector3i(blockPos.x, blockPos.y, blockPos.z + 1), // North
            new Vector3i(blockPos.x, blockPos.y, blockPos.z - 1)  // South
        };

        for (Vector3i adjacentPos : adjacentPositions) {
            BlockType adjacentBlock = world.getBlockAt(adjacentPos.x, adjacentPos.y, adjacentPos.z);
            if (adjacentBlock.isSolid()) {
                return true;
            }
        }

        return false;
    }

    /**
     * Gets the effective collision height of a block at the given position.
     */
    private float getBlockCollisionHeight(int x, int y, int z) {
        BlockType block = world.getBlockAt(x, y, z);
        if (block == BlockType.SNOW) {
            return world.getSnowHeight(x, y, z);
        }
        return block.getCollisionHeight();
    }

    /**
     * Result of block placement validation.
     */
    public static class PlacementValidationResult {
        private final boolean canPlace;
        private final FailureReason failureReason;

        public PlacementValidationResult(boolean canPlace) {
            this.canPlace = canPlace;
            this.failureReason = null;
        }

        public PlacementValidationResult(boolean canPlace, FailureReason failureReason) {
            this.canPlace = canPlace;
            this.failureReason = failureReason;
        }

        public boolean canPlace() {
            return canPlace;
        }

        public FailureReason getFailureReason() {
            return failureReason;
        }

        public enum FailureReason {
            INVALID_LOCATION,
            PLAYER_COLLISION,
            NO_SUPPORT
        }
    }
}