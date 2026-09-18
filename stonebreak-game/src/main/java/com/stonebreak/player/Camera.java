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

    // Cinematic override (battle camera). While active the camera answers from an explicit
    // eye/target/roll instead of the player-driven position + Euler angles; the player-driven
    // state is left untouched so clearing the override restores first/third person exactly.
    private boolean cinematicActive;
    private final Vector3f cinematicEye = new Vector3f();
    private final Vector3f cinematicFront = new Vector3f(0.0f, 0.0f, -1.0f);
    private final Vector3f cinematicUp = new Vector3f(0.0f, 1.0f, 0.0f);
    private final Vector3f cinematicRight = new Vector3f(1.0f, 0.0f, 0.0f);
    
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
        if (cinematicActive) {
            Vector3f cinematicTarget = new Vector3f(cinematicEye).add(cinematicFront);
            return new Matrix4f().lookAt(cinematicEye, cinematicTarget, cinematicUp);
        }
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
        if (cinematicActive) return; // scripted camera: mouse look is ignored
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
        return cinematicActive ? cinematicEye : position;
    }
    
    /**
     * Gets the camera's front vector.
     */
    public Vector3f getFront() {
        return cinematicActive ? cinematicFront : front;
    }
    
    /**
     * Gets the camera's up vector.
     */
    public Vector3f getUp() {
        return cinematicActive ? cinematicUp : up;
    }
    
    /**
     * Gets the camera's right vector.
     */
    public Vector3f getRight() {
        return cinematicActive ? cinematicRight : right;
    }
    
    /**
     * Takes the camera over for a scripted shot. While active, {@link #getViewMatrix()},
     * {@link #getPosition()} and the basis vectors answer from this eye/target/roll and mouse
     * look is ignored. The player-driven position and Euler angles are not modified, so
     * {@link #clearCinematicView()} restores the normal view exactly.
     *
     * @param eye     world-space camera position
     * @param target  world-space look-at point (must differ from {@code eye})
     * @param rollDeg roll about the view axis in degrees (0 = level horizon)
     */
    public void setCinematicView(Vector3f eye, Vector3f target, float rollDeg) {
        Vector3f f = new Vector3f(target).sub(eye);
        if (f.lengthSquared() < 1.0e-10f) {
            f.set(cinematicFront); // degenerate request: keep the previous direction
        }
        f.normalize();
        Vector3f r = new Vector3f(f).cross(worldUp);
        if (r.lengthSquared() < 1.0e-8f) {
            r.set(1.0f, 0.0f, 0.0f); // looking straight up/down: pick a stable right axis
        }
        r.normalize();
        Vector3f u = new Vector3f(r).cross(f).normalize();
        if (rollDeg != 0.0f) {
            float a = (float) Math.toRadians(rollDeg);
            r.rotateAxis(a, f.x, f.y, f.z);
            u.rotateAxis(a, f.x, f.y, f.z);
        }
        cinematicEye.set(eye);
        cinematicFront.set(f);
        cinematicRight.set(r);
        cinematicUp.set(u);
        cinematicActive = true;
    }

    /** Ends a scripted shot; the player-driven camera state resumes unchanged. */
    public void clearCinematicView() {
        cinematicActive = false;
    }

    /** True while a scripted shot owns the view. */
    public boolean isCinematicActive() {
        return cinematicActive;
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
