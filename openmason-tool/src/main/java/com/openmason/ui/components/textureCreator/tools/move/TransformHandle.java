package com.openmason.ui.components.textureCreator.tools.move;

/**
 * Identifiers for transformation handles rendered by the move tool.
 *
 * Handles map to the classic 8 scaling grips plus 4 rotation grips located on
 * the midpoints of each edge. Translation is handled by clicking inside the
 * transformed selection region and therefore does not require a dedicated
 * handle type.
 */
public enum TransformHandle {
    NONE,

    // Corner scaling (uniform/stretch depending on modifier keys)
    SCALE_NORTH_WEST,
    SCALE_NORTH_EAST,
    SCALE_SOUTH_EAST,
    SCALE_SOUTH_WEST,

    // Edge scaling / stretching
    SCALE_NORTH,
    SCALE_EAST,
    SCALE_SOUTH,
    SCALE_WEST,

    // Rotation handles positioned outside the bounding box
    ROTATE_NORTH,
    ROTATE_EAST,
    ROTATE_SOUTH,
    ROTATE_WEST
}
