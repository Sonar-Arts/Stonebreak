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

    /**
     * Get the coverage fraction for a pixel coordinate.
     *
     * <p>Returns a value between 0.0 (fully outside) and 1.0 (fully inside).
     * Pixels at mask boundaries may return fractional values to enable
     * smooth edge blending. Tools should multiply their own coverage
     * (e.g., brush edge falloff) by this mask coverage for the final blend.
     *
     * <p>The default implementation returns 1.0 for editable pixels and
     * 0.0 for non-editable pixels (binary behavior). Subclasses like
     * {@link PolygonShapeMask} override this to provide smooth boundaries.
     *
     * @param x pixel X coordinate
     * @param y pixel Y coordinate
     * @return coverage fraction in [0.0, 1.0]
     */
    default float getCoverage(int x, int y) {
        return isEditable(x, y) ? 1.0f : 0.0f;
    }
}
