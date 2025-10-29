package com.openmason.ui.components.textureCreator.tools.move;

/**
 * Identifiers for transformation handles rendered by the move tool.
 *
 * Follows Photoshop-style interaction:
 * - 8 scaling handles (corners + edges)
 * - Rotation triggered by hovering outside corners
 * - Center pivot point for rotation reference
 * - Translation by clicking inside the selection
 */
public enum TransformHandle {
    NONE,

    // Corner scaling (uniform/stretch depending on modifier keys)
    // Hovering slightly outside these triggers rotation mode
    SCALE_NORTH_WEST,
    SCALE_NORTH_EAST,
    SCALE_SOUTH_EAST,
    SCALE_SOUTH_WEST,

    // Edge scaling / stretching
    SCALE_NORTH,
    SCALE_EAST,
    SCALE_SOUTH,
    SCALE_WEST,

    // Center pivot point (visible but not draggable in current implementation)
    PIVOT_CENTER
}
