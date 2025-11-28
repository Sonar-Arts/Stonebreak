package com.openmason.main.systems.viewport.state;

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
     * Render editable block models (.OMO files).
     */
    BLOCK_MODEL,

    /**
     * Render blocks using CBR API.
     */
    BLOCK,

    /**
     * Render voxelized items.
     */
    ITEM
}
