package com.openmason.main.systems.viewport;

import com.openmason.main.systems.viewport.controllers.ArcballCameraController;
import com.openmason.main.systems.viewport.controllers.CameraController;
import com.openmason.main.systems.viewport.controllers.FirstPersonCameraController;
import com.openmason.main.systems.viewport.util.CameraMath;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 3D camera system supporting both arc-ball and first-person modes.
 * Designed for versatile 3D viewport navigation in OpenMason.
 * <p>
 * Uses the Strategy pattern to delegate camera behavior to specialized controllers.
 */
public class ViewportCamera {

    private static final Logger logger = LoggerFactory.getLogger(ViewportCamera.class);

    /**
     * Camera mode enumeration.
     */
    public enum CameraMode {
        ARCBALL,        // Orbit around target point
        FIRST_PERSON    // Free movement like FPS
    }

    // Controllers
    private final ArcballCameraController arcballController;
    private final FirstPersonCameraController firstPersonController;
    private CameraController activeController;
    private CameraMode currentMode;

    // Projection parameters
    private float fov = 45.0f;
    private float aspectRatio = 1.0f;
    private final float nearPlane = 0.1f;
    private final float farPlane = 1000.0f;  // Increased from 100.0f to support infinite grid rendering

    // Matrices
    private final Matrix4f viewMatrix = new Matrix4f();
    private final Matrix4f projectionMatrix = new Matrix4f();
    private boolean viewMatrixDirty = true;
    private boolean projectionMatrixDirty = true;

    /**
     * Creates a camera with default arc-ball mode.
     */
    public ViewportCamera() {
        this.arcballController = new ArcballCameraController();
        this.firstPersonController = new FirstPersonCameraController();
        this.activeController = arcballController;
        this.currentMode = CameraMode.ARCBALL;

        updateMatrices();
    }

    /**
     * Handles mouse rotation input (delegated to active controller).
     *
     * @param deltaX Horizontal mouse movement
     * @param deltaY Vertical mouse movement
     */
    public void rotate(float deltaX, float deltaY) {
        activeController.rotate(deltaX, deltaY);
        viewMatrixDirty = true;
    }

    /**
     * Handles zoom input (delegated to active controller).
     *
     * @param scrollDelta Scroll wheel delta
     */
    public void zoom(float scrollDelta) {
        activeController.zoom(scrollDelta);
        viewMatrixDirty = true;
    }

    /**
     * Resets camera to default position based on current mode.
     */
    public void reset() {
        activeController.reset();
        viewMatrixDirty = true;
        logger.debug("Camera reset (mode: {})", currentMode);
    }

    /**
     * Updates camera interpolation and matrices for smooth animation.
     * Should be called every frame.
     *
     * @param deltaTime Time since last update in seconds
     */
    public void update(float deltaTime) {
        boolean isAnimating = activeController.update(deltaTime);

        if (isAnimating || viewMatrixDirty) {
            viewMatrixDirty = true;
            updateMatrices();
        }
    }

    /**
     * Sets the aspect ratio and marks projection matrix as dirty.
     *
     * @param aspectRatio Width / Height ratio
     */
    public void setAspectRatio(float aspectRatio) {
        this.aspectRatio = aspectRatio;
        projectionMatrixDirty = true;
    }

    /**
     * Gets the view matrix (camera transform).
     *
     * @return View matrix
     */
    public Matrix4f getViewMatrix() {
        if (viewMatrixDirty) {
            updateViewMatrix();
        }
        return viewMatrix;
    }

    /**
     * Gets the projection matrix.
     *
     * @return Projection matrix
     */
    public Matrix4f getProjectionMatrix() {
        if (projectionMatrixDirty) {
            updateProjectionMatrix();
        }
        return projectionMatrix;
    }

    /**
     * Updates both matrices if needed.
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
     * Updates the view matrix based on current controller state.
     */
    private void updateViewMatrix() {
        Vector3f position = activeController.getPosition();
        Vector3f target = activeController.getTarget();
        Vector3f up = activeController.getUpVector();

        viewMatrix.identity().lookAt(position, target, up);
        viewMatrixDirty = false;
    }

    /**
     * Updates the projection matrix.
     */
    private void updateProjectionMatrix() {
        projectionMatrix.identity().perspective(
            CameraMath.toRadians(fov),
            aspectRatio,
            nearPlane,
            farPlane
        );
        projectionMatrixDirty = false;
    }

    /**
     * Gets camera position in world space.
     *
     * @return Camera position vector
     */
    public Vector3f getPosition() {
        return activeController.getPosition();
    }


    // ========== Camera Mode Management ==========

    public CameraMode getCameraMode() {
        return currentMode;
    }

    public void setCameraMode(CameraMode mode) {
        if (this.currentMode == mode) {
            return;
        }

        this.currentMode = mode;
        this.activeController = (mode == CameraMode.ARCBALL) ? arcballController : firstPersonController;
        viewMatrixDirty = true;

        logger.info("Camera mode changed to: {}", mode);
    }

    // ========== Getters and Setters ==========

    public float getFov() {
        return fov;
    }

    public void setFov(float fov) {
        this.fov = CameraMath.clamp(fov, CameraMath.MIN_FOV, CameraMath.MAX_FOV);
        projectionMatrixDirty = true;
    }

    public void setMouseSensitivity(float sensitivity) {
        arcballController.setMouseSensitivity(sensitivity);
        firstPersonController.setMouseSensitivity(sensitivity);
    }

    // ========== Arc-ball Specific Methods ==========

    /**
     * Gets the arc-ball distance (only valid in arc-ball mode).
     *
     * @return Distance from target
     */
    public float getDistance() {
        return arcballController.getDistance();
    }

    /**
     * Sets the arc-ball distance (only valid in arc-ball mode).
     *
     * @param distance Distance from target
     */
    public void setDistance(float distance) {
        arcballController.setDistance(distance);
        if (currentMode == CameraMode.ARCBALL) {
            viewMatrixDirty = true;
        }
    }

    /**
     * Gets the arc-ball yaw angle (only valid in arc-ball mode).
     *
     * @return Yaw angle in degrees
     */
    public float getYaw() {
        return arcballController.getYaw();
    }

    /**
     * Sets the arc-ball yaw angle (only valid in arc-ball mode).
     *
     * @param yaw Yaw angle in degrees
     */
    public void setYaw(float yaw) {
        arcballController.setYaw(yaw);
        if (currentMode == CameraMode.ARCBALL) {
            viewMatrixDirty = true;
        }
    }

    /**
     * Gets the arc-ball pitch angle (only valid in arc-ball mode).
     *
     * @return Pitch angle in degrees
     */
    public float getPitch() {
        return arcballController.getPitch();
    }

    /**
     * Sets the arc-ball pitch angle (only valid in arc-ball mode).
     *
     * @param pitch Pitch angle in degrees
     */
    public void setPitch(float pitch) {
        arcballController.setPitch(pitch);
        if (currentMode == CameraMode.ARCBALL) {
            viewMatrixDirty = true;
        }
    }

    // ========== First-Person Specific Methods ==========

    /**
     * Moves the camera forward/backward (only valid in first-person mode).
     *
     * @param amount Movement amount
     */
    public void moveForward(float amount) {
        if (currentMode == CameraMode.FIRST_PERSON) {
            firstPersonController.moveForward(amount);
            viewMatrixDirty = true;
        }
    }

    /**
     * Moves the camera right/left (only valid in first-person mode).
     *
     * @param amount Movement amount
     */
    public void moveRight(float amount) {
        if (currentMode == CameraMode.FIRST_PERSON) {
            firstPersonController.moveRight(amount);
            viewMatrixDirty = true;
        }
    }

    /**
     * Moves the camera up/down (only valid in first-person mode).
     *
     * @param amount Movement amount
     */
    public void moveUp(float amount) {
        if (currentMode == CameraMode.FIRST_PERSON) {
            firstPersonController.moveUp(amount);
            viewMatrixDirty = true;
        }
    }
}
