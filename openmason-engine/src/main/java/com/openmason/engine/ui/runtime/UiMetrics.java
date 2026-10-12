package com.openmason.engine.ui.runtime;

/**
 * The independent scale inputs of a UI instance (#287). Documents author lengths in logical
 * pixels; layout runs in device (framebuffer) pixels, so one logical pixel is
 * {@code uiScale × pixelRatio} device pixels and Yoga rounds to the device pixel grid.
 *
 * <p>Editor zoom is deliberately absent: it is a view transform applied after layout
 * ({@code PreviewMapping} in the tool), so zooming the preview shows exactly the game's
 * pixels instead of re-laying the screen out at another scale.
 *
 * @param viewportWidth  framebuffer width in device pixels
 * @param viewportHeight framebuffer height in device pixels
 * @param uiScale        the player's UI scale setting
 * @param pixelRatio     framebuffer pixels per window point (DPI); 1 everywhere today
 */
public record UiMetrics(float viewportWidth, float viewportHeight, float uiScale, float pixelRatio) {

    public UiMetrics {
        if (!(viewportWidth >= 0) || !(viewportHeight >= 0)) {
            throw new IllegalArgumentException("viewport must be >= 0");
        }
        if (!(uiScale > 0) || !(pixelRatio > 0)) {
            throw new IllegalArgumentException("scales must be > 0");
        }
    }

    public static UiMetrics of(float width, float height, float uiScale) {
        return new UiMetrics(width, height, uiScale, 1f);
    }

    /** Device pixels per logical pixel. */
    public float scale() {
        return uiScale * pixelRatio;
    }

    public UiMetrics withViewport(float width, float height) {
        return new UiMetrics(width, height, uiScale, pixelRatio);
    }

    public UiMetrics withUiScale(float scale) {
        return new UiMetrics(viewportWidth, viewportHeight, scale, pixelRatio);
    }
}
