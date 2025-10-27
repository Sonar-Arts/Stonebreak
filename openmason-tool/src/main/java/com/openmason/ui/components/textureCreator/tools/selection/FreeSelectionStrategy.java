package com.openmason.ui.components.textureCreator.tools.selection;

import com.openmason.ui.components.textureCreator.selection.FreeSelection;
import com.openmason.ui.components.textureCreator.selection.SelectionRegion;

import java.util.HashSet;
import java.util.Set;

/**
 * Free-form selection strategy - creates selections by painting with the mouse.
 * Click and drag to paint individual pixels into the selection.
 *
 * SOLID: Single Responsibility - handles free-form selection creation only
 * KISS: Simple pixel accumulation during drag
 * DRY: Uses Bresenham line algorithm to fill gaps between mouse positions
 *
 * @author Open Mason Team
 */
public class FreeSelectionStrategy implements SelectionStrategy {

    // Brush size for selection painting (mutable to support runtime changes)
    private int brushSize;

    // Accumulated pixels during drag
    private final Set<FreeSelection.Pixel> selectedPixels = new HashSet<>();

    // Last mouse position (for line interpolation)
    private int lastX = -1;
    private int lastY = -1;

    // Created selection
    private SelectionRegion createdSelection = null;
    private boolean hasSelection = false;

    /**
     * Creates a free selection strategy with default brush size (1 pixel).
     */
    public FreeSelectionStrategy() {
        this(1);
    }

    /**
     * Creates a free selection strategy with specified brush size.
     *
     * @param brushSize Size of brush in pixels (minimum 1)
     */
    public FreeSelectionStrategy(int brushSize) {
        this.brushSize = Math.max(1, brushSize);
    }

    @Override
    public void onMouseDown(int x, int y) {
        // Start new selection
        selectedPixels.clear();
        createdSelection = null;
        hasSelection = false;

        // Paint initial pixel(s)
        paintBrush(x, y);
        lastX = x;
        lastY = y;
    }

    @Override
    public void onMouseDrag(int x, int y) {
        // Paint line from last position to current position
        // This fills gaps when mouse moves quickly
        if (lastX != -1 && lastY != -1) {
            paintLine(lastX, lastY, x, y);
        } else {
            paintBrush(x, y);
        }

        lastX = x;
        lastY = y;
    }

    @Override
    public void onMouseUp() {
        // Finalize selection
        if (!selectedPixels.isEmpty()) {
            createdSelection = new FreeSelection(selectedPixels);
            hasSelection = true;
        } else {
            // No pixels selected - clear selection
            createdSelection = null;
            hasSelection = true;
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
        // Return preview of current pixel selection
        if (!selectedPixels.isEmpty()) {
            // Convert FreeSelection.Pixel to PixelPreview.Pixel
            Set<PixelPreview.Pixel> previewPixels = new HashSet<>();
            for (FreeSelection.Pixel pixel : selectedPixels) {
                previewPixels.add(new PixelPreview.Pixel(pixel.x, pixel.y));
            }
            return new PixelPreview(previewPixels);
        }
        return null;
    }

    @Override
    public void reset() {
        selectedPixels.clear();
        lastX = -1;
        lastY = -1;
        createdSelection = null;
        hasSelection = false;
    }

    @Override
    public String getName() {
        return "Free Selection";
    }

    @Override
    public String getDescription() {
        return "Click and drag to paint selection regions";
    }

    /**
     * Paints brush at specified position.
     * For brush size > 1, paints a square centered on the position.
     *
     * @param x Center x-coordinate
     * @param y Center y-coordinate
     */
    private void paintBrush(int x, int y) {
        if (brushSize == 1) {
            selectedPixels.add(new FreeSelection.Pixel(x, y));
        } else {
            // Paint square brush
            int radius = brushSize / 2;
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    selectedPixels.add(new FreeSelection.Pixel(x + dx, y + dy));
                }
            }
        }
    }

    /**
     * Paints a line from (x1, y1) to (x2, y2) using Bresenham's algorithm.
     * Fills gaps between mouse positions during fast dragging.
     *
     * @param x1 Start x-coordinate
     * @param y1 Start y-coordinate
     * @param x2 End x-coordinate
     * @param y2 End y-coordinate
     */
    private void paintLine(int x1, int y1, int x2, int y2) {
        // Bresenham's line algorithm
        int dx = Math.abs(x2 - x1);
        int dy = Math.abs(y2 - y1);
        int sx = x1 < x2 ? 1 : -1;
        int sy = y1 < y2 ? 1 : -1;
        int err = dx - dy;

        int x = x1;
        int y = y1;

        while (true) {
            paintBrush(x, y);

            if (x == x2 && y == y2) {
                break;
            }

            int e2 = 2 * err;
            if (e2 > -dy) {
                err -= dy;
                x += sx;
            }
            if (e2 < dx) {
                err += dx;
                y += sy;
            }
        }
    }

    /**
     * Gets the current brush size.
     *
     * @return Brush size in pixels
     */
    public int getBrushSize() {
        return brushSize;
    }

    /**
     * Sets the brush size for this strategy.
     * The brush size will be clamped to a minimum of 1 pixel.
     *
     * @param size New brush size in pixels (minimum 1)
     */
    public void setBrushSize(int size) {
        this.brushSize = Math.max(1, size);
    }
}
