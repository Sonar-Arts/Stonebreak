package com.openmason.ui.components.textureCreator.tools.move;

/**
 * Identifiers for transformation handles rendered by the move tool.
 *
 * Interaction model:
 * - 8 scaling handles (corners + edges)
 * - 1 dedicated rotation handle above selection
 * - Center pivot point for rotation reference
 * - Translation by clicking inside the selection
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

    // Dedicated rotation handle (positioned above selection)
    ROTATE,

    // Center pivot point (visible but not draggable in current implementation)
    PIVOT_CENTER
}
