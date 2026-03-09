package com.openmason.main.systems.menus.textureCreator.canvas;

/**
 * Defines the editable pixel region on a canvas.
 *
 * <p>When set on a {@link PixelCanvas}, only pixels where
 * {@link #isEditable(int, int)} returns true can be modified via
 * {@link PixelCanvas#setPixel}. A null mask means all pixels are editable.
 *
 * <p>Implementations include polygon-based masks for per-face editing
 * ({@link PolygonShapeMask}), cube net layout masks ({@link CubeNetShapeMask}),
 * and composite masks ({@link CompositeShapeMask}) that combine multiple
 * constraints.
 *
 * @see PixelCanvas
 * @see PolygonShapeMask
 * @see CubeNetShapeMask
 * @see CompositeShapeMask
 */
public interface CanvasShapeMask {

    /**
     * Test whether a pixel coordinate is editable (inside the mask).
     *
     * @param x pixel X coordinate
     * @param y pixel Y coordinate
     * @return true if the pixel can be modified
     */
    boolean isEditable(int x, int y);

    /**
     * Get mask width in pixels.
     *
     * @return width in pixels
     */
    int getWidth();

    /**
     * Get mask height in pixels.
     *
     * @return height in pixels
     */
    int getHeight();
}
