package com.stonebreak.player;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Camera class for the player's view.
 */
public class Camera {
    
    // Default camera values
    private static final float YAW = -90.0f;
    private static final float PITCH = 0.0f;
    private static final float MAX_PITCH = 89.0f;
    
    // Camera attributes
    private final Vector3f position;
    private final Vector3f front;
    private Vector3f up;
    private Vector3f right;
    private final Vector3f worldUp;
    
    // Euler angles
    private float yaw;
    private float pitch;
    
    /**
     * Creates a camera with default values.
     */
    public Camera() {
        this.position = new Vector3f(0.0f, 0.0f, 0.0f);
        this.worldUp = new Vector3f(0.0f, 1.0f, 0.0f);
        this.yaw = YAW;
        this.pitch = PITCH;
        this.front = new Vector3f(0.0f, 0.0f, -1.0f);
        this.up = new Vector3f(0.0f, 1.0f, 0.0f);
        this.right = new Vector3f(1.0f, 0.0f, 0.0f);
        updateCameraVectors();
    }
    
    /**
     * Creates a camera with specified parameters.
     */
    public Camera(Vector3f position, Vector3f up, float yaw, float pitch) {
        this.position = position;
        this.worldUp = up;
        this.yaw = yaw;
        this.pitch = pitch;
        this.front = new Vector3f(0.0f, 0.0f, -1.0f);
        updateCameraVectors();
    }
    
    /**
     * Returns the view matrix calculated using Euler angles and the LookAt matrix.
     */
    public Matrix4f getViewMatrix() {
        // Create a temporary "look at" point that is position + front
        Vector3f target = new Vector3f();
        position.add(front, target);
        
        // Create the view matrix
        return new Matrix4f().lookAt(position, target, up);
    }
    
    /**
     * Processes input received from a mouse input system.
     */
    public void processMouseMovement(float xOffset, float yOffset) {
        yaw += xOffset;
        pitch += yOffset;
        
        // Constrain pitch
        if (pitch > MAX_PITCH) {
            pitch = MAX_PITCH;
        } else if (pitch < -MAX_PITCH) {
            pitch = -MAX_PITCH;
        }
        
        // Update front, right and up vectors using the updated Euler angles
        updateCameraVectors();
    }
    
    /**
     * Calculates the front, right and up vectors from the camera's Euler angles.
     */
    private void updateCameraVectors() {
        // Calculate the new front vector
        front.x = (float) (Math.cos(Math.toRadians(yaw)) * Math.cos(Math.toRadians(pitch)));
        front.y = (float) Math.sin(Math.toRadians(pitch));
        front.z = (float) (Math.sin(Math.toRadians(yaw)) * Math.cos(Math.toRadians(pitch)));
        front.normalize();
        
        // Recalculate the right vector
        front.cross(worldUp, right);
        right.normalize();
        
        // Recalculate the up vector
        right.cross(front, up);
        up.normalize();
    }
    
    /**
     * Sets the camera position.
     */
    public void setPosition(float x, float y, float z) {
        position.set(x, y, z);
    }
    
    /**
     * Gets the camera position.
     */
    public Vector3f getPosition() {
        return position;
    }
    
    /**
     * Gets the camera's front vector.
     */
    public Vector3f getFront() {
        return front;
    }
    
    /**
     * Gets the camera's up vector.
     */
    public Vector3f getUp() {
        return up;
    }
    
    /**
     * Gets the camera's right vector.
     */
    public Vector3f getRight() {
        return right;
    }
    
    /**
     * Resets the camera to default orientation for a new world.
     */
    public void reset() {
        this.yaw = YAW;
        this.pitch = PITCH;
        this.front.set(0.0f, 0.0f, -1.0f);
        updateCameraVectors();
    }
    
    /**
     * Gets the camera's yaw angle in degrees.
     */
    public float getYaw() {
        return yaw;
    }
    
    /**
     * Gets the camera's pitch angle in degrees.
     */
    public float getPitch() {
        return pitch;
    }
    
    /**
     * Sets the camera's yaw angle.
     */
    public void setYaw(float yaw) {
        this.yaw = yaw;
        updateCameraVectors();
    }
    
    /**
     * Sets the camera's pitch angle.
     */
    public void setPitch(float pitch) {
        this.pitch = Math.max(-MAX_PITCH, Math.min(MAX_PITCH, pitch));
        updateCameraVectors();
    }
}
