package com.openmason.main.systems.menus.textureCreator.tools.move;

import com.openmason.main.systems.menus.textureCreator.canvas.CanvasShapeMask;
import com.openmason.main.systems.menus.textureCreator.canvas.PixelCanvas;
import com.openmason.main.systems.menus.textureCreator.selection.SelectionRegion;

import java.awt.Rectangle;

/**
 * Immutable capture of a selection's pixel data and mask at the moment a move
 * interaction begins. The snapshot is the single source of truth for all
 * transformation previews so that we never re-sample the canvas mid-gesture.
 */
public final class SelectionSnapshot {

    private final SelectionRegion originalSelection;
    private final Rectangle bounds;
    private final int canvasWidth;
    private final int canvasHeight;
    private final int[] pixels;
    private final boolean[] mask;
    private final CanvasShapeMask shapeMask;

    private SelectionSnapshot(SelectionRegion originalSelection,
                              Rectangle bounds,
                              int canvasWidth,
                              int canvasHeight,
                              int[] pixels,
                              boolean[] mask,
                              CanvasShapeMask shapeMask) {
        this.originalSelection = originalSelection;
        this.bounds = new Rectangle(bounds);
        this.canvasWidth = canvasWidth;
        this.canvasHeight = canvasHeight;
        this.pixels = pixels;
        this.mask = mask;
        this.shapeMask = shapeMask;
    }

    public static SelectionSnapshot capture(PixelCanvas canvas, SelectionRegion selection) {
        Rectangle bounds = selection.getBounds();
        int width = Math.max(bounds.width, 1);
        int height = Math.max(bounds.height, 1);

        int[] pixels = new int[width * height];
        boolean[] mask = new boolean[width * height];

        for (int dy = 0; dy < height; dy++) {
            int canvasY = bounds.y + dy;
            for (int dx = 0; dx < width; dx++) {
                int canvasX = bounds.x + dx;
                int index = dy * width + dx;
                boolean inside = selection.contains(canvasX, canvasY);
                mask[index] = inside;
                if (inside) {
                    pixels[index] = canvas.getPixel(canvasX, canvasY);
                }
            }
        }

        return new SelectionSnapshot(selection, bounds, canvas.getWidth(), canvas.getHeight(),
            pixels, mask, canvas.getShapeMask());
    }

    public SelectionRegion originalSelection() {
        return originalSelection;
    }

    public Rectangle bounds() {
        return new Rectangle(bounds);
    }

    public int canvasWidth() {
        return canvasWidth;
    }

    public int canvasHeight() {
        return canvasHeight;
    }

    public int width() {
        return bounds.width;
    }

    public int height() {
        return bounds.height;
    }

    public int[] pixels() {
        return pixels;
    }

    public boolean[] mask() {
        return mask;
    }

    public int indexFor(int localX, int localY) {
        return localY * width() + localX;
    }

    /**
     * Check if a pixel coordinate is editable according to the canvas shape mask
     * that was active at snapshot time.
     *
     * @param x pixel X coordinate
     * @param y pixel Y coordinate
     * @return true if the pixel is editable (or no mask was active)
     */
    public boolean isEditablePixel(int x, int y) {
        if (shapeMask == null) {
            return x >= 0 && x < canvasWidth && y >= 0 && y < canvasHeight;
        }
        return shapeMask.isEditable(x, y);
    }

}
