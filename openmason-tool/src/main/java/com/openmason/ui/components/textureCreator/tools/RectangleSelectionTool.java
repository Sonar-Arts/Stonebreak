package com.openmason.ui.components.textureCreator.tools;

import com.openmason.ui.components.textureCreator.canvas.PixelCanvas;
import com.openmason.ui.components.textureCreator.commands.DrawCommand;
import com.openmason.ui.components.textureCreator.selection.RectangularSelection;
import com.openmason.ui.components.textureCreator.selection.SelectionRegion;

/**
 * Rectangle selection tool - creates rectangular selection regions.
 * Click and drag to create a new rectangular selection.
 * Use the Move tool to translate selections.
 *
 * Pattern: Similar to ColorPickerTool - stores results internally,
 * CanvasPanel reads them and updates state.
 *
 * SOLID: Single responsibility - handles selection creation only
 * KISS: Simple rectangular drag selection
 *
 * @author Open Mason Team
 */
public class RectangleSelectionTool implements DrawingTool {

    // Selection creation
    private int selectionStartX = -1;
    private int selectionStartY = -1;
    private int selectionEndX = -1;
    private int selectionEndY = -1;

    // Created selection (read by CanvasPanel)
    private SelectionRegion createdSelection = null;

    // Flag for CanvasPanel to read
    private boolean selectionCreatedThisDrag = false;

    @Override
    public void onMouseDown(int x, int y, int color, PixelCanvas canvas, DrawCommand command) {
        // Start new selection
        selectionStartX = x;
        selectionStartY = y;
        selectionEndX = x;
        selectionEndY = y;
        selectionCreatedThisDrag = false;
        createdSelection = null;
    }

    @Override
    public void onMouseDrag(int x, int y, int color, PixelCanvas canvas, DrawCommand command) {
        // Update selection bounds
        selectionEndX = x;
        selectionEndY = y;
    }

    @Override
    public void onMouseUp(int color, PixelCanvas canvas, DrawCommand command) {
        // Finalize selection
        if (selectionStartX != -1 && selectionStartY != -1) {
            // Only create selection if there's an actual area (not just a single point)
            int width = Math.abs(selectionEndX - selectionStartX);
            int height = Math.abs(selectionEndY - selectionStartY);

            if (width > 0 || height > 0) {
                createdSelection = new RectangularSelection(
                        selectionStartX, selectionStartY,
                        selectionEndX, selectionEndY
                );
                selectionCreatedThisDrag = true;
            } else {
                // Single point click - clear selection
                createdSelection = null;
                selectionCreatedThisDrag = true;
            }
        }
    }

    /**
     * Get the selection bounds for preview rendering.
     * Used by CanvasPanel to render selection preview during drag.
     *
     * @return array [startX, startY, endX, endY] or null if not selecting
     */
    public int[] getSelectionPreviewBounds() {
        if (selectionStartX != -1) {
            return new int[]{selectionStartX, selectionStartY, selectionEndX, selectionEndY};
        }
        return null;
    }

    /**
     * Get the created selection region.
     * CanvasPanel should read this after onMouseUp to update state.
     *
     * @return The created selection, or null if no selection was created
     */
    public SelectionRegion getCreatedSelection() {
        return createdSelection;
    }

    /**
     * Check if a selection was created during the last drag operation.
     * This includes both new selections and explicit clear (single-point click).
     *
     * @return true if selection state changed
     */
    public boolean wasSelectionCreated() {
        return selectionCreatedThisDrag;
    }

    /**
     * Clear the created selection flag.
     * Should be called by CanvasPanel after reading the selection.
     */
    public void clearSelectionCreatedFlag() {
        selectionCreatedThisDrag = false;
    }

    @Override
    public void reset() {
        selectionStartX = -1;
        selectionStartY = -1;
        selectionEndX = -1;
        selectionEndY = -1;
        createdSelection = null;
        selectionCreatedThisDrag = false;
    }

    @Override
    public String getName() {
        return "Rectangle Selection";
    }

    @Override
    public String getDescription() {
        return "Select rectangular regions";
    }
}
