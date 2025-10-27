package com.openmason.ui.components.textureCreator.tools.selection;

import java.util.Collections;
import java.util.Set;

/**
 * Preview data for pixel-based (free-form) selection strategies.
 * Holds a set of individual pixel coordinates that are selected.
 *
 * SOLID: Single Responsibility - holds pixel preview data only
 * KISS: Simple immutable data holder with pixel coordinates
 *
 * @author Open Mason Team
 */
public class PixelPreview implements SelectionPreview {

    /**
     * Simple pixel coordinate holder.
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

    /**
     * Creates a pixel preview from a set of pixels.
     *
     * @param pixels Set of pixels to preview (will be wrapped in unmodifiable set)
     */
    public PixelPreview(Set<Pixel> pixels) {
        this.pixels = Collections.unmodifiableSet(pixels);
    }

    @Override
    public PreviewType getType() {
        return PreviewType.PIXELS;
    }

    /**
     * Gets the set of pixels in this preview.
     *
     * @return Unmodifiable set of pixels
     */
    public Set<Pixel> getPixels() {
        return pixels;
    }
}
