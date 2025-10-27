package com.openmason.ui.components.textureCreator.tools.selection;

import com.openmason.ui.components.textureCreator.canvas.PixelCanvas;
import com.openmason.ui.components.textureCreator.commands.DrawCommand;
import com.openmason.ui.components.textureCreator.selection.SelectionRegion;
import com.openmason.ui.components.textureCreator.tools.DrawingTool;

/**
 * Base controller for selection tools.
 * Implements Template Method pattern - delegates to SelectionStrategy for specific behavior.
 *
 * SOLID Principles:
 * - Single Responsibility: Coordinates selection tool lifecycle
 * - Open/Closed: Extensible via strategies without modification
 * - Dependency Inversion: Depends on SelectionStrategy abstraction
 *
 * Design Patterns:
 * - Strategy Pattern: Delegates to pluggable SelectionStrategy
 * - Template Method: Defines tool lifecycle, strategies implement specifics
 *
 * @author Open Mason Team
 */
public class SelectionToolController implements DrawingTool {

    private final SelectionStrategy strategy;

    /**
     * Creates a selection tool controller with the specified strategy.
     *
     * @param strategy The selection strategy to use (must not be null)
     * @throws IllegalArgumentException if strategy is null
     */
    public SelectionToolController(SelectionStrategy strategy) {
        if (strategy == null) {
            throw new IllegalArgumentException("Selection strategy cannot be null");
        }
        this.strategy = strategy;
    }

    @Override
    public void onMouseDown(int x, int y, int color, PixelCanvas canvas, DrawCommand command) {
        // Selection tools don't modify canvas directly
        // Delegate to strategy for selection creation
        strategy.onMouseDown(x, y);
    }

    @Override
    public void onMouseDrag(int x, int y, int color, PixelCanvas canvas, DrawCommand command) {
        // Update selection in progress
        strategy.onMouseDrag(x, y);
    }

    @Override
    public void onMouseUp(int color, PixelCanvas canvas, DrawCommand command) {
        // Finalize selection
        strategy.onMouseUp();
    }

    /**
     * Gets the created selection region.
     * Should be called after checking hasSelection().
     *
     * @return The created selection, or null if no selection available
     */
    public SelectionRegion getCreatedSelection() {
        return strategy.getSelection();
    }

    /**
     * Checks if a selection was created.
     *
     * @return true if a selection is available
     */
    public boolean hasSelection() {
        return strategy.hasSelection();
    }

    /**
     * Clears the selection created flag.
     * Should be called after reading the selection.
     */
    public void clearSelectionFlag() {
        strategy.clearSelection();
    }

    /**
     * Gets preview data for rendering during selection creation.
     *
     * @return Preview data, or null if no preview available
     */
    public SelectionPreview getPreview() {
        return strategy.getPreview();
    }

    @Override
    public void reset() {
        strategy.reset();
    }

    @Override
    public String getName() {
        return strategy.getName();
    }

    @Override
    public String getDescription() {
        return strategy.getDescription();
    }

    /**
     * Gets the underlying strategy (useful for strategy-specific operations).
     *
     * @return The selection strategy
     */
    public SelectionStrategy getStrategy() {
        return strategy;
    }
}
