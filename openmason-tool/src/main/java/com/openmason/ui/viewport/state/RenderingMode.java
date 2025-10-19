package com.openmason.ui.viewport.state;

/**
 * Enumeration of rendering modes for the viewport.
 * Each mode represents a different type of content to display.
 */
public enum RenderingMode {
    /**
     * Render 3D models (e.g., cow model with animations).
     */
    MODEL,

    /**
     * Render blocks using CBR API.
     */
    BLOCK,

    /**
     * Render voxelized items.
     */
    ITEM
}
