package com.openmason.engine.rendering.gl;

import org.joml.Matrix4f;

public class RenderingConfigurationManager {
    private static final float DEFAULT_FAR_PLANE = 1000.0f;
    /** Default vertical field of view in degrees. */
    public static final float DEFAULT_FOV_DEGREES = 70.0f;
    private static final float MIN_FOV_DEGREES = 10.0f;
    private static final float MAX_FOV_DEGREES = 120.0f;

    private int windowWidth;
    private int windowHeight;
    private float farPlane = DEFAULT_FAR_PLANE;
    private float fovDegrees = DEFAULT_FOV_DEGREES;
    private final Matrix4f projectionMatrix;

    public RenderingConfigurationManager(int width, int height) {
        this.windowWidth = width;
        this.windowHeight = height;
        this.projectionMatrix = new Matrix4f();
        updateProjectionMatrix();
    }

    public void updateWindowSize(int width, int height) {
        this.windowWidth = width;
        this.windowHeight = height;
        updateProjectionMatrix();
    }

    /**
     * Sets the far clip distance and rebuilds the projection on change. The
     * game drives this from its render/LOD distance settings so large distant
     * -terrain rings aren't clipped by the default far plane. The projection
     * {@link Matrix4f} is a shared instance mutated in place — every consumer
     * holding a reference (frustum cullers, shadow cascades, render passes)
     * picks the new far plane up automatically.
     */
    public void setFarPlane(float farPlane) {
        if (this.farPlane != farPlane) {
            this.farPlane = farPlane;
            updateProjectionMatrix();
        }
    }

    /**
     * Sets the vertical field of view (degrees, clamped to a sane range) and rebuilds the shared
     * projection in place on change. Used by scripted cameras for zooms and FOV punches; callers
     * must restore {@link #DEFAULT_FOV_DEGREES} when they release the camera.
     */
    public void setFieldOfViewDegrees(float degrees) {
        float clamped = Math.max(MIN_FOV_DEGREES, Math.min(MAX_FOV_DEGREES, degrees));
        if (Float.isNaN(clamped)) {
            clamped = DEFAULT_FOV_DEGREES;
        }
        if (this.fovDegrees != clamped) {
            this.fovDegrees = clamped;
            updateProjectionMatrix();
        }
    }

    public float getFieldOfViewDegrees() {
        return fovDegrees;
    }

    private void updateProjectionMatrix() {
        float aspectRatio = (float) windowWidth / windowHeight;
        projectionMatrix.setPerspective((float) Math.toRadians(fovDegrees), aspectRatio, 0.1f, farPlane);
    }
    
    public int getWindowWidth() {
        return windowWidth;
    }
    
    public int getWindowHeight() {
        return windowHeight;
    }
    
    public Matrix4f getProjectionMatrix() {
        return projectionMatrix;
    }
}