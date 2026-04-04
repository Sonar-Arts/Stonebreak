package com.openmason.engine.rendering.api;

import org.joml.Matrix4f;

/**
 * Shared rendering context containing common data needed by renderers.
 * Provides camera matrices, viewport dimensions, and render mode.
 *
 * <p>This is the engine-level RenderContext. Both Stonebreak and Open Mason
 * create instances by providing an {@link IRenderCamera} implementation.
 */
public class RenderContext {

    private final IRenderCamera camera;
    private final Matrix4f viewProjectionMatrix = new Matrix4f();

    private int viewportWidth;
    private int viewportHeight;
    private boolean unrenderedMode;

    /**
     * Create render context with camera.
     *
     * @param camera the camera providing view and projection matrices
     */
    public RenderContext(IRenderCamera camera) {
        this.camera = camera;
    }

    /**
     * Update context with current viewport state.
     * Should be called before each frame.
     *
     * @param width          viewport width in pixels
     * @param height         viewport height in pixels
     * @param unrenderedMode true if rendering in unrendered (solid gray) mode
     */
    public void update(int width, int height, boolean unrenderedMode) {
        this.viewportWidth = width;
        this.viewportHeight = height;
        this.unrenderedMode = unrenderedMode;

        camera.getProjectionMatrix().mul(camera.getViewMatrix(), viewProjectionMatrix);
    }

    /**
     * Get view-projection matrix as float array.
     *
     * @return 16-element float array in column-major order
     */
    public float[] getViewProjectionArray() {
        float[] array = new float[16];
        viewProjectionMatrix.get(array);
        return array;
    }

    public IRenderCamera getCamera() { return camera; }
    public int getViewportWidth() { return viewportWidth; }
    public int getViewportHeight() { return viewportHeight; }
    public boolean isUnrenderedMode() { return unrenderedMode; }

    @Override
    public String toString() {
        return String.format("RenderContext{%dx%d, unrendered=%s}",
                viewportWidth, viewportHeight, unrenderedMode);
    }
}
