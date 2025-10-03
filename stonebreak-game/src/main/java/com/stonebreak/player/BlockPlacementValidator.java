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
        return validatePlacement(blockPos, playerPos, blockType, true);
    }

    /**
     * Validates whether a block can be placed at the given position.
     *
     * @param blockPos The position to place the block
     * @param playerPos The current player position
     * @param blockType The type of block to place
     * @param playerOnGround Whether the player is currently on the ground
     * @return ValidationResult containing whether placement is valid and any adjustments needed
     */
    public PlacementValidationResult validatePlacement(Vector3i blockPos, Vector3f playerPos, BlockType blockType, boolean playerOnGround) {
        // Check if target position is valid for placement
        if (!isValidPlacementLocation(blockPos, blockType)) {
            return new PlacementValidationResult(false, PlacementValidationResult.FailureReason.INVALID_LOCATION);
        }


        // Check if block would intersect with player
        if (wouldIntersectWithPlayer(blockPos, playerPos, blockType, playerOnGround)) {
            return new PlacementValidationResult(false, PlacementValidationResult.FailureReason.PLAYER_COLLISION);
        }

        // Check if block has proper support (no floating blocks allowed)
        // Exception: Allow nerdpoling (placing blocks underneath while jumping)
        boolean hasSupport = hasAdjacentSolidBlock(blockPos);
        boolean isWaterReplacement = world.getBlockAt(blockPos.x, blockPos.y, blockPos.z) == BlockType.WATER;
        boolean isNerdpoling = !playerOnGround &&
                              (blockPos.y < playerPos.y) &&
                              (blockPos.y >= playerPos.y - 3.0f) &&
                              isHorizontallyAligned(blockPos, playerPos);

        if (!hasSupport && !isWaterReplacement && !isNerdpoling) {
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
        return wouldIntersectWithPlayer(blockPos, playerPos, blockType, true);
    }

    /**
     * Checks if placing a specific block type would intersect with the player.
     * Uses precise AABB (Axis-Aligned Bounding Box) collision detection.
     * Allows special case for placing blocks directly beneath player's feet when jumping.
     */
    public boolean wouldIntersectWithPlayer(Vector3i blockPos, Vector3f playerPos, BlockType blockType, boolean playerOnGround) {
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

        // Special case: Allow nerdpoling (placing blocks beneath feet) ONLY when jumping
        // If there's a collision, check if this could be a valid nerdpole placement
        if (hasCollision && !playerOnGround) {
            // Check if block is strictly underneath the player's feet (very close)
            // Block must be directly below the player and very close
            boolean isDirectlyUnderneath = (blockPos.y < playerPos.y) && // Block must be below player's feet
                                         (blockPos.y >= playerPos.y - 1.5f) && // Must be very close (within 1.5 blocks)
                                         collisionX && collisionZ; // Block overlaps with player horizontally

            if (isDirectlyUnderneath) {
                return false; // Allow placement
            }
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
     * Checks if a block position is horizontally aligned with the player (overlaps horizontally).
     */
    private boolean isHorizontallyAligned(Vector3i blockPos, Vector3f playerPos) {
        float halfPlayerWidth = PLAYER_WIDTH / 2;
        float pMinX = playerPos.x - halfPlayerWidth;
        float pMaxX = playerPos.x + halfPlayerWidth;
        float pMinZ = playerPos.z - halfPlayerWidth;
        float pMaxZ = playerPos.z + halfPlayerWidth;

        float bMinX = blockPos.x;
        float bMaxX = blockPos.x + 1.0f;
        float bMinZ = blockPos.z;
        float bMaxZ = blockPos.z + 1.0f;

        boolean overlapX = (pMinX < bMaxX) && (pMaxX > bMinX);
        boolean overlapZ = (pMinZ < bMaxZ) && (pMaxZ > bMinZ);

        return overlapX && overlapZ;
    }

    /**
     * Checks if this is a "clutch" block placement - placing a block near the player's feet while jumping.
     */
    private boolean isClutchPlacement(Vector3i blockPos, Vector3f playerPos, BlockType blockType) {
        // Player's physics bounding box for horizontal overlap check
        float halfPlayerWidth = PLAYER_WIDTH / 2;
        float pMinX = playerPos.x - halfPlayerWidth;
        float pMaxX = playerPos.x + halfPlayerWidth;
        float pMinZ = playerPos.z - halfPlayerWidth;
        float pMaxZ = playerPos.z + halfPlayerWidth;

        // Block's bounding box
        float bMinX = blockPos.x;
        float bMaxX = blockPos.x + 1.0f;
        float bMinZ = blockPos.z;
        float bMaxZ = blockPos.z + 1.0f;

        // Check horizontal overlap
        boolean overlapX = (pMinX < bMaxX) && (pMaxX > bMinX);
        boolean overlapZ = (pMinZ < bMaxZ) && (pMaxZ > bMinZ);

        // Check if block is near player's feet vertically (extremely permissive for testing)
        boolean nearFeetVertically = (blockPos.y >= playerPos.y - 5.0f) && (blockPos.y <= playerPos.y + 2.0f);


        return overlapX && overlapZ && nearFeetVertically;
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