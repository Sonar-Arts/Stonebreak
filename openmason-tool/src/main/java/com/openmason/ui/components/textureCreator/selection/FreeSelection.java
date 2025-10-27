package com.openmason.ui.components.textureCreator.selection;

import java.awt.Rectangle;
import java.util.HashSet;
import java.util.Set;

/**
 * Free-form selection region implementation.
 * Stores individual pixels in a set for arbitrary-shaped selections.
 * Used by free-select (paint) tool.
 *
 * SOLID: Single responsibility - manages pixel-based selection
 * KISS: Simple set-based pixel storage with cached bounds
 *
 * @author Open Mason Team
 */
public class FreeSelection implements SelectionRegion {

    /**
     * Simple immutable pixel coordinate holder.
     */
    public static class Pixel {
        public final int x;
        public final int y;

        public Pixel(int x, int y) {
            this.x = x;
            this.y = y;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof Pixel)) return false;
            Pixel other = (Pixel) obj;
            return x == other.x && y == other.y;
        }

        @Override
        public int hashCode() {
            return 31 * x + y;
        }
    }

    private final Set<Pixel> pixels;
    private final Rectangle bounds;

    /**
     * Creates a free selection from a set of pixels.
     *
     * @param pixels Set of pixels to include in selection
     */
    public FreeSelection(Set<Pixel> pixels) {
        if (pixels == null || pixels.isEmpty()) {
            throw new IllegalArgumentException("Pixel set cannot be null or empty");
        }

        // Store defensive copy
        this.pixels = new HashSet<>(pixels);

        // Calculate bounding rectangle
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;

        for (Pixel pixel : this.pixels) {
            minX = Math.min(minX, pixel.x);
            minY = Math.min(minY, pixel.y);
            maxX = Math.max(maxX, pixel.x);
            maxY = Math.max(maxY, pixel.y);
        }

        this.bounds = new Rectangle(minX, minY, maxX - minX + 1, maxY - minY + 1);
    }

    @Override
    public boolean contains(int x, int y) {
        return pixels.contains(new Pixel(x, y));
    }

    @Override
    public Rectangle getBounds() {
        return new Rectangle(bounds); // Return defensive copy
    }

    @Override
    public SelectionType getType() {
        return SelectionType.LASSO;
    }

    @Override
    public boolean isEmpty() {
        return pixels.isEmpty();
    }

    @Override
    public SelectionRegion translate(int dx, int dy) {
        // Create new set with translated pixels
        Set<Pixel> translatedPixels = new HashSet<>();
        for (Pixel pixel : pixels) {
            translatedPixels.add(new Pixel(pixel.x + dx, pixel.y + dy));
        }
        return new FreeSelection(translatedPixels);
    }

    /**
     * Creates a scaled version of this selection.
     * Each pixel coordinate is scaled from the anchor point.
     * When scaling up, fills gaps by expanding each pixel into a rectangle to maintain connectivity.
     * When scaling down, pixels may merge or be lost (expected behavior).
     * Supports negative scale factors for flipping.
     *
     * @param anchorX X coordinate of the scale anchor point
     * @param anchorY Y coordinate of the scale anchor point
     * @param scaleX Horizontal scale factor (negative = flip horizontally)
     * @param scaleY Vertical scale factor (negative = flip vertically)
     * @return A new FreeSelection with scaled pixel coordinates and filled gaps
     */
    public FreeSelection scale(int anchorX, int anchorY, double scaleX, double scaleY) {
        Set<Pixel> scaledPixels = new HashSet<>();

        // Calculate fill size - based on absolute scale to handle flipping
        // Each original pixel becomes a rectangle when scaling up
        int fillWidth = Math.max(1, (int) Math.ceil(Math.abs(scaleX)));
        int fillHeight = Math.max(1, (int) Math.ceil(Math.abs(scaleY)));

        // Determine fill direction based on scale sign
        int fillDirX = scaleX >= 0 ? 1 : -1;
        int fillDirY = scaleY >= 0 ? 1 : -1;

        for (Pixel pixel : pixels) {
            // Scale from anchor point (preserves sign for flipping)
            int scaledX = anchorX + (int) Math.round((pixel.x - anchorX) * scaleX);
            int scaledY = anchorY + (int) Math.round((pixel.y - anchorY) * scaleY);

            // Fill a rectangle in the appropriate direction
            // For negative scales (flipping), fill in negative direction
            for (int dy = 0; dy < fillHeight; dy++) {
                for (int dx = 0; dx < fillWidth; dx++) {
                    int fillX = scaledX + (dx * fillDirX);
                    int fillY = scaledY + (dy * fillDirY);
                    scaledPixels.add(new Pixel(fillX, fillY));
                }
            }
        }

        return new FreeSelection(scaledPixels);
    }

    /**
     * Creates a rotated version of this selection.
     * Rotates all pixel coordinates around the center of the bounding box.
     * Gaps may appear for non-90-degree rotations due to discrete pixel positions.
     *
     * @param angleDegrees Rotation angle in degrees (positive = clockwise)
     * @return A new FreeSelection with rotated pixel coordinates
     */
    public FreeSelection rotate(double angleDegrees) {
        Set<Pixel> rotatedPixels = new HashSet<>();

        // Calculate center of bounds
        Rectangle bounds = getBounds();
        double centerX = bounds.x + bounds.width / 2.0;
        double centerY = bounds.y + bounds.height / 2.0;

        // Convert to radians
        double angleRad = Math.toRadians(angleDegrees);
        double cos = Math.cos(angleRad);
        double sin = Math.sin(angleRad);

        for (Pixel pixel : pixels) {
            // Translate to origin
            double dx = pixel.x - centerX;
            double dy = pixel.y - centerY;

            // Rotate
            double rotatedX = dx * cos - dy * sin;
            double rotatedY = dx * sin + dy * cos;

            // Translate back and round to nearest pixel
            int finalX = (int) Math.round(centerX + rotatedX);
            int finalY = (int) Math.round(centerY + rotatedY);

            rotatedPixels.add(new Pixel(finalX, finalY));
        }

        return new FreeSelection(rotatedPixels);
    }

    /**
     * Gets the number of pixels in this selection.
     *
     * @return Pixel count
     */
    public int getPixelCount() {
        return pixels.size();
    }

    /**
     * Gets an unmodifiable view of the pixels in this selection.
     *
     * @return Set of pixels
     */
    public Set<Pixel> getPixels() {
        return new HashSet<>(pixels); // Return defensive copy
    }

    @Override
    public String toString() {
        return String.format("FreeSelection[%d pixels, bounds=%s]", pixels.size(), bounds);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof FreeSelection)) return false;
        FreeSelection other = (FreeSelection) obj;
        return pixels.equals(other.pixels);
    }

    @Override
    public int hashCode() {
        return pixels.hashCode();
    }
}
