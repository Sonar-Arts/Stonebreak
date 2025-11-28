package com.openmason.ui.textureCreator.tools.selection;

import com.openmason.ui.textureCreator.selection.SelectionRegion;
import com.openmason.ui.textureCreator.tools.DrawingTool;

/**
 * Interface for selection tools that create selection regions on the canvas.
 * Extends DrawingTool with selection-specific capabilities.
 *
 * SOLID Principles:
 * - Interface Segregation: Adds only selection-specific methods to DrawingTool
 * - Open/Closed: New selection tool types can be added without modifying existing code
 *
 * Design Pattern: Template Method (implemented in AbstractSelectionTool)
 *
 * @author Open Mason Team
 */
public interface SelectionTool extends DrawingTool {

    /**
     * Gets the created selection region.
     * Should be called after checking hasSelection().
     *
     * @return The created selection, or null if no selection available
     */
    SelectionRegion getSelection();

    /**
     * Checks if a selection was created.
     * This includes both new selections and explicit clear (single-point click).
     *
     * @return true if selection state changed
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
     *
     * @return Preview data object, or null if no preview available
     */
    SelectionPreview getPreview();
}
