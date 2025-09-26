package com.stonebreak.blocks.waterSystem.states;

import com.stonebreak.blocks.waterSystem.WaterBlock;
import org.joml.Vector3i;

/**
 * Manages water block state transitions and validation.
 * Enforces state permanence rules and proper transitions.
 */
public interface WaterStateManager {

    /**
     * Determines the appropriate state for a water block based on its
     * type, position, and surrounding conditions.
     *
     * @param waterBlock The water block to analyze
     * @param position Position of the water block
     * @return Appropriate water state
     */
    WaterState determineState(WaterBlock waterBlock, Vector3i position);

    /**
     * Updates the state of a water block, enforcing state transition rules.
     *
     * @param waterBlock The water block to update
     * @param newState The desired new state
     * @param position Position of the water block
     * @return true if state was updated, false if transition was invalid
     */
    boolean updateState(WaterBlock waterBlock, WaterState newState, Vector3i position);

    /**
     * Validates if a state transition is allowed for the given water block.
     *
     * @param waterBlock The water block
     * @param currentState Current state
     * @param newState Desired new state
     * @return true if transition is valid
     */
    boolean canTransition(WaterBlock waterBlock, WaterState currentState, WaterState newState);

    /**
     * Gets the default state for a newly created water block.
     *
     * @param waterBlock The water block
     * @return Appropriate default state
     */
    WaterState getDefaultState(WaterBlock waterBlock);


}