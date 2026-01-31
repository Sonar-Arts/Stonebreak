package com.openmason.main.systems.viewport.viewportRendering;

import com.openmason.main.systems.viewport.ViewportCamera;
import org.joml.Matrix4f;

/**
 * Shared rendering context containing common data needed by renderers.
 * Provides camera matrices, viewport dimensions, and utility methods.
 */
public class RenderContext {

    private final ViewportCamera viewportCamera;
    private final Matrix4f viewProjectionMatrix = new Matrix4f();

    private int viewportWidth;
    private int viewportHeight;
    private boolean wireframeMode;

    /**
     * Create render context with camera.
     */
    public RenderContext(ViewportCamera viewportCamera) {
        this.viewportCamera = viewportCamera;
    }

    /**
     * Update context with current viewport state.
     * Should be called before each frame.
     */
    public void update(int width, int height, boolean wireframeMode) {
        this.viewportWidth = width;
        this.viewportHeight = height;
        this.wireframeMode = wireframeMode;

        // Update view-projection matrix
        viewportCamera.getProjectionMatrix().mul(viewportCamera.getViewMatrix(), viewProjectionMatrix);
    }

    /**
     * Get view-projection matrix as float array.
     */
    public float[] getViewProjectionArray() {
        float[] array = new float[16];
        viewProjectionMatrix.get(array);
        return array;
    }

    // Getters
    public ViewportCamera getCamera() { return viewportCamera; }
    public int getViewportWidth() { return viewportWidth; }
    public int getViewportHeight() { return viewportHeight; }

    @Override
    public String toString() {
        return String.format("RenderContext{%dx%d, wireframe=%s}",
                           viewportWidth, viewportHeight, wireframeMode);
    }
}
