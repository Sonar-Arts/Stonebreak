package com.openmason.main.systems.menus.textureCreator.tools.move;

import com.openmason.main.systems.menus.textureCreator.canvas.PixelCanvas;

import java.awt.Rectangle;

/**
 * Result of transforming a {@link SelectionSnapshot}. Stores colour data and a
 * mask in the destination coordinate space. Both arrays use a compact
 * top-left origin addressing scheme for cache friendliness when applying to a
 * {@link PixelCanvas}.
 */
public record TransformedImage(Rectangle bounds, int[] pixels, boolean[] mask, int pixelCount) {

    public TransformedImage(Rectangle bounds, int[] pixels, boolean[] mask, int pixelCount) {
        this.bounds = new Rectangle(bounds);
        this.pixels = pixels;
        this.mask = mask;
        this.pixelCount = pixelCount;
    }

    @Override
    public Rectangle bounds() {
        return new Rectangle(bounds);
    }

    public int width() {
        return bounds.width;
    }

    public int height() {
        return bounds.height;
    }

    public boolean isEmpty() {
        return pixelCount == 0;
    }

}
