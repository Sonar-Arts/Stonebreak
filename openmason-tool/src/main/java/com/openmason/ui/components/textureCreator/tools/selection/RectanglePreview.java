package com.openmason.ui.components.textureCreator.tools.selection;

/**
 * Preview data for rectangular selection strategies.
 * Simple immutable data class holding rectangle bounds.
 *
 * SOLID: Single Responsibility - holds rectangle preview data only
 * KISS: Simple data holder with no complex logic
 *
 * @author Open Mason Team
 */
public class RectanglePreview implements SelectionPreview {

    private final int startX;
    private final int startY;
    private final int endX;
    private final int endY;

    /**
     * Creates a rectangle preview.
     *
     * @param startX Start x-coordinate
     * @param startY Start y-coordinate
     * @param endX   End x-coordinate
     * @param endY   End y-coordinate
     */
    public RectanglePreview(int startX, int startY, int endX, int endY) {
        this.startX = startX;
        this.startY = startY;
        this.endX = endX;
        this.endY = endY;
    }

    @Override
    public PreviewType getType() {
        return PreviewType.RECTANGLE;
    }

    public int getStartX() {
        return startX;
    }

    public int getStartY() {
        return startY;
    }

    public int getEndX() {
        return endX;
    }

    public int getEndY() {
        return endY;
    }
}
