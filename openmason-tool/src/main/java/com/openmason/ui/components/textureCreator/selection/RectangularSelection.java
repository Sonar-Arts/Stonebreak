package com.openmason.ui.components.textureCreator.selection;

import java.awt.Rectangle;

/**
 * Rectangular selection region implementation.
 * Stores normalized bounds (x1 <= x2, y1 <= y2) for efficient containment testing.
 *
 * SOLID: Single responsibility - manages rectangular selection bounds
 * KISS: Simple rectangle containment logic
 */
public class RectangularSelection implements SelectionRegion {

    private final int x1;
    private final int y1;
    private final int x2;
    private final int y2;

    /**
     * Creates a rectangular selection from two corner points.
     * Automatically normalizes coordinates so x1 <= x2 and y1 <= y2.
     *
     * @param startX First corner x-coordinate
     * @param startY First corner y-coordinate
     * @param endX   Second corner x-coordinate
     * @param endY   Second corner y-coordinate
     */
    public RectangularSelection(int startX, int startY, int endX, int endY) {
        this.x1 = Math.min(startX, endX);
        this.y1 = Math.min(startY, endY);
        this.x2 = Math.max(startX, endX);
        this.y2 = Math.max(startY, endY);
    }

    /**
     * Creates a rectangular selection from a Rectangle object.
     *
     * @param rect The rectangle to create selection from
     */
    public RectangularSelection(Rectangle rect) {
        this(rect.x, rect.y, rect.x + rect.width - 1, rect.y + rect.height - 1);
    }

    @Override
    public boolean contains(int x, int y) {
        return x >= x1 && x <= x2 && y >= y1 && y <= y2;
    }

    @Override
    public Rectangle getBounds() {
        return new Rectangle(x1, y1, x2 - x1 + 1, y2 - y1 + 1);
    }

    @Override
    public SelectionType getType() {
        return SelectionType.RECTANGLE;
    }

    @Override
    public boolean isEmpty() {
        return x1 > x2 || y1 > y2;
    }

    @Override
    public SelectionRegion translate(int dx, int dy) {
        return new RectangularSelection(x1 + dx, y1 + dy, x2 + dx, y2 + dy);
    }

    /**
     * Gets the minimum x-coordinate of the selection.
     */
    public int getX1() {
        return x1;
    }

    /**
     * Gets the minimum y-coordinate of the selection.
     */
    public int getY1() {
        return y1;
    }

    /**
     * Gets the maximum x-coordinate of the selection.
     */
    public int getX2() {
        return x2;
    }

    /**
     * Gets the maximum y-coordinate of the selection.
     */
    public int getY2() {
        return y2;
    }

    /**
     * Gets the width of the selection.
     */
    public int getWidth() {
        return x2 - x1 + 1;
    }

    /**
     * Gets the height of the selection.
     */
    public int getHeight() {
        return y2 - y1 + 1;
    }

    @Override
    public String toString() {
        return String.format("RectangularSelection[(%d,%d) to (%d,%d), size %dx%d]",
                x1, y1, x2, y2, getWidth(), getHeight());
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof RectangularSelection)) return false;
        RectangularSelection other = (RectangularSelection) obj;
        return x1 == other.x1 && y1 == other.y1 && x2 == other.x2 && y2 == other.y2;
    }

    @Override
    public int hashCode() {
        int result = x1;
        result = 31 * result + y1;
        result = 31 * result + x2;
        result = 31 * result + y2;
        return result;
    }
}
