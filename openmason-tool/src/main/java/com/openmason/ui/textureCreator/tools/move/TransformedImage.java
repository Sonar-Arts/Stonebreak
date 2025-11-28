package com.openmason.ui.textureCreator.tools.move;

import com.openmason.ui.textureCreator.canvas.PixelCanvas;

import java.awt.Rectangle;

/**
 * Result of transforming a {@link SelectionSnapshot}. Stores colour data and a
 * mask in the destination coordinate space. Both arrays use a compact
 * top-left origin addressing scheme for cache friendliness when applying to a
 * {@link PixelCanvas}.
 */
public final class TransformedImage {

    private final Rectangle bounds;
    private final int[] pixels;
    private final boolean[] mask;
    private final int pixelCount;

    public TransformedImage(Rectangle bounds, int[] pixels, boolean[] mask, int pixelCount) {
        this.bounds = new Rectangle(bounds);
        this.pixels = pixels;
        this.mask = mask;
        this.pixelCount = pixelCount;
    }

    public Rectangle bounds() {
        return new Rectangle(bounds);
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

    public int pixelCount() {
        return pixelCount;
    }

    public boolean isEmpty() {
        return pixelCount == 0;
    }

    public int indexFor(int localX, int localY) {
        return localY * width() + localX;
    }

    public boolean hasPixel(int localX, int localY) {
        if (localX < 0 || localY < 0 || localX >= width() || localY >= height()) {
            return false;
        }
        return mask[indexFor(localX, localY)];
    }

    public int colorAt(int localX, int localY) {
        return pixels[indexFor(localX, localY)];
    }
}
