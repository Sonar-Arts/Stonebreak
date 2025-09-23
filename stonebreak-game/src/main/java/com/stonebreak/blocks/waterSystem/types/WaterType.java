package com.stonebreak.blocks.waterSystem.types;

/**
 * Defines the fundamental behavior and properties of different water types.
 * Implements the core distinction between source blocks and flow blocks
 * as specified in the water rules.
 */
public interface WaterType {

    /**
     * Gets the water depth value for this type.
     *
     * @return Depth value (0 = source, 1-7 = flowing)
     */
    int getDepth();

    /**
     * Determines if this water type can generate flow to adjacent blocks.
     *
     * @return true if this water can create flow blocks
     */
    boolean canGenerateFlow();

    /**
     * Determines if this water type can create new source blocks
     * under collision conditions.
     *
     * @return true if this water can create source blocks when colliding
     */
    boolean canCreateSource();

    /**
     * Checks if this is a source type water block.
     *
     * @return true if this is any variant of source water
     */
    boolean isSource();

    /**
     * Gets the flow pressure this water type generates.
     * Source blocks generate pressure 7, flowing water generates based on depth.
     *
     * @return Flow pressure value (0-7)
     */
    int getFlowPressure();
}