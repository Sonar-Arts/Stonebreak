package com.openmason.camera;

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
 */
public class ArcBallCamera {
    
    private static final Logger logger = LoggerFactory.getLogger(ArcBallCamera.class);
    
    // Camera constants
    private static final float MIN_DISTANCE = 2.0f;
    private static final float MAX_DISTANCE = 100.0f;
    private static final float MIN_ELEVATION = -89.0f;
    private static final float MAX_ELEVATION = 89.0f;
    private static final float DEFAULT_FOV = 45.0f;
    
    // Movement speeds
    private static final float DEFAULT_MOVE_SPEED = 5.0f;
    private static final float DEFAULT_MOUSE_SENSITIVITY = 0.3f;
    private static final float ZOOM_SENSITIVITY = 1.08f;
    private static final float INTERPOLATION_SPEED = 10.0f;
    
    // Camera mode
    public enum CameraMode {
        ARCBALL,        // Orbit around target point
        FIRST_PERSON    // Free movement like FPS
    }
    
    // Current camera mode
    private CameraMode cameraMode = CameraMode.ARCBALL;
    
    // Arc-ball camera state
    private float distance = 15.0f;
    private float azimuth = -45.0f;      // Horizontal angle (degrees)
    private float elevation = 25.0f;     // Vertical angle (degrees)
    private float fov = DEFAULT_FOV;
    
    // Target state for smooth interpolation (arc-ball)
    private float targetDistance = 15.0f;
    private float targetAzimuth = -45.0f;
    private float targetElevation = 25.0f;
    
    // Target for arc-ball mode
    private final Vector3f target = new Vector3f(0, 0, 0);
    
    // First-person camera state
    private final Vector3f fpPosition = new Vector3f(0, 5, 15);
    private final Vector3f fpTargetPosition = new Vector3f(0, 5, 15);
    private float fpYaw = -90.0f;   // Horizontal rotation (degrees)
    private float fpPitch = 0.0f;   // Vertical rotation (degrees)
    private float fpTargetYaw = -90.0f;
    private float fpTargetPitch = 0.0f;
    
    // First-person movement
    private float moveSpeed = DEFAULT_MOVE_SPEED;
    private float mouseSensitivity = DEFAULT_MOUSE_SENSITIVITY;
    
    // Cached matrices
    private final Matrix4f viewMatrix = new Matrix4f();
    private final Vector3f cameraPosition = new Vector3f();
    private final Vector3f up = new Vector3f(0, 1, 0);
    
    // Animation state
    private boolean isAnimating = false;
    private long lastUserInputTime = 0;
    
    /**
     * Creates a new camera with arc-ball mode by default.
     */
    public ArcBallCamera() {
        logger.info("Initializing 3D camera system (default: arc-ball mode)");
        updateCameraPosition();
        updateViewMatrix();
    }
    
    /**
     * Updates camera interpolation and matrices.
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
            
            if (Math.abs(targetAzimuth - azimuth) > 0.1f) {
                azimuth = lerpAngle(azimuth, targetAzimuth, lerpSpeed);
                isAnimating = true;
            }
            
            if (Math.abs(targetElevation - elevation) > 0.1f) {
                elevation = lerp(elevation, targetElevation, lerpSpeed);
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
        
        if (isAnimating || !wasAnimating) {
            updateCameraPosition();
            updateViewMatrix();
        }
        
        if (wasAnimating && !isAnimating) {
            logger.debug("Camera animation completed (mode: {})", cameraMode);
        }
    }
    
    /**
     * Handle mouse rotation input (mode-dependent).
     */
    public void rotate(float deltaX, float deltaY) {
        lastUserInputTime = System.currentTimeMillis();
        
        if (cameraMode == CameraMode.ARCBALL) {
            // Arc-ball rotation
            targetAzimuth = normalizeAngle(targetAzimuth - deltaX * mouseSensitivity);
            targetElevation = clamp(targetElevation - deltaY * mouseSensitivity, MIN_ELEVATION, MAX_ELEVATION);
            
            logger.trace("ArcBall Rotate - Azimuth: {}°, Elevation: {}°", targetAzimuth, targetElevation);
        } else if (cameraMode == CameraMode.FIRST_PERSON) {
            // First-person mouse look
            fpTargetYaw += deltaX * mouseSensitivity;
            fpTargetPitch = clamp(fpTargetPitch - deltaY * mouseSensitivity, MIN_ELEVATION, MAX_ELEVATION);
            
            // Normalize yaw
            while (fpTargetYaw > 360.0f) fpTargetYaw -= 360.0f;
            while (fpTargetYaw < 0.0f) fpTargetYaw += 360.0f;
            
            logger.trace("FirstPerson Look - Yaw: {}°, Pitch: {}°", fpTargetYaw, fpTargetPitch);
        }
    }
    
    /**
     * Handle mouse zoom input.
     */
    public void zoom(float scrollDelta) {
        float zoomFactor = (float) Math.pow(ZOOM_SENSITIVITY, scrollDelta);
        targetDistance = clamp(targetDistance * zoomFactor, MIN_DISTANCE, MAX_DISTANCE);
        
        lastUserInputTime = System.currentTimeMillis();
        logger.trace("Zoom - Distance: {}", targetDistance);
    }
    
    /**
     * Handle panning (mode-dependent).
     */
    public void pan(float deltaX, float deltaY) {
        lastUserInputTime = System.currentTimeMillis();
        
        if (cameraMode == CameraMode.ARCBALL) {
            // Pan the target point in arc-ball mode
            Vector3f right = new Vector3f();
            Vector3f up = new Vector3f();
            
            // Calculate camera's right and up vectors
            getCameraDirection().cross(this.up, right).normalize();
            right.cross(getCameraDirection(), up).normalize();
            
            // Move target based on camera orientation
            Vector3f panOffset = new Vector3f(right).mul(deltaX * 0.1f)
                               .add(new Vector3f(up).mul(-deltaY * 0.1f));
            target.add(panOffset);
            
            logger.trace("ArcBall Pan - Target: ({}, {}, {})", target.x, target.y, target.z);
        } else if (cameraMode == CameraMode.FIRST_PERSON) {
            // Strafe movement in first-person mode
            moveRight(deltaX * 0.1f);
            moveUp(deltaY * 0.1f);
        }
    }
    
    /**
     * Reset camera to default position based on current mode.
     */
    public void reset() {
        if (cameraMode == CameraMode.ARCBALL) {
            targetDistance = 15.0f;
            targetAzimuth = -45.0f;
            targetElevation = 25.0f;
            target.set(0, 0, 0);
        } else if (cameraMode == CameraMode.FIRST_PERSON) {
            fpTargetPosition.set(0, 5, 15);
            fpTargetYaw = -90.0f;
            fpTargetPitch = 0.0f;
        }
        
        logger.info("Camera reset to default position (mode: {})", cameraMode);
    }
    
    /**
     * Frame the origin with specified model size.
     */
    public void frameOrigin(float modelSize) {
        // Calculate optimal distance to frame the model
        float optimalDistance = modelSize * 3.0f; // Better viewing distance with more context
        targetDistance = clamp(optimalDistance, MIN_DISTANCE, MAX_DISTANCE);
        
        logger.info("Camera framed origin with model size: {}, distance: {}", modelSize, targetDistance);
    }
    
    /**
     * Frame the origin with default model size.
     */
    public void frameOrigin() {
        frameOrigin(2.0f); // Default green cube size
    }
    
    // Getters for camera state
    public Matrix4f getViewMatrix() {
        return new Matrix4f(viewMatrix);
    }
    
    public Matrix4f getProjectionMatrix(int width, int height, float nearPlane, float farPlane) {
        float aspectRatio = (float) width / (float) Math.max(height, 1);
        return new Matrix4f().perspective(
            (float) Math.toRadians(fov),
            aspectRatio,
            nearPlane,
            farPlane
        );
    }
    
    public Vector3f getCameraPosition() {
        return new Vector3f(cameraPosition);
    }
    
    public Vector3f getTarget() {
        return new Vector3f(target);
    }
    
    public float getDistance() {
        return distance;
    }
    
    public float getAzimuth() {
        return azimuth;
    }
    
    public float getElevation() {
        return elevation;
    }
    
    public float getFOV() {
        return fov;
    }
    
    public boolean isAnimating() {
        return isAnimating;
    }
    
    public boolean isUserInputActive() {
        return (System.currentTimeMillis() - lastUserInputTime) < 500; // 500ms timeout
    }
    
    // Camera presets for different viewing angles
    public enum CameraPreset {
        FRONT(0, 0, "Front View"),
        BACK(180, 0, "Back View"),
        LEFT(-90, 0, "Left View"),
        RIGHT(90, 0, "Right View"),
        TOP(0, 89, "Top View"),
        BOTTOM(0, -89, "Bottom View"),
        ISOMETRIC(45, 35, "Isometric View"),
        PERSPECTIVE(45, 30, "Professional Perspective"),
        HIGH_ANGLE(30, 60, "High Angle View");
        
        public final float azimuth;
        public final float elevation;
        public final String displayName;
        
        CameraPreset(float azimuth, float elevation, String displayName) {
            this.azimuth = azimuth;
            this.elevation = elevation;
            this.displayName = displayName;
        }
    }
    
    /**
     * Apply a camera preset.
     */
    public void applyPreset(CameraPreset preset) {
        targetAzimuth = preset.azimuth;
        targetElevation = preset.elevation;
        
        logger.info("Applied camera preset: {}", preset.displayName);
    }
    
    // Additional methods to maintain compatibility with existing viewport code
    public void setTarget(Vector3f newTarget) {
        // This camera is designed to always target the origin (0,0,0)
        // Log any attempts to change target for debugging
        if (newTarget != null && !newTarget.equals(target)) {
            logger.debug("Target change requested from ({},{},{}) to ({},{},{}), but always maintaining origin focus", 
                target.x, target.y, target.z, newTarget.x, newTarget.y, newTarget.z);
        }
    }
    
    public void setDistance(float newDistance) {
        distance = clamp(newDistance, MIN_DISTANCE, MAX_DISTANCE);
        targetDistance = distance;
    }
    
    public void setOrientation(float newAzimuth, float newElevation) {
        azimuth = normalizeAngle(newAzimuth);
        elevation = clamp(newElevation, MIN_ELEVATION, MAX_ELEVATION);
        targetAzimuth = azimuth;
        targetElevation = elevation;
    }
    
    public void setOrientationSmooth(float azimuth, float elevation) {
        targetAzimuth = normalizeAngle(azimuth);
        targetElevation = clamp(elevation, MIN_ELEVATION, MAX_ELEVATION);
    }
    
    public void setDistanceSmooth(float distance) {
        targetDistance = clamp(distance, MIN_DISTANCE, MAX_DISTANCE);
    }
    
    public void frameObject(Vector3f min, Vector3f max) {
        // Calculate size and frame accordingly
        Vector3f size = new Vector3f(max).sub(min);
        float maxDimension = Math.max(Math.max(size.x, size.y), size.z);
        frameOrigin(maxDimension);
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
            getCameraDirection().cross(up, right).normalize();
            fpTargetPosition.add(new Vector3f(right).mul(amount * moveSpeed));
            lastUserInputTime = System.currentTimeMillis();
        }
    }
    
    /**
     * Move camera up/down in first-person mode.
     */
    public void moveUp(float amount) {
        if (cameraMode == CameraMode.FIRST_PERSON) {
            fpTargetPosition.add(new Vector3f(up).mul(amount * moveSpeed));
            lastUserInputTime = System.currentTimeMillis();
        }
    }
    
    /**
     * Get camera direction vector.
     */
    public Vector3f getCameraDirection() {
        if (cameraMode == CameraMode.ARCBALL) {
            return new Vector3f(target).sub(cameraPosition).normalize();
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
            logger.info("Switching camera mode from {} to {}", this.cameraMode, mode);
            
            if (mode == CameraMode.FIRST_PERSON && this.cameraMode == CameraMode.ARCBALL) {
                // Transition from arc-ball to first-person
                fpTargetPosition.set(cameraPosition);
                fpTargetYaw = azimuth;
                fpTargetPitch = elevation;
            } else if (mode == CameraMode.ARCBALL && this.cameraMode == CameraMode.FIRST_PERSON) {
                // Transition from first-person to arc-ball
                target.set(0, 0, 0); // Reset target to origin
                
                // Calculate distance from current position to target
                Vector3f directionToTarget = new Vector3f(target).sub(fpPosition);
                targetDistance = directionToTarget.length();
                
                // Calculate angles
                targetAzimuth = fpYaw;
                targetElevation = fpPitch;
            }
            
            this.cameraMode = mode;
        }
    }
    
    /**
     * Toggle between camera modes.
     */
    public void toggleCameraMode() {
        setCameraMode(cameraMode == CameraMode.ARCBALL ? CameraMode.FIRST_PERSON : CameraMode.ARCBALL);
    }
    
    // ========== Settings ==========
    
    /**
     * Set movement speed for first-person mode.
     */
    public void setMoveSpeed(float speed) {
        this.moveSpeed = Math.max(0.1f, Math.min(20.0f, speed));
    }
    
    /**
     * Get movement speed.
     */
    public float getMoveSpeed() {
        return moveSpeed;
    }
    
    /**
     * Set mouse sensitivity.
     */
    public void setMouseSensitivity(float sensitivity) {
        this.mouseSensitivity = Math.max(0.1f, Math.min(5.0f, sensitivity));
    }
    
    /**
     * Get mouse sensitivity.
     */
    public float getMouseSensitivity() {
        return mouseSensitivity;
    }
    
    // Private helper methods
    private void updateCameraPosition() {
        if (cameraMode == CameraMode.ARCBALL) {
            // Arc-ball camera position calculation
            float azimuthRad = (float) Math.toRadians(azimuth);
            float elevationRad = (float) Math.toRadians(elevation);
            
            float cosElevation = (float) Math.cos(elevationRad);
            cameraPosition.x = target.x + distance * cosElevation * (float) Math.sin(azimuthRad);
            cameraPosition.y = target.y + distance * (float) Math.sin(elevationRad);
            cameraPosition.z = target.z + distance * cosElevation * (float) Math.cos(azimuthRad);
            
        } else if (cameraMode == CameraMode.FIRST_PERSON) {
            // First-person camera position is directly set
            cameraPosition.set(fpPosition);
        }
    }
    
    private void updateViewMatrix() {
        if (cameraMode == CameraMode.ARCBALL) {
            viewMatrix.identity().lookAt(cameraPosition, target, up);
        } else if (cameraMode == CameraMode.FIRST_PERSON) {
            Vector3f lookTarget = new Vector3f(cameraPosition).add(getCameraDirection());
            viewMatrix.identity().lookAt(cameraPosition, lookTarget, up);
        }
    }
    
    private float lerp(float a, float b, float t) {
        return a + (b - a) * Math.min(t, 1.0f);
    }
    
    private float lerpAngle(float from, float to, float t) {
        float difference = to - from;
        
        // Handle angle wraparound
        if (difference > 180) {
            difference -= 360;
        } else if (difference < -180) {
            difference += 360;
        }
        
        return normalizeAngle(from + difference * Math.min(t, 1.0f));
    }
    
    private float normalizeAngle(float angle) {
        while (angle < 0) angle += 360;
        while (angle >= 360) angle -= 360;
        return angle;
    }
    
    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}