package com.openmason.ui.viewport;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Simple camera class for 3D viewport navigation.
 * Provides arc-ball style camera controls with orbit, zoom, and pan.
 */
public class Camera {
    
    // Camera parameters
    private float distance = 10.0f;
    private float yaw = 0.0f;
    private float pitch = 0.0f;
    private Vector3f target = new Vector3f(0, 0, 0);
    
    // Projection parameters
    private float fov = 45.0f;
    private float aspectRatio = 1.0f;
    private float nearPlane = 0.1f;
    private float farPlane = 100.0f;
    
    // Matrices
    private Matrix4f viewMatrix = new Matrix4f();
    private Matrix4f projectionMatrix = new Matrix4f();
    private boolean viewMatrixDirty = true;
    private boolean projectionMatrixDirty = true;
    
    // Constraints
    private static final float MIN_DISTANCE = 1.0f;
    private static final float MAX_DISTANCE = 50.0f;
    private static final float MIN_PITCH = -89.0f;
    private static final float MAX_PITCH = 89.0f;
    
    public Camera() {
        updateMatrices();
    }
    
    /**
     * Rotate camera around target.
     */
    public void rotate(float deltaYaw, float deltaPitch) {
        yaw += deltaYaw;
        pitch += deltaPitch;
        
        // Constrain pitch
        pitch = Math.max(MIN_PITCH, Math.min(MAX_PITCH, pitch));
        
        viewMatrixDirty = true;
    }
    
    /**
     * Zoom camera in/out.
     */
    public void zoom(float deltaDistance) {
        distance -= deltaDistance;
        distance = Math.max(MIN_DISTANCE, Math.min(MAX_DISTANCE, distance));
        viewMatrixDirty = true;
    }
    
    /**
     * Pan camera (move target).
     */
    public void pan(float deltaX, float deltaY) {
        // Calculate right and up vectors in world space
        Vector3f right = new Vector3f();
        Vector3f up = new Vector3f();
        
        getViewMatrix().getColumn(0, right).negate();
        getViewMatrix().getColumn(1, up).negate();
        
        // Move target
        target.add(right.mul(deltaX * distance * 0.001f));
        target.add(up.mul(deltaY * distance * 0.001f));
        
        viewMatrixDirty = true;
    }
    
    /**
     * Reset camera to default position.
     */
    public void reset() {
        distance = 10.0f;
        yaw = 0.0f;
        pitch = 0.0f;
        target.set(0, 0, 0);
        viewMatrixDirty = true;
    }
    
    /**
     * Set aspect ratio and mark projection matrix as dirty.
     */
    public void setAspectRatio(float aspectRatio) {
        this.aspectRatio = aspectRatio;
        projectionMatrixDirty = true;
    }
    
    /**
     * Get view matrix (camera transform).
     */
    public Matrix4f getViewMatrix() {
        if (viewMatrixDirty) {
            updateViewMatrix();
        }
        return viewMatrix;
    }
    
    /**
     * Get projection matrix.
     */
    public Matrix4f getProjectionMatrix() {
        if (projectionMatrixDirty) {
            updateProjectionMatrix();
        }
        return projectionMatrix;
    }
    
    /**
     * Update matrices if needed.
     */
    public void updateMatrices() {
        if (viewMatrixDirty) {
            updateViewMatrix();
        }
        if (projectionMatrixDirty) {
            updateProjectionMatrix();
        }
    }
    
    /**
     * Update view matrix based on current camera parameters.
     */
    private void updateViewMatrix() {
        // Calculate camera position based on spherical coordinates
        float yawRad = (float) Math.toRadians(yaw);
        float pitchRad = (float) Math.toRadians(pitch);
        
        float x = distance * (float) (Math.cos(pitchRad) * Math.cos(yawRad));
        float y = distance * (float) Math.sin(pitchRad);
        float z = distance * (float) (Math.cos(pitchRad) * Math.sin(yawRad));
        
        Vector3f position = new Vector3f(x, y, z).add(target);
        Vector3f up = new Vector3f(0, 1, 0);
        
        viewMatrix.identity().lookAt(position, target, up);
        viewMatrixDirty = false;
    }
    
    /**
     * Update projection matrix.
     */
    private void updateProjectionMatrix() {
        projectionMatrix.identity().perspective(
            (float) Math.toRadians(fov),
            aspectRatio,
            nearPlane,
            farPlane
        );
        projectionMatrixDirty = false;
    }
    
    /**
     * Get camera position in world space.
     */
    public Vector3f getPosition() {
        float yawRad = (float) Math.toRadians(yaw);
        float pitchRad = (float) Math.toRadians(pitch);
        
        float x = distance * (float) (Math.cos(pitchRad) * Math.cos(yawRad));
        float y = distance * (float) Math.sin(pitchRad);
        float z = distance * (float) (Math.cos(pitchRad) * Math.sin(yawRad));
        
        return new Vector3f(x, y, z).add(target);
    }
    
    // Getters and setters
    public float getDistance() { return distance; }
    public void setDistance(float distance) { 
        this.distance = Math.max(MIN_DISTANCE, Math.min(MAX_DISTANCE, distance));
        viewMatrixDirty = true;
    }
    
    public float getYaw() { return yaw; }
    public void setYaw(float yaw) { 
        this.yaw = yaw;
        viewMatrixDirty = true;
    }
    
    public float getPitch() { return pitch; }
    public void setPitch(float pitch) { 
        this.pitch = Math.max(MIN_PITCH, Math.min(MAX_PITCH, pitch));
        viewMatrixDirty = true;
    }
    
    public Vector3f getTarget() { return new Vector3f(target); }
    public void setTarget(Vector3f target) { 
        this.target.set(target);
        viewMatrixDirty = true;
    }
    
    public float getFov() { return fov; }
    public void setFov(float fov) { 
        this.fov = fov;
        projectionMatrixDirty = true;
    }
    
    public float getAspectRatio() { return aspectRatio; }
    public float getNearPlane() { return nearPlane; }
    public float getFarPlane() { return farPlane; }
    
    /**
     * Get camera azimuth (yaw) angle in degrees.
     */
    public float getAzimuth() { 
        return yaw; 
    }
    
    /**
     * Get camera elevation (pitch) angle in degrees.
     */
    public float getElevation() { 
        return pitch; 
    }
    
    /**
     * Set camera orientation using azimuth and elevation angles.
     */
    public void setOrientation(float azimuth, float elevation) {
        this.yaw = azimuth;
        this.pitch = Math.max(MIN_PITCH, Math.min(MAX_PITCH, elevation));
        viewMatrixDirty = true;
    }
}