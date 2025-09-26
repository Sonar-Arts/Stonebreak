package com.stonebreak.blocks.waterSystem.states;

/**
 * Defines the different states that water blocks can be in.
 * State management is critical for proper water behavior and rendering.
 */
public enum WaterState {

    /**
     * Source block that is not actively generating flows.
     * Occurs when source is surrounded or unable to flow.
     */
    STAGNANT,

    /**
     * Source block actively generating flows OR any flow block.
     * This includes both horizontal and vertical (falling) water.
     *
     * CRITICAL RULE: Flow blocks ALWAYS remain in FLOWING state.
     * Flow blocks never exit this state once assigned.
     */
    FLOWING;

    /**
     * Checks if this state represents active water movement.
     *
     * @return true if water is actively moving
     */
    public boolean isActive() {
        return this != STAGNANT;
    }

    /**
     * Checks if this state can transition to another state.
     * Flow blocks cannot change states.
     *
     * @param isFlowBlock true if this is a flow block
     * @return true if state transitions are allowed
     */
    public boolean canTransition(boolean isFlowBlock) {
        // Flow blocks always remain in FLOWING state
        if (isFlowBlock) {
            return false;
        }
        // Source blocks can transition between states
        return true;
    }

    /**
     * Gets the default state for a water type.
     *
     * @param isSource true if this is a source block
     * @return appropriate default state
     */
    public static WaterState getDefaultState(boolean isSource) {
        return isSource ? STAGNANT : FLOWING;
    }
}