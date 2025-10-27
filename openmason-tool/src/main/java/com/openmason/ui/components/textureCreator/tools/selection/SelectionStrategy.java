package com.openmason.ui.components.textureCreator.tools.selection;

import com.openmason.ui.components.textureCreator.selection.SelectionRegion;

/**
 * Strategy interface for different selection creation methods.
 * Implements the Strategy pattern for extensible selection tools.
 *
 * SOLID: Interface Segregation - minimal contract for selection strategies
 * Open/Closed: New strategies can be added without modifying existing code
 *
 * Examples: Rectangle drag, Free-form paint, Ellipse, Magic Wand
 *
 * @author Open Mason Team
 */
public interface SelectionStrategy {

    /**
     * Handles mouse down event - starts selection creation.
     *
     * @param x Canvas x-coordinate
     * @param y Canvas y-coordinate
     */
    void onMouseDown(int x, int y);

    /**
     * Handles mouse drag event - updates selection in progress.
     *
     * @param x Canvas x-coordinate
     * @param y Canvas y-coordinate
     */
    void onMouseDrag(int x, int y);

    /**
     * Handles mouse up event - finalizes selection creation.
     * After this call, hasSelection() should return true if a selection was created.
     */
    void onMouseUp();

    /**
     * Checks if this strategy has created a selection ready to be retrieved.
     *
     * @return true if a selection is available via getSelection()
     */
    boolean hasSelection();

    /**
     * Gets the created selection region.
     * Should be called after hasSelection() returns true.
     *
     * @return The created selection, or null if no selection available
     */
    SelectionRegion getSelection();

    /**
     * Clears the selection created flag.
     * Called by controller after reading the selection.
     */
    void clearSelection();

    /**
     * Gets preview data for rendering during selection creation.
     * The specific data structure depends on the strategy implementation.
     *
     * @return Preview data object, or null if no preview available
     */
    SelectionPreview getPreview();

    /**
     * Resets the strategy state.
     * Called when canceling operations or switching tools.
     */
    void reset();

    /**
     * Gets the name of this selection strategy.
     *
     * @return Strategy display name
     */
    String getName();

    /**
     * Gets the description of this selection strategy.
     *
     * @return Strategy description for tooltips
     */
    String getDescription();
}
