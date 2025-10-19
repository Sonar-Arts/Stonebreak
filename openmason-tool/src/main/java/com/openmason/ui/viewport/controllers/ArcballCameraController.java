package com.openmason.ui.viewport.controllers;

import com.openmason.ui.viewport.util.CameraInterpolator;
import com.openmason.ui.viewport.util.CameraMath;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Arc-ball camera controller that orbits around a target point.
 * Uses spherical coordinates for professional 3D viewport navigation.
 */
public class ArcballCameraController implements CameraController {

    private static final Logger logger = LoggerFactory.getLogger(ArcballCameraController.class);

    // Default values
    private static final float DEFAULT_DISTANCE = 15.0f;
    private static final float DEFAULT_YAW = -45.0f;
    private static final float DEFAULT_PITCH = 25.0f;

    // Current state
    private float distance;
    private float yaw;
    private float pitch;
    private final Vector3f target;

    // Target state for interpolation
    private float targetDistance;
    private float targetYaw;
    private float targetPitch;

    // Settings
    private float mouseSensitivity;

    /**
     * Creates an arc-ball camera controller with default settings.
     */
    public ArcballCameraController() {
        this.distance = DEFAULT_DISTANCE;
        this.yaw = DEFAULT_YAW;
        this.pitch = DEFAULT_PITCH;
        this.target = new Vector3f(0, 0, 0);

        this.targetDistance = DEFAULT_DISTANCE;
        this.targetYaw = DEFAULT_YAW;
        this.targetPitch = DEFAULT_PITCH;

        this.mouseSensitivity = 3.0f;
    }

    @Override
    public void rotate(float deltaX, float deltaY) {
        targetYaw = CameraMath.normalizeAngle(targetYaw - deltaX * mouseSensitivity);
        targetPitch = CameraMath.clamp(targetPitch - deltaY * mouseSensitivity,
            CameraMath.MIN_PITCH, CameraMath.MAX_PITCH);

        // Immediate update for responsive feel
        yaw = targetYaw;
        pitch = targetPitch;

        logger.trace("ArcBall Rotate - Yaw: {}°, Pitch: {}°", yaw, pitch);
    }

    @Override
    public void zoom(float scrollDelta) {
        targetDistance = CameraMath.applyZoom(targetDistance, scrollDelta);

        // Immediate update for responsive feel
        distance = targetDistance;

        logger.trace("ArcBall Zoom - Distance: {}", distance);
    }

    @Override
    public void reset() {
        targetDistance = DEFAULT_DISTANCE;
        targetYaw = DEFAULT_YAW;
        targetPitch = DEFAULT_PITCH;
        target.set(0, 0, 0);

        logger.debug("ArcBall camera reset to defaults");
    }

    @Override
    public boolean update(float deltaTime) {
        boolean isAnimating = false;
        float lerpSpeed = CameraInterpolator.INTERPOLATION_SPEED * deltaTime;

        // Interpolate distance
        if (!CameraInterpolator.isApproximately(distance, targetDistance)) {
            distance = CameraInterpolator.lerp(distance, targetDistance, lerpSpeed);
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
        return calculatePositionFromSpherical();
    }

    @Override
    public Vector3f getDirection() {
        return new Vector3f(target).sub(getPosition()).normalize();
    }

    @Override
    public Vector3f getTarget() {
        return new Vector3f(target);
    }

    /**
     * Calculates camera position using spherical coordinates.
     *
     * @return Camera position in world space
     */
    private Vector3f calculatePositionFromSpherical() {
        float azimuthRad = CameraMath.toRadians(yaw);
        float elevationRad = CameraMath.toRadians(pitch);

        float cosElevation = (float) Math.cos(elevationRad);
        float x = target.x + distance * cosElevation * (float) Math.sin(azimuthRad);
        float y = target.y + distance * (float) Math.sin(elevationRad);
        float z = target.z + distance * cosElevation * (float) Math.cos(azimuthRad);

        return new Vector3f(x, y, z);
    }

    // Getters and setters for configuration

    public float getDistance() {
        return distance;
    }

    public void setDistance(float distance) {
        this.distance = CameraMath.clamp(distance, CameraMath.MIN_DISTANCE, CameraMath.MAX_DISTANCE);
        this.targetDistance = this.distance;
    }

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
}
