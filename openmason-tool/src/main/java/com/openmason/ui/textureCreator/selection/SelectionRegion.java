package com.openmason.ui.textureCreator.selection;

import java.awt.Rectangle;

/**
 * Interface for selection regions in the texture editor.
 * Designed for extensibility to support multiple selection shapes (rectangle, ellipse, lasso, magic wand).
 *
 * SOLID: Interface segregation - defines minimal contract for selection regions
 * YAGNI: Start with rectangle, but architecture supports future shapes
 */
public interface SelectionRegion {

    /**
     * Selection shape types.
     */
    enum SelectionType {
        RECTANGLE,
        ELLIPSE,
        LASSO,
        MAGIC_WAND,
        FREEFORM
    }

    /**
     * Tests if a point is contained within this selection region.
     *
     * @param x The x-coordinate to test
     * @param y The y-coordinate to test
     * @return true if the point is inside the selection, false otherwise
     */
    boolean contains(int x, int y);

    /**
     * Gets the bounding rectangle of this selection.
     * Used for rendering and translation operations.
     *
     * @return The bounding rectangle (never null)
     */
    Rectangle getBounds();

    /**
     * Gets the type of this selection.
     *
     * @return The selection type
     */
    SelectionType getType();

    /**
     * Checks if this selection is empty (no area).
     *
     * @return true if the selection has no area, false otherwise
     */
    boolean isEmpty();

    /**
     * Translates this selection by the given offset.
     *
     * @param dx The x-offset to translate by
     * @param dy The y-offset to translate by
     * @return A new SelectionRegion with the translated bounds
     */
    SelectionRegion translate(int dx, int dy);
}
