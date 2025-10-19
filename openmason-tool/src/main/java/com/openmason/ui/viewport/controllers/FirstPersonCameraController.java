package com.openmason.ui.viewport.controllers;

import com.openmason.ui.viewport.util.CameraInterpolator;
import com.openmason.ui.viewport.util.CameraMath;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * First-person camera controller with free movement.
 * Supports WASD-style movement and mouse look.
 */
public class FirstPersonCameraController implements CameraController {

    private static final Logger logger = LoggerFactory.getLogger(FirstPersonCameraController.class);

    // Default values
    private static final float DEFAULT_YAW = -90.0f;
    private static final float DEFAULT_PITCH = 0.0f;
    private static final Vector3f DEFAULT_POSITION = new Vector3f(0, 5, 15);

    // Current state
    private final Vector3f position;
    private float yaw;
    private float pitch;

    // Target state for interpolation
    private final Vector3f targetPosition;
    private float targetYaw;
    private float targetPitch;

    // Settings
    private float mouseSensitivity;
    private float moveSpeed;

    /**
     * Creates a first-person camera controller with default settings.
     */
    public FirstPersonCameraController() {
        this.position = new Vector3f(DEFAULT_POSITION);
        this.yaw = DEFAULT_YAW;
        this.pitch = DEFAULT_PITCH;

        this.targetPosition = new Vector3f(DEFAULT_POSITION);
        this.targetYaw = DEFAULT_YAW;
        this.targetPitch = DEFAULT_PITCH;

        this.mouseSensitivity = 3.0f;
        this.moveSpeed = 5.0f;
    }

    @Override
    public void rotate(float deltaX, float deltaY) {
        targetYaw += deltaX * mouseSensitivity;
        targetPitch = CameraMath.clamp(targetPitch - deltaY * mouseSensitivity,
            CameraMath.MIN_PITCH, CameraMath.MAX_PITCH);

        // Normalize yaw
        targetYaw = CameraMath.normalizeAngle(targetYaw);

        // Immediate update for responsive feel
        yaw = targetYaw;
        pitch = targetPitch;

        logger.trace("FirstPerson Look - Yaw: {}°, Pitch: {}°", yaw, pitch);
    }

    @Override
    public void zoom(float scrollDelta) {
        // First-person cameras typically don't zoom
        // Could be used to adjust FOV or move speed if desired
        logger.trace("FirstPerson zoom not implemented (scroll delta: {})", scrollDelta);
    }

    @Override
    public void reset() {
        targetPosition.set(DEFAULT_POSITION);
        targetYaw = DEFAULT_YAW;
        targetPitch = DEFAULT_PITCH;

        logger.debug("FirstPerson camera reset to defaults");
    }

    @Override
    public boolean update(float deltaTime) {
        boolean isAnimating = false;
        float lerpSpeed = CameraInterpolator.INTERPOLATION_SPEED * deltaTime;

        // Interpolate position
        if (!CameraInterpolator.isAtTarget(position, targetPosition)) {
            position.lerp(targetPosition, lerpSpeed);
            isAnimating = true;
        }

        // Interpolate yaw
        if (!CameraInterpolator.isAngleApproximately(yaw, targetYaw)) {
            yaw = CameraInterpolator.lerpAngle(yaw, targetYaw, lerpSpeed);
            isAnimating = true;
        }

        // Interpolate pitch
        if (!CameraInterpolator.isAngleApproximately(pitch, targetPitch)) {
            pitch = CameraInterpolator.lerp(pitch, targetPitch, lerpSpeed);
            isAnimating = true;
        }

        return isAnimating;
    }

    @Override
    public Vector3f getPosition() {
        return new Vector3f(position);
    }

    @Override
    public Vector3f getDirection() {
        float yawRad = CameraMath.toRadians(yaw);
        float pitchRad = CameraMath.toRadians(pitch);

        float x = (float) (Math.cos(yawRad) * Math.cos(pitchRad));
        float y = (float) Math.sin(pitchRad);
        float z = (float) (Math.sin(yawRad) * Math.cos(pitchRad));

        return new Vector3f(x, y, z).normalize();
    }

    @Override
    public Vector3f getTarget() {
        // First-person camera looks at position + direction
        return new Vector3f(position).add(getDirection());
    }

    // First-person specific movement methods

    /**
     * Moves the camera forward/backward along its direction vector.
     *
     * @param amount Movement amount (positive = forward, negative = backward)
     */
    public void moveForward(float amount) {
        Vector3f forward = getDirection();
        targetPosition.add(new Vector3f(forward).mul(amount * moveSpeed));
    }

    /**
     * Moves the camera right/left perpendicular to its direction.
     *
     * @param amount Movement amount (positive = right, negative = left)
     */
    public void moveRight(float amount) {
        Vector3f right = new Vector3f();
        getDirection().cross(getUpVector(), right).normalize();
        targetPosition.add(new Vector3f(right).mul(amount * moveSpeed));
    }

    /**
     * Moves the camera up/down along the world up vector.
     *
     * @param amount Movement amount (positive = up, negative = down)
     */
    public void moveUp(float amount) {
        targetPosition.add(new Vector3f(0, 1, 0).mul(amount * moveSpeed));
    }

    // Getters and setters for configuration

    public float getYaw() {
        return yaw;
    }

    public void setYaw(float yaw) {
        this.yaw = CameraMath.normalizeAngle(yaw);
        this.targetYaw = this.yaw;
    }

    public float getPitch() {
        return pitch;
    }

    public void setPitch(float pitch) {
        this.pitch = CameraMath.clamp(pitch, CameraMath.MIN_PITCH, CameraMath.MAX_PITCH);
        this.targetPitch = this.pitch;
    }

    public void setMouseSensitivity(float sensitivity) {
        this.mouseSensitivity = CameraMath.clamp(sensitivity,
            CameraMath.MIN_SENSITIVITY, CameraMath.MAX_SENSITIVITY);
    }

    public void setMoveSpeed(float moveSpeed) {
        this.moveSpeed = Math.max(0.1f, moveSpeed);
    }

    public float getMoveSpeed() {
        return moveSpeed;
    }
}
