package com.openmason.ui.components.textureCreator.tools.selection;

import com.openmason.ui.components.textureCreator.selection.RectangularSelection;
import com.openmason.ui.components.textureCreator.selection.SelectionRegion;

/**
 * Rectangle selection strategy - creates rectangular selections via click-and-drag.
 *
 * SOLID: Single Responsibility - handles rectangular selection creation only
 * KISS: Simple drag-to-create rectangle logic
 * DRY: Common selection logic delegated to controller
 *
 * @author Open Mason Team
 */
public class RectangleSelectionStrategy implements SelectionStrategy {

    // Selection bounds during drag
    private int startX = -1;
    private int startY = -1;
    private int endX = -1;
    private int endY = -1;

    // Created selection (read by controller)
    private SelectionRegion createdSelection = null;

    // Flag indicating selection was created/cleared
    private boolean hasSelection = false;

    @Override
    public void onMouseDown(int x, int y) {
        // Start new rectangular selection
        startX = x;
        startY = y;
        endX = x;
        endY = y;
        hasSelection = false;
        createdSelection = null;
    }

    @Override
    public void onMouseDrag(int x, int y) {
        // Update selection end point
        endX = x;
        endY = y;
    }

    @Override
    public void onMouseUp() {
        // Finalize selection
        if (startX != -1 && startY != -1) {
            // Calculate area
            int width = Math.abs(endX - startX);
            int height = Math.abs(endY - startY);

            if (width > 0 || height > 0) {
                // Create rectangular selection
                createdSelection = new RectangularSelection(startX, startY, endX, endY);
                hasSelection = true;
            } else {
                // Single point click - clear selection
                createdSelection = null;
                hasSelection = true; // Flag that selection state changed (cleared)
            }
        }
    }

    @Override
    public boolean hasSelection() {
        return hasSelection;
    }

    @Override
    public SelectionRegion getSelection() {
        return createdSelection;
    }

    @Override
    public void clearSelection() {
        hasSelection = false;
    }

    @Override
    public SelectionPreview getPreview() {
        // Return preview bounds if currently dragging
        if (startX != -1 && startY != -1) {
            return new RectanglePreview(startX, startY, endX, endY);
        }
        return null;
    }

    @Override
    public void reset() {
        startX = -1;
        startY = -1;
        endX = -1;
        endY = -1;
        createdSelection = null;
        hasSelection = false;
    }

    @Override
    public String getName() {
        return "Rectangle Selection";
    }

    @Override
    public String getDescription() {
        return "Click and drag to select rectangular regions";
    }
}
