package com.openmason.ui.textureCreator.selection;

import java.awt.Rectangle;

/**
 * Interface for selection regions in the texture editor.
 * Designed for extensibility to support multiple selection shapes (rectangle, ellipse, lasso, magic wand).
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
     */
    boolean contains(int x, int y);

    /**
     * Gets the bounding rectangle of this selection.
     */
    Rectangle getBounds();

    /**
     * Gets the type of this selection.
     */
    SelectionType getType();

    /**
     * Checks if this selection is empty (no area).
     */
    boolean isEmpty();

    /**
     * Translates this selection by the given offset.
     */
    SelectionRegion translate(int dx, int dy);
}
