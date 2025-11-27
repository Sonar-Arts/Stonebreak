package com.openmason.ui.viewport.controllers;

import com.openmason.ui.viewport.util.CameraInterpolator;
import com.openmason.ui.viewport.util.CameraMath;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FirstPersonCameraController implements CameraController {

    private static final Logger logger = LoggerFactory.getLogger(FirstPersonCameraController.class);

    private static final float DEFAULT_YAW = -90.0f;
    private static final float DEFAULT_PITCH = 0.0f;
    private static final Vector3f DEFAULT_POSITION = new Vector3f(0, 5, 15);

    private final Vector3f position;
    private float yaw;
    private float pitch;

    private final Vector3f targetPosition;
    private float targetYaw;
    private float targetPitch;

    private float mouseSensitivity;
    private float moveSpeed;

    public FirstPersonCameraController() {
        this.position = new Vector3f(DEFAULT_POSITION);
        this.yaw = DEFAULT_YAW;
        this.pitch = DEFAULT_PITCH;

        this.targetPosition = new Vector3f(DEFAULT_POSITION);
        this.targetYaw = DEFAULT_YAW;
        this.targetPitch = DEFAULT_PITCH;

        this.mouseSensitivity = 3.0f;
        this.moveSpeed = 10.0f;
    }

    @Override
    public void rotate(float deltaX, float deltaY) {
        targetYaw = CameraMath.normalizeAngle(targetYaw - deltaX * mouseSensitivity);
        targetPitch = CameraMath.clamp(targetPitch + deltaY * mouseSensitivity,
            CameraMath.MIN_PITCH, CameraMath.MAX_PITCH);

        yaw = targetYaw;
        pitch = targetPitch;

        logger.trace("FirstPerson Look - Yaw: {}°, Pitch: {}°", yaw, pitch);
    }

    @Override
    public void zoom(float scrollDelta) {
        float speedAdjust = scrollDelta * 0.5f;
        moveSpeed = CameraMath.clamp(moveSpeed + speedAdjust, 1.0f, 50.0f);
        logger.trace("FirstPerson speed: {}", moveSpeed);
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

        if (!CameraInterpolator.isAtTarget(position, targetPosition)) {
            position.lerp(targetPosition, lerpSpeed);
            isAnimating = true;
        }

        if (!CameraInterpolator.isAngleApproximately(yaw, targetYaw)) {
            yaw = CameraInterpolator.lerpAngle(yaw, targetYaw, lerpSpeed);
            isAnimating = true;
        }

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
        return new Vector3f(position).add(getDirection());
    }

    public void moveForward(float amount) {
        Vector3f forward = getDirection();
        targetPosition.add(new Vector3f(forward).mul(amount * moveSpeed));
    }

    public void moveRight(float amount) {
        Vector3f right = new Vector3f();
        getDirection().cross(getUpVector(), right).normalize();
        targetPosition.add(new Vector3f(right).mul(amount * moveSpeed));
    }

    public void moveUp(float amount) {
        targetPosition.add(new Vector3f(0, 1, 0).mul(amount * moveSpeed));
    }

    public void setMouseSensitivity(float sensitivity) {
        this.mouseSensitivity = CameraMath.clamp(sensitivity,
            CameraMath.MIN_SENSITIVITY, CameraMath.MAX_SENSITIVITY);
    }
}
