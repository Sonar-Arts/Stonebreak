package com.openmason.ui.textureCreator.tools.selection;

/**
 * Preview data for rectangular selection strategies.
 */
public record RectanglePreview(int startX, int startY, int endX, int endY) implements SelectionPreview {

    /**
     * Creates a rectangle preview.
     */
    public RectanglePreview {
    }

    @Override
    public PreviewType getType() {
        return PreviewType.RECTANGLE;
    }
}
