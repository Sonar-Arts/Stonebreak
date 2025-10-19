package com.openmason.ui.viewport;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Professional 3D camera system supporting both arc-ball and first-person modes.
 * Designed for versatile 3D viewport navigation in OpenMason.
 * 
 * Features:
 * - Arc-ball mode: orbit around a target point (default)
 * - First-person mode: free camera movement like FPS games
 * - Smooth interpolation for professional feel
 * - Mouse and keyboard controls for both modes
 * - Professional default viewing angles
 * - Seamless mode switching during runtime
 * - Enhanced mouse sensitivity controls
 * - Improved angle normalization and constraints
 * - Animation system with user input tracking
 */
public class Camera {
    
    private static final Logger logger = LoggerFactory.getLogger(Camera.class);
    
    // Camera mode
    public enum CameraMode {
        ARCBALL,        // Orbit around target point
        FIRST_PERSON    // Free movement like FPS
    }
    
    // Current camera mode
    private CameraMode cameraMode = CameraMode.ARCBALL;
    
    // Arc-ball camera parameters (current state)
    private float distance = 15.0f;
    private float yaw = -45.0f;   // Azimuth angle (professional default)
    private float pitch = 25.0f;  // Elevation angle (professional default) 
    private Vector3f target = new Vector3f(0, 0, 0);
    
    // Target state for smooth interpolation (arc-ball)
    private float targetDistance = 15.0f;
    private float targetYaw = -45.0f;
    private float targetPitch = 25.0f;
    
    // First-person camera state
    private final Vector3f fpPosition = new Vector3f(0, 5, 15);
    private final Vector3f fpTargetPosition = new Vector3f(0, 5, 15);
    private float fpYaw = -90.0f;   // Horizontal rotation (degrees)
    private float fpPitch = 0.0f;   // Vertical rotation (degrees)
    private float fpTargetYaw = -90.0f;
    private float fpTargetPitch = 0.0f;
    
    // Animation and interaction state
    private boolean isAnimating = false;
    private long lastUserInputTime = 0;
    private static final float INTERPOLATION_SPEED = 15.0f; // Increased for more responsive feel
    
    // Movement and mouse settings
    private float mouseSensitivity = 3.0f;
    private float moveSpeed = 5.0f;
    
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
    
    // Constraints (enhanced ranges)
    private static final float MIN_DISTANCE = 2.0f;
    private static final float MAX_DISTANCE = 100.0f;
    private static final float MIN_PITCH = -89.0f;
    private static final float MAX_PITCH = 89.0f;
    private static final float ZOOM_SENSITIVITY = 1.08f;
    
    // Camera constants for first-person mode
    private static final float DEFAULT_MOVE_SPEED = 5.0f;
    private static final float DEFAULT_MOUSE_SENSITIVITY = 3.0f;
    
    public Camera() {
        // logger.info("Initializing professional 3D camera system (default: arc-ball mode)");
        updateMatrices();
    }
    
    /**
     * Handle mouse rotation input (mode-dependent).
     */
    public void rotate(float deltaX, float deltaY) {
        lastUserInputTime = System.currentTimeMillis();
        
        // Debug logging (trace level to avoid spam)
        logger.trace("Camera.rotate() called - deltaX: {}, deltaY: {}, mode: {}, sensitivity: {}", 
            deltaX, deltaY, cameraMode, mouseSensitivity);
        
        if (cameraMode == CameraMode.ARCBALL) {
            // Arc-ball rotation
            float oldYaw = targetYaw;
            float oldPitch = targetPitch;
            
            targetYaw = normalizeAngle(targetYaw - deltaX * mouseSensitivity);
            targetPitch = clamp(targetPitch - deltaY * mouseSensitivity, MIN_PITCH, MAX_PITCH);
            
            // For immediate response during active dragging, also update current values
            yaw = targetYaw;
            pitch = targetPitch;
            
            logger.trace("ArcBall Rotate - Old: Yaw={}°, Pitch={}° | New: Yaw={}°, Pitch={}°", 
                oldYaw, oldPitch, targetYaw, targetPitch);
        } else if (cameraMode == CameraMode.FIRST_PERSON) {
            // First-person mouse look
            float oldYaw = fpTargetYaw;
            float oldPitch = fpTargetPitch;
            
            fpTargetYaw += deltaX * mouseSensitivity;
            fpTargetPitch = clamp(fpTargetPitch - deltaY * mouseSensitivity, MIN_PITCH, MAX_PITCH);
            
            // Normalize yaw
            while (fpTargetYaw > 360.0f) fpTargetYaw -= 360.0f;
            while (fpTargetYaw < 0.0f) fpTargetYaw += 360.0f;
            
            // For immediate response during active dragging, also update current values
            fpYaw = fpTargetYaw;
            fpPitch = fpTargetPitch;
            
            logger.trace("FirstPerson Look - Old: Yaw={}°, Pitch={}° | New: Yaw={}°, Pitch={}°", 
                oldYaw, oldPitch, fpTargetYaw, fpTargetPitch);
        }
        
        viewMatrixDirty = true;
    }
    
    /**
     * Zoom camera in/out with enhanced zoom sensitivity.
     */
    public void zoom(float scrollDelta) {
        lastUserInputTime = System.currentTimeMillis();
        
        // Use professional zoom sensitivity with exponential scaling
        float zoomFactor = (float) Math.pow(ZOOM_SENSITIVITY, scrollDelta);
        targetDistance = clamp(targetDistance * zoomFactor, MIN_DISTANCE, MAX_DISTANCE);
        
        // For immediate response during zooming, also update current distance
        distance = targetDistance;
        
        logger.trace("Camera zoom - Target Distance: {}", targetDistance);
        viewMatrixDirty = true;
    }
    
    /**
     * Reset camera to default position based on current mode.
     */
    public void reset() {
        if (cameraMode == CameraMode.ARCBALL) {
            targetDistance = 15.0f;
            targetYaw = -45.0f;
            targetPitch = 25.0f;
            target.set(0, 0, 0);
        } else if (cameraMode == CameraMode.FIRST_PERSON) {
            fpTargetPosition.set(0, 5, 15);
            fpTargetYaw = -90.0f;
            fpTargetPitch = 0.0f;
        }
        
        lastUserInputTime = System.currentTimeMillis();
        viewMatrixDirty = true;
        
        // logger.info("Camera reset to default position (mode: {})", cameraMode);
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
     * Update view matrix based on current camera parameters and mode.
     */
    private void updateViewMatrix() {
        Vector3f up = new Vector3f(0, 1, 0);
        
        if (cameraMode == CameraMode.ARCBALL) {
            // Calculate camera position using professional spherical coordinates
            float azimuthRad = (float) Math.toRadians(yaw);
            float elevationRad = (float) Math.toRadians(pitch);
            
            // Professional arc-ball camera position calculation
            float cosElevation = (float) Math.cos(elevationRad);
            float x = target.x + distance * cosElevation * (float) Math.sin(azimuthRad);
            float y = target.y + distance * (float) Math.sin(elevationRad);
            float z = target.z + distance * cosElevation * (float) Math.cos(azimuthRad);
            
            Vector3f position = new Vector3f(x, y, z);
            viewMatrix.identity().lookAt(position, target, up);
            
        } else if (cameraMode == CameraMode.FIRST_PERSON) {
            Vector3f lookTarget = new Vector3f(fpPosition).add(getCameraDirection());
            viewMatrix.identity().lookAt(fpPosition, lookTarget, up);
        }
        
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
        if (cameraMode == CameraMode.ARCBALL) {
            float azimuthRad = (float) Math.toRadians(yaw);
            float elevationRad = (float) Math.toRadians(pitch);
            
            float cosElevation = (float) Math.cos(elevationRad);
            float x = target.x + distance * cosElevation * (float) Math.sin(azimuthRad);
            float y = target.y + distance * (float) Math.sin(elevationRad);
            float z = target.z + distance * cosElevation * (float) Math.cos(azimuthRad);
            
            return new Vector3f(x, y, z);
        } else if (cameraMode == CameraMode.FIRST_PERSON) {
            return new Vector3f(fpPosition);
        }
        return new Vector3f();
    }
    
    // ========== Enhanced Getters and Setters ==========
    public float getDistance() { return distance; }
    public void setDistance(float distance) { 
        this.distance = clamp(distance, MIN_DISTANCE, MAX_DISTANCE);
        this.targetDistance = this.distance; // Immediate update without animation
        viewMatrixDirty = true;
    }
    
    public float getYaw() { return yaw; }
    public void setYaw(float yaw) { 
        this.yaw = normalizeAngle(yaw);
        this.targetYaw = this.yaw; // Immediate update without animation
        viewMatrixDirty = true;
    }
    
    public float getPitch() { return pitch; }
    public void setPitch(float pitch) { 
        this.pitch = clamp(pitch, MIN_PITCH, MAX_PITCH);
        this.targetPitch = this.pitch; // Immediate update without animation
        viewMatrixDirty = true;
    }
    
    public Vector3f getTarget() { return new Vector3f(target); }
    public void setTarget(Vector3f target) { 
        this.target.set(target);
        viewMatrixDirty = true;
        // logger.debug("Camera target set to: ({}, {}, {})", target.x, target.y, target.z);
    }
    
    public float getFov() { return fov; }
    public void setFov(float fov) { 
        this.fov = clamp(fov, 10.0f, 120.0f); // Professional FOV range
        projectionMatrixDirty = true;
        // logger.debug("Camera FOV set to: {}°", this.fov);
    }
    
    public float getAspectRatio() { return aspectRatio; }
    public float getNearPlane() { return nearPlane; }
    public float getFarPlane() { return farPlane; }

    // ========== Professional Camera Animation System ==========
    
    /**
     * Updates camera interpolation and matrices for smooth animation.
     * Should be called every frame for smooth camera movement.
     */
    public void update(float deltaTime) {
        boolean wasAnimating = isAnimating;
        isAnimating = false;
        
        float lerpSpeed = INTERPOLATION_SPEED * deltaTime;
        
        if (cameraMode == CameraMode.ARCBALL) {
            // Arc-ball mode interpolation
            if (Math.abs(targetDistance - distance) > 0.01f) {
                distance = lerp(distance, targetDistance, lerpSpeed);
                isAnimating = true;
            }
            
            if (Math.abs(targetYaw - yaw) > 0.1f) {
                yaw = lerpAngle(yaw, targetYaw, lerpSpeed);
                isAnimating = true;
            }
            
            if (Math.abs(targetPitch - pitch) > 0.1f) {
                pitch = lerp(pitch, targetPitch, lerpSpeed);
                isAnimating = true;
            }
            
        } else if (cameraMode == CameraMode.FIRST_PERSON) {
            // First-person mode interpolation
            Vector3f positionDelta = new Vector3f(fpTargetPosition).sub(fpPosition);
            if (positionDelta.length() > 0.01f) {
                fpPosition.lerp(fpTargetPosition, lerpSpeed);
                isAnimating = true;
            }
            
            if (Math.abs(fpTargetYaw - fpYaw) > 0.1f) {
                fpYaw = lerpAngle(fpYaw, fpTargetYaw, lerpSpeed);
                isAnimating = true;
            }
            
            if (Math.abs(fpTargetPitch - fpPitch) > 0.1f) {
                fpPitch = lerp(fpPitch, fpTargetPitch, lerpSpeed);
                isAnimating = true;
            }
        }
        
        // Update matrices if animating or animation just finished
        if (isAnimating || !wasAnimating) {
            viewMatrixDirty = true;
            updateMatrices();
        }
        
        if (wasAnimating && !isAnimating) {
            // logger.debug("Camera animation completed (mode: {})", cameraMode);
        }
    }
    
    /**
     * Check if camera is currently animating.
     */
    public boolean isAnimating() {
        return isAnimating;
    }
    
    /**
     * Check if user input is currently active (within recent timeframe).
     */
    public boolean isUserInputActive() {
        return (System.currentTimeMillis() - lastUserInputTime) < 500; // 500ms timeout
    }

    // ========== Enhanced Settings ==========
    
    /**
     * Set mouse sensitivity for camera rotation.
     */
    public void setMouseSensitivity(float sensitivity) {
        this.mouseSensitivity = clamp(sensitivity, 0.1f, 5.0f);
        // logger.debug("Mouse sensitivity set to: {}", this.mouseSensitivity);
    }

    // ========== First-Person Movement Methods ==========
    
    /**
     * Move camera forward/backward in first-person mode.
     */
    public void moveForward(float amount) {
        if (cameraMode == CameraMode.FIRST_PERSON) {
            Vector3f forward = getCameraDirection();
            fpTargetPosition.add(new Vector3f(forward).mul(amount * moveSpeed));
            lastUserInputTime = System.currentTimeMillis();
        }
    }
    
    /**
     * Move camera right/left in first-person mode.
     */
    public void moveRight(float amount) {
        if (cameraMode == CameraMode.FIRST_PERSON) {
            Vector3f right = new Vector3f();
            getCameraDirection().cross(new Vector3f(0, 1, 0), right).normalize();
            fpTargetPosition.add(new Vector3f(right).mul(amount * moveSpeed));
            lastUserInputTime = System.currentTimeMillis();
        }
    }
    
    /**
     * Move camera up/down in first-person mode.
     */
    public void moveUp(float amount) {
        if (cameraMode == CameraMode.FIRST_PERSON) {
            fpTargetPosition.add(new Vector3f(0, 1, 0).mul(amount * moveSpeed));
            lastUserInputTime = System.currentTimeMillis();
        }
    }
    
    /**
     * Get camera direction vector.
     */
    public Vector3f getCameraDirection() {
        if (cameraMode == CameraMode.ARCBALL) {
            return new Vector3f(target).sub(getPosition()).normalize();
        } else if (cameraMode == CameraMode.FIRST_PERSON) {
            float yawRad = (float) Math.toRadians(fpYaw);
            float pitchRad = (float) Math.toRadians(fpPitch);
            
            float x = (float) (Math.cos(yawRad) * Math.cos(pitchRad));
            float y = (float) Math.sin(pitchRad);
            float z = (float) (Math.sin(yawRad) * Math.cos(pitchRad));
            
            return new Vector3f(x, y, z).normalize();
        }
        return new Vector3f(0, 0, -1); // Default forward
    }
    
    // ========== Camera Mode Management ==========
    
    /**
     * Get current camera mode.
     */
    public CameraMode getCameraMode() {
        return cameraMode;
    }
    
    /**
     * Set camera mode.
     */
    public void setCameraMode(CameraMode mode) {
        if (this.cameraMode != mode) {
            // logger.info("Switching camera mode from {} to {}", this.cameraMode, mode);
            
            if (mode == CameraMode.FIRST_PERSON && this.cameraMode == CameraMode.ARCBALL) {
                // Transition from arc-ball to first-person
                fpTargetPosition.set(getPosition());
                fpTargetYaw = yaw;
                fpTargetPitch = pitch;
            } else if (mode == CameraMode.ARCBALL && this.cameraMode == CameraMode.FIRST_PERSON) {
                // Transition from first-person to arc-ball
                target.set(0, 0, 0); // Reset target to origin
                
                // Calculate distance from current position to target
                Vector3f directionToTarget = new Vector3f(target).sub(fpPosition);
                targetDistance = directionToTarget.length();
                
                // Calculate angles
                targetYaw = fpYaw;
                targetPitch = fpPitch;
            }
            
            this.cameraMode = mode;
        }
    }

    // ========== Professional Utility Methods ==========
    
    /**
     * Linear interpolation between two values.
     */
    private float lerp(float a, float b, float t) {
        return a + (b - a) * Math.min(t, 1.0f);
    }
    
    /**
     * Angle-aware interpolation that handles wraparound correctly.
     */
    private float lerpAngle(float from, float to, float t) {
        float difference = to - from;
        
        // Handle angle wraparound for smooth rotation
        if (difference > 180) {
            difference -= 360;
        } else if (difference < -180) {
            difference += 360;
        }
        
        return normalizeAngle(from + difference * Math.min(t, 1.0f));
    }
    
    /**
     * Normalize angle to [0, 360) range.
     */
    private float normalizeAngle(float angle) {
        while (angle < 0) angle += 360;
        while (angle >= 360) angle -= 360;
        return angle;
    }
    
    /**
     * Clamp value between min and max bounds.
     */
    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}