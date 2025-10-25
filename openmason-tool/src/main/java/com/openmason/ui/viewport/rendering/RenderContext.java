package com.openmason.ui.viewport.rendering;

import com.openmason.ui.viewport.Camera;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;

import java.nio.FloatBuffer;

/**
 * Shared rendering context containing common data needed by renderers.
 * Provides camera matrices, viewport dimensions, and utility methods.
 */
public class RenderContext {

    private final Camera camera;
    private final Matrix4f viewProjectionMatrix = new Matrix4f();
    private final FloatBuffer matrixBuffer = BufferUtils.createFloatBuffer(16);

    private int viewportWidth;
    private int viewportHeight;
    private boolean wireframeMode;

    /**
     * Create render context with camera.
     */
    public RenderContext(Camera camera) {
        this.camera = camera;
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
        camera.getProjectionMatrix().mul(camera.getViewMatrix(), viewProjectionMatrix);
    }

    /**
     * Get view-projection matrix (camera's projection * view).
     */
    public Matrix4f getViewProjectionMatrix() {
        return viewProjectionMatrix;
    }

    /**
     * Get view-projection matrix as float array.
     */
    public float[] getViewProjectionArray() {
        float[] array = new float[16];
        viewProjectionMatrix.get(array);
        return array;
    }

    /**
     * Get reusable float buffer for matrix data.
     * Buffer is cleared before each use.
     */
    public FloatBuffer getMatrixBuffer() {
        matrixBuffer.clear();
        return matrixBuffer;
    }

    /**
     * Upload matrix to buffer and return it.
     */
    public FloatBuffer uploadMatrix(Matrix4f matrix) {
        matrixBuffer.clear();
        matrix.get(matrixBuffer);
        return matrixBuffer;
    }

    /**
     * Create MVP matrix by multiplying view-projection with model matrix.
     */
    public Matrix4f createMVPMatrix(Matrix4f modelMatrix) {
        Matrix4f mvp = new Matrix4f();
        viewProjectionMatrix.mul(modelMatrix, mvp);
        return mvp;
    }

    // Getters
    public Camera getCamera() { return camera; }
    public int getViewportWidth() { return viewportWidth; }
    public int getViewportHeight() { return viewportHeight; }
    public boolean isWireframeMode() { return wireframeMode; }

    /**
     * Get aspect ratio.
     */
    public float getAspectRatio() {
        return viewportHeight > 0 ? (float) viewportWidth / viewportHeight : 1.0f;
    }

    @Override
    public String toString() {
        return String.format("RenderContext{%dx%d, wireframe=%s}",
                           viewportWidth, viewportHeight, wireframeMode);
    }
}
