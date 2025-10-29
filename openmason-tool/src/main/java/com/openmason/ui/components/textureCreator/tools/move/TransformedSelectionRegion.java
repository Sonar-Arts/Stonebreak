package com.openmason.ui.components.textureCreator.tools.move;

import com.openmason.ui.components.textureCreator.selection.SelectionRegion;

import java.awt.Rectangle;

/**
 * Selection region backed by a transformed mask. This allows the editor to
 * keep an accurate outline after rotations or non-uniform scaling so that
 * subsequent operations remain precise.
 */
public final class TransformedSelectionRegion implements SelectionRegion {

    private final Rectangle bounds;
    private final boolean[] mask;
    private final int width;
    private final int height;
    private final int pixelCount;

    public TransformedSelectionRegion(Rectangle bounds, boolean[] mask, int pixelCount) {
        this.bounds = new Rectangle(bounds);
        this.width = Math.max(bounds.width, 1);
        this.height = Math.max(bounds.height, 1);
        this.mask = mask;
        this.pixelCount = pixelCount;
    }

    public static TransformedSelectionRegion fromImage(TransformedImage image) {
        return new TransformedSelectionRegion(image.bounds(), image.mask(), image.pixelCount());
    }

    @Override
    public boolean contains(int x, int y) {
        int localX = x - bounds.x;
        int localY = y - bounds.y;
        if (localX < 0 || localY < 0 || localX >= width || localY >= height) {
            return false;
        }
        return mask[localY * width + localX];
    }

    @Override
    public Rectangle getBounds() {
        return new Rectangle(bounds);
    }

    @Override
    public SelectionType getType() {
        return SelectionType.FREEFORM;
    }

    @Override
    public boolean isEmpty() {
        return pixelCount == 0;
    }

    @Override
    public SelectionRegion translate(int dx, int dy) {
        Rectangle moved = new Rectangle(bounds);
        moved.translate(dx, dy);
        return new TransformedSelectionRegion(moved, mask, pixelCount);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof TransformedSelectionRegion)) return false;
        TransformedSelectionRegion other = (TransformedSelectionRegion) obj;
        return pixelCount == other.pixelCount &&
                bounds.equals(other.bounds) &&
                java.util.Arrays.equals(mask, other.mask);
    }

    @Override
    public int hashCode() {
        int result = java.util.Objects.hash(bounds, pixelCount);
        result = 31 * result + java.util.Arrays.hashCode(mask);
        return result;
    }
}
