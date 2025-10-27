package com.openmason.ui.components.textureCreator.tools.movetool.state;

/**
 * Enumeration of possible drag states during transform operations.
 * Follows KISS principle - simple, clear state representation.
 *
 * @author Open Mason Team
 */
public enum DragState {
    /** No active drag operation */
    IDLE,

    /** Moving the selection by dragging interior or center handle */
    MOVING,

    /** Scaling from a corner handle with opposite corner as anchor */
    SCALING_CORNER,

    /** Stretching from an edge handle (one-axis scaling) */
    STRETCHING_EDGE,

    /** Rotating around center point */
    ROTATING
}
