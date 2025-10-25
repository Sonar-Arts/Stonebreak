package com.openmason.ui.viewport.controllers;

import org.joml.Vector3f;

/**
 * Interface for camera controller implementations.
 * Follows the Strategy pattern to support different camera modes.
 */
public interface CameraController {

    /**
     * Handles mouse rotation input.
     *
     * @param deltaX Horizontal mouse movement
     * @param deltaY Vertical mouse movement
     */
    void rotate(float deltaX, float deltaY);

    /**
     * Handles zoom input.
     *
     * @param scrollDelta Scroll wheel delta
     */
    void zoom(float scrollDelta);

    /**
     * Resets camera to default position and orientation.
     */
    void reset();

    /**
     * Updates camera state with smooth interpolation.
     *
     * @param deltaTime Time since last update in seconds
     * @return true if still animating, false if reached target state
     */
    boolean update(float deltaTime);

    /**
     * Gets the current camera position in world space.
     *
     * @return Camera position vector
     */
    Vector3f getPosition();

    /**
     * Gets the camera direction vector (normalized).
     *
     * @return Camera direction vector
     */
    Vector3f getDirection();

    /**
     * Gets the camera target point (what the camera is looking at).
     *
     * @return Target position vector
     */
    Vector3f getTarget();

    /**
     * Gets the camera up vector.
     *
     * @return Up vector (typically 0, 1, 0)
     */
    default Vector3f getUpVector() {
        return new Vector3f(0, 1, 0);
    }
}
