package com.openmason.main.systems.menus.textureCreator.tools.selection;

import com.openmason.main.systems.menus.textureCreator.selection.SelectionRegion;
import com.openmason.main.systems.menus.textureCreator.tools.DrawingTool;

/**
 * Interface for selection tools that create selection regions on the canvas.
 */
public interface SelectionTool extends DrawingTool {

    /**
     * Gets the created selection region.
     */
    SelectionRegion getSelection();

    /**
     * Checks if a selection was created.
     */
    boolean hasSelection();

    /**
     * Clears the selection created flag.
     * Should be called after reading the selection.
     */
    void clearSelection();

    /**
     * Gets preview data for rendering during selection creation.
     * Returns null if no preview is available.
     */
    SelectionPreview getPreview();
}
