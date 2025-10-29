package com.openmason.ui.components.textureCreator.tools.selection;

import com.openmason.ui.components.textureCreator.canvas.PixelCanvas;
import com.openmason.ui.components.textureCreator.commands.DrawCommand;
import com.openmason.ui.components.textureCreator.selection.SelectionRegion;

/**
 * Abstract base class for selection tools.
 * Implements Template Method pattern - provides common selection state management,
 * subclasses implement specific selection creation logic.
 *
 * SOLID Principles:
 * - Single Responsibility: Manages common selection state and lifecycle
 * - Template Method: Defines the skeleton, subclasses fill in specifics
 * - DRY: Common selection logic implemented once
 *
 * Subclasses must implement:
 * - createSelectionFromDrag() - creates selection from mouse drag operation
 * - createPreview() - creates preview data for rendering during drag
 * - getToolName() - returns tool-specific name
 * - getToolDescription() - returns tool-specific description
 *
 * @author Open Mason Team
 */
public abstract class AbstractSelectionTool implements SelectionTool {

    // Selection state
    protected int startX = -1;
    protected int startY = -1;
    protected int currentX = -1;
    protected int currentY = -1;

    // Selection result
    private SelectionRegion createdSelection = null;
    private boolean hasSelection = false;

    // Drag state
    private boolean isDragging = false;

    @Override
    public final void onMouseDown(int x, int y, int color, PixelCanvas canvas, DrawCommand command) {
        // Start new selection
        startX = x;
        startY = y;
        currentX = x;
        currentY = y;
        isDragging = true;
        hasSelection = false;
        createdSelection = null;

        // Allow subclasses to perform additional initialization
        onSelectionStart(x, y);
    }

    @Override
    public final void onMouseDrag(int x, int y, int color, PixelCanvas canvas, DrawCommand command) {
        if (!isDragging) {
            return;
        }

        // Update current position
        currentX = x;
        currentY = y;

        // Allow subclasses to update during drag
        onSelectionUpdate(x, y);
    }

    @Override
    public final void onMouseUp(int color, PixelCanvas canvas, DrawCommand command) {
        if (!isDragging) {
            return;
        }

        isDragging = false;

        // Finalize selection
        if (startX != -1 && startY != -1) {
            // Calculate drag distance
            int width = Math.abs(currentX - startX);
            int height = Math.abs(currentY - startY);

            if (width > 0 || height > 0) {
                // Create selection from drag
                createdSelection = createSelectionFromDrag(startX, startY, currentX, currentY);
                hasSelection = true;
            } else {
                // Single point click - clear selection
                createdSelection = null;
                hasSelection = true; // Flag that selection state changed (cleared)
            }
        }

        // Allow subclasses to finalize
        onSelectionEnd();
    }

    @Override
    public final SelectionRegion getSelection() {
        return createdSelection;
    }

    @Override
    public final boolean hasSelection() {
        return hasSelection;
    }

    @Override
    public final void clearSelection() {
        hasSelection = false;
    }

    @Override
    public final SelectionPreview getPreview() {
        // Return preview if currently dragging
        if (isDragging && startX != -1 && startY != -1) {
            return createPreview(startX, startY, currentX, currentY);
        }
        return null;
    }

    @Override
    public void reset() {
        startX = -1;
        startY = -1;
        currentX = -1;
        currentY = -1;
        createdSelection = null;
        hasSelection = false;
        isDragging = false;
    }

    @Override
    public final String getName() {
        return getToolName();
    }

    @Override
    public final String getDescription() {
        return getToolDescription();
    }

    // Template methods for subclasses

    /**
     * Creates a selection region from the completed drag operation.
     * Called when mouse is released after dragging.
     *
     * @param startX Start x-coordinate
     * @param startY Start y-coordinate
     * @param endX   End x-coordinate
     * @param endY   End y-coordinate
     * @return The created selection region
     */
    protected abstract SelectionRegion createSelectionFromDrag(int startX, int startY, int endX, int endY);

    /**
     * Creates preview data for rendering during drag.
     * Called during mouse drag to provide visual feedback.
     *
     * @param startX Start x-coordinate
     * @param startY Start y-coordinate
     * @param endX   Current x-coordinate
     * @param endY   Current y-coordinate
     * @return Preview data for rendering
     */
    protected abstract SelectionPreview createPreview(int startX, int startY, int endX, int endY);

    /**
     * Gets the tool-specific name.
     *
     * @return Tool display name
     */
    protected abstract String getToolName();

    /**
     * Gets the tool-specific description.
     *
     * @return Tool description for tooltips
     */
    protected abstract String getToolDescription();

    // Optional hooks for subclasses

    /**
     * Called when selection starts (mouse down).
     * Subclasses can override for additional initialization.
     *
     * @param x Start x-coordinate
     * @param y Start y-coordinate
     */
    protected void onSelectionStart(int x, int y) {
        // Default: do nothing
    }

    /**
     * Called during selection update (mouse drag).
     * Subclasses can override for additional updates.
     *
     * @param x Current x-coordinate
     * @param y Current y-coordinate
     */
    protected void onSelectionUpdate(int x, int y) {
        // Default: do nothing
    }

    /**
     * Called when selection ends (mouse up).
     * Subclasses can override for cleanup.
     */
    protected void onSelectionEnd() {
        // Default: do nothing
    }

    // Protected helper methods

    /**
     * Checks if currently dragging.
     *
     * @return true if drag in progress
     */
    protected final boolean isDragging() {
        return isDragging;
    }
}
