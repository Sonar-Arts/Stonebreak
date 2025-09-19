package com.stonebreak.player;

import org.joml.Vector3f;
import org.joml.Vector3i;
import com.stonebreak.blocks.BlockType;

/**
 * Interface for block placement validation services.
 * Follows Dependency Inversion Principle (SOLID) by depending on abstractions.
 */
public interface IBlockPlacementService {

    /**
     * Validates whether a block can be placed at the given position.
     *
     * @param blockPos The position to place the block
     * @param playerPos The current player position
     * @param blockType The type of block to place
     * @return ValidationResult containing whether placement is valid and any adjustments needed
     */
    BlockPlacementValidator.PlacementValidationResult validatePlacement(Vector3i blockPos, Vector3f playerPos, BlockType blockType);

    /**
     * Checks if a block position would intersect with the player at the given position.
     *
     * @param blockPos The block position to check
     * @param playerPos The player position
     * @return true if intersection would occur
     */
    boolean wouldIntersectWithPlayer(Vector3i blockPos, Vector3f playerPos);

    /**
     * Checks if placing a specific block type would intersect with the player.
     *
     * @param blockPos The block position to check
     * @param playerPos The player position
     * @param blockType The type of block to place
     * @return true if intersection would occur
     */
    boolean wouldIntersectWithPlayer(Vector3i blockPos, Vector3f playerPos, BlockType blockType);

    /**
     * Checks if a block position has proper support for placement.
     *
     * @param blockPos The position to check
     * @return true if the position has adequate support
     */
    boolean hasProperSupport(Vector3i blockPos);
}